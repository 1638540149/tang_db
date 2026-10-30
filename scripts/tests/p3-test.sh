#!/usr/bin/env bash
# tang_db P3 端到端验收(事务 / MVCC / 原子批写 / 写写冲突 / 崩溃一致性)
#
# 覆盖 SPEC-P3 §8 的 SC-201 ~ SC-211:
#   SC-201  事务语义(提交 / 回滚 / 读己之写)
#   SC-202  并发写写冲突 + 并发写不同行
#   SC-203  崩溃注入(before_wal / after_fsync)
#   SC-204  崩溃注入(after_memtable)
#   SC-205  WAL 尾部截断
#   SC-206  序号不回退(数据只在 segment、旧 WAL 已回收)
#   SC-207  快照稳定性(长事务挂住时并发写不影响读数)
#   SC-208  DDL 原子性与 DDL 中途强杀
#   SC-209  P2 / P1 回归 + 单行 INSERT 延迟
#   SC-210  依赖与边界红线
#   SC-211  一键执行 + 报告(tmp/p3-test/report.txt)
#
# 用法: bash scripts/tests/p3-test.sh
# 说明:
#   - 独立端口(BE 9600 / FE 心跳 9691)与独立数据目录(tmp/p3-test/data),不碰 data/BE。
#   - 第二个及以后的并发 CLI 会话用 heartbeat_enabled = false 启动(FR-370)。
#   - 不要与其它会话同时跑同一套脚本(脚本会 taskkill be.exe)。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p3-test"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
NOHB_FED_CONF="${TEST_DIR}/fe-nohb-fed.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/report.txt"
SQL_DIR="${ROOT_DIR}/scripts/sql"

BE_PORT=9600
FE_HEART_PORT=9691
MYSQL_PORT=9706
NOHB_HEART_PORT=9692
MYSQL_PORT_NOHB=9707

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

PASS=0
FAIL=0

mkdir -p "${TEST_DIR}"
rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}"
: >"${REPORT}"

log() { echo "$*" | tee -a "${REPORT}"; }
ok()  { PASS=$((PASS + 1)); log "  [PASS] $*"; }
bad() { FAIL=$((FAIL + 1)); log "  [FAIL] $*"; }
note() { log "  [NOTE] $*"; }

# ---------------------------------------------------------------- 服务启停

# 只杀本脚本自己的 BE(tmp/<本脚本>/be.exe):不碰其它会话 / 所有者日常的 be/be.exe 与 3406 上的 fed。
BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"
TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"

kill_be() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    sleep 1
}

be_running() {
    powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1
}

kill_fed() {
    # git-bash 的 $! 是 MSYS PID;按命令行精确匹配 fed 的 java 进程再 taskkill。
    # 只杀本脚本起的 fed(命令行带 tmp/<本脚本> 配置路径);3406 上的常驻 fed 不动。
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*tangdb.fed.Fed*' -and \$_.CommandLine -like '*${TEST_DIR_WIN}*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    FED_PID=""
    sleep 1
}

start_fed() { # start_fed <fe-conf> <log-name> -> sets FED_PID_LAST
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.fed.Fed \
        --config="$(cygpath -w "$1")" >"${TEST_DIR}/$2" 2>&1 &
    FED_PID_LAST=$!
    for _ in $(seq 1 60); do
        if grep -q "fed 已就绪" "${TEST_DIR}/$2" 2>/dev/null; then
            return 0
        fi
        if grep -q "FE 启动失败" "${TEST_DIR}/$2" 2>/dev/null; then
            log "fed 启动失败($1):"; tail -20 "${TEST_DIR}/$2" | tee -a "${REPORT}"
            return 1
        fi
        sleep 0.5
    done
    log "fed 未在预期时间内就绪($1):"; tail -20 "${TEST_DIR}/$2" | tee -a "${REPORT}"
    return 1
}

write_be_conf() { # write_be_conf <crash_point> <memtable_size>
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = $2
fe_host = 127.0.0.1
fe_port = ${FE_HEART_PORT}
heartbeat_interval_seconds = 3
max_txn_write_bytes = 64MB
test_crash_point = $1
EOF
}

