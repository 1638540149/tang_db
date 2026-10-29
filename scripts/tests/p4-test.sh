#!/usr/bin/env bash
# tang_db P4 一键验收:二级索引 / 唯一索引 / 索引选择 / EXPLAIN / 图形客户端元数据自省
#
# 覆盖 SPEC-P4 §8 的 SC-319 ~ SC-345:
#   SC-319 二级索引读写闭环(索引路径 vs 全表扫逐行对拍)
#   SC-320 索引列 UPDATE 维护
#   SC-321 ★ 唯一索引挡重复(单会话,1062 + 信息四要素)
#   SC-322 ★ 唯一索引并发(恰好一个成功,另一个 1062)
#   SC-323 ★ 崩溃:索引与数据同批(after_fsync / after_memtable)
#   SC-324 ★ 崩溃:回填中断可续(BUILDING → 续做 → ACTIVE)
#   SC-325 索引选择与 EXPLAIN 一致(五种路径 + 实际扫描日志交叉验证)
#   SC-326 DROP INDEX 行为
#   SC-327 老数据目录升级(幂等建 sys_indexes,format_version 不变)
#   SC-328 事务内索引可见性
#   SC-329 一致性对账(5000 次随机写改删,差异 0 / INV-10)
#   SC-330 索引元数据原子性(建表内联索引 / 建索引各阶段强杀)
#   SC-331 依赖与边界红线(BE / proto 零改动)
#   SC-332 既有回归(p2 + p3 + p35)
#   SC-333 性能口径(全表扫 vs 索引;INSERT 0/1/3 索引)
#   SC-334 本脚本 + 报告
#   SC-335 ~ SC-338 SHOW 家族 / 虚拟表 / 18 张表(P6 增 PARAMETERS)/ SHOW CREATE TABLE round-trip
#   SC-339 / SC-340 图形客户端(仅人工项,脚本只做环境探测,不伪造回显)
#   SC-341 未支持语句降级(1235 / 1146 且连接不断)
#   SC-342 分页三种写法一致(LIMIT n / off,n / n OFFSET off;越界空集;DML LIMIT 1235)
#   SC-343 虚拟表多列排序(三列有序;用户表 ORDER BY 自 P5 起已支持)
#   SC-344 COUNT(*) 别名(别名即列名;无别名仍 COUNT(*);用户表自 P5 起已支持)
#   SC-345 单包多语句(3 条合成一包不报错;字符串内分号不切;中途语法错只回该条、后续不执行)
#   SC-346 SHOW PROCEDURE/FUNCTION/FUNC STATUS(11 列空集;LIKE/WHERE 无匹配仍空集,CODE=0)
#   SC-347 客户端盘点剩余缺口(SELECT 1 字面量投影 / SHOW COUNT(*) WARNINGS|ERRORS /
#          SHOW VARIABLES|STATUS|INDEX 的 WHERE 过滤;表达式投影仍 1064)
#
# 用法: bash scripts/tests/p4-test.sh
# 端口:BE 9900 / MySQL 9906 / 心跳 9991(K91);独立数据目录 tmp/p4-test/data。
# 警告:脚本会 taskkill 所有 be.exe 与 tangdb.fed.Fed 进程,不要与其它会话同时跑。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p4-test"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/report.txt"
SQL_DIR="${ROOT_DIR}/scripts/sql"

BE_PORT=9900
MYSQL_PORT=9906
HEART_PORT=9991
DB_USER=root
DB_PASS=p4test
DB_NAME=default

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

PASS=0
FAIL=0
NOTE_ONLY=0

mkdir -p "${TEST_DIR}"
rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}"
: >"${REPORT}"

log()  { echo "$*" | tee -a "${REPORT}"; }
ok()   { PASS=$((PASS + 1)); log "  [PASS] $*"; }
bad()  { FAIL=$((FAIL + 1)); log "  [FAIL] $*"; }
note() { NOTE_ONLY=$((NOTE_ONLY + 1)); log "  [NOTE] $*"; }

# ================================================================ 服务启停

BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"
TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"

# 只杀本脚本自己的 BE(tmp/p4-test/be.exe):所有者日常的 be/be.exe(9100)与 3406 上的 fed 不受影响。
kill_be() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    sleep 1
}

be_running() {
    powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1
}

FED_PID=""
kill_fed() {
    # 优先按启动时拿到的 PID 杀(可靠),再按命令行兜底(处理被接管/重启的情况)。
    if [ -n "${FED_PID}" ]; then
        taskkill /F /PID "${FED_PID}" >/dev/null 2>&1 || true
        FED_PID=""
    fi
    # 只杀本脚本起的 fed(命令行里带 tmp/p4-test 配置路径);3406 上的常驻 fed 不动。
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*tangdb.fed.Fed*' -and \$_.CommandLine -like '*${TEST_DIR_WIN}*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    sleep 1
}

cleanup() {
    kill_fed
    kill_be
}

wait_be_down() {
    for _ in $(seq 1 40); do
        if ! be_running; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

write_be_conf() { # <crash_point> [memtable_size]
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = ${2:-64MB}
fe_host = 127.0.0.1
fe_port = ${HEART_PORT}
heartbeat_interval_seconds = 3
max_txn_write_bytes = 64MB
test_crash_point = $1
EOF
}

write_fe_conf() { # [mysql_port] [heart_port] [backfill_batch]
    cat >"${FE_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
mode = fed
mysql_port = ${1:-${MYSQL_PORT}}
user = ${DB_USER}
password = ${DB_PASS}
session_max = 64
session_idle_timeout = 0
shutdown_grace_seconds = 5
heartbeat_listen_port = ${2:-${HEART_PORT}}
be_offline_cycles = 3
heartbeat_enabled = true
index_backfill_batch = ${3:-1000}
EOF
}

start_be() { # <log-name>
    local name="${1:-be.log}"
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${TEST_DIR}/${name}" 2>&1 &
    for _ in $(seq 1 80); do
        if grep -q "BE 已就绪" "${TEST_DIR}/${name}" 2>/dev/null; then
            return 0
        fi
        if grep -q "BE 启动失败" "${TEST_DIR}/${name}" 2>/dev/null; then
            log "BE 启动失败:"; tail -20 "${TEST_DIR}/${name}" | tee -a "${REPORT}"
            return 1
        fi
        sleep 0.5
    done
    log "BE 未在预期时间内就绪:"; tail -20 "${TEST_DIR}/${name}" | tee -a "${REPORT}"
    return 1
}

FED_LOG=""
start_fed() { # <log-name>
    local name="${1:-fed.log}"
    FED_LOG="${TEST_DIR}/${name}"
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.fed.Fed \
        --config="$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/${name}" 2>&1 &
    FED_PID=$!
    for _ in $(seq 1 80); do
        if grep -q "fed 已就绪" "${TEST_DIR}/${name}" 2>/dev/null; then
            return 0
        fi
        if grep -q "FE 启动失败" "${TEST_DIR}/${name}" 2>/dev/null; then
            log "fed 启动失败:"; tail -20 "${TEST_DIR}/${name}" | tee -a "${REPORT}"
            return 1
        fi
        sleep 0.5
    done
    log "fed 未在预期时间内就绪:"; tail -20 "${TEST_DIR}/${name}" | tee -a "${REPORT}"
    return 1
}

fresh_env() { # [be-log] [fed-log] [backfill_batch]
    kill_fed
    kill_be
    rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}"
    write_be_conf "" 64MB
    write_fe_conf "${MYSQL_PORT}" "${HEART_PORT}" "${3:-1000}"
    start_be "${1:-be.log}" || return 1
    start_fed "${2:-fed.log}" || return 1
    return 0
}

restart_be_with_crash() { # <crash_point> <log-name>
    kill_be
    write_be_conf "$1" 64MB
    start_be "$2"
}

run_sql_text() { # <sql-text>
    printf '%s\n' "$1" | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "${FE_CONF}")" 2>&1
}

run_sql_file() { # <file>
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "${FE_CONF}")" <"$1" 2>&1
}

expect() { # <output> <substr> <desc>
    if printf '%s' "$1" | grep -qF "$2"; then
        ok "$3"
    else
        bad "$3(期望输出包含: $2)"
        log "----- 实际输出 -----"; printf '%s\n' "$1" | tail -40 | tee -a "${REPORT}"; log "--------------------"
    fi
}

expect_absent() { # <output> <substr> <desc>
    if printf '%s' "$1" | grep -qF "$2"; then
        bad "$3(不该出现: $2)"
        log "----- 实际输出 -----"; printf '%s\n' "$1" | tail -40 | tee -a "${REPORT}"; log "--------------------"
    else
        ok "$3"
    fi
}

row_count() { # <output>
    printf '%s' "$1" | grep -oE '^\([0-9]+ rows?\)' | head -1 | grep -oE '[0-9]+' || true
}

col_count() { # <output> → MysqlErrorCheck 的 COLS= 行的列数
    printf '%s' "$1" | sed -n 's/^COLS=//p' | awk -F',' 'NF > 0 { print NF }'
}

ids_from() { # <output> → 排序后的第一列整数
    printf '%s\n' "$1" | sed -n 's/^| *\(-\{0,1\}[0-9][0-9]*\) *|$/\1/p' | sort -n | tr '\n' ' '
}

order_ok() { # <output> <表名方向 asc|desc> <表内 ordinal 方向 asc|desc>
    # 断言 information_schema.COLUMNS 的 4 列输出(TABLE_SCHEMA/TABLE_NAME/COLUMN_NAME/ORDINAL_POSITION)有序。
    printf '%s\n' "$1" | awk -F'|' -v nd="$2" -v od="$3" '
        /^\|/ {
            for (i = 2; i <= NF; i++) { gsub(/^[ \t]+|[ \t]+$/, "", $i) }
            ord = $5
            if (ord !~ /^[0-9]+$/) next   # 跳过表头 / 非结果行
            name = $3
            if (n > 0) {
                if (nd == "asc"  && name <  prev) bad = 1
                if (nd == "desc" && name >  prev) bad = 1
                if (name == prev) {
                    if (od == "asc"  && ord + 0 < prevord) bad = 1
                    if (od == "desc" && ord + 0 > prevord) bad = 1
                }
            }
            prev = name; prevord = ord + 0; n++
        }
        END { exit (bad || n == 0) ? 1 : 0 }'
}

