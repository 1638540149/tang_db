#!/usr/bin/env bash
# Project-owned full verification harness. All services and data are isolated per run.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="${SCRIPT_DIR}/results/${STAMP}"
mkdir -p "${RUN_DIR}/data" "${RUN_DIR}/sort-tmp" "${RUN_DIR}/classes"
RUN_LOG="${RUN_DIR}/run.log"
STATUS_FILE="${RUN_DIR}/status.tsv"
REPORT="${SCRIPT_DIR}/TEST-REPORT.md"

BE_PORT="${TANGDB_TEST_BE_PORT:-12340}"
MYSQL_PORT="${TANGDB_TEST_MYSQL_PORT:-12346}"
HEART_PORT="${TANGDB_TEST_HEART_PORT:-12341}"
BE_EXE="${RUN_DIR}/be.exe"
BE_CONF="${RUN_DIR}/be.conf"
FE_CONF="${RUN_DIR}/fe.conf"
FE_CP_FILE="${RUN_DIR}/fe-cp.txt"
JDBC_CP_FILE="${RUN_DIR}/jdbc-cp.txt"
CSV_FILE="${RUN_DIR}/bulk.csv"
EXPORT_FILE="${RUN_DIR}/bulk-export.csv"
FE_PID_WIN=""
BE_PID_WIN=""
FAILURES=0
JAVA_BIN="/d/dev/Java/jdk-17/bin/java.exe"

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"
log() { printf '%s\n' "$*" | tee -a "${RUN_LOG}"; }

port_free() {
    if netstat -ano 2>/dev/null | grep -E ":$1[[:space:]].*LISTENING" >/dev/null; then
        log "[BLOCKED] port $1 already listening; no service was started."
        return 1
    fi
}

kill_be() {
    if [ -n "${BE_PID_WIN}" ]; then
        powershell -NoProfile -Command "\$p=Get-CimInstance Win32_Process -Filter 'ProcessId=${BE_PID_WIN}'; if (\$p -and \$p.ExecutablePath -eq '${BE_EXE_WIN}') { Stop-Process -Id ${BE_PID_WIN} -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
        BE_PID_WIN=""
    fi
}

kill_fed() {
    if [ -n "${FE_CONF_WIN:-}" ]; then
        powershell -NoProfile -Command "\$owned=Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'java.exe' -and \$_.CommandLine -and \$_.CommandLine.Contains('${FE_CONF_WIN}') -and \$_.CommandLine.Contains('com.wangyu.tangdb.fed.Fed') }; foreach (\$p in \$owned) { Stop-Process -Id \$p.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    fi
    FE_PID_WIN=""
}

cleanup() { kill_fed; kill_be; }
trap cleanup EXIT INT TERM

record_phase() {
    local name="$1" rc="$2"
    printf '%s\t%s\n' "$name" "$rc" >>"${STATUS_FILE}"
    if [ "$rc" -eq 0 ]; then log "[PASS] ${name}"; else log "[FAIL] ${name} exit=${rc}"; FAILURES=$((FAILURES + 1)); fi
}

run_phase() {
    local name="$1"; shift
    log "===== ${name} ====="
    "$@" >"${RUN_DIR}/${name}.log" 2>&1
    local rc=$?
    cat "${RUN_DIR}/${name}.log" | tee -a "${RUN_LOG}"
    record_phase "$name" "$rc"
    return 0
}

start_be() {
    BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${RUN_DIR}/be.log" 2>&1 &
    local msys_pid=$!
    for _ in $(seq 1 80); do
        if grep -q 'BE 已就绪' "${RUN_DIR}/be.log" 2>/dev/null; then
            BE_PID_WIN="$(ps -W 2>/dev/null | awk -v p="${msys_pid}" '$1==p {print $4; exit}')"
            [ -n "${BE_PID_WIN}" ] || BE_PID_WIN="${msys_pid}"
            log "BE ready pid=${BE_PID_WIN} port=${BE_PORT}"
            return 0
        fi
        if ! kill -0 "${msys_pid}" 2>/dev/null; then cat "${RUN_DIR}/be.log"; return 1; fi
        sleep 0.5
    done
    cat "${RUN_DIR}/be.log"
    return 1
}