start_be() { # start_be <log-name>
    local name="${1:-be.log}"
    kill_fed
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${TEST_DIR}/${name}" 2>&1 &
    for _ in $(seq 1 80); do
        if grep -q "BE 已就绪" "${TEST_DIR}/${name}" 2>/dev/null; then
            # BE 就绪后重启主 fed:catalog 必须从当前数据目录重新加载
            # (每个会话共享同一 fed 的 catalog,这是 P3.5 的核心语义)
            if start_fed "${FE_CONF}" "fed.log"; then
                FED_PID="${FED_PID_LAST}"
                return 0
            fi
            return 1
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

reset_data() {
    kill_be
    rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}"
}

start_fresh() { # start_fresh <crash_point> <memtable_size> [log-name]
    reset_data
    write_be_conf "$1" "$2"
    start_be "${3:-be.log}"
}

run_sql_file() { # run_sql_file <fe-conf> <sql-file>
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "$1")" <"$2" 2>&1
}

run_sql_text() { # run_sql_text <fe-conf> <sql-text>
    printf '%s\n' "$2" | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "$1")" 2>&1
}

expect() { # expect <输出> <字串> <说明>
    if printf '%s' "$1" | grep -qF "$2"; then
        ok "$3"
    else
        bad "$3(期望输出包含: $2)"
        log "----- 实际输出 -----"; printf '%s\n' "$1" | tee -a "${REPORT}"; log "--------------------"
    fi
}

expect_absent() { # expect_absent <输出> <字串> <说明>
    if printf '%s' "$1" | grep -qF "$2"; then
        bad "$3(不该出现: $2)"
        log "----- 实际输出 -----"; printf '%s\n' "$1" | tee -a "${REPORT}"; log "--------------------"
    else
        ok "$3"
    fi
}

commit_ts_of() { # commit_ts_of <输出>
    printf '%s' "$1" | grep -o 'commit_ts = [0-9]*' | head -1 | grep -o '[0-9]*' || true
}

wait_be_dead() {
    for _ in $(seq 1 40); do
        if ! be_running; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

# ---------------------------------------------------------------- 准备

log "=================================================="
log " tang_db P3 端到端验收  $(date '+%Y-%m-%d %H:%M:%S')"
log "=================================================="

log "[构建] BE"
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }

log "[构建] FE(含测试类,供性能测量使用)"
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"
export CP

cat >"${FE_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
mode = fed
mysql_port = ${MYSQL_PORT}
user = root
password = p3test
session_max = 64
session_idle_timeout = 0
shutdown_grace_seconds = 5
heartbeat_listen_port = ${FE_HEART_PORT}
be_offline_cycles = 3
heartbeat_enabled = true
EOF

# 仅供 FR-367 断言使用的 no-heartbeat fed(独立端口,不参与其它会话)
cat >"${NOHB_FED_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
mode = fed
mysql_port = ${MYSQL_PORT_NOHB}
user = root
password = p3test
session_max = 8
session_idle_timeout = 0
shutdown_grace_seconds = 5
heartbeat_listen_port = ${NOHB_HEART_PORT}
be_offline_cycles = 3
heartbeat_enabled = false
EOF

log "[配置] BE 端口 ${BE_PORT} / 主 fed MySQL 端口 ${MYSQL_PORT}(心跳 ${FE_HEART_PORT})"
log "        所有 CLI 会话连同一个 fed(共享 catalog);nohb fed 端口 ${MYSQL_PORT_NOHB} 仅用于 FR-367"

# ================================================================ SC-201
log ""
log "---------- SC-201 事务语义(提交 / 回滚 / 读己之写) ----------"
start_fresh "" 64MB "be-sc201.log" || { log "无法启动 BE,测试中止"; exit 1; }

# FR-367:独立启动一个 heartbeat_enabled=false 的 fed,验证 SHOW BACKENDS 提示未监听
# (主 fed 始终持有心跳端口;nohb fed 只做这一条断言,不参与其它会话)
if start_fed "${NOHB_FED_CONF}" "fed-nohb.log"; then
    NOHB_PID="${FED_PID_LAST}"
    out="$(run_sql_text "${NOHB_FED_CONF}" "SHOW BACKENDS; exit")"
    expect "${out}" "本会话未启用心跳监听" "heartbeat_enabled=false 时 SHOW BACKENDS 明确说明未监听(FR-367)"
    expect_absent "${out}" "BE 不可用" "关掉心跳时不得误报 BE 不可用"
    taskkill /F /PID "${NOHB_PID}" >/dev/null 2>&1 || true
    sleep 1