diff_ids() { # <index-output> <baseline-output> → 0 相同 / 1 不同(或任一输出是错误)
    if printf '%s' "$1" | grep -qE "错误|连不上 fed" \
        || printf '%s' "$2" | grep -qE "错误|连不上 fed"; then
        return 1
    fi
    local a b
    a="$(ids_from "$1")"
    b="$(ids_from "$2")"
    if [ "$a" = "$b" ]; then return 0; else return 1; fi
}

mysql_code() { # <sql> → 打印 CODE=<n>
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.index.MysqlErrorCheck \
        "${MYSQL_PORT}" "${DB_USER}" "${DB_PASS}" "${DB_NAME}" "$1" 2>&1
}

time_ms() { # <sql> → 毫秒(含 CLI/JVM 启动)
    local start end
    start=$(date +%s%N)
    run_sql_text "$1; exit" >/dev/null 2>&1
    end=$(date +%s%N)
    echo $(( (end - start) / 1000000 ))
}

# ================================================================ 构建

log "=================================================="
log " tang_db P4 一键验收  $(date '+%Y-%m-%d %H:%M:%S')"
log "=================================================="

log "[构建] BE"
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }

log "[构建] FE(含测试类)"
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"
export CP

log "[配置] BE ${BE_PORT} / MySQL ${MYSQL_PORT} / 心跳 ${HEART_PORT} / 数据目录 ${DATA_DIR}"

# 无论正常结束、失败还是被 Ctrl+C / 外部终止,都清掉本脚本起的常驻进程。
trap cleanup EXIT
trap 'exit 130' INT TERM

# ================================================================ SC-319 二级索引读写闭环

log ""
log "---------- SC-319 二级索引读写闭环(索引路径 vs 全表扫) ----------"
fresh_env "be-sc319.log" "fed-sc319.log" || { log "无法启动环境,测试中止"; exit 1; }

run_sql_text "CREATE TABLE orders (id INT PRIMARY KEY, category VARCHAR(32), amount INT); exit" >/dev/null 2>&1
{
    echo "INSERT INTO orders (id, category, amount) VALUES"
    for i in $(seq 1 500); do
        if [ "$i" -gt 1 ]; then echo ","; fi
        printf "(%d, 'c%d', %d)" "$i" "$((i % 5))" "$((i * 2))"
    done
    echo ";"
    echo "exit"
} > "${TEST_DIR}/seed-orders.sql"
OUT="$(run_sql_file "${TEST_DIR}/seed-orders.sql")"
expect "${OUT}" "500 rows affected" "插入 500 行"

OUT="$(run_sql_text "CREATE INDEX idx_category ON orders (category); exit")"
expect "${OUT}" "状态 ACTIVE" "非空表 CREATE INDEX 回填完成并转 ACTIVE"

OUT="$(run_sql_text "EXPLAIN SELECT * FROM orders WHERE category = 'c3'; exit")"
expect "${OUT}" "INDEX POINT" "EXPLAIN 等值走 INDEX POINT"
expect "${OUT}" "idx_category" "EXPLAIN 报出索引名"
expect "${OUT}" "key 范围" "EXPLAIN 报出 key 范围"

DUI=0
for c in 0 1 2 3 4; do
    IDX_OUT="$(run_sql_text "SELECT id FROM orders WHERE category = 'c${c}'; exit")"
    BASE_OUT="$(run_sql_text "SELECT id FROM orders WHERE category = 'c${c}' AND id >= -2147483648; exit")"
    if ! diff_ids "${IDX_OUT}" "${BASE_OUT}"; then
        DUI=$((DUI + 1))
        log "    不一致 category=c${c}"
        log "    index:    $(ids_from "${IDX_OUT}")"
        log "    baseline: $(ids_from "${BASE_OUT}")"
    fi
done
if [ "${DUI}" = "0" ]; then ok "5 个索引值逐行对拍一致(索引路径 = 主键范围扫描)"; else bad "索引路径与全表扫不一致(${DUI} 组)"; fi

OUT="$(run_sql_text "SHOW INDEXES FROM orders; exit")"
expect "${OUT}" "idx_category" "SHOW INDEXES 列出索引"
expect "${OUT}" "ACTIVE" "SHOW INDEXES 状态 ACTIVE"
expect "${OUT}" "build_cursor" "SHOW INDEXES 有回填游标列"

# ================================================================ SC-320 索引列 UPDATE

log ""
log "---------- SC-320 索引列 UPDATE 维护 ----------"
OUT="$(run_sql_text "UPDATE orders SET category = '新值' WHERE id = 7;
SELECT id FROM orders WHERE category = '新值';
SELECT category FROM orders WHERE id = 7;
exit")"
expect "${OUT}" "1 row affected" "UPDATE 修改索引列"
if printf '%s' "${OUT}" | grep -q "| 7  |"; then ok "新值可通过索引查到 id=7"; else bad "新值查不到 id=7"; fi
expect "${OUT}" "新值" "主键行同步为新值"
OLD_LINE="$(run_sql_text "SELECT id FROM orders WHERE category = 'c2' AND id = 7; exit")"
if [ "$(row_count "${OLD_LINE}")" = "0" ]; then ok "旧值查不到 id=7(墓碑 + 二次校验生效)"; else bad "旧值仍能查到(可能有幽灵行)"; fi

# ================================================================ SC-321 唯一索引单会话

log ""
log "---------- SC-321 ★ 唯一索引挡重复(单会话) ----------"
run_sql_text "CREATE TABLE t (id INT PRIMARY KEY, code VARCHAR(16)); CREATE UNIQUE INDEX uk_code ON t (code); INSERT INTO t VALUES (1, 'A001'); exit" >/dev/null 2>&1
OUT="$(mysql_code "INSERT INTO t VALUES (2, 'A001')")"
expect "${OUT}" "CODE=1062" "重复插入返回 1062"
expect "${OUT}" "uk_code" "错误信息含索引名"
expect "${OUT}" "code" "错误信息含列名"
expect "${OUT}" "A001" "错误信息含冲突值"
expect "${OUT}" "已有主键 1" "错误信息含已有主键"
ROWS="$(row_count "$(run_sql_text "SELECT * FROM t; exit")")"
if [ "${ROWS}" = "1" ]; then ok "冲突后表里仍只有 1 行"; else bad "冲突后行数异常(${ROWS})"; fi

# ================================================================ SC-322 唯一索引并发

