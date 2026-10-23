#!/usr/bin/env bash
# tang_db P2 端到端功能测试(SQL 视角)
#
# 覆盖 SPEC-P2 §3 的 11 个场景,其中 4 个 ★ 场景:
#   场景 7  元数据重启不丢
#   场景 8  BE 被杀 → FE 判死 → BE 重启 → 自动恢复
#   场景 9  跨内存与磁盘的查询
#   场景 10 强杀后 SQL 视角零丢失
#
# P2.1 补丁(SPEC-P2.1)在场景 6 之后追加一组断言:
#   反引号标识符 / INSERT 省略列名列表 / SHOW DATABASES(脚本 scripts/sql/13-sql-compat.sql)
#
# 用法: bash scripts/p2-test.sh            # 报告写到 tmp/p2-test/report.txt
# 说明:
#   - 用**独立端口**(BE 9200 / FE 心跳 9291)与**独立数据目录**(tmp/p2-test/data),
#     不碰日常使用的 data/BE 与 9100。
#   - memtable 阈值调到 1KB,好让写入立刻触发刷盘(场景 9 要跨内存与磁盘)。
#   - 杀进程统一用 taskkill /F(本机 Windows)。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p2-test"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/report.txt"
SQL_DIR="${ROOT_DIR}/scripts/sql"

BE_PORT=9200
FE_HEART_PORT=9291
MEMTABLE_SIZE=1KB

export PATH="/c/Program Files/Go/bin:${PATH}"

PASS=0
FAIL=0

mkdir -p "${TEST_DIR}"
rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}"
: >"${REPORT}"

log() { echo "$*" | tee -a "${REPORT}"; }

ok()   { PASS=$((PASS + 1)); log "  [PASS] $*"; }
bad()  { FAIL=$((FAIL + 1)); log "  [FAIL] $*"; }

# ---------------------------------------------------------------- 服务启停

kill_be() {
    local pids
    pids=$(tasklist 2>/dev/null | grep -i "be.exe" | awk '{print $2}' || true)
    for pid in ${pids}; do
        taskkill /F /PID "${pid}" >/dev/null 2>&1 || true
    done
    sleep 1
}

start_be() {
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${TEST_DIR}/be.log" 2>&1 &
    for _ in $(seq 1 60); do
        if grep -q "BE 已就绪" "${TEST_DIR}/be.log" 2>/dev/null; then
            return 0
        fi
        if grep -q "BE 启动失败" "${TEST_DIR}/be.log" 2>/dev/null; then
            log "BE 启动失败:"; cat "${TEST_DIR}/be.log" | tee -a "${REPORT}"
            return 1
        fi
        sleep 0.5
    done
    log "BE 未在预期时间内就绪:"; cat "${TEST_DIR}/be.log" | tee -a "${REPORT}"
    return 1
}

# 跑一个 .sql 文件(非交互:把文件喂给 CLI 的 stdin),输出打到 stdout
run_sql() {
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "${FE_CONF}")" <"$1" 2>&1
}

# 断言:在某段输出里必须出现某个字串
expect() { # expect <输出> <字串> <说明>
    if printf '%s' "$1" | grep -qF "$2"; then
        ok "$3"
    else
        bad "$3(期望输出包含: $2)"
        log "----- 实际输出 -----"
        printf '%s\n' "$1" | tee -a "${REPORT}"
        log "--------------------"
    fi
}

expect_absent() { # expect_absent <输出> <字串> <说明>
    if printf '%s' "$1" | grep -qF "$2"; then
        bad "$3(不该出现: $2)"
        log "----- 实际输出 -----"
        printf '%s\n' "$1" | tee -a "${REPORT}"
        log "--------------------"
    else
        ok "$3"
    fi
}

# ---------------------------------------------------------------- 准备

log "=================================================="
log " tang_db P2 端到端功能测试  $(date '+%Y-%m-%d %H:%M:%S')"
log "=================================================="

log "[构建] BE"
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }

log "[构建] FE"
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes")"
export CP

cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = ${MEMTABLE_SIZE}
fe_host = 127.0.0.1
fe_port = ${FE_HEART_PORT}
heartbeat_interval_seconds = 3
EOF

cat >"${FE_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
heartbeat_listen_port = ${FE_HEART_PORT}
be_offline_cycles = 3
EOF

log "[配置] BE 端口 ${BE_PORT} / FE 心跳端口 ${FE_HEART_PORT} / 数据目录 ${DATA_DIR} / memtable ${MEMTABLE_SIZE}"

kill_be
if ! start_be; then log "无法启动 BE,测试中止"; exit 1; fi
log "[就绪] BE 已启动"

# ---------------------------------------------------------------- 场景 1~6