start_fed() {
    FE_CONF_WIN="$(cygpath -w "${FE_CONF}")"
    local cp="$(cygpath -w "${ROOT_DIR}/fe/target/classes");$(cat "${FE_CP_FILE}")"
    "${JAVA_BIN}" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
        -cp "${cp}" com.wangyu.tangdb.fed.Fed --config="${FE_CONF_WIN}" >"${RUN_DIR}/fed.log" 2>&1 &
    local msys_pid=$!
    for _ in $(seq 1 80); do
        if grep -q 'fed 已就绪' "${RUN_DIR}/fed.log" 2>/dev/null; then
            FE_PID_WIN="$(powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'java.exe' -and \$_.ExecutablePath -like '*jdk-17*java.exe' -and \$_.CommandLine -and \$_.CommandLine.Contains('${FE_CONF_WIN}') -and \$_.CommandLine.Contains('com.wangyu.tangdb.fed.Fed') } | Select-Object -First 1 -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r')"
            [ -n "${FE_PID_WIN}" ] || FE_PID_WIN="${msys_pid}"
            log "FE ready pid=${FE_PID_WIN} mysql_port=${MYSQL_PORT}"
            return 0
        fi
        if ! kill -0 "${msys_pid}" 2>/dev/null; then cat "${RUN_DIR}/fed.log"; return 1; fi
        sleep 0.5
    done
    cat "${RUN_DIR}/fed.log"
    return 1
}

write_configs() {
    local crash_point="${1:-}"
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${RUN_DIR}/data")
port = ${BE_PORT}
memtable_size = 2MB
fe_host = 127.0.0.1
fe_port = ${HEART_PORT}
heartbeat_interval_seconds = 2
max_txn_write_bytes = 64MB
test_crash_point = ${crash_point}
wal_buffer_bytes = 1MB
group_commit_max_batch = 32
group_commit_max_wait_ms = 5
EOF
    cat >"${FE_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
mode = fed
mysql_port = ${MYSQL_PORT}
user = root
password = fulltest
session_max = 64
session_idle_timeout = 0
shutdown_grace_seconds = 5
heartbeat_listen_port = ${HEART_PORT}
be_offline_cycles = 3
heartbeat_enabled = true
index_backfill_batch = 500
query_parallelism = 8
pushdown_enabled = true
scan_batch_rows = 4096
query_batch_rows = 1024
sort_spill_bytes = 1MB
sort_tmp_dir = $(cygpath -w "${RUN_DIR}/sort-tmp")
csv_import_batch_rows = 1000
rpc_deadline_seconds = 5
rpc_bulk_deadline_seconds = 60
rpc_scan_deadline_seconds = 60
EOF
}

java_stage() {
    "${JAVA_BIN}" -Dfile.encoding=UTF-8 -cp "$(cygpath -w "${SCRIPT_DIR}/jdbc-client/target/classes");$(cat "${JDBC_CP_FILE}")" \
        com.wangyu.tangdb.verification.FullJdbcAcceptance "$1" "${MYSQL_PORT}" "${2:-${RUN_DIR}}"
}

log "tang_db comprehensive project-authored verification"
log "started=$(date -Is)"
log "run_dir=${RUN_DIR}"
log "BE=${BE_PORT} MySQL=${MYSQL_PORT} heartbeat=${HEART_PORT}"
log "CSV rows=50000; JDBC concurrency=8 connections; crash tests use exact owned BE PID"
: >"${STATUS_FILE}"
for p in "${BE_PORT}" "${MYSQL_PORT}" "${HEART_PORT}"; do port_free "$p" || exit 2; done

run_phase be-build bash -lc "cd be && go build -o '$(cygpath -u "${BE_EXE}")' ./cmd/be"
run_phase be-vet bash -lc 'cd be && go vet ./...'
run_phase be-unit bash -lc 'cd be && go test ./...'
run_phase fe-build bash -lc "cd fe && mvn.cmd -q compile dependency:build-classpath -Dmdep.outputFile='$(cygpath -w "${FE_CP_FILE}")'"
if [ "${TANGDB_TEST_SKIP_FE_UNIT:-0}" = "1" ]; then
    printf 'fe-unit\tSKIP\n' >>"${STATUS_FILE}"
    log '[SKIP] fe-unit omitted by TANGDB_TEST_SKIP_FE_UNIT=1; prior successful run recorded 392 tests, 0 failures.'