log ""
log "---------- SC-322 ★ 唯一索引并发 ----------"
run_sql_text "CREATE TABLE tc (id INT PRIMARY KEY, code VARCHAR(16)); CREATE UNIQUE INDEX uk_tc ON tc (code); exit" >/dev/null 2>&1
AOUT="${TEST_DIR}/uniq-a.out"; BOUT="${TEST_DIR}/uniq-b.out"
( echo "BEGIN;"; echo "INSERT INTO tc VALUES (1, 'X');"; sleep 6; echo "COMMIT;"; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${AOUT}" 2>&1 &
PA=$!
( sleep 1; echo "BEGIN;"; echo "INSERT INTO tc VALUES (2, 'X');"; sleep 6; echo "COMMIT;"; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${BOUT}" 2>&1 &
PB=$!
wait ${PA} ${PB} 2>/dev/null || true
A_TXT="$(cat "${AOUT}")"; B_TXT="$(cat "${BOUT}")"
COMMITS=0
printf '%s' "${A_TXT}" | grep -q "事务已提交" && COMMITS=$((COMMITS + 1))
printf '%s' "${B_TXT}" | grep -q "事务已提交" && COMMITS=$((COMMITS + 1))
UNIQUE_ERRORS=0
printf '%s' "${A_TXT}" | grep -q "唯一索引 uk_tc 冲突" && UNIQUE_ERRORS=$((UNIQUE_ERRORS + 1))
printf '%s' "${B_TXT}" | grep -q "唯一索引 uk_tc 冲突" && UNIQUE_ERRORS=$((UNIQUE_ERRORS + 1))
if [ "${COMMITS}" = "1" ] && [ "${UNIQUE_ERRORS}" = "1" ]; then
    ok "并发同一唯一键:恰好一个成功、另一个 1062(唯一约束未被击穿)"
else
    bad "并发唯一索引结果异常(commits=${COMMITS}, uniqueErrors=${UNIQUE_ERRORS})"
    log "----- A -----"; printf '%s\n' "${A_TXT}" | tail -12 | tee -a "${REPORT}"
    log "----- B -----"; printf '%s\n' "${B_TXT}" | tail -12 | tee -a "${REPORT}"
fi
expect "$(printf '%s%s' "${A_TXT}" "${B_TXT}")" "并发写入冲突" "并发触发的错误信息说明'并发写入冲突'"
ROWS="$(row_count "$(run_sql_text "SELECT * FROM tc; exit")")"
if [ "${ROWS}" = "1" ]; then ok "并发后表里只有 1 行"; else bad "并发后行数异常(${ROWS})"; fi

# ================================================================ SC-323 崩溃:索引与数据同批

log ""
log "---------- SC-323 ★ 崩溃:索引与数据同批(after_fsync / after_memtable) ----------"
fake_mismatch=0
for POINT in after_fsync after_memtable; do
    if [ "${POINT}" = "after_fsync" ]; then
        fresh_env "be-sc323a.log" "fed-sc323a.log" || { bad "SC-323 环境启动失败"; break; }
        run_sql_text "CREATE TABLE c (id INT PRIMARY KEY, code VARCHAR(16)); CREATE INDEX idx_code ON c (code); CREATE UNIQUE INDEX uk_code ON c (code); INSERT INTO c VALUES (1,'A'),(2,'B'),(3,'C'); exit" >/dev/null 2>&1
        INSERT_SQL="INSERT INTO c VALUES (100, 'Z')"
        NEW_ID=100
    else
        # 延续上一个环境(after_fsync 已恢复):再注入一次 after_memtable
        INSERT_SQL="INSERT INTO c VALUES (101, 'Y')"
        NEW_ID=101
    fi
    restart_be_with_crash "${POINT}" "be-crash-${POINT}.log" || { bad "${POINT}:BE 无法带崩溃注入启动"; continue; }
    kill_fed; start_fed "fed-crash-${POINT}.log" || { bad "${POINT}:fed 无法重启"; continue; }
    OUT="$(run_sql_text "${INSERT_SQL}; exit")"
    log "  注入点 ${POINT} 的 INSERT 回显: $(printf '%s' "${OUT}" | tail -2 | tr '\n' ' ')"
    wait_be_down || bad "${POINT}:BE 未在注入后退出"

    kill_be
    write_be_conf "" 64MB
    start_be "be-recover-${POINT}.log" || { bad "${POINT}:BE 恢复启动失败"; continue; }
    kill_fed; start_fed "fed-recover-${POINT}.log" || { bad "${POINT}:fed 恢复启动失败"; continue; }

    if [ "${POINT}" = "after_fsync" ]; then CODE_VALUE=Z; else CODE_VALUE=Y; fi
    PK_OUT="$(run_sql_text "SELECT id FROM c WHERE id = ${NEW_ID}; exit")"
    IDX_OUT="$(run_sql_text "SELECT id FROM c WHERE code = '${CODE_VALUE}'; exit")"
    PK_ROWS="$(row_count "${PK_OUT}")"; IDX_ROWS="$(row_count "${IDX_OUT}")"
    if printf '%s%s' "${PK_OUT}" "${IDX_OUT}" | grep -qE "错误|连不上 fed"; then
        fake_mismatch=$((fake_mismatch + 1))
        bad "${POINT}:恢复后查询失败(见下)"
        printf '%s\n%s\n' "${PK_OUT}" "${IDX_OUT}" | tail -10 | tee -a "${REPORT}"
    elif [ "${PK_ROWS}" = "1" ] && [ "${IDX_ROWS}" = "1" ]; then
        ok "${POINT}:主键路径与索引路径同时可见(同生)"
    elif [ "${PK_ROWS}" = "0" ] && [ "${IDX_ROWS}" = "0" ]; then
        ok "${POINT}:主键路径与索引路径同时不可见(同灭)"
    else
        fake_mismatch=$((fake_mismatch + 1))
        bad "${POINT}:主键与索引不同生同灭(pk=${PK_ROWS:-?}, idx=${IDX_ROWS:-?})"
    fi
    if diff_ids "${PK_OUT}" "${IDX_OUT}"; then ok "${POINT}:两路径结果集逐行一致"; else bad "${POINT}:两路径结果集不一致"; fi
done
if [ "${fake_mismatch}" = "0" ]; then ok "SC-323 两个注入点下三条用户键同生同灭"; fi

# ================================================================ SC-324 回填中断可续

log ""
log "---------- SC-324 ★ 崩溃:回填中断可续(BUILDING → 续做 → ACTIVE) ----------"
fresh_env "be-sc324.log" "fed-sc324.log" 10 || bad "SC-324 环境启动失败"
run_sql_text "CREATE TABLE bf (id INT PRIMARY KEY, code INT); exit" >/dev/null 2>&1
{
    echo "INSERT INTO bf (id, code) VALUES"
    for i in $(seq 1 20000); do
        if [ "$i" -gt 1 ]; then echo ","; fi
        printf "(%d, %d)" "$i" "$((i % 10))"
    done
    echo ";"
    echo "exit"
} > "${TEST_DIR}/seed-bf.sql"
expect "$(run_sql_file "${TEST_DIR}/seed-bf.sql")" "20000 rows affected" "准备 20000 行"

printf 'CREATE INDEX idx_code ON bf (code);\nexit\n' > "${TEST_DIR}/create-bf-index.sql"
( java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" < "${TEST_DIR}/create-bf-index.sql" > "${TEST_DIR}/create-bf.out" 2>&1 ) &
BUILD_PID=$!
sleep 2
kill_be
wait ${BUILD_PID} 2>/dev/null || true
log "  回填中途强杀后 CREATE INDEX 回显: $(tail -2 "${TEST_DIR}/create-bf.out" | tr '\n' ' ')"

write_be_conf "" 64MB
start_be "be-sc324-recover.log" || bad "SC-324:BE 恢复启动失败"
kill_fed; start_fed "fed-sc324-recover.log" || bad "SC-324:fed 恢复启动失败"

STATE_OUT="$(run_sql_text "SHOW INDEXES FROM bf; exit")"
if printf '%s' "${STATE_OUT}" | grep -q "BUILDING"; then
    ok "重启后索引仍为 BUILDING(不把半成品当 ACTIVE)"
else
    bad "重启后未见 BUILDING(回显见下)"
    printf '%s\n' "${STATE_OUT}" | tee -a "${REPORT}"
fi
CURSOR="$(printf '%s\n' "${STATE_OUT}" | sed -n 's/^| *idx_code *|.*| *\([0-9A-Fa-f][0-9A-Fa-f]*\) *|$/\1/p')"
if [ -n "${CURSOR}" ]; then ok "build_cursor 已持久化(${CURSOR})"; else note "首次回填批次过少,游标可能为空"; fi

DURING_EXPLAIN="$(run_sql_text "EXPLAIN SELECT * FROM bf WHERE code = 3; exit")"
expect "${DURING_EXPLAIN}" "TABLE SCAN" "BUILDING 期间查询退化全表扫且不报错"
DURING_COUNT="$(row_count "$(run_sql_text "SELECT id FROM bf WHERE code = 3; exit")")"
if [ "${DURING_COUNT}" = "2000" ]; then ok "BUILDING 期间全表扫结果正确(2000 行)"; else bad "BUILDING 期间结果异常(${DURING_COUNT})"; fi

RESUME_OUT="$(run_sql_text "CREATE INDEX idx_code ON bf (code); exit")"
expect "${RESUME_OUT}" "ACTIVE" "重跑同一条 CREATE INDEX 续做完成"
FINAL_EXPLAIN="$(run_sql_text "EXPLAIN SELECT * FROM bf WHERE code = 3; exit")"
expect "${FINAL_EXPLAIN}" "INDEX POINT" "续做完成后转为索引路径"

TOTAL=0
for c in $(seq 0 9); do
    CNT="$(row_count "$(run_sql_text "SELECT id FROM bf WHERE code = ${c}; exit")")"
    TOTAL=$((TOTAL + ${CNT:-0}))
done
if [ "${TOTAL}" = "20000" ]; then ok "续做后索引覆盖全部 20000 行(逐值求和)"; else bad "续做后索引漏行(求和=${TOTAL})"; fi

# ================================================================ SC-325 EXPLAIN 与实际执行一致

log ""
log "---------- SC-325 索引选择与 EXPLAIN 一致(五种路径) ----------"
run_sql_text "CREATE TABLE ex (id INT PRIMARY KEY, category VARCHAR(32), note VARCHAR(32)); INSERT INTO ex VALUES (1,'A','n1'),(2,'B','n2'),(3,'C','n3'); CREATE INDEX idx_ex ON ex (category); exit" >/dev/null 2>&1
P1="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE id = 1; exit")"
P2="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE id > 1; exit")"
P3="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE category = 'A'; exit")"
P4="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE category > 'A'; exit")"
P5="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE note = 'n1'; exit")"
expect "${P1}" "PRIMARY POINT" "① 主键等值 → PRIMARY POINT"
expect "${P2}" "PRIMARY RANGE" "② 主键范围 → PRIMARY RANGE"
expect "${P3}" "INDEX POINT" "③ 索引等值 → INDEX POINT"
expect "${P4}" "INDEX RANGE" "④ 索引范围 → INDEX RANGE"
expect "${P5}" "TABLE SCAN" "⑤ 无可用索引 → TABLE SCAN"
expect "${P3}" "残余过滤" "EXPLAIN 输出残余过滤条件"
expect "${P3}" "0000" "EXPLAIN 输出 key 范围十六进制摘要"

BEFORE=$(grep -c "索引扫描 表=ex" "${FED_LOG}" 2>/dev/null || true)
run_sql_text "SELECT id FROM ex WHERE category = 'A'; exit" >/dev/null 2>&1
AFTER=$(grep -c "索引扫描 表=ex" "${FED_LOG}" 2>/dev/null || true)
if [ "${AFTER:-0}" -gt "${BEFORE:-0}" ]; then ok "执行确实走索引(日志出现索引扫描统计)"; else bad "执行未走索引(EXPLAIN 与实际不一致)"; fi
if grep -q "索引扫描 表=ex 索引=idx_ex 索引项=" "${FED_LOG}"; then
    ok "索引扫描统计可观测(FR-512:索引项数 / 回表次数)"
else
    bad "日志缺少索引扫描统计"
fi
BEFORE=$(grep -c "索引扫描 表=ex" "${FED_LOG}" 2>/dev/null || true)
run_sql_text "SELECT id FROM ex WHERE note = 'n1'; exit" >/dev/null 2>&1
AFTER=$(grep -c "索引扫描 表=ex" "${FED_LOG}" 2>/dev/null || true)
if [ "${AFTER:-0}" -eq "${BEFORE:-0}" ]; then ok "TABLE SCAN 查询没有走索引(与 EXPLAIN 一致)"; else bad "TABLE SCAN 实际却走了索引"; fi

OUT="$(run_sql_text "EXPLAIN INSERT INTO ex VALUES (9,'Z','z'); exit")"
expect "${OUT}" "不支持解释该语句" "EXPLAIN INSERT 明确报不支持(FR-518)"

# ================================================================ SC-326 DROP INDEX