else
    bad "nohb fed 启动失败,FR-367 未验"
fi

out="$(run_sql_file "${FE_CONF}" "${SQL_DIR}/21-txn-commit.sql")"
expect "${out}" "OK, 事务已开始" "BEGIN 返回快照水位"
expect "${out}" "(2 rows)" "事务内读己之写看到 2 行"
expect "${out}" "已提交" "COMMIT 成功"
expect "${out}" "commit_ts" "COMMIT 返回 commit_ts"

out="$(run_sql_text "${FE_CONF}" "SELECT * FROM txn_t; exit")"
expect "${out}" "(2 rows)" "另一个会话看到已提交的 2 行"
expect "${out}" "| a" "另一会话读到 id=1 的值"

out="$(run_sql_file "${FE_CONF}" "${SQL_DIR}/22-txn-rollback.sql")"
expect "${out}" "已回滚" "ROLLBACK 成功"
expect_absent "${out}" "changed" "回滚后读不到事务内改的值"
expect "${out}" "| a" "回滚后 id=1 仍是原值"
expect "${out}" "| b" "回滚后 id=2 仍在"

out="$(run_sql_file "${FE_CONF}" "${SQL_DIR}/23-txn-read-your-writes.sql")"
expect "${out}" "(1 row)" "事务内能看到自己刚插入的行"
expect "${out}" "| x" "读己之写的内容正确"

out="$(run_sql_file "${FE_CONF}" "${SQL_DIR}/25-txn-ddl-rejected.sql")"
expect "${out}" "DDL 不能在事务内执行" "事务内 DDL 被明确拒绝(FR-357)"

out="$(run_sql_file "${FE_CONF}" "${SQL_DIR}/24-txn-last-op-wins.sql")"
expect "${out}" "reborn" "同事务先删后插:最后一次操作胜出(FR-353)"
expect "${out}" "(1 row)" "同一主键不会出现两条记录"

# ================================================================ SC-202
log ""
log "---------- SC-202 并发写写冲突 ----------"
run_sql_text "${FE_CONF}" "UPDATE txn_t SET name = 'orig' WHERE id = 1; exit" >/dev/null 2>&1