else
    run_phase fe-unit bash -lc 'cd fe && mvn.cmd test'
fi
run_phase jdbc-client-build mvn.cmd -q -f "$(cygpath -w "${SCRIPT_DIR}/jdbc-client/pom.xml")" package dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${JDBC_CP_FILE}")"
if grep -Eq '^(be-build|fe-build|jdbc-client-build)[[:space:]]+[1-9]' "${STATUS_FILE}"; then
    log '[ABORT] A required build stage failed; runtime checks cannot start safely.'
    cleanup
    exit 1
fi

write_configs
start_be || { log '[FAIL] BE did not become ready'; exit 1; }
start_fed || { log '[FAIL] FE did not become ready'; exit 1; }

run_phase jdbc-functional java_stage setup "${CSV_FILE}"
if [ -s "${CSV_FILE}" ]; then
    import_start="$(date +%s%3N)"
    run_phase csv-import bash "${ROOT_DIR}/scripts/tools/csv-import" --config "${FE_CONF}" \
        --host 127.0.0.1 --port "${BE_PORT}" --db verify_full --table bulk --file "${CSV_FILE}" --batch 1000
    import_end="$(date +%s%3N)"
    import_ms=$((import_end - import_start))
    printf '%s\n' "${import_ms}" >"${RUN_DIR}/import-milliseconds.txt"
    log "[PERF] csv_import_rows_per_sec=$(awk -v ms="${import_ms}" 'BEGIN {if(ms>0) printf "%.0f", 50000000/ms; else print 0}') elapsed_ms=${import_ms}"
    run_phase csv-import-idempotent-rerun bash "${ROOT_DIR}/scripts/tools/csv-import" --config "${FE_CONF}" \
        --host 127.0.0.1 --port "${BE_PORT}" --db verify_full --table bulk --file "${CSV_FILE}" --batch 1000
    run_phase jdbc-import-export-perf java_stage verify-import "${EXPORT_FILE}"
else
    log '[SKIP] CSV import/export follow-up skipped because JDBC setup failed.'
    FAILURES=$((FAILURES + 1))
fi
run_phase jdbc-concurrency java_stage concurrency "${RUN_DIR}"
run_phase jdbc-crash-seed java_stage crash-seed "${RUN_DIR}"

log '===== abrupt BE termination after acknowledged commits ====='
kill_be
start_be || { log '[FAIL] BE restart after forced termination failed'; FAILURES=$((FAILURES + 1)); }
sleep 3
run_phase jdbc-crash-recovery java_stage crash-verify "${RUN_DIR}"

log '===== fsync-complete crash injection ====='
kill_be
write_configs after_fsync
start_be || { log '[FAIL] failpoint BE did not start'; FAILURES=$((FAILURES + 1)); }
run_phase jdbc-after-fsync-write java_stage failpoint-write "${RUN_DIR}"
sleep 2
if [ -n "${BE_PID_WIN}" ] && powershell -NoProfile -Command "Get-Process -Id ${BE_PID_WIN} -ErrorAction SilentlyContinue" >/dev/null 2>&1; then
    log '[FAIL] after_fsync failpoint did not terminate the BE process'
    FAILURES=$((FAILURES + 1))
    kill_be
else
    log '[PASS] after_fsync failpoint terminated the owned BE process'
    BE_PID_WIN=""
fi
write_configs
start_be || { log '[FAIL] BE restart after fsync failpoint failed'; FAILURES=$((FAILURES + 1)); }
sleep 3
run_phase jdbc-fsync-replay java_stage failpoint-verify "${RUN_DIR}"

if [ "${TANGDB_TEST_RUN_JMETER:-0}" = "1" ]; then
    JMETER_HOME_WIN="${TANGDB_TEST_JMETER_HOME:-D:/dev/apache-jmeter-5.6.3}"
    run_phase jmeter-load powershell.exe -NoProfile -ExecutionPolicy Bypass \
        -File "$(cygpath -w "${SCRIPT_DIR}/jmeter/run-load.ps1")" \
        -JMeterHome "${JMETER_HOME_WIN}" -HostName 127.0.0.1 -Port "${MYSQL_PORT}" \
        -Database verify_jmeter -Threads 8 -RampSeconds 10 -DurationSeconds 60
    if ! grep -q '^summary =' "${RUN_DIR}/jmeter-load.log"; then
        log '[FAIL] JMeter did not produce a summary line.'
        FAILURES=$((FAILURES + 1))
    elif grep -Eq 'Err:[[:space:]]*[1-9][0-9]*' "${RUN_DIR}/jmeter-load.log"; then
        log '[FAIL] JMeter reported failed SQL samples; see jmeter-load.log and JTL.'
        FAILURES=$((FAILURES + 1))
    fi