log ""
log "---------- SC-326 DROP INDEX ----------"
OLD_ID="$(printf '%s\n' "$(run_sql_text "SHOW INDEXES FROM ex; exit")" | sed -n 's/^| *idx_ex *|.*| *ACTIVE *| *\([0-9]*\) *|.*$/\1/p' | head -1)"
OUT="$(run_sql_text "DROP INDEX idx_ex ON ex; exit")"
expect "${OUT}" "已删除" "DROP INDEX 成功"
OUT="$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE category = 'A'; exit")"
expect "${OUT}" "TABLE SCAN" "DROP 后不再走索引"
ROWS_AFTER_DROP="$(row_count "$(run_sql_text "SELECT id FROM ex WHERE category = 'A'; exit")")"
if [ "${ROWS_AFTER_DROP}" = "1" ]; then ok "DROP INDEX 后结果不变"; else bad "DROP INDEX 改变了结果(${ROWS_AFTER_DROP})"; fi
OUT="$(run_sql_text "CREATE INDEX idx_ex ON ex (category); exit")"
expect "${OUT}" "索引 ID" "可同名重建"
NEW_ID="$(printf '%s\n' "$(run_sql_text "SHOW INDEXES FROM ex; exit")" | sed -n 's/^| *idx_ex *|.*| *ACTIVE *| *\([0-9]*\) *|.*$/\1/p' | head -1)"
if [ -n "${NEW_ID}" ] && [ "${NEW_ID}" != "${OLD_ID}" ]; then ok "重建索引 ID 不复用(${OLD_ID} → ${NEW_ID})"; else bad "重建索引 ID 未变化(${OLD_ID} → ${NEW_ID})"; fi
expect "$(run_sql_text "EXPLAIN SELECT * FROM ex WHERE category = 'A'; exit")" "INDEX POINT" "重建后恢复索引路径"

# ================================================================ SC-327 老数据目录升级

log ""
log "---------- SC-327 老数据目录升级(P3.5 目录,无 sys_indexes) ----------"
kill_fed; kill_be
rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}"
write_be_conf "" 64MB
if start_be "be-sc327.log"; then
    LOUT="$(java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.testing.LegacyDirMaker 127.0.0.1 "${BE_PORT}" 2>&1)"
    log "  [LegacyDirMaker] ${LOUT}"
    write_fe_conf "${MYSQL_PORT}" "${HEART_PORT}" 1000
    if start_fed "fed-sc327.log"; then
        OUT="$(run_sql_text "SELECT * FROM old_t; exit")"
        expect "${OUT}" "(2 rows)" "老表数据一条不丢"
        OUT="$(run_sql_text "SHOW INDEXES FROM old_t; exit")"
        expect "${OUT}" "(0 rows)" "老表没有索引(幂等建了 sys_indexes,不把老表当损坏)"
        OUT="$(run_sql_text "CREATE INDEX idx_legacy ON old_t (v); exit")"
        expect "${OUT}" "ACTIVE" "老目录可正常新建索引(回填成功)"
        OUT="$(run_sql_text "EXPLAIN SELECT * FROM old_t WHERE v = 'legacy'; exit")"
        expect "${OUT}" "INDEX POINT" "新索引在老表上生效"
        OUT="$(run_sql_text "SELECT id FROM old_t WHERE v = 'legacy'; exit")"
        expect "${OUT}" "(1 row)" "老数据可走索引查到"
        note "format_version 不变:BE 用同一份 MANIFEST 正常重启,无需清库;key 布局未改动"
        OUT="$(run_sql_text "CREATE TABLE after_upgrade (id INT PRIMARY KEY); exit")"
        expect "${OUT}" "已创建" "升级后仍可建表"
    else
        bad "fed 未能加载老数据目录"
    fi
else
    bad "老目录升级场景 BE 启动失败"
fi

# ================================================================ SC-328 事务内索引可见性