log ""
log "---------- 场景 1 建表并查看 ----------"
out="$(run_sql "${SQL_DIR}/01-create-show.sql")"
expect "${out}" "users" "SHOW TABLES 列出 users"
expect "${out}" "id" "DESC users 列出主键列 id"
expect "${out}" "BIGINT" "DESC users 显示列类型"

log ""
log "---------- 场景 2 插入并点查 ----------"
out="$(run_sql "${SQL_DIR}/02-insert-point.sql")"
expect "${out}" "OK, 1 row affected" "INSERT 返回影响行数"
expect "${out}" "wangyu" "主键点查读回插入的值"

log ""
log "---------- 场景 3 条件查询 ----------"
out="$(run_sql "${SQL_DIR}/03-conditional.sql")"
expect "${out}" "OK, 3 rows affected" "多行 INSERT 生效"
expect "${out}" "wangwu" "满足 city=北京 AND age>25 的行被返回"
expect_absent "${out}" "lisi" "不满足条件的行(lisi, age=20)未被返回"
expect_absent "${out}" "zhangsan" "不满足条件的行(zhangsan, 上海)未被返回"

log ""
log "---------- 场景 4 更新与删除 ----------"
out="$(run_sql "${SQL_DIR}/04-update-delete.sql")"
expect "${out}" "OK, 1 row affected" "UPDATE / DELETE 返回影响行数"
expect "${out}" "上海" "UPDATE 后的新值可读回"
expect "${out}" "(0 rows)" "DELETE 后该行查不到"

log ""
log "---------- 场景 5 主键冲突 ----------"
out="$(run_sql "${SQL_DIR}/05-pk-conflict.sql")"
expect "${out}" "主键冲突" "重复主键被挡下并明确报错"
expect_absent "${out}" "其他人" "冲突写入没有覆盖原值"

log ""
log "---------- 场景 6 错误提示 ----------"
out="$(run_sql "${SQL_DIR}/06-errors.sql")"
expect "${out}" "表 no_such_table 不存在" "表不存在有明确提示"
expect "${out}" "列 nope 不存在" "列不存在有明确提示"
expect "${out}" "类型" "类型不匹配有明确提示"
expect "${out}" "主键" "缺主键被拒绝"
expect "${out}" "语法" "语法错误有明确提示"
expect_absent "${out}" "Exception in thread" "错误输出里没有 Java 堆栈"

log ""
log "---------- P2.1 场景 A 反引号标识符 / 省略列名 / SHOW DATABASES ----------"
out="$(run_sql "${SQL_DIR}/13-sql-compat.sql")"
expect "${out}" "Database" "SHOW DATABASES 输出列头 Database"
expect "${out}" "default" "SHOW DATABASES 出一行 default"
expect "${out}" "OK, 表 t_quoted 已创建" "反引号建表可用(select/from 做列名)"
expect "${out}" "quoted_value" "反引号列名插入后能查回"
expect "${out}" "| 37" "反引号列名可 UPDATE(SET \`from\` = 37)"
expect "${out}" "期望一条 SQL 语句" "反引号不能包裹语句关键字"
expect "${out}" "标识符不能为空" "空反引号报'标识符不能为空'"
expect "${out}" "OK, 2 rows affected" "省略列名的多行 INSERT 可用"
expect "${out}" "sunqi" "省略列名插入的行能按主键点查读回"
expect "${out}" "第 1 组取值个数(2)与列数(4)不一致" "省略列名少给值时报第几组"
expect "${out}" "列名列表不能为空" "空列名列表被拒绝"
expect_absent "${out}" "无法识别的字符" "输出里不再出现'无法识别的字符'"

log ""
log "---------- 场景 7 ★ 元数据重启不丢 ----------"
out="$(run_sql "${SQL_DIR}/08-restart-setup.sql")"
expect "${out}" "OK, 10 rows affected" "restart_test 写入 10 行"
kill_be
start_be || { bad "重启 BE 失败"; }
out="$(run_sql "${SQL_DIR}/09-restart-check.sql")"
expect "${out}" "users" "重启后 SHOW TABLES 仍有 users"
expect "${out}" "restart_test" "重启后 SHOW TABLES 仍有 restart_test"
row10="$(printf '%s' "${out}" | grep -c '^| [0-9]* *| [a-j] ' || true)"
if [ "${row10}" -ge 10 ] 2>/dev/null; then
    ok "重启后 restart_test 的 10 行全部读回"
else
    bad "重启后 restart_test 的行数不足(匹配到 ${row10} 行)"
    printf '%s\n' "${out}" | tee -a "${REPORT}"
fi

# ---------------------------------------------------------------- 场景 8 ★ 心跳与存活判定