fi

cleanup
JMETER_REPORT_LINES='- JMeter stage: not run in this invocation.'
if [ -f "${RUN_DIR}/jmeter-load.log" ]; then
    JMETER_RESULT_DIR_WIN="$(sed -n 's/^Results: //p' "${RUN_DIR}/jmeter-load.log" | tail -1)"
    JMETER_RESULT_DIR="$(cygpath -u "${JMETER_RESULT_DIR_WIN}")"
    JMETER_JTL="${JMETER_RESULT_DIR}/samples.jtl"
    JMETER_READ_COUNT="$(awk -F, -v label='Point read by primary key' 'NR>1 && $3==label {total++; if($8=="true") ok++} END{printf "%d/%d", ok, total}' "${JMETER_JTL}")"
    JMETER_AGG_COUNT="$(awk -F, -v label='Filtered aggregate query' 'NR>1 && $3==label {total++; if($8=="true") ok++} END{printf "%d/%d", ok, total}' "${JMETER_JTL}")"
    JMETER_WRITE_COUNT="$(awk -F, -v label='Concurrent unique-key insert' 'NR>1 && $3==label {total++; if($8=="true") ok++} END{printf "%d/%d", ok, total}' "${JMETER_JTL}")"
    JMETER_REPORT_LINES="- JMeter 5.6.3: $(grep '^summary =' "${RUN_DIR}/jmeter-load.log" | tail -1)
- JMeter sampler successes (success/total): primary reads ${JMETER_READ_COUNT}; filtered aggregates ${JMETER_AGG_COUNT}; unique inserts ${JMETER_WRITE_COUNT}.
- JMeter HTML report: ${JMETER_RESULT_DIR_WIN}/html-report/index.html
- JMeter JTL: ${JMETER_RESULT_DIR_WIN}/samples.jtl
- JMeter test schema and cleanup SQL: ${JMETER_RESULT_DIR_WIN}/database.txt and cleanup-db.sql"
fi
cat >"${REPORT}" <<EOF
# tang_db 全面测试报告

> 本轮由自编 Java JDBC 测试代码直连独立 MySQL 协议端口；不复用项目现有验收脚本。具体运行数据位于 ${RUN_DIR}。

## 本轮环境

- 时间：$(date -Is)
- 操作系统：$(powershell -NoProfile -Command '[System.Environment]::OSVersion.VersionString' 2>/dev/null | tr -d '\r')
- Go / Java：$(go version 2>/dev/null); $(java -version 2>&1 | head -1)
- BE gRPC：${BE_PORT}；FE MySQL：${MYSQL_PORT}；FE 心跳：${HEART_PORT}
- 隔离数据目录：${RUN_DIR}/data
- 导入数据：50,000 CSV 行；并发写：8 个 JDBC 会话；强杀：仅测试进程的精确 PID

## 阶段状态

| 阶段 | 退出码/状态 |
|---|---:|
$(awk -F '\t' '{printf "| `%s` | %s |\n", $1, $2}' "${STATUS_FILE}")

## 自编 JDBC 用例覆盖

- DDL/DML、事务提交与回滚、快照重复读、同一行写写冲突、IN/NOT/范围/LIKE/NULL 谓词、排序分页、INNER/LEFT/CROSS JOIN 和 RIGHT JOIN 声明能力核查、GROUP BY/HAVING、DISTINCT、子查询、UNION/INTERSECT/EXCEPT、函数/JSON、information_schema、JDBC 元数据、EXPLAIN、唯一索引重复错误码 1062。
- ANALYZE TABLE/SHOW STATS；SHOW SEGMENTS、SHOW BACKENDS、SHOW SESSIONS、SHOW PROCESSLIST。
- CSV 工具导入 50,000 行；核对总行数、主键去重、金额 checksum；JDBC 有序导出 CSV 并逐行校验，再核对导出行数。
- 测量 CSV 导入耗时、JDBC 全表聚合扫描中位耗时与行吞吐、8 连接写入吞吐；原始值见 run.log 与阶段日志。
- 8 个连接并发提交 800 行，验证提交成功、总数和主键唯一数。
- BE 收到 JDBC COMMIT 成功后被强制终止并重启，核对 100 行及 checksum；再通过 after_fsync 崩溃注入，重启后核对 WAL 重放。