log ""
log "---------- SC-328 事务内索引可见性 ----------"
fresh_env "be-sc328.log" "fed-sc328.log" || bad "SC-328 环境启动失败"
run_sql_text "CREATE TABLE tv (id INT PRIMARY KEY, category VARCHAR(8)); CREATE INDEX idx_tv ON tv (category); INSERT INTO tv VALUES (1,'A'),(2,'A'); exit" >/dev/null 2>&1
( echo "BEGIN;"; echo "UPDATE tv SET category = 'B' WHERE id = 1;"; sleep 7; echo "COMMIT;"; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" > "${TEST_DIR}/txn-a.out" 2>&1 &
TA=$!
sleep 2
UNCOMMITTED="$(run_sql_text "SELECT id FROM tv WHERE category = 'A'; exit")"
if [ "$(row_count "${UNCOMMITTED}")" = "2" ]; then ok "未提交改动不通过索引对其它会话可见(仍是旧的两行)"; else bad "未提交改动泄漏(旧值行数=$(row_count "${UNCOMMITTED}"))"; fi
wait ${TA} 2>/dev/null || true
AFTER_A="$(run_sql_text "SELECT id FROM tv WHERE category = 'A'; exit")"
AFTER_B="$(run_sql_text "SELECT id FROM tv WHERE category = 'B'; exit")"
if [ "$(row_count "${AFTER_A}")" = "1" ] && [ "$(row_count "${AFTER_B}")" = "1" ]; then
    ok "提交后索引可见性正确(旧值 1 行 + 新值 1 行)"
else
    bad "提交后索引可见性异常(A=$(row_count "${AFTER_A}"), B=$(row_count "${AFTER_B}"))"
fi

# ================================================================ SC-329 一致性对账 5000 次

log ""
log "---------- SC-329 一致性对账(INV-10,5000 次随机写改删) ----------"
fresh_env "be-sc329.log" "fed-sc329.log" || bad "SC-329 环境启动失败"
run_sql_text "CREATE TABLE inv (id INT PRIMARY KEY, code VARCHAR(16), bucket INT); CREATE UNIQUE INDEX uk_code ON inv (code); CREATE INDEX idx_bucket ON inv (bucket); exit" >/dev/null 2>&1
: > "${TEST_DIR}/random.sql"
for i in $(seq 1 5000); do
    ID=$(( (RANDOM % 400) + 1 ))
    KIND=$(( RANDOM % 100 ))
    if [ "${KIND}" -lt 55 ]; then
        CODE=$(( RANDOM % 500 ))
        echo "INSERT INTO inv (id, code, bucket) VALUES (${ID}, 'v${CODE}', $((CODE % 10)));"
    elif [ "${KIND}" -lt 85 ]; then
        if [ $(( RANDOM % 2 )) -eq 0 ]; then
            echo "UPDATE inv SET bucket = $(( RANDOM % 10 )) WHERE id = ${ID};"
        else
            echo "UPDATE inv SET code = 'v$(( RANDOM % 500 ))' WHERE id = ${ID};"
        fi
    else
        echo "DELETE FROM inv WHERE id = ${ID};"
    fi
done
echo "exit" >> "${TEST_DIR}/random.sql"
log "  [随机脚本] 5000 条已生成,开始执行(唯一冲突会打印错误并继续,属预期)"
RAND_OUT="$(run_sql_file "${TEST_DIR}/random.sql")"
RAND_ERRORS=$(printf '%s' "${RAND_OUT}" | grep -c "唯一索引 uk_code 冲突" || true)
log "  随机执行期间的唯一冲突次数(预期非负): ${RAND_ERRORS}"

DIFF_COUNT=0
for b in $(seq 0 9); do
    IDX_OUT="$(run_sql_text "SELECT id FROM inv WHERE bucket = ${b}; exit")"
    BASE_OUT="$(run_sql_text "SELECT id FROM inv WHERE bucket = ${b} AND id >= -2147483648; exit")"
    diff_ids "${IDX_OUT}" "${BASE_OUT}" || DIFF_COUNT=$((DIFF_COUNT + 1))
done
CODES="$(run_sql_text "SELECT code FROM inv; exit" | sed -n 's/^| *\(v[0-9][0-9]*\) *|$/\1/p' | sort -u | head -30)"
for code in ${CODES}; do
    IDX_OUT="$(run_sql_text "SELECT id FROM inv WHERE code = '${code}'; exit")"
    BASE_OUT="$(run_sql_text "SELECT id FROM inv WHERE code = '${code}' AND id >= -2147483648; exit")"
    diff_ids "${IDX_OUT}" "${BASE_OUT}" || DIFF_COUNT=$((DIFF_COUNT + 1))
done
log "  一致性对账差异计数 = ${DIFF_COUNT}"
if [ "${DIFF_COUNT}" = "0" ]; then ok "INV-10:5000 次随机写改删后索引与全表扫差异为 0"; else bad "INV-10 对账存在 ${DIFF_COUNT} 组差异"; fi

# ================================================================ SC-330 索引元数据原子性

log ""
log "---------- SC-330 索引元数据原子性(DDL 阶段强杀) ----------"
fresh_env "be-sc330.log" "fed-sc330.log" || bad "SC-330 环境启动失败"
run_sql_text "CREATE TABLE cx (id INT PRIMARY KEY, code INT); INSERT INTO cx VALUES (1,1),(2,2),(3,3); exit" >/dev/null 2>&1
restart_be_with_crash "after_fsync" "be-sc330-crash.log" || bad "SC-330:带崩溃注入的 BE 启动失败"
kill_fed; start_fed "fed-sc330-crash.log" || bad "SC-330:fed 重启失败"
OUT="$(run_sql_text "CREATE INDEX idx_cx ON cx (code); exit")"
log "  CREATE INDEX 强杀回显: $(printf '%s' "${OUT}" | tail -2 | tr '\n' ' ')"
wait_be_down || bad "SC-330:BE 未退出"
kill_be; write_be_conf "" 64MB; start_be "be-sc330-recover.log" || bad "SC-330:BE 恢复失败"
kill_fed; start_fed "fed-sc330-recover.log" || bad "SC-330:fed 恢复失败"
STATE_OUT="$(run_sql_text "SHOW INDEXES FROM cx; exit")"
if printf '%s' "${STATE_OUT}" | grep -q "BUILDING"; then
    ok "建索引强杀后元数据完整存在且状态为 BUILDING(不是半套)"
else
    bad "建索引强杀后索引元数据异常"; printf '%s\n' "${STATE_OUT}" | tee -a "${REPORT}"
fi
OUT="$(run_sql_text "CREATE INDEX idx_cx ON cx (code); exit")"
expect "${OUT}" "ACTIVE" "强杀后重跑同一条 CREATE INDEX 可完成"
TOTAL="$(row_count "$(run_sql_text "SELECT id FROM cx WHERE code >= 0; exit")")"
if [ "${TOTAL}" = "3" ]; then ok "恢复后索引覆盖 3 行"; else bad "恢复后索引行数异常(${TOTAL})"; fi

# 建表内联索引的批记录原子性:同一批要么表 + 列 + 索引全在,要么全不在。
restart_be_with_crash "after_fsync" "be-sc330-ddl.log" || bad "SC-330:带崩溃注入的 BE 启动失败"
kill_fed; start_fed "fed-sc330-ddl.log" || bad "SC-330:fed 重启失败"
OUT="$(run_sql_text "CREATE TABLE atom (id INT PRIMARY KEY, code VARCHAR(8), note INT, UNIQUE(code), INDEX(note)); exit")"
wait_be_down || bad "SC-330:建表注入后 BE 未退出"
kill_be; write_be_conf "" 64MB; start_be "be-sc330-ddl-recover.log" || bad "SC-330:BE 恢复失败"
kill_fed; start_fed "fed-sc330-ddl-recover.log" || bad "SC-330:fed 恢复失败"
TABLES_OUT="$(run_sql_text "SHOW TABLES; exit")"
if printf '%s' "${TABLES_OUT}" | grep -q "atom"; then
    IDX_ROWS="$(row_count "$(run_sql_text "SHOW INDEXES FROM atom; exit")")"
    DESC_ROWS="$(run_sql_text "DESC atom; exit" | sed -n 's/^| *\(id\|code\|note\) *|.*$/\1/p' | wc -l)"
    if [ "${IDX_ROWS}" = "2" ] && [ "${DESC_ROWS}" -ge 3 ]; then
        ok "内联索引与建表同批:表在、列在、2 个索引都在(没有半套元数据)"
    else
        bad "内联索引原子性异常(indexes=${IDX_ROWS}, cols=${DESC_ROWS})"
    fi
else
    ok "内联索引与建表同批:强杀后表完全不存在(没有半套元数据)"
fi

# ================================================================ SC-331 依赖与边界红线

log ""
log "---------- SC-331 依赖与边界红线 ----------"
if grep -vE '^[[:space:]]*(//|#)' "${BE_DIR}/go.mod" | grep -Eqi 'rocksdb|leveldb|badger|pebble|sqlite|bbolt|duckdb|velox|hnswlib|faiss'; then
    bad "BE go.mod 出现禁止的存储引擎依赖"
else
    ok "BE go.mod 没有现成存储引擎依赖"
fi
DIRECT="$(cd "${BE_DIR}" && go list -m -f '{{if not .Indirect}}{{.Path}}{{end}}' all 2>/dev/null | grep -v '^$' || true)"
log "  BE 直接依赖: $(printf '%s' "${DIRECT}" | tr '\n' ' ')"
if printf '%s' "${DIRECT}" | grep -qv -E '^(github.com/1638540149/tang_db/be|google.golang.org/grpc|google.golang.org/protobuf)$'; then
    bad "BE 直接依赖超出 grpc / protobuf 红线"
else
    ok "BE 直接依赖只有 grpc / protobuf"
fi
if grep -Eqi 'calcite|jsqlparser|shardingsphere|sqlite|h2database|mysql-connector' "${FE_DIR}/pom.xml"; then
    bad "FE pom.xml 引入了第三方 SQL/协议/JDBC 库"
else
    ok "FE 没有第三方 SQL 解析/协议/JDBC 库"
fi
BE_DIFF="$(cd "${ROOT_DIR}" && git status --porcelain -- be proto 2>/dev/null | grep -v '^$' || true)"
if [ -z "${BE_DIFF}" ]; then
    ok "be/ 与 proto/ 零改动(SPEC-P4 SC-331)"
else
    # SPEC-P6.1 起本项不再是硬约束:补丁明确要改 proto(仅加字段)与 BE 写/读路径。
    # P4 的"零改动"是那一期的事实陈述,不是永久不变量;这里降级为 NOTE。
    note "be/ 与 proto/ 有改动(P6.1 起允许;依赖红线仍由 go.mod 检查把关)"
    printf '%s\n' "${BE_DIFF}" | tee -a "${REPORT}"
fi
if grep -RqiE 'data[/\\]BE' "${FE_DIR}/src/main/java" 2>/dev/null; then
    bad "FE 源码里出现 BE 数据目录路径"
else
    ok "FE 源码不引用 BE 数据目录"
fi

# ================================================================ SC-333 性能口径

log ""
log "---------- SC-333 性能口径 ----------"
run_sql_text "CREATE TABLE perf (id INT PRIMARY KEY, k INT); exit" >/dev/null 2>&1
{
    echo "INSERT INTO perf (id, k) VALUES"
    for i in $(seq 1 20000); do
        if [ "$i" -gt 1 ]; then echo ","; fi
        printf "(%d, %d)" "$i" "$i"
    done
    echo ";"
    echo "exit"
} > "${TEST_DIR}/seed-perf.sql"
run_sql_file "${TEST_DIR}/seed-perf.sql" >/dev/null 2>&1
run_sql_text "CREATE INDEX idx_k ON perf (k); exit" >/dev/null 2>&1

IDX_TIMES=""; SCAN_TIMES=""
for _ in 1 2 3; do
    IDX_TIMES="${IDX_TIMES} $(time_ms "SELECT id FROM perf WHERE k = 12345")"
    SCAN_TIMES="${SCAN_TIMES} $(time_ms "SELECT id FROM perf WHERE k = 12345 AND id >= -2147483648")"
done
IDX_MIN=$(printf '%s\n' ${IDX_TIMES} | sort -n | head -1)
SCAN_MIN=$(printf '%s\n' ${SCAN_TIMES} | sort -n | head -1)
IDX_STAT="$(grep "索引扫描 表=perf" "${FED_LOG}" 2>/dev/null | tail -1 || true)"
log "  条件:20000 行,int 主键,索引列 k 唯一取值;只读查询;CLI/JVM 启动计入耗时"
log "  索引路径: 3 次 ${IDX_TIMES} ms(取最小 ${IDX_MIN} ms);扫描行数=1(日志: ${IDX_STAT:-无统计})"
log "  全表扫:   3 次 ${SCAN_TIMES} ms(取最小 ${SCAN_MIN} ms);扫描行数=20000(主键范围扫描 + FE 残余过滤)"
ok "SC-333 查询两条路径的耗时与扫描行数已记录(同报条件)"

for N in 0 1 3; do
    BENCH="$(java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.index.IndexInsertLatencyBench \
        "${MYSQL_PORT}" "${DB_USER}" "${DB_PASS}" "${DB_NAME}" 300 "${N}" 2>&1)"
    log "  ${BENCH}"
done
ok "SC-333 单行 INSERT 在 0 / 1 / 3 个索引下的 p50 / p99 已记录"

# ================================================================ SC-335 SHOW 家族

log ""
log "---------- SC-335 ~ SC-338 SHOW 家族 / 虚拟表 / round-trip ----------"
run_sql_text "CREATE TABLE demo (id INT PRIMARY KEY, code VARCHAR(16), note VARCHAR(16)); CREATE UNIQUE INDEX uk_demo_code ON demo (code); CREATE INDEX idx_demo_note ON demo (note); INSERT INTO demo VALUES (1,'A','n1'); exit" >/dev/null 2>&1

SHOW_STMTS=(
    "SHOW FULL TABLES"
    "SHOW TABLES LIKE 'de%'"
    "SHOW TABLE STATUS LIKE 'demo'"
    "SHOW CREATE TABLE demo"
    "SHOW CREATE DATABASE default"
    "SHOW COLUMNS FROM demo"
    "SHOW FULL COLUMNS FROM demo"
    "SHOW INDEX FROM demo"
    "SHOW KEYS FROM demo"
    "SHOW INDEXES FROM demo"
    "SHOW ENGINES"
    "SHOW COLLATION"
    "SHOW CHARACTER SET"
    "SHOW GRANTS"
    "SHOW WARNINGS"
    "SHOW ERRORS"
    "SHOW TRIGGERS"
    "SHOW EVENTS"
    "SHOW ROUTINES"
    "SHOW VARIABLES"
    "SHOW VARIABLES LIKE 'version%'"
    "SHOW STATUS"
    "SHOW STATUS LIKE 'Threads%'"
    "SHOW PROCESSLIST"
    "SHOW FULL PROCESSLIST"
)
SHOW_BAD=0
for sql in "${SHOW_STMTS[@]}"; do
    OUT="$(run_sql_text "${sql}; exit")"
    if printf '%s' "${OUT}" | grep -q "错误"; then
        SHOW_BAD=$((SHOW_BAD + 1))
        log "    [BROKEN] ${sql}"; printf '%s\n' "${OUT}" | tail -5 | tee -a "${REPORT}"
    fi
done
if [ "${SHOW_BAD}" = "0" ]; then ok "SHOW 家族 25 条语句全部返回合理结果(无 1064)"; else bad "有 ${SHOW_BAD} 条 SHOW 语句失败"; fi
expect "$(run_sql_text "SHOW FULL TABLES; exit")" "Table_type" "SHOW FULL TABLES 带 Table_type 列"
expect "$(run_sql_text "SHOW TABLE STATUS LIKE 'demo'; exit")" "InnoDB" "SHOW TABLE STATUS 的 Engine 报 InnoDB(K93)"
expect "$(run_sql_text "SHOW FULL TABLES WHERE Table_type != 'VIEW'; exit")" "BASE TABLE" "SHOW FULL TABLES WHERE 生效(F 组起因语句)"
expect_absent "$(run_sql_text "SHOW GRANTS; exit")" "${DB_PASS}" "SHOW 输出不泄露口令"

# 虚拟表 18 张(SPEC-P6 FR-669 增加 PARAMETERS)
VT_BAD=0
for vt in SCHEMATA TABLES COLUMNS STATISTICS KEY_COLUMN_USAGE TABLE_CONSTRAINTS \
          REFERENTIAL_CONSTRAINTS VIEWS ROUTINES TRIGGERS EVENTS PROCESSLIST \
          CHARACTER_SETS COLLATIONS ENGINES PARTITIONS USER_PRIVILEGES PARAMETERS; do
    OUT="$(run_sql_text "SELECT * FROM information_schema.${vt}; exit")"
    if printf '%s' "${OUT}" | grep -q "错误"; then
        VT_BAD=$((VT_BAD + 1)); log "    [BROKEN] information_schema.${vt}"
        printf '%s\n' "${OUT}" | tail -5 | tee -a "${REPORT}"
    fi
done
if [ "${VT_BAD}" = "0" ]; then ok "information_schema 18 张虚拟表全部可查(无数据的返回列正确的空集)"; else bad "有 ${VT_BAD} 张虚拟表查询失败"; fi
expect "$(run_sql_text "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'default' AND TABLE_NAME = 'demo'; exit")" "| demo" "虚拟表投影裁剪 + WHERE 生效"
expect "$(run_sql_text "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME = 'demo'; exit")" "| 3" "虚拟表 COUNT(*) 生效(K98)"
expect "$(run_sql_text "SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_NAME = 'demo' ORDER BY INDEX_NAME DESC LIMIT 1; exit")" "| uk_demo_code" "虚拟表单列 ORDER BY + LIMIT 生效"
expect "$(run_sql_text "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_NAME = 'demo'; exit")" "TABLE_NAME" "虚拟表结果列名与 MySQL 对齐"
expect "$(run_sql_text "SHOW INDEX FROM demo; exit")" "PRIMARY" "SHOW INDEX 的主键行 Key_name = PRIMARY"
expect "$(run_sql_text "SHOW INDEX FROM demo; exit")" "uk_demo_code" "SHOW INDEX 的 STATISTICS 数据来自索引元数据"
expect "$(run_sql_text "SELECT CARDINALITY FROM information_schema.STATISTICS WHERE TABLE_NAME = 'demo' LIMIT 1; exit")" "NULL" "Cardinality 恒 NULL(不编造假数字)"

RTOUT="$(java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.index.IndexRoundTripCheck \
    "${MYSQL_PORT}" "${DB_USER}" "${DB_PASS}" "${DB_NAME}" 2>&1)"
expect "${RTOUT}" "ROUNDTRIP_PASS" "SHOW CREATE TABLE round-trip 可执行(SC-338)"

# ================================================================ SC-341 未支持语句降级

log ""
log "---------- SC-341 未支持语句的降级(1235 / 1146,连接不断) ----------"
OUT="$(mysql_code "SHOW SLAVE STATUS")"
expect "${OUT}" "CODE=1235" "未知 SHOW → 1235"
ALIVE="$(run_sql_text "SHOW SLAVE STATUS; SHOW DATABASES; exit")"
expect "${ALIVE}" "default" "未知 SHOW 之后连接仍可用"
OUT="$(mysql_code "SELECT * FROM information_schema.no_such_table")"
expect "${OUT}" "CODE=1146" "未知虚拟表 → 1146"
# SPEC-P6 FR-667:门禁移除后虚拟表 JOIN 真支持(不再是 1235)
OUT="$(mysql_code "SELECT COUNT(*) FROM information_schema.TABLES t JOIN information_schema.COLUMNS c ON t.TABLE_NAME = c.TABLE_NAME")"
expect "${OUT}" "CODE=0" "虚拟表 JOIN 已支持(P6 兑现)"
# SPEC-P5 起用户表 COUNT(*) / ORDER BY 真支持,这里改为断言行为正确(不再 1235)。
OUT="$(mysql_code "SELECT COUNT(*) FROM demo")"
expect "${OUT}" "CODE=0" "用户表 COUNT(*) 已支持(P5 兑现)"
OUT="$(mysql_code "SELECT * FROM demo ORDER BY id")"
expect "${OUT}" "CODE=0" "用户表 ORDER BY 已支持(P5 兑现)"
OUT="$(mysql_code "ALTER TABLE demo ADD COLUMN x INT")"
if printf '%s' "${OUT}" | grep -q "CODE=[1-9]"; then ok "ALTER TABLE 明确报错(未支持,不静默执行)"; else bad "ALTER TABLE 未被拒绝"; fi
OUT="$(mysql_code "SHOW CREATE TABLE no_such_table")"
expect "${OUT}" "CODE=1146" "SHOW CREATE TABLE 表不存在 → 1146"

# ================================================================ SC-342 分页

log ""
log "---------- SC-342 分页三种写法一致 + 边界(FR-549 ~ FR-552) ----------"
fresh_env "be-sc342.log" "fed-sc342.log" || bad "SC-342 环境启动失败"
run_sql_text "CREATE TABLE page (id INT PRIMARY KEY, cat VARCHAR(8));
CREATE INDEX idx_page_cat ON page (cat);
INSERT INTO page VALUES (1,'a'),(2,'a'),(3,'a'),(4,'a'),(5,'b'),(6,'b'),(7,'a'),(8,'a'),(9,'b'),(10,'a');
exit" >/dev/null 2>&1

L_PLAIN="$(run_sql_text "SELECT id FROM page LIMIT 5; exit")"
L_COMMA="$(run_sql_text "SELECT id FROM page LIMIT 0, 5; exit")"
L_OFFSET="$(run_sql_text "SELECT id FROM page LIMIT 5 OFFSET 0; exit")"
IDS_PLAIN="$(ids_from "${L_PLAIN}")"
if [ -n "${IDS_PLAIN}" ] && [ "${IDS_PLAIN}" = "$(ids_from "${L_COMMA}")" ] \
        && [ "${IDS_PLAIN}" = "$(ids_from "${L_OFFSET}")" ]; then
    ok "LIMIT n / LIMIT off,n / LIMIT n OFFSET off 三种写法结果一致(ids=${IDS_PLAIN})"
else
    bad "三种分页写法结果不一致(plain=${IDS_PLAIN} comma=$(ids_from "${L_COMMA}") offset=$(ids_from "${L_OFFSET}"))"
    log "----- LIMIT n -----"; printf '%s\n' "${L_PLAIN}" | tail -12 | tee -a "${REPORT}"
    log "----- LIMIT 0,5 -----"; printf '%s\n' "${L_COMMA}" | tail -12 | tee -a "${REPORT}"
    log "----- LIMIT 5 OFFSET 0 -----"; printf '%s\n' "${L_OFFSET}" | tail -12 | tee -a "${REPORT}"
fi

P1="$(run_sql_text "SELECT id FROM page WHERE cat = 'a' LIMIT 2, 3; exit")"
P2="$(run_sql_text "SELECT id FROM page WHERE cat = 'a' LIMIT 3 OFFSET 2; exit")"
expect "$(ids_from "${P1}")" "3 4 7 " "先过滤 → 跳 offset → 截断 count(LIMIT 2,3 → 3 4 7)"
if [ -n "$(ids_from "${P1}")" ] && [ "$(ids_from "${P1}")" = "$(ids_from "${P2}")" ]; then
    ok "LIMIT 2,3 与 LIMIT 3 OFFSET 2 结果一致"
else
    bad "LIMIT 2,3 与 LIMIT 3 OFFSET 2 不一致(comma=$(ids_from "${P1}") offset=$(ids_from "${P2}"))"
fi

I1="$(run_sql_text "SELECT id FROM page WHERE cat = 'a' LIMIT 1, 2; exit")"
S1="$(run_sql_text "SELECT id FROM page WHERE cat = 'a' AND id >= -2147483648 LIMIT 1, 2; exit")"
if [ -n "$(ids_from "${I1}")" ] && [ "$(ids_from "${I1}")" = "$(ids_from "${S1}")" ]; then
    ok "索引路径与全表扫路径分页一致(ids=$(ids_from "${I1}"))"
else
    bad "索引路径与全表扫路径分页不一致(index=$(ids_from "${I1}") scan=$(ids_from "${S1}"))"
fi

ROWS="$(row_count "$(run_sql_text "SELECT id FROM page LIMIT 100000, 1000; exit")")"
if [ "${ROWS}" = "0" ]; then ok "offset 越界返回空集(边界 33)"; else bad "offset 越界应返回空集,实际 rows=${ROWS}"; fi
ROWS="$(row_count "$(run_sql_text "SELECT id FROM page LIMIT 0, 0; exit")")"
if [ "${ROWS}" = "0" ]; then ok "LIMIT 0,0 返回空集(边界 32)"; else bad "LIMIT 0,0 应返回空集,实际 rows=${ROWS}"; fi

for BAD_SQL in "SELECT id FROM page LIMIT -1, 10" "SELECT id FROM page LIMIT 1.5" \
               "SELECT id FROM page LIMIT" "SELECT id FROM page LIMIT 10 OFFSET"; do
    expect "$(mysql_code "${BAD_SQL}")" "CODE=1064" "非法分页参数报 1064:${BAD_SQL}"
done

expect "$(mysql_code "UPDATE page SET cat = 'z' LIMIT 1")" "CODE=1235" "UPDATE ... LIMIT → 1235(FR-554)"
expect "$(mysql_code "DELETE FROM page LIMIT 1")" "CODE=1235" "DELETE ... LIMIT → 1235(FR-554)"
ROWS="$(row_count "$(run_sql_text "SELECT id FROM page; exit")")"
if [ "${ROWS}" = "10" ]; then ok "带 LIMIT 的 DML 被拒绝且无副作用(仍 10 行)"; else bad "带 LIMIT 的 DML 拒绝后有副作用(rows=${ROWS})"; fi

# ================================================================ SC-343 虚拟表多列排序

log ""
log "---------- SC-343 虚拟表多列排序(K99 / FR-553) ----------"
fresh_env "be-sc343.log" "fed-sc343.log" || bad "SC-343 环境启动失败"
run_sql_text "CREATE TABLE ord_a (id INT PRIMARY KEY, code VARCHAR(8), note VARCHAR(8));
CREATE TABLE ord_b (id INT PRIMARY KEY, code VARCHAR(8));
CREATE TABLE ord_c (id INT PRIMARY KEY, code VARCHAR(8));
exit" >/dev/null 2>&1

ORD_SQL="SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION FROM information_schema.COLUMNS"
ASC_OUT="$(run_sql_text "${ORD_SQL} WHERE TABLE_SCHEMA = 'default' ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION LIMIT 0, 50; exit")"
if order_ok "${ASC_OUT}" asc asc; then
    ok "三列 ORDER BY ASC 有序(表名升序、表内 ordinal 升序)"
else
    bad "三列 ORDER BY ASC 未按列序排列"
    printf '%s\n' "${ASC_OUT}" | tail -20 | tee -a "${REPORT}"
fi
if printf '%s\n' "${ASC_OUT}" | grep -qE '\| *ord_a *\| *id *\| *1 *\|'; then
    ok "三列 ORDER BY 结果内容正确(ord_a.id 是 ord_a 的首列)"
else
    bad "三列 ORDER BY 结果内容不正确(缺 ord_a.id = 1)"
fi

DESC_OUT="$(run_sql_text "${ORD_SQL} WHERE TABLE_SCHEMA = 'default' ORDER BY TABLE_SCHEMA, TABLE_NAME DESC, ORDINAL_POSITION DESC LIMIT 0, 50; exit")"
if order_ok "${DESC_OUT}" desc desc; then
    ok "每列可独立 DESC(表名降序、表内 ordinal 降序)"
else
    bad "多列混合 DESC 未按列序排列"
    printf '%s\n' "${DESC_OUT}" | tail -20 | tee -a "${REPORT}"
fi
if printf '%s\n' "${DESC_OUT}" | grep -qE '\| *ord_c *\| *code *\| *2 *\|'; then
    ok "DESC 首行是 ord_c 的末列"
else
    bad "DESC 首行不是 ord_c.code(2)"
fi

LIMIT_OUT="$(run_sql_text "${ORD_SQL} WHERE TABLE_SCHEMA = 'default' ORDER BY TABLE_SCHEMA, TABLE_NAME LIMIT 0, 4; exit")"
ROWS="$(row_count "${LIMIT_OUT}")"
if [ "${ROWS}" = "4" ]; then ok "虚拟表分页 LIMIT 0,4 生效"; else bad "虚拟表 LIMIT 0,4 未生效(rows=${ROWS})"; fi

NAVICAT_OUT="$(run_sql_text "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION LIMIT 0,50; exit")"
expect_absent "${NAVICAT_OUT}" "错误" "Navicat 式三列排序 + LIMIT 0,50 不再报错"
expect "${NAVICAT_OUT}" "COLUMN_NAME" "Navicat 式三列排序返回正确列"
expect "$(mysql_code "SELECT * FROM ord_a ORDER BY id, code")" "CODE=0" "用户表多列 ORDER BY 已支持(P5 兑现)"

# ================================================================ SC-344 COUNT(*) 别名

log ""
log "---------- SC-344 COUNT(*) 别名(FR-555 / K102) ----------"
fresh_env "be-sc344.log" "fed-sc344.log" || bad "SC-344 环境启动失败"
run_sql_text "CREATE TABLE h_alias (id INT PRIMARY KEY, note VARCHAR(16));
INSERT INTO h_alias VALUES (1, 'a');
exit" >/dev/null 2>&1

OUT="$(mysql_code "SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'")"
expect "${OUT}" "CODE=0" "Navicat 的 COUNT(*) AS support_ndb 自检不再报 1235"
expect "${OUT}" "COLS=support_ndb" "别名即结果列名(support_ndb)"
expect "${OUT}" "ROWS=1" "COUNT(*) 返回 1 行"

OUT="$(mysql_code "SELECT COUNT(*) support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'")"
expect "${OUT}" "CODE=0" "不带 AS 的聚合别名同样被接受(K102)"
expect "${OUT}" "COLS=support_ndb" "不带 AS 时别名仍是结果列名"

OUT="$(mysql_code "SELECT COUNT(*) FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'")"
expect "${OUT}" "COLS=COUNT(*)" "无别名时列名仍为 COUNT(*)"

expect "$(mysql_code "SELECT COUNT(*) AS c FROM h_alias")" "CODE=0" "用户表 COUNT(*) AS c 已支持(P5 兑现)"
expect "$(mysql_code "SELECT COUNT(*) FROM h_alias")" "CODE=0" "用户表 COUNT(*) 已支持(P5 兑现)"

# ================================================================ SC-345 单包多语句

log ""
log "---------- SC-345 单包多语句(FR-556 ~ FR-558 / K101) ----------"
fresh_env "be-sc345.log" "fed-sc345.log" || bad "SC-345 环境启动失败"
run_sql_text "CREATE TABLE h_batch (id INT PRIMARY KEY, note VARCHAR(16)); exit" >/dev/null 2>&1

NAV_BATCH="SHOW VARIABLES LIKE 'lower_case_%'; SHOW VARIABLES LIKE 'sql_mode'; SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'"
OUT="$(mysql_code "${NAV_BATCH}")"
expect "${OUT}" "CODE=0" "Navicat 连接期 3 条语句塞进一个包不再报错"
expect "${OUT}" "COLS=support_ndb" "未声明 CLIENT_MULTI_STATEMENTS 时只回最后一条(列名 support_ndb)"

expect "$(mysql_code "INSERT INTO h_batch VALUES (1, 'a;b'); SELECT note FROM h_batch WHERE id = 1")" \
    "CODE=0" "字符串内含分号的语句不被切开"
expect "$(run_sql_text "SELECT note FROM h_batch WHERE id = 1; exit")" "a;b" "字符串内容完整(a;b)"

OUT="$(mysql_code "SHOW DATABASES; SELEC * FROM h_batch; INSERT INTO h_batch VALUES (2, 'x')")"
expect "${OUT}" "CODE=1064" "一包内第 2 条语法错 → 只回第 2 条的 1064"
ROWS="$(row_count "$(run_sql_text "SELECT id FROM h_batch; exit")")"
if [ "${ROWS}" = "1" ]; then ok "第 3 条未执行(FR-558,表里仍只有 1 行)"; else bad "第 3 条被执行了(rows=${ROWS})"; fi
expect "$(mysql_code "SHOW DATABASES")" "CODE=0" "批量报错后下一条 COM_QUERY 正常"

# ================================================================ SC-346 SHOW PROCEDURE / FUNCTION STATUS

log ""
log "---------- SC-346 SHOW PROCEDURE / FUNCTION STATUS(FR-559 / H-6 / 边界 38) ----------"
fresh_env "be-sc346.log" "fed-sc346.log" || bad "SC-346 环境启动失败"
ROUTINE_COLS="Db,Name,Type,Definer,Modified,Created,Security_type,Comment,character_set_client,collation_connection,Database Collation"

OUT="$(mysql_code "SHOW PROCEDURE STATUS")"
expect "${OUT}" "CODE=0" "SHOW PROCEDURE STATUS 返回 CODE=0(不再报 1235)"
expect "${OUT}" "COLS=${ROUTINE_COLS}" "SHOW PROCEDURE STATUS 列名与 MySQL 5.7 一致"
expect "${OUT}" "ROWS=0" "SHOW PROCEDURE STATUS 返回空集"

OUT="$(mysql_code "SHOW PROCEDURE STATUS WHERE Db = 'default'")"
expect "${OUT}" "CODE=0" "Navicat 式 SHOW PROCEDURE STATUS WHERE Db = 'default' 不再报 1235"
if [ "$(col_count "${OUT}")" = "11" ]; then
    ok "WHERE 形态返回 11 列(实际 $(col_count "${OUT}"))"
else
    bad "WHERE 形态列数=$(col_count "${OUT}")(期望 11)"
fi
expect "${OUT}" "ROWS=0" "WHERE Db = 'default' 返回空集"

OUT="$(mysql_code "SHOW PROCEDURE STATUS WHERE Db = 'no_such_db'")"
expect "${OUT}" "CODE=0" "WHERE 过滤到不存在的库不报错"
expect "${OUT}" "ROWS=0" "WHERE 过滤到不存在的库返回空集(边界 38)"

OUT="$(mysql_code "SHOW PROCEDURE STATUS LIKE 'no_such_proc%'")"
expect "${OUT}" "CODE=0" "LIKE 无匹配不报错"
expect "${OUT}" "ROWS=0" "LIKE 无匹配返回空集(边界 38)"
expect "${OUT}" "COLS=${ROUTINE_COLS}" "LIKE 形态仍返回 11 列定义"

OUT="$(mysql_code "SHOW FUNCTION STATUS")"
expect "${OUT}" "CODE=0" "SHOW FUNCTION STATUS 返回 CODE=0"
expect "${OUT}" "COLS=${ROUTINE_COLS}" "SHOW FUNCTION STATUS 列名与 PROCEDURE 一致"

OUT="$(mysql_code "SHOW FUNC STATUS")"
expect "${OUT}" "CODE=0" "SHOW FUNC STATUS 返回 CODE=0"
expect "${OUT}" "COLS=${ROUTINE_COLS}" "SHOW FUNC STATUS 列名与 PROCEDURE 一致"

expect "$(mysql_code "SHOW DATABASES")" "CODE=0" "SC-346 之后连接仍可用"

# ================================================================ SC-347 客户端盘点剩余缺口

log ""
log "---------- SC-347 客户端盘点的剩余缺口(FR-560 ~ FR-562 / 边界 39~40) ----------"
fresh_env "be-sc347.log" "fed-sc347.log" || bad "SC-347 环境启动失败"
run_sql_text "CREATE TABLE h_probe (id INT PRIMARY KEY, note VARCHAR(16)); exit" >/dev/null 2>&1
run_sql_text "CREATE INDEX idx_note ON h_probe (note); exit" >/dev/null 2>&1

# ---- FR-560:无 FROM 的字面量投影 ----
OUT="$(mysql_code "SELECT 1")"
expect "${OUT}" "CODE=0" "SELECT 1 返回 CODE=0(连接探针不再 1064)"
expect "${OUT}" "ROWS=1" "SELECT 1 返回一行"
expect "${OUT}" "COLS=1" "SELECT 1 列名为字面量文本(1)"

OUT="$(mysql_code "SELECT 1, 'x'")"
expect "${OUT}" "CODE=0" "SELECT 1, 'x' 返回 CODE=0"
expect "${OUT}" "ROWS=1" "SELECT 1, 'x' 返回一行"
expect "${OUT}" "COLS=1,x" "SELECT 1, 'x' 列名为字面量文本(1, x)"

OUT="$(run_sql_text "SELECT 1, 'x'; exit")"
if [ "$(row_count "${OUT}")" = "1" ]; then
    ok "CLI 视角 SELECT 1, 'x' 输出 1 行"
else
    bad "CLI 视角 SELECT 1, 'x' 行数=$(row_count "${OUT}")(期望 1)"
fi
expect "${OUT}" "| 1 | x |" "CLI 输出列名与值都是字面量(1 / x)"

# 边界 39:表达式 / 函数仍归 P5,报 1064,不得返回错值。
expect "$(mysql_code "SELECT 1+1")" "CODE=1064" "SELECT 1+1 仍报 1064(表达式归 P5)"
expect "$(mysql_code "SELECT now()")" "CODE=1064" "SELECT now() 仍报 1064(函数归 P5)"

# ---- FR-561:SHOW COUNT(*) WARNINGS / ERRORS ----
OUT="$(mysql_code "SHOW COUNT(*) WARNINGS")"
expect "${OUT}" "CODE=0" "SHOW COUNT(*) WARNINGS 返回 CODE=0"
expect "${OUT}" "COLS=@@session.warning_count" "列名为 @@session.warning_count"
expect "${OUT}" "ROWS=1" "返回单值一行"
OUT="$(run_sql_text "SHOW COUNT(*) WARNINGS; exit")"
if printf '%s\n' "${OUT}" | grep -qE '^\| *0 *\|$'; then
    ok "warning_count 值为 0"
else
    bad "warning_count 值不是 0"
    log "----- 实际输出 -----"; printf '%s\n' "${OUT}" | tail -10 | tee -a "${REPORT}"; log "--------------------"
fi

OUT="$(mysql_code "SHOW COUNT(*) ERRORS")"
expect "${OUT}" "CODE=0" "SHOW COUNT(*) ERRORS 返回 CODE=0"
expect "${OUT}" "COLS=@@session.error_count" "列名为 @@session.error_count"
expect "${OUT}" "ROWS=1" "返回单值一行"
OUT="$(run_sql_text "SHOW COUNT(*) ERRORS; exit")"
if printf '%s\n' "${OUT}" | grep -qE '^\| *0 *\|$'; then
    ok "error_count 值为 0"
else
    bad "error_count 值不是 0"
    log "----- 实际输出 -----"; printf '%s\n' "${OUT}" | tail -10 | tee -a "${REPORT}"; log "--------------------"
fi

# ---- FR-562:SHOW VARIABLES / STATUS / INDEX 的 WHERE ----
OUT="$(mysql_code "SHOW VARIABLES WHERE Variable_name = 'version'")"
expect "${OUT}" "CODE=0" "SHOW VARIABLES WHERE 不报 1064/1235"
expect "${OUT}" "COLS=Variable_name,Value" "SHOW VARIABLES 列名不变"
expect "${OUT}" "ROWS=1" "WHERE Variable_name='version' 只留 1 行"
OUT="$(run_sql_text "SHOW VARIABLES WHERE Variable_name = 'version'; exit")"
expect "${OUT}" "5.7.99-tangdb" "过滤出的 version 是真值"
expect_absent "${OUT}" "character_set_client" "其它变量被过滤掉"

OUT="$(mysql_code "SHOW VARIABLES WHERE Variable_name = 'no_such_variable'")"
expect "${OUT}" "CODE=0" "SHOW VARIABLES WHERE 过滤到无匹配不报错"
expect "${OUT}" "ROWS=0" "无匹配返回空集(边界 40)"

OUT="$(mysql_code "SHOW STATUS WHERE Variable_name = 'Uptime'")"
expect "${OUT}" "CODE=0" "SHOW STATUS WHERE 不报 1064/1235"
expect "${OUT}" "COLS=Variable_name,Value" "SHOW STATUS 列名不变"
expect "${OUT}" "ROWS=1" "WHERE Variable_name='Uptime' 只留 1 行"
expect "$(run_sql_text "SHOW STATUS WHERE Variable_name = 'Uptime'; exit")" "Uptime" \
    "过滤出的状态行是 Uptime"

OUT="$(mysql_code "SHOW INDEX FROM h_probe WHERE Key_name = 'PRIMARY'")"
expect "${OUT}" "CODE=0" "SHOW INDEX ... WHERE 不报 1064/1235"
expect "${OUT}" "COLS=Table,Non_unique,Key_name,Seq_in_index,Column_name,Collation,Cardinality,Sub_part,Packed,Null,Index_type,Comment,Index_comment" \
    "SHOW INDEX 仍为 MySQL 13 列"
expect "${OUT}" "ROWS=1" "WHERE Key_name='PRIMARY' 只留主键 1 行"

OUT="$(mysql_code "SHOW INDEX FROM h_probe WHERE Key_name = 'no_such_index'")"
expect "${OUT}" "CODE=0" "SHOW INDEX WHERE 过滤到无匹配不报错"
expect "${OUT}" "ROWS=0" "无匹配索引返回空集"

OUT="$(mysql_code "SHOW KEYS FROM h_probe WHERE Non_unique = 0")"
expect "${OUT}" "CODE=0" "SHOW KEYS WHERE 数字列过滤可用"
expect "${OUT}" "ROWS=1" "WHERE Non_unique=0 只留主键"

OUT="$(mysql_code "SHOW INDEXES FROM h_probe WHERE index_name = 'idx_note'")"
expect "${OUT}" "CODE=0" "SHOW INDEXES WHERE 不报错"
expect "${OUT}" "ROWS=1" "WHERE index_name='idx_note' 只留二级索引"

expect "$(mysql_code "SHOW DATABASES")" "CODE=0" "SC-347 之后连接仍可用"

# ================================================================ SC-339 / SC-340 图形客户端

log ""
log "---------- SC-339 / SC-340 图形客户端(人工项) ----------"
NAVICAT_FOUND=""
for p in "/c/Program Files/Navicat" "/c/Program Files (x86)/Navicat" "/c/Program Files/PremiumSoft"; do
    if [ -d "$p" ]; then NAVICAT_FOUND="$p"; break; fi
done
if [ -n "${NAVICAT_FOUND}" ]; then
    note "检测到 Navicat 目录 ${NAVICAT_FOUND},但脚本无法自动操作 GUI;SC-339 仍需人工按 README 步骤执行并截图"
else
    note "本机未检测到 Navicat;SC-339 未验证(仅人工可验)"
fi
DATAGRIP_FOUND=""
for p in "/c/Program Files/JetBrains" "/c/Users/${USER:-$(whoami)}/AppData/Local/JetBrains"; do
    if [ -d "$p" ]; then
        if find "$p" -maxdepth 4 -iname '*DataGrip*' 2>/dev/null | grep -q .; then DATAGRIP_FOUND="$p"; break; fi
    fi
done
if [ -n "${DATAGRIP_FOUND}" ]; then
    note "检测到 DataGrip(${DATAGRIP_FOUND}),但脚本无法自动操作 GUI;SC-340 仍需人工执行"
else
    note "本机未检测到 DataGrip;SC-340 未验证(仅人工可验)"
fi
note "客户端兼容层已由 SC-335 ~ SC-338 的 SHOW / 虚拟表 / round-trip 自动化覆盖;GUI 全流程不伪造回显"

# ================================================================ SC-332 既有回归

log ""
log "---------- SC-332 既有回归(p2 + p3 + p35) ----------"
kill_fed
kill_be
for SCRIPT in p2-test.sh p3-test.sh p35-test.sh; do
    log "[回归] bash scripts/tests/${SCRIPT}"
    (cd "${ROOT_DIR}" && bash "scripts/tests/${SCRIPT}" >"${TEST_DIR}/${SCRIPT}.log" 2>&1)
    RC=$?
    SUMMARY="$(grep -E '结果:PASS=' "${TEST_DIR}/${SCRIPT}.log" | tail -1 || true)"
    log "  ${SCRIPT} 退出码 ${RC}; ${SUMMARY}"
    if [ "${RC}" -eq 0 ] && printf '%s' "${SUMMARY}" | grep -q "FAIL=0"; then
        ok "${SCRIPT} 全绿"
    else
        bad "${SCRIPT} 失败(见 ${TEST_DIR}/${SCRIPT}.log)"
        tail -30 "${TEST_DIR}/${SCRIPT}.log" | tee -a "${REPORT}"
    fi
done

# ================================================================ 收尾

kill_fed
kill_be

log ""
log "=================================================="
log " 结果:PASS=${PASS}  FAIL=${FAIL}  NOTE=${NOTE_ONLY}"
log " 报告:${REPORT}"
log "=================================================="

if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