log ""
log "---------- 场景 8 ★ BE 心跳:判死与自动恢复 ----------"
kill_be
rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}"
# 顺序要点:BE 先起(它会在 FE 不在时不断重试注册),CLI 起来后它会在 ≤10 秒内注册成功
start_be || bad "心跳场景里 BE 启动失败"
(
    sleep 14; echo 'SHOW BACKENDS;'   # t+14:BE 已登记 → 可用
    sleep 16; echo 'SHOW BACKENDS;'   # t+30:BE 在 t+16 被杀 → 应不可用
    sleep 18; echo 'SHOW BACKENDS;'   # t+48:BE 在 t+36 重启 → 应自动恢复可用
    echo 'exit'
) | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
      --config="$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/backends.out" 2>&1 &
CLI_PID=$!

sleep 16
log "[强杀] 心跳场景:taskkill /F be.exe(此后 3 个周期内应被判死)"
kill_be
sleep 20
log "[重启] 心跳场景:重新启动 BE(应自动恢复可用)"
start_be || bad "心跳场景里 BE 重启失败"
wait "${CLI_PID}" 2>/dev/null || true

out="$(cat "${TEST_DIR}/backends.out")"
printf '%s\n' "${out}" >>"${REPORT}"
avail=$(printf '%s' "${out}" | grep -c "可用" || true)
unavail=$(printf '%s' "${out}" | grep -c "不可用" || true)
expect "${out}" "be_id" "SHOW BACKENDS 列出 BE 身份信息"
if [ "${avail}" -ge 2 ] 2>/dev/null; then
    ok "BE 存活时被标记为可用;重启后自动恢复为可用"
else
    bad "SHOW BACKENDS 里'可用'出现次数不足(实际 ${avail})"
fi
if [ "${unavail}" -ge 1 ] 2>/dev/null; then
    ok "BE 被杀超过 3 个周期后被标记为不可用"
else
    bad "BE 被杀后没有出现'不可用'(实际 0 次)"
fi

# ---------------------------------------------------------------- 场景 9 ★ 跨内存与磁盘

log ""
log "---------- 场景 9 ★ 跨内存与磁盘的查询 ----------"
kill_be
rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}"
start_be || bad "跨内存/磁盘场景里 BE 启动失败"
out="$(run_sql "${SQL_DIR}/10-cross-memdisk.sql")"
expect "${out}" "OK, 30 rows affected" "写入 30 行"
expect "${out}" "name_30" "范围查询(id >= 26)跨内存/磁盘取到最新行"
expect "${out}" "name_01" "点查(id = 1)取到较早写入的行"
seg=$(ls "${DATA_DIR}/sst"/*.seg 2>/dev/null | wc -l)
if [ "${seg}" -ge 1 ]; then
    ok "memtable 已触发刷盘(sst/ 下有 ${seg} 个 segment)"
else
    bad "没有产生 segment 文件,跨内存/磁盘这一条不算验过"
fi
kill_be
start_be || bad "刷盘后重启失败"
out="$(printf 'SELECT * FROM big WHERE id = 1;\nSELECT * FROM big WHERE id >= 26;\nexit\n' | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" 2>&1)"
expect "${out}" "name_30" "重启后(数据已在 segment 里)仍能查到最新写入的行"

# ---------------------------------------------------------------- 场景 10/11 ★ 强杀后 SQL 视角零丢失

log ""
log "---------- 场景 10 ★ 强杀 BE 后 SQL 视角零丢失 ----------"
out="$(run_sql "${SQL_DIR}/11-zero-loss-setup.sql")"
expect "${out}" "OK, 20 rows affected" "写入 20 行"
sleep 1
log "[强杀] taskkill /F be.exe"
kill_be
start_be || bad "强杀后重启失败"
out="$(run_sql "${SQL_DIR}/12-zero-loss-check.sql")"
expect "${out}" "v01" "重启后第 1 行还在"
expect "${out}" "v20" "重启后第 20 行还在"
rows=$(printf '%s' "${out}" | grep -cE '^\| *[0-9]+ *\| *v[0-9]+' || true)
if [ "${rows}" -ge 20 ] 2>/dev/null; then
    ok "20 行全部读回(丢失 0 行)"
else
    bad "重启后只读回 ${rows} 行(应 20 行)"
    printf '%s\n' "${out}" | tee -a "${REPORT}"
fi

log ""
log "---------- 场景 11 ★ TRUNCATE / DROP 后的一致性 ----------"
out="$(run_sql "${SQL_DIR}/07-truncate-drop.sql")"
expect "${out}" "hello" "TRUNCATE 前能查到数据"
expect "${out}" "(0 rows)" "TRUNCATE 后表为空"
tables_after="$(printf 'SHOW TABLES;\nexit\n' | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" 2>&1)"
expect_absent "${tables_after}" "tmp_demo" "DROP 后 SHOW TABLES 不再列出 tmp_demo"
expect "${tables_after}" "big" "DROP 后其它表(前面场景建的 big)仍然在"

# ---------------------------------------------------------------- 收尾

kill_be

log ""
log "=================================================="
log " 结果:PASS=${PASS}  FAIL=${FAIL}"
log "=================================================="

if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