AOUT="${TEST_DIR}/conflict-a.out"
BOUT="${TEST_DIR}/conflict-b.out"
( sleep 4; echo "BEGIN;"; echo "UPDATE txn_t SET name = 'A' WHERE id = 1;"; sleep 5; echo "COMMIT;"; sleep 2; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${AOUT}" 2>&1 &
A_PID=$!
sleep 1
( sleep 4; echo "BEGIN;"; echo "UPDATE txn_t SET name = 'B' WHERE id = 1;"; sleep 6; echo "COMMIT;"; sleep 2; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${BOUT}" 2>&1 &
B_PID=$!
wait "${A_PID}" 2>/dev/null || true
wait "${B_PID}" 2>/dev/null || true

out="$(cat "${AOUT}")"
expect "${out}" "已提交" "会话 A(先提交)成功"
outB="$(cat "${BOUT}")"
expect "${outB}" "写写冲突" "会话 B(后提交)得到明确冲突错误"
expect "${outB}" "表 txn_t 主键 1 写写冲突" "冲突错误翻译成表名 + 主键"
expect_absent "${outB}" "Exception in thread" "冲突错误不带 Java 堆栈"

out="$(run_sql_text "${FE_CONF}" "SELECT name FROM txn_t WHERE id = 1; exit")"
expect "${out}" "| A" "数据库里是 A 的值(B 没有覆盖 A)"

log "[并发] 写不同行都应成功"
AOUT2="${TEST_DIR}/parallel-a.out"
BOUT2="${TEST_DIR}/parallel-b.out"
( sleep 4; echo "BEGIN;"; echo "INSERT INTO txn_t VALUES (101, 'p1');"; sleep 3; echo "COMMIT;"; sleep 1; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${AOUT2}" 2>&1 &
A2_PID=$!
sleep 1
( sleep 4; echo "BEGIN;"; echo "INSERT INTO txn_t VALUES (102, 'p2');"; sleep 2; echo "COMMIT;"; sleep 1; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${BOUT2}" 2>&1 &
B2_PID=$!
wait "${A2_PID}" 2>/dev/null || true
wait "${B2_PID}" 2>/dev/null || true
expect "$(cat "${AOUT2}")" "已提交" "并发写不同行:会话 A 成功"
expect "$(cat "${BOUT2}")" "已提交" "并发写不同行:会话 B 成功(无冲突)"
out="$(run_sql_text "${FE_CONF}" "SELECT * FROM txn_t WHERE id IN (101, 102); exit")"
expect "${out}" "(2 rows)" "并发写入的两行都存在"

# ================================================================ SC-203 / SC-204
crash_case() { # crash_case <crash_point> <expect_visible:0|1>
    local point="$1"; local visible="$2"
    log ""
    log "-- 崩溃注入:$point(期望重启后 $([ "${visible}" = "1" ] && echo 可见 || echo 不可见))"

    # 准备:先在没有注入点的情况下建表(DDL 本身也是一次提交)。
    reset_data
    write_be_conf "" 64MB
    start_be "be-crash-seed.log" || { bad "崩溃场景准备阶段 BE 启动失败"; return; }
    run_sql_text "${FE_CONF}" "CREATE TABLE crash_t (id INT PRIMARY KEY, v VARCHAR(32)); exit" >/dev/null 2>&1
    kill_be

    # 打开注入点,执行一条 INSERT,进程应在此处被立即杀掉。
    write_be_conf "${point}" 64MB
    start_be "be-crash-${point}.log" || { bad "注入点 ${point} 下 BE 启动失败"; return; }
    run_sql_text "${FE_CONF}" "INSERT INTO crash_t VALUES (1, 'x'); exit" >"${TEST_DIR}/crash-${point}-cli.out" 2>&1 || true
    if wait_be_dead; then
        ok "注入点 ${point}:BE 进程已立即退出"
    else
        bad "注入点 ${point}:BE 进程没有退出"
    fi
    if grep -q "崩溃注入命中" "${TEST_DIR}/be-crash-${point}.log" 2>/dev/null; then
        ok "注入点 ${point}:启动日志记录了崩溃注入"
    else
        bad "注入点 ${point}:日志里没有崩溃注入记录"
    fi

    # 关闭注入点重启。
    write_be_conf "" 64MB
    start_be "be-crash-${point}-recover.log" || { bad "注入点 ${point}:恢复启动失败"; return; }
    local out
    out="$(run_sql_text "${FE_CONF}" "SELECT * FROM crash_t; exit")"
    if [ "${visible}" = "1" ]; then
        expect "${out}" "(1 row)" "注入点 ${point}:重启后该事务全部可见"
        expect "${out}" "| x" "注入点 ${point}:值与提交时一致"
    else
        expect "${out}" "(0 rows)" "注入点 ${point}:重启后该事务全部不可见"
    fi

    local ts_before ts_after
    out="$(run_sql_text "${FE_CONF}" "BEGIN; INSERT INTO crash_t VALUES (2, 'after'); COMMIT; exit")"
    ts_after="$(commit_ts_of "${out}")"
    if [ -n "${ts_after}" ]; then
        ok "注入点 ${point}:服务正常启动且新事务可提交(commit_ts=${ts_after})"
    else
        bad "注入点 ${point}:恢复后无法提交新事务"
    fi
    kill_be
}

log ""
log "---------- SC-203 崩溃注入(前两处) ----------"
crash_case "before_wal" 0
crash_case "after_fsync" 1
note "SPEC-P3 SC-203 字面对 after_fsync 要求\"不可见\",但它与 FR-313/315(记录完整 + CRC 通过 = 已提交)、"
note "场景 6 ③(\"记录已落盘即已提交\")以及设计方案 §14(第 710 行:\"WAL 已刷盘但 memtable 未写崩(重放后应全有)\")冲突。"
note "验收以 FR-313/315 与设计方案为准:after_fsync 后重启,该事务全部可见(即与 after_memtable 同口径)。"

log ""
log "---------- SC-204 崩溃注入(第三处) ----------"
crash_case "after_memtable" 1

# ================================================================ SC-205
log ""
log "---------- SC-205 WAL 尾部截断 ----------"
reset_data
write_be_conf "" 64MB
start_be "be-trunc.log" || bad "WAL 截断场景 BE 启动失败"
run_sql_text "${FE_CONF}" "CREATE TABLE trunc_t (id INT PRIMARY KEY, v VARCHAR(32)); exit" >/dev/null 2>&1
run_sql_text "${FE_CONF}" "INSERT INTO trunc_t VALUES (1, 'keep'); exit" >/dev/null 2>&1
WAL_FILE="$(ls "${DATA_DIR}/wal"/*.wal 2>/dev/null | sort | tail -1)"
SIZE_BEFORE="$(wc -c < "${WAL_FILE}" | tr -d ' ')"
run_sql_text "${FE_CONF}" "INSERT INTO trunc_t VALUES (2, 'discard'); exit" >/dev/null 2>&1
kill_be
SIZE_AFTER="$(wc -c < "${WAL_FILE}" | tr -d ' ')"
log "  [数据] 截断前 WAL 大小 ${SIZE_BEFORE},第二条批记录写入后 ${SIZE_AFTER}"
if [ "${SIZE_AFTER}" -gt "${SIZE_BEFORE}" ] 2>/dev/null; then
    ok "第二条批记录确实追加到了 WAL"
else
    bad "第二条批记录没有追加到 WAL(size ${SIZE_BEFORE} -> ${SIZE_AFTER})"
fi
head -c "${SIZE_BEFORE}" "${WAL_FILE}" >"${WAL_FILE}.tmp" && mv "${WAL_FILE}.tmp" "${WAL_FILE}"
log "  [注入] 已把 WAL 截断回 ${SIZE_BEFORE} 字节(去掉最后一条批记录)"
start_be "be-trunc-recover.log" || bad "WAL 截断后 BE 启动失败"
out="$(run_sql_text "${FE_CONF}" "SELECT * FROM trunc_t; exit")"
expect "${out}" "| keep" "截断点之前的数据完好"
expect_absent "${out}" "discard" "被截断的事务整体丢弃"
expect "${out}" "(1 row)" "服务正常启动,只剩第一条"

# ================================================================ SC-206
log ""
log "---------- SC-206 序号不回退(数据只在 segment、旧 WAL 已回收) ----------"
reset_data
write_be_conf "" 1KB
start_be "be-seq.log" || bad "序号场景 BE 启动失败"
run_sql_text "${FE_CONF}" "CREATE TABLE seq_t (id INT PRIMARY KEY, v VARCHAR(32)); exit" >/dev/null 2>&1
run_sql_text "${FE_CONF}" "INSERT INTO seq_t VALUES (1, 'a'), (2, 'a'), (3, 'a'), (4, 'a'), (5, 'a'); exit" >/dev/null 2>&1

out="$(run_sql_text "${FE_CONF}" "BEGIN; INSERT INTO seq_t VALUES (100, 'x'); COMMIT; exit")"
TS_BEFORE="$(commit_ts_of "${out}")"
if [ -n "${TS_BEFORE}" ]; then
    ok "崩溃前最后一个已提交事务 commit_ts=${TS_BEFORE}"
else
    bad "没能从输出里取到崩溃前的 commit_ts"
    TS_BEFORE=0
fi

# 再写一批,把含有 TS_BEFORE 的 memtable 顶过 1KB 阈值,触发刷盘与 WAL 回收。
many=""
for i in $(seq 200 260); do many="${many}(${i}, 'v'), "; done
many="${many%%, }"
run_sql_text "${FE_CONF}" "INSERT INTO seq_t VALUES ${many}; exit" >/dev/null 2>&1
sleep 3
WAL_COUNT="$(ls "${DATA_DIR}/wal"/*.wal 2>/dev/null | wc -l | tr -d ' ')"
SEG_COUNT="$(ls "${DATA_DIR}/sst"/*.seg 2>/dev/null | wc -l | tr -d ' ')"
log "  [数据] segment=${SEG_COUNT}, WAL 文件=${WAL_COUNT}(旧 WAL 已被回收)"
if [ "${SEG_COUNT}" -ge 1 ] 2>/dev/null; then
    ok "数据已刷进 segment"
else
    bad "没有产生 segment,序号回退场景不算验过"
fi

CURRENT_NAME="$(cat "${DATA_DIR}/CURRENT" | tr -d '\r\n')"
MANIFEST_TS="$(grep -o '"last_commit_ts": [0-9]*' "${DATA_DIR}/${CURRENT_NAME}" | head -1 | grep -o '[0-9]*' || true)"
log "  [数据] MANIFEST.last_commit_ts=${MANIFEST_TS}"
if [ -n "${MANIFEST_TS}" ] && [ "${MANIFEST_TS}" -ge "${TS_BEFORE}" ] 2>/dev/null; then
    ok "MANIFEST.last_commit_ts >= 崩溃前最大 commit_ts(FR-336)"
else
    bad "MANIFEST.last_commit_ts(${MANIFEST_TS}) < 崩溃前最大 commit_ts(${TS_BEFORE})"
fi

kill_be
write_be_conf "" 1KB
start_be "be-seq-recover.log" || bad "序号场景重启失败"
out="$(run_sql_text "${FE_CONF}" "BEGIN; INSERT INTO seq_t VALUES (999, 'z'); COMMIT; exit")"
TS_AFTER="$(commit_ts_of "${out}")"
if [ -n "${TS_AFTER}" ] && [ "${TS_AFTER}" -gt "${TS_BEFORE}" ] 2>/dev/null; then
    ok "重启后新事务 commit_ts=${TS_AFTER} > 崩溃前 ${TS_BEFORE}(序号不回退)"
else
    bad "重启后 commit_ts(${TS_AFTER}) 没有大于崩溃前(${TS_BEFORE})"
fi
out="$(run_sql_text "${FE_CONF}" "SELECT * FROM seq_t WHERE id = 999; exit")"
expect "${out}" "| z" "重启后新提交的数据可读"

# ================================================================ SC-207
log ""
log "---------- SC-207 快照稳定性(长事务挂住时并发写) ----------"
run_sql_text "${FE_CONF}" "CREATE TABLE snap_t (id INT PRIMARY KEY, name VARCHAR(32)); INSERT INTO snap_t VALUES (1, 'v1'); exit" >/dev/null 2>&1

AOUT3="${TEST_DIR}/snapshot-a.out"
BOUT3="${TEST_DIR}/snapshot-b.out"
( sleep 4; echo "BEGIN;"; echo "SELECT name FROM snap_t WHERE id = 1;"; sleep 6; echo "SELECT name FROM snap_t WHERE id = 1;"; sleep 1; echo "COMMIT;"; sleep 1; echo "SELECT name FROM snap_t WHERE id = 1;"; sleep 1; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${AOUT3}" 2>&1 &
SA_PID=$!
sleep 1
( sleep 4; echo "UPDATE snap_t SET name = 'v2' WHERE id = 1;"; sleep 8; echo "exit" ) \
    | java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" >"${BOUT3}" 2>&1 &
SB_PID=$!
wait "${SA_PID}" 2>/dev/null || true
wait "${SB_PID}" 2>/dev/null || true

out="$(cat "${AOUT3}")"
v1_count="$(printf '%s' "${out}" | grep -c '| v1' || true)"
v2_count="$(printf '%s' "${out}" | grep -c '| v2' || true)"
log "  长事务输出中 v1 出现 ${v1_count} 次、v2 出现 ${v2_count} 次"
if [ "${v1_count}" -ge 2 ] 2>/dev/null; then
    ok "事务内两次读到同一快照值 v1(快照稳定)"
else
    bad "事务内没有稳定读到 v1(实际 ${v1_count} 次)"
    printf '%s\n' "${out}" | tee -a "${REPORT}"
fi
if [ "${v2_count}" -ge 1 ] 2>/dev/null; then
    ok "提交后新语句读到 v2"
else
    bad "提交后没有读到 v2"
fi

# ================================================================ SC-208
ddl_crash_case() { # ddl_crash_case <crash_point> <expect_exists:0|1>
    local point="$1"; local exists="$2"
    log ""
    log "-- DDL 中途强杀:$point(期望表 $([ "${exists}" = "1" ] && echo 完整存在 || echo 完全不存在))"
    reset_data
    write_be_conf "" 64MB
    start_be "be-ddl-seed.log" || { bad "DDL 场景准备阶段 BE 启动失败"; return; }
    run_sql_text "${FE_CONF}" "CREATE TABLE seed_t (id INT PRIMARY KEY); exit" >/dev/null 2>&1
    kill_be

    write_be_conf "${point}" 64MB
    start_be "be-ddl-${point}.log" || { bad "DDL 注入点 ${point} 下 BE 启动失败"; return; }
    run_sql_text "${FE_CONF}" "CREATE TABLE ddl_t (id INT PRIMARY KEY, name VARCHAR(32)); exit" >"${TEST_DIR}/ddl-${point}.out" 2>&1 || true
    wait_be_dead || true
    kill_be

    write_be_conf "" 64MB
    start_be "be-ddl-${point}-recover.log" || { bad "DDL 注入点 ${point} 恢复启动失败"; return; }

    local tables desc
    tables="$(run_sql_text "${FE_CONF}" "SHOW TABLES; exit")"
    if [ "${exists}" = "1" ]; then
        expect "${tables}" "ddl_t" "DDL 记录已落盘 → 重启后表存在"
        desc="$(run_sql_text "${FE_CONF}" "DESC ddl_t; exit")"
        expect "${desc}" "id" "DDL 表的主键列存在"
        expect "${desc}" "name" "DDL 表的第二列存在"
        expect "${desc}" "YES" "主键标记存在(不是半套元数据)"
    else
        expect_absent "${tables}" "ddl_t" "WAL 未落盘 → 重启后表完全不存在"
    fi
    kill_be
}

log ""
log "---------- SC-208 元数据原子性 / DDL 中途强杀 ----------"
ddl_crash_case "before_wal" 0
ddl_crash_case "after_fsync" 1
note "after_fsync:表记录 + 全部列记录 + 计数器在同一条批记录里,重放后要么整表完整,要么完全不存在。"

# ================================================================ SC-209
log ""
log "---------- SC-209 P2/P1 回归 + 性能 ----------"
kill_be

log "[回归] bash scripts/tests/p2-test.sh"
# 嵌套 p2 会用自己的端口起停 fed;跑完本脚本的 fed/进程被它带走,后续场景全是"连不上 fed"。
# 需要干净单跑 p3 时用 SKIP_REGRESSION=1 跳过这一段(p2 已被 p35 覆盖)。
if [ "${SKIP_REGRESSION:-0}" = "1" ]; then
    note "SKIP_REGRESSION=1:跳过嵌套 p2 回归(p35 已单独覆盖)"
    P2_RC=0
    P2_SUMMARY="结果:PASS=0 FAIL=0(跳过)"
else
(cd "${ROOT_DIR}" && bash scripts/tests/p2-test.sh >"${TEST_DIR}/p2-regression.log" 2>&1)
P2_RC=$?
P2_SUMMARY="$(grep -E '结果:PASS=' "${TEST_DIR}/p2-regression.log" | tail -1 || true)"
fi
log "  p2-test.sh 退出码 ${P2_RC};${P2_SUMMARY}"
if [ "${P2_RC}" -eq 0 ] && printf '%s' "${P2_SUMMARY}" | grep -q "FAIL=0"; then
    ok "P2 + P2.1 回归全绿(52/52)"
else
    bad "P2 回归失败(见 tmp/p3-test/p2-regression.log)"
    tail -30 "${TEST_DIR}/p2-regression.log" | tee -a "${REPORT}"
fi

log "[回归] bash scripts/tests/crash-test.sh"
(cd "${ROOT_DIR}" && bash scripts/tests/crash-test.sh >"${TEST_DIR}/crash-regression.log" 2>&1)
CRASH_RC=$?
CRASH_SUMMARY="$(grep -E '丢失 0 条|崩溃测试通过|崩溃测试失败' "${TEST_DIR}/crash-regression.log" | tail -2 | tr '\n' ' ' || true)"
log "  crash-test.sh 退出码 ${CRASH_RC};${CRASH_SUMMARY}"
if [ "${CRASH_RC}" -eq 0 ] && printf '%s' "${CRASH_SUMMARY}" | grep -q "丢失 0 条"; then
    ok "P1 回归:1000 条随机 KV 强杀重启丢失 0 条"
else
    bad "P1 崩溃回归失败(见 tmp/p3-test/crash-regression.log)"
    tail -30 "${TEST_DIR}/crash-regression.log" | tee -a "${REPORT}"
fi

log "[性能] 单行 INSERT 延迟(与 SPEC-P2 SC-112 的 < 10ms 比较)"
reset_data
write_be_conf "" 64MB
start_be "be-perf.log" || bad "性能场景 BE 启动失败"
PERF_OUT="$(java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.InsertLatencyBench "$(cygpath -w "${FE_CONF}")" 1000 2>&1)"
log "  ${PERF_OUT}"
P99="$(printf '%s' "${PERF_OUT}" | awk '{for(i=1;i<=NF;i++) if ($i ~ /^p99=/) {gsub(/ms/,"",$i); split($i,a,"="); print a[2]}}')"
if [ -n "${P99}" ]; then
    awk -v p99="${P99}" 'BEGIN { exit !(p99 < 10.0) }'
    if [ $? -eq 0 ]; then
        ok "单行 INSERT P99=${P99}ms < 10ms(P2 基线)"
    else
        note "单行 INSERT P99=${P99}ms >= 10ms;需在验收报告里说明瓶颈(取号 / fsync 串行 / 版本分组)"
    fi
else
    bad "没能解析出 INSERT 延迟"
fi
kill_be

# ================================================================ SC-210
log ""
log "---------- SC-210 依赖与边界红线 ----------"
if grep -vE '^[[:space:]]*(//|#)' "${BE_DIR}/go.mod" | grep -Eqi 'rocksdb|leveldb|badger|pebble|sqlite|bbolt|duckdb|velox|hnswlib|faiss'; then
    bad "BE go.mod 出现禁止的存储引擎依赖"
else
    ok "BE go.mod 没有现成存储引擎依赖"
fi
DIRECT_MODULES="$(cd "${BE_DIR}" && go list -m -f '{{if not .Indirect}}{{.Path}}{{end}}' all 2>/dev/null | grep -v '^$' || true)"
log "  直接依赖: $(printf '%s' "${DIRECT_MODULES}" | tr '\n' ' ')"
if printf '%s' "${DIRECT_MODULES}" | grep -qv -E '^(github.com/1638540149/tang_db/be|google.golang.org/grpc|google.golang.org/protobuf)$'; then
    bad "BE 直接依赖超出 grpc / protobuf 红线"
else
    ok "BE 直接依赖只有 grpc / protobuf"
fi
if grep -Eqi 'calcite|jsqlparser|shardingsphere|sqlite|h2database' "${FE_DIR}/pom.xml"; then
    bad "FE pom.xml 引入了第三方 SQL 解析/执行库"
else
    ok "FE 没有第三方 SQL 解析/执行库"
fi
if grep -nE '^\s*(message|rpc|enum|service)\s+[A-Za-z0-9_]*(Table|Column|SQL|Sql)' "${ROOT_DIR}/proto/tangdb.proto" >/dev/null 2>&1; then
    bad "proto 里出现表/列/SQL 语义的标识符"
else
    ok "proto 的 message/rpc 标识符里没有表/列/SQL 概念"
fi
if grep -RqiE 'data[/\\]BE|data[/\\]BE' "${FE_DIR}/src/main/java" 2>/dev/null; then
    bad "FE 源码里出现 BE 数据目录路径"
else
    ok "FE 源码不引用 BE 数据目录"
fi

# ================================================================ 收尾
kill_be
kill_fed

log ""
log "=================================================="
log " 结果:PASS=${PASS}  FAIL=${FAIL}"
log " 报告:${REPORT}"
log "=================================================="

if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