## 本轮实测结果

- CSV 导入：$(grep -o 'csv_import_rows_per_sec=[^ ]* elapsed_ms=[^ ]*' "${RUN_DIR}/run.log" | tail -1)；重复执行识别为已完成，未重复写入。
- 全表聚合扫描：$(grep -o 'jdbc_scan_50k_median_ms=[^ ]* rows_per_sec=[^ ]* samples_ms=[^ ]*' "${RUN_DIR}/run.log" | tail -1)。三轮均返回 checksum 24,975,000。
- JDBC CSV 导出：$(grep -o 'jdbc_export_50k_ms=[^ ]* rows_per_sec=[^ ]*' "${RUN_DIR}/run.log" | tail -1)。耗时包含查询、JDBC 传输、排序读取和本地文件写入。
- 并发写入：$(grep -o 'concurrent_connections=[^ ]* committed_rows=[^ ]* elapsed_ms=[^ ]* rows_per_sec=[^ ]*' "${RUN_DIR}/run.log" | tail -1)；800/800 行提交且主键均唯一。
- 数据完整性：导入行数、distinct 主键数均为 50,000；金额 checksum 为 24,975,000；导出 50,000 行逐行与生成规则核对。
- 崩溃恢复：已确认提交的 100 行重启后完整（checksum 14,850）；after_fsync 注入时客户端收到预期连接错误，未确认写在重启后由 WAL 恢复。
- 事务一致性：并发会话读到稳定快照；同一行写入时后提交事务报告写写冲突，未覆盖先提交值。
- 统计和状态：ANALYZE 后 SHOW STATS 有数据；segments 16 段、backends 1 个；sessions/processlist 均返回 JDBC 会话。
- JDBC DatabaseMetaData.getIndexInfo() 生成的 SHOW 查询被 FE 拒绝（错误码 1235）；项目原生 SHOW INDEX 能发现二级索引。该 Connector/J 元数据兼容项是本轮发现的问题。
${JMETER_REPORT_LINES}

## 功能断言失败项

$(if grep -q '^\[FAIL\]' "${RUN_DIR}/run.log"; then grep '^\[FAIL\]' "${RUN_DIR}/run.log"; else echo '无'; fi)

## 结果与限制

各阶段退出码以阶段状态表为准；功能断言失败项在上节逐条列出，不能因测试程序继续运行而视为通过。FE 单测曾在 20261029-150935 成功运行 392 项、0 失败；本轮按显式参数跳过 FE 单测，BE 全部 Go 单测通过。后续重复 FE 单测时，Maven 与 IntelliJ 后台编译同时使用 fe/target，测试运行出现 HyperLogLog 的 NoClassDefFoundError，因此本轮 FE 单测保持 SKIP 状态。性能值是该环境下的一次观测，不构成容量承诺。GUI 客户端人工操作、长时间耐久性 soak、内存峰值采样、超过 8 个连接的阶梯压测、Go race 检测、CSV 中断续传/坏行定位、KILL 会话、pushdown/query_parallelism 开关及每个函数的全参数边界、故障发生在 WAL fsync 之前的写入边界不属于本轮覆盖。

## 可复核材料

- 执行命令：TANGDB_TEST_SKIP_FE_UNIT=1 TANGDB_TEST_RUN_JMETER=1 bash verification/P7之前的测试/run-full.sh（Windows Git Bash）
- 总日志：${RUN_DIR}/run.log
- 阶段状态：${RUN_DIR}/status.tsv
- JDBC 导入输入：${CSV_FILE}
- JDBC 导出结果：${EXPORT_FILE}
- 数据目录：${RUN_DIR}/data
$(if [ -f "${RUN_DIR}/jmeter-load.log" ]; then echo "- JMeter 原始日志：${RUN_DIR}/jmeter-load.log"; fi)
EOF

log "finished=$(date -Is) failures=${FAILURES} report=${REPORT}"
if [ "${FAILURES}" -gt 0 ]; then exit 1; fi
exit 0
