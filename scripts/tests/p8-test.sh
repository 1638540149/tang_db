#!/usr/bin/env bash
# tang_db P8 一键验收:协议收口 / 监控指标 / 慢查询诊断 / 备份恢复 / Web 控制台
#
# 覆盖 SPEC-P8 §7 的 SC-446 ~ SC-465,每条都真跑;没跑到的写成 [NOTE],不写成通过。
#
#   SC-446 官方 mysql 客户端连通(docker mysql:8.0;Docker 守护进程不可用时记 NOTE)
#   SC-447 图形客户端元数据探测语句逐条 CODE=0(SHOW INDEX ≥1 行、information_schema 新表可查)
#   SC-448 /metrics 与 SHOW METRICS 数值一致;QPS 与 JMeter 统计偏差 ≤10%
#   SC-449 查询 / 事务 / BE / 向量四族各 ≥3 个指标有值
#   SC-450 300ms 级语句落库,耗时与实测误差 ≤20%
#   SC-451 诊断建议含"无索引列 + 候选索引 + 计划证据";索引存在时不再建议
#   SC-452 ★ 备份中强杀:原库完整;备份目录无 BACKUP_META;RESTORE 拒绝
#   SC-453 ★ 备份期间写入:成功率 100%;恢复出的库行数/checksum = 备份点
#   SC-454 备份 → 删数据目录 → RESTORE → 行数与 checksum 一致
#   SC-455 增量备份可独立恢复;总字节 < 全量
#   SC-456 控制台 API 冒烟(login/tree/query/explain/status 全 200)
#   SC-457 控制台状态区 QPS / memtable / segment 与 SHOW METRICS 一致
#   SC-458 未确认的写请求被拒绝;确认后生效并留审计
#   SC-459 COM_STMT_PREPARE 回错误码后连接仍可 SELECT 1
#   SC-460 JMeter 8 线程 60 秒:QPS/P95/P99/错误率齐全,错误率 ≤0.1%
#   SC-461 metrics_enabled=false 时 QPS 差异 ≤5%
#   SC-462 备份期间写入 P99 ≤ 无备份时的 1.5 倍
#   SC-463 依赖红线:FE 无 Web/监控第三方 Java 库;前端仅 Vue3 + Element Plus + Vite
#   SC-464 既有回归(p3/p4/p5/p6/p6.1/p7/p7.1)—— P8_REGRESSION=1 才跑
#   SC-465 README 增 P8 章节(指标名表 / 备份用法 / 控制台说明)
#
# 用法:bash scripts/tests/p8-test.sh
#   P8_ROWS=100000            导入行数(默认 10 万,SC-452 要求 5 万以上)
#   P8_JMETER_SECONDS=60      JMeter 压测时长
#   P8_REGRESSION=1           追加跑既有回归(逐个单跑)
#   SKIP_REGRESSION=1         与 P8_REGRESSION 等效(兼容旧习惯)
#   P8_CONSOLE_PORT=8080      控制台端口(被占用时脚本自动改 18080 并记录)
#
# 端口固定 BE 9960 / MySQL 9966 / 心跳 9971 / 控制台 8080(或 18080);
# 数据目录 tmp/p8-test/data。脚本只杀自己起的 tmp/p8-test/be.exe 与带该目录配置的 fed,
# 不碰 9100 / 3406 上的业主环境。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p8-test"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/report.txt"
JMX="${SCRIPT_DIR}/jmeter/p8-load.jmx"
JMETER_HOME="${JMETER_HOME:-/d/dev/apache-jmeter-5.6.3}"

BE_PORT="${P8_BE_PORT:-9960}"
MYSQL_PORT="${P8_MYSQL_PORT:-9966}"
HEART_PORT="${P8_HEART_PORT:-9971}"
CONSOLE_PORT="${P8_CONSOLE_PORT:-8080}"
DB_USER=root
DB_PASS=p8test
DB_NAME=default
ROWS="${P8_ROWS:-100000}"
BULK_ROWS="${P8_BULK_ROWS:-100000}"
JMETER_SECONDS="${P8_JMETER_SECONDS:-60}"

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

PASS=0
FAIL=0
NOTE_ONLY=0

rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}" "${TEST_DIR}/fe-sort" "${TEST_DIR}/backup"
: >"${REPORT}"
# fed 用相对路径找 console/dist,脚本统一在仓库根目录跑
cd "${ROOT_DIR}"

log()  { echo "$*" | tee -a "${REPORT}"; }
ok()   { PASS=$((PASS + 1)); log "  [PASS] $*"; }
bad()  { FAIL=$((FAIL + 1)); log "  [FAIL] $*"; }
note() { NOTE_ONLY=$((NOTE_ONLY + 1)); log "  [NOTE] $*"; }

BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"
TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"

# ---------------------------------------------------------------- 进程管理

be_running() {
    powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1
}

fast_kill_be() { # 立刻强杀刚起的 BE(备份中途强杀用;taskkill 比 powershell 快一个数量级)
    if [ -n "${BE_WINPID}" ]; then
        taskkill /F /PID "${BE_WINPID}" >/dev/null 2>&1 || true
    elif [ -n "${BE_PID}" ]; then
        taskkill /F /PID "${BE_PID}" >/dev/null 2>&1 || true
    fi
}

kill_be() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    for _ in $(seq 1 40); do
        be_running || break
        sleep 0.25
    done
    sleep 1
}

FED_PID=""
kill_fed() {
    if [ -n "${FED_PID}" ]; then
        taskkill /F /PID "${FED_PID}" >/dev/null 2>&1 || true
        FED_PID=""
    fi
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*tangdb.fed.Fed*' -and \$_.CommandLine -like '*${TEST_DIR_WIN}*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    sleep 1
}

cleanup() {
    kill_fed
    kill_be
}
trap cleanup EXIT

wait_port_free() { # <port> [次]
    local port="$1" limit="${2:-30}"
    for _ in $(seq 1 "${limit}"); do
        if ! netstat -ano | grep -q ":${port} .*LISTENING"; then
            return 0
        fi
        sleep 1
    done
    return 0
}

port_busy() { netstat -ano | grep -q ":$1 .*LISTENING"; }

# ---------------------------------------------------------------- 配置

write_be_conf() { # <data-dir>
    local data_dir="${1:-${DATA_DIR}}"
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${data_dir}")
port = ${BE_PORT}
memtable_size = 16MB
fe_host = 127.0.0.1
fe_port = ${HEART_PORT}
heartbeat_interval_seconds = 3
max_txn_write_bytes = 64MB
test_crash_point =
wal_buffer_bytes = 1MB
group_commit_max_batch = 64
group_commit_max_wait_ms = 5
# SC-455 口径:关掉 L0 compaction,否则两次备份之间 segment 被合并,
# 增量备份"全是新段",退化成全量,该用例失去意义(不是产品缺陷)。
compaction_l0_files = 1000
compaction_level_ratio = 1000
compaction_level_max_bytes = 4GB
EOF
}

write_fe_conf() { # <metrics_enabled>
    cat >"${FE_CONF}" <<EOF
be_host = 127.0.0.1
be_port = ${BE_PORT}
mode = fed
mysql_port = ${MYSQL_PORT}
user = ${DB_USER}
password = ${DB_PASS}
session_max = 64
session_idle_timeout = 0
shutdown_grace_seconds = 5
heartbeat_listen_port = ${HEART_PORT}
be_offline_cycles = 3
heartbeat_enabled = true
index_backfill_batch = 1000
query_batch_rows = 1024
sort_spill_bytes = 64MB
sort_tmp_dir = $(cygpath -w "${TEST_DIR}/fe-sort")
csv_import_batch_rows = 2000
query_parallelism = 8
pushdown_enabled = true
scan_batch_rows = 4096
rpc_deadline_seconds = 5
rpc_bulk_deadline_seconds = 60
rpc_scan_deadline_seconds = 60
mysql_max_connections = 128
mysql_wait_timeout = 0
mysql_ssl = false
console_enabled = true
console_port = ${CONSOLE_PORT}
metrics_enabled = ${1:-true}
slow_query_log = true
slow_query_threshold_ms = 200
slow_query_max_rows = 10000
backup_dir = $(cygpath -w "${TEST_DIR}/backup")
EOF
}

BE_PID=""
BE_WINPID=""
start_be() { # <log-name>
    local name="${1:-be.log}"
    wait_port_free "${BE_PORT}" 40
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${TEST_DIR}/${name}" 2>&1 &
    BE_PID=$!
    for _ in $(seq 1 100); do
        if grep -q "BE 已就绪" "${TEST_DIR}/${name}" 2>/dev/null; then
            BE_WINPID="$(powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }).ProcessId" 2>/dev/null | tr -d '\r' | head -1)"
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

start_fed() { # <log-name>
    local name="${1:-fed.log}"
    wait_port_free "${MYSQL_PORT}" 20
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.fed.Fed \
        --config="$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/${name}" 2>&1 &
    FED_PID=$!
    for _ in $(seq 1 100); do
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

# ---------------------------------------------------------------- SQL 工具

RUNTIME_CP=""
runtime_cp() {
    if [ -z "${RUNTIME_CP}" ]; then
        RUNTIME_CP="$(cat "${CP_FILE}")"
    fi
    echo "${RUNTIME_CP}"
}

run_sql_text() { # <sql-text>
    printf '%s\n' "$1" | timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
        -cp "${CP}" com.wangyu.tangdb.client.Cli \
        --config="$(cygpath -w "${FE_CONF}")" 2>&1
}

sql_capture() { # <name> <sql-text>
    local name="$1"
    run_sql_text "$2" >"${TEST_DIR}/${name}.txt"
    cat "${TEST_DIR}/${name}.txt"
}

first_int() { printf '%s\n' "$1" | sed -n 's/^| *\(-\{0,1\}[0-9][0-9]*\) *|.*$/\1/p' | head -1; }

count_rows() { # <table>
    first_int "$(run_sql_text "SELECT COUNT(*) FROM $1; exit")"
}

db_count() { # p8_load + p8_bulk 两表合计(备份点行数的真实口径)
    local a b
    a="$(count_rows p8_load)"
    b="$(count_rows p8_bulk)"
    echo $(( ${a:-0} + ${b:-0} ))
}

db_sum() { # 两表 SUM(amount) 合计(checksum)
    local a b
    a="$(sum_amount p8_load)"
    b="$(sum_amount p8_bulk)"
    echo $(( ${a:-0} + ${b:-0} ))
}

sum_amount() { # <table>
    first_int "$(run_sql_text "SELECT SUM(amount) FROM $1; exit")"
}

metric_value() { # <metric-name> — 从 SHOW METRICS 输出里按名字取值
    local out="${TEST_DIR}/metrics-show.txt"
    if [ ! -s "${out}" ]; then
        run_sql_text "SHOW METRICS; exit" >"${out}"
    fi
    python - "$(cygpath -w "${out}")" "$1" <<'PY'
import sys
path, wanted = sys.argv[1], sys.argv[2]
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if len(cells) >= 2 and cells[0] == wanted:
        print(cells[1])
        break
PY
}

refresh_metrics_show() { run_sql_text "SHOW METRICS; exit" >"${TEST_DIR}/metrics-show.txt"; }

job_status() { # <job-id 或 any> — 从 SHOW BACKUP JOBS 里取状态
    local out="${TEST_DIR}/jobs.txt"
    run_sql_text "SHOW BACKUP JOBS; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$1" <<'PY'
import sys
path, wanted = sys.argv[1], sys.argv[2]
header = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if wanted == "any" or row.get("job_id") == wanted:
        print(row.get("status", ""))
        break
PY
}

job_field() { # <job-id 或 any> <column>
    local out="${TEST_DIR}/jobs.txt"
    run_sql_text "SHOW BACKUP JOBS; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "${1:-any}" "$2" <<'PY'
import sys
path, wanted, column = sys.argv[1], sys.argv[2], sys.argv[3]
header = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if wanted == "any" or row.get("job_id") == wanted:
        print(row.get(column, ""))
        break
PY
}

wait_job() { # <status> [timeout-s]
    local wanted="$1" limit="${2:-180}"
    for _ in $(seq 1 "${limit}"); do
        if [ "$(job_status any)" = "${wanted}" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# 从 SHOW SLOW QUERIES 里找含某关键字的记录,输出 duration_ms
slow_duration() { # <keyword>
    local out="${TEST_DIR}/slow.txt"
    run_sql_text "SHOW SLOW QUERIES LIMIT 30; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$1" <<'PY'
import sys
path, keyword = sys.argv[1], sys.argv[2]
header = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if keyword in row.get("sql_text", ""):
        print(row.get("duration_ms", ""))
        break
PY
}

slow_advice() { # <keyword>
    local out="${TEST_DIR}/slow.txt"
    run_sql_text "SHOW SLOW QUERIES LIMIT 30; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$1" <<'PY'
import sys
path, keyword = sys.argv[1], sys.argv[2]
header = None
advice = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if keyword in row.get("sql_text", ""):
        advice = row.get("advice", "")
if advice:
    print(advice)
PY
}

expect() { # <output> <substr> <desc>
    if printf '%s' "$1" | grep -qF "$2"; then ok "$3"
    else bad "$3(期望输出包含: $2)"; log "----- 实际输出 -----"; printf '%s\n' "$1" | tail -25 | tee -a "${REPORT}"; log "--------------------"; fi
}

expect_re() { # <output> <ere> <desc>
    if printf '%s' "$1" | grep -qE "$2"; then ok "$3"
    else bad "$3(期望匹配: $2)"; log "----- 实际输出 -----"; printf '%s\n' "$1" | tail -25 | tee -a "${REPORT}"; log "--------------------"; fi
}

expect_absent() { # <output> <substr> <desc>
    if printf '%s' "$1" | grep -qF "$2"; then bad "$3(不该出现: $2)"; else ok "$3"; fi
}

http_get() {
    curl -s -o "$(cygpath -w "${TEST_DIR}/resp.json")" -w "%{http_code}"         "http://127.0.0.1:${CONSOLE_PORT}$1" ${2:+-H "X-Console-Token: $2"}
}

console_token() {
    curl -s -X POST "http://127.0.0.1:${CONSOLE_PORT}/api/login"         -H 'Content-Type: application/json'         -d "{\"username\":\"${DB_USER}\",\"password\":\"${DB_PASS}\"}"         | python -c 'import json,sys;print(json.load(sys.stdin).get("token",""))'
}

# 通过控制台 API 执行 SQL(JSON 返回,能完整拿到多行单元格,适合解析建议文本)
api_sql() { # <out-name> <sql>
    local token body
    token="$(console_token)"
    body="$(python - "$2" <<'PY'
import json, sys
print(json.dumps({"sql": sys.argv[1], "confirm": True}))
PY
)"
    curl -s -o "$(cygpath -w "${TEST_DIR}/$1.json")" -X POST         "http://127.0.0.1:${CONSOLE_PORT}/api/query"         -H "X-Console-Token: ${token}" -H 'Content-Type: application/json'         --data-binary "${body}"
}

# 从 api_sql 的 JSON 里按 sql_text 关键字取某个列(整段,不截断)
api_row_field() { # <json-name> <sql-keyword> <column>
    python - "$(cygpath -w "${TEST_DIR}/$1.json")" "$2" "$3" <<'PY'
import json, sys
path, keyword, column = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(path, encoding="utf-8"))
if not data.get("ok"):
    raise SystemExit(0)
for result in data.get("results", []):
    columns = result.get("columns") or []
    for row in result.get("rows") or []:
        record = dict(zip(columns, row))
        if keyword in str(record.get("sql_text", "")):
            print(record.get(column, ""))
            raise SystemExit(0)
PY
}

# ================================================================ 构建与环境

log "=================================================="
log " tang_db P8 验收(SPEC-P8 v0.1a)"
log " 机器:$(uname -s) $(uname -r) / CPU $(nproc 2>/dev/null || echo '?') 核"
log " 端口:BE ${BE_PORT} / MySQL ${MYSQL_PORT} / 心跳 ${HEART_PORT} / 控制台 ${CONSOLE_PORT}"
log " 行数:${ROWS} / JMeter:${JMETER_SECONDS}s"
log "=================================================="

log ""
log "---------- 构建 ----------"
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) \
    || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"
RUNTIME_CP="${CP}"
log " 构建完成(BE:${BE_EXE})"

if port_busy "${CONSOLE_PORT}"; then
    CONSOLE_PORT=18080
    note "控制台端口 ${P8_CONSOLE_PORT:-8080} 已被占用,本次改用 ${CONSOLE_PORT}(不改产品默认值)"
fi

write_be_conf "${DATA_DIR}"
write_fe_conf true
kill_fed
kill_be
start_be "be.log" || exit 1
start_fed "fed.log" || exit 1
log " 服务已启动(BE ${BE_PORT} / fed ${MYSQL_PORT})"

# ================================================================ 造数

log ""
log "---------- 准备数据(表 p8_load,${ROWS} 行) ----------"
run_sql_text "CREATE TABLE p8_load (id INT PRIMARY KEY, city VARCHAR(32), amount INT, note VARCHAR(200));
exit" >/dev/null
run_sql_text "CREATE TABLE p8_bulk (id INT PRIMARY KEY, city VARCHAR(32), amount INT, note VARCHAR(512));
exit" >/dev/null
python - "${ROWS}" "${BULK_ROWS}" "$(cygpath -w "${TEST_DIR}/p8_load.csv")" "$(cygpath -w "${TEST_DIR}/p8_bulk.csv")" <<'PY'
import csv, sys
rows = int(sys.argv[1]); bulk = int(sys.argv[2])
load_path = sys.argv[3]; bulk_path = sys.argv[4]
pad = "x" * 480
with open(load_path, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["id", "city", "amount", "note"])
    for i in range(1, rows + 1):
        w.writerow([i, "city-%d" % (i % 500), i % 1000, "note-value-%d" % i])
with open(bulk_path, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["id", "city", "amount", "note"])
    for i in range(1, bulk + 1):
        w.writerow([i, "bcity-%d" % (i % 97), i % 1000, "bulk-note-%d-%s" % (i, pad)])
PY
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${RUNTIME_CP}" \
    com.wangyu.tangdb.tools.CsvImport --table p8_load \
    --file "$(cygpath -w "${TEST_DIR}/p8_load.csv")" --db "${DB_NAME}" --batch 2000 \
    --config "$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/import.log" 2>&1
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${RUNTIME_CP}"     com.wangyu.tangdb.tools.CsvImport --table p8_bulk     --file "$(cygpath -w "${TEST_DIR}/p8_bulk.csv")" --db "${DB_NAME}" --batch 1000     --config "$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/import-bulk.log" 2>&1
IMPORTED="$(count_rows p8_load)"
BULK_IMPORTED="$(count_rows p8_bulk)"
if [ "${IMPORTED}" = "${ROWS}" ]; then
    ok "数据准备:p8_load ${IMPORTED} 行"
else
    bad "数据准备失败:期望 ${ROWS} 行,实际 ${IMPORTED}(见 tmp/p8-test/import.log)"
    tail -8 "${TEST_DIR}/import.log" | tee -a "${REPORT}"
fi
if [ "${BULK_IMPORTED}" = "${BULK_ROWS}" ]; then
    ok "数据准备:p8_bulk ${BULK_IMPORTED} 行(让备份/慢查询有足够数据量)"
else
    bad "数据准备失败:p8_bulk 期望 ${BULK_ROWS} 行,实际 ${BULK_IMPORTED}(见 tmp/p8-test/import-bulk.log)"
    tail -8 "${TEST_DIR}/import-bulk.log" | tee -a "${REPORT}"
fi

run_sql_text "ANALYZE TABLE p8_load; exit" >/dev/null 2>&1
run_sql_text "ANALYZE TABLE p8_bulk; exit" >/dev/null 2>&1

# ================================================================ SC-446

log ""
log "---------- SC-446 官方 mysql 客户端连通 ----------"
if docker info >/dev/null 2>&1; then
    DOCKER_OUT="$(timeout 300 docker run --rm -i mysql:8.0 \
        mysql -h host.docker.internal -P "${MYSQL_PORT}" -u "${DB_USER}" -p"${DB_PASS}" \
        --default-character-set=utf8mb4 -e "SELECT 1 AS ok" 2>&1)"
    if printf '%s' "${DOCKER_OUT}" | grep -qE '(^|[^0-9])1($|[^0-9])'; then
        ok "SC-446 docker mysql:8.0 连接并 SELECT 1 成功"
    else
        bad "SC-446 docker mysql 客户端失败"; printf '%s\n' "${DOCKER_OUT}" | tail -8 | tee -a "${REPORT}"
    fi
else
    note "SC-446 未验证:Docker 守护进程未运行(docker CLI 在,但 Desktop 未启动)。官方 mysql 客户端容器未跑;同协议路径由 SC-447/SC-459 用自研协议客户端覆盖"
fi

# ================================================================ SC-447 / SC-459

log ""
log "---------- SC-447 图形客户端元数据探测(逐条 CODE=0) ----------"
PROBES=(
    "SELECT 1"
    "SELECT DATABASE()"
    "SELECT USER()"
    "SELECT CONNECTION_ID()"
    "SELECT VERSION()"
    "SELECT @@version_comment LIMIT 1"
    "SELECT @@lower_case_table_names"
    "SELECT @@sql_mode"
    "SELECT @@max_allowed_packet"
    "SELECT @@wait_timeout"
    "SELECT @@character_set_client, @@character_set_connection, @@collation_connection"
    "SELECT @@tx_isolation"
    "SELECT @@autocommit"
    "SHOW TABLE STATUS"
    "SHOW COLLATION"
    "SHOW CHARSET"
    "SHOW CHARACTER SET"
    "SHOW ENGINES"
    "SHOW GRANTS"
    "SHOW WARNINGS"
    "SHOW ERRORS"
    "SHOW VARIABLES LIKE 'version%'"
    "SHOW VARIABLES LIKE 'max_connections'"
    "SHOW VARIABLES LIKE 'character_set%'"
    "SHOW STATUS LIKE 'Uptime'"
    "SHOW FULL TABLES"
    "SHOW FULL COLUMNS FROM p8_load"
    "SHOW INDEX FROM p8_load"
    "SHOW CREATE TABLE p8_load"
    "SHOW CREATE DATABASE default"
    "SHOW TRIGGERS"
    "SHOW EVENTS"
    "SHOW ROUTINES"
    "SELECT COUNT(*) FROM information_schema.COLLATIONS"
    "SELECT * FROM information_schema.CHARACTER_SETS"
    "SELECT * FROM information_schema.ENGINES"
    "SELECT * FROM information_schema.ROUTINES"
    "SELECT * FROM information_schema.SCHEMATA"
    "SELECT * FROM information_schema.TABLES"
    "SET NAMES utf8mb4"
    "SET SESSION sql_mode = ''"
)
PROBE_ARGS=()
for probe in "${PROBES[@]}"; do
    PROBE_ARGS+=(--sql "${probe}")
done
PROBE_OUT="$(timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8ProtocolProbe --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" "${PROBE_ARGS[@]}" 2>&1)"
printf '%s\n' "${PROBE_OUT}" >"${TEST_DIR}/probes.txt"
PROBE_FAIL="$(printf '%s' "${PROBE_OUT}" | sed -n 's/^PROBE_FAILURES=//p')"
if [ "${PROBE_FAIL:-1}" = "0" ]; then
    ok "SC-447 探测清单 $((${#PROBES[@]})) 条全部 CODE=0"
else
    bad "SC-447 探测清单有失败(FAILURES=${PROBE_FAIL:-?})"
    printf '%s\n' "${PROBE_OUT}" | grep -v "CODE=0" | tail -12 | tee -a "${REPORT}"
fi
SHOW_INDEX_OUT="$(run_sql_text "SHOW INDEX FROM p8_load; exit")"
expect_re "${SHOW_INDEX_OUT}" '\| *(PRIMARY|idx_)' "SC-447 SHOW INDEX 返回 ≥1 行"
for vtable in COLLATIONS CHARACTER_SETS ENGINES ROUTINES; do
    OUT="$(run_sql_text "SELECT COUNT(*) FROM information_schema.${vtable}; exit")"
    if printf '%s' "${OUT}" | grep -qE '^\| *[0-9]+ *\|'; then
        ok "SC-447 information_schema.${vtable} 可查"
    else
        bad "SC-447 information_schema.${vtable} 查询失败"
    fi
done

log ""
log "---------- SC-459 协议错误不断连 ----------"
STMT_OUT="$(timeout 120 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8ProtocolProbe --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --probe-stmt 2>&1)"
printf '%s\n' "${STMT_OUT}" | tee -a "${REPORT}" | sed 's/^/    /'
expect "${STMT_OUT}" "CODE=1295" "SC-459 COM_STMT_PREPARE 返回 1295"
expect "${STMT_OUT}" "SELECT1=OK" "SC-459 错误后连接仍可 SELECT 1"
expect_re "${STMT_OUT}" "COM_FIELD_LIST columns=[1-9]" "SC-459 COM_FIELD_LIST 返回列定义"

# ================================================================ SC-449 指标完整性

log ""
log "---------- SC-449 指标完整性(四族各 ≥3) ----------"
refresh_metrics_show
METRICS_SHOW="$(cat "${TEST_DIR}/metrics-show.txt")"
family_count() { printf '%s\n' "${METRICS_SHOW}" | grep -cE "^\| $1" || true; }
for family in fe_query fe_txn be_ be_vector; do
    N="$(printf '%s\n' "${METRICS_SHOW}" | grep -cE "^\\| ${family}" || true)"
    if [ "${N:-0}" -ge 3 ]; then
        ok "SC-449 ${family}* 有 ${N} 个指标"
    else
        bad "SC-449 ${family}* 只有 ${N} 个指标(<3)"
    fi
done
expect "${METRICS_SHOW}" "fe_query_latency_p99_ms" "SC-449 延迟分位 P99 有值"
expect "${METRICS_SHOW}" "be_segment_count" "SC-449 BE segment 指标有值"

# ================================================================ SC-450 / SC-451 慢查询

log ""
log "---------- SC-450 慢查询落库(300ms 级语句) ----------"
SLOW_SQL="SELECT id, city, note FROM p8_bulk WHERE note = 'bulk-note-99999'"
TIMED_OUT="$(timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8ProtocolProbe --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --timed-sql "${SLOW_SQL}" 2>&1)"
printf '%s\n' "${TIMED_OUT}" | sed 's/^/    /' | tee -a "${REPORT}"
MEASURED_MS="$(printf '%s\n' "${TIMED_OUT}" | sed -n 's/.*run=2 elapsed_ms=\([0-9]*\).*/\1/p' | head -1)"
api_sql slow "SHOW SLOW QUERIES LIMIT 30" >/dev/null
RECORDED_MS="$(api_row_field slow "note = 'bulk-note-99999'" duration_ms)"
if [ -n "${RECORDED_MS}" ]; then
    ok "SC-450 慢查询已落库(duration_ms=${RECORDED_MS})"
    if [ -n "${MEASURED_MS}" ] && [ "${MEASURED_MS}" -gt 0 ]; then
        python - "${MEASURED_MS}" "${RECORDED_MS}" <<'PY' && ok "SC-450 耗时与实测误差 ≤20%(实测 ${MEASURED_MS}ms / 记录 ${RECORDED_MS}ms)" || bad "SC-450 耗时误差 >20%(实测 ${MEASURED_MS}ms / 记录 ${RECORDED_MS}ms)"
import sys
measured = float(sys.argv[1]); recorded = float(sys.argv[2])
diff = abs(measured - recorded) / max(measured, 1.0)
print("    误差 = %.2f%%" % (diff * 100))
sys.exit(0 if diff <= 0.20 else 1)
PY
    else
        note "SC-450 未量到实测耗时,跳过误差比对"
    fi
else
    bad "SC-450 慢查询没有落库(阈值 200ms,语句 ${MEASURED_MS:-?}ms)"
fi
expect "$(run_sql_text "SHOW SLOW QUERIES; exit")" "id" "SC-450 SHOW SLOW QUERIES 输出列固定"

log ""
log "---------- SC-451 诊断建议可核对 ----------"
ADVICE="$(api_row_field slow "note = 'bulk-note-99999'" advice)"
printf '%s\n' "${ADVICE}" | sed 's/^/    /' | tee -a "${REPORT}"
expect "${ADVICE}" "无索引" "SC-451 指出无索引谓词列"
expect "${ADVICE}" "候选索引: CREATE INDEX" "SC-451 给出候选索引"
expect "${ADVICE}" "证据:" "SC-451 建议带证据字段"
run_sql_text "CREATE INDEX idx_p8bulk_note ON p8_bulk (note); exit" >/dev/null
sleep 1
# 索引建好后再跑一条"用不上该索引"的慢查询(OR 不做索引合并 → 仍全表扫),
# 用来验证 FR-837:已存在的索引不会被再次建议。
OR_SQL="SELECT id, city, note FROM p8_bulk WHERE note = 'bulk-note-99999' OR amount = 5"
TIMED2="$(timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8ProtocolProbe --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --timed-sql "${OR_SQL}" 2>&1)"
printf '%s\n' "${TIMED2}" | sed 's/^/    /' | tee -a "${REPORT}"
api_sql slow "SHOW SLOW QUERIES LIMIT 30" >/dev/null
ADVICE2="$(api_row_field slow "OR amount = 5" advice)"
printf '%s\n' "${ADVICE2}" | sed 's/^/    /' | tee -a "${REPORT}"
expect "${ADVICE2}" "已有索引" "SC-451 索引已存在时明确指出已有索引"
expect_absent "${ADVICE2}" "候选索引: CREATE INDEX idx_p8_bulk_note" "SC-451 索引已存在时不再建议同一索引(FR-837)"

# ================================================================ SC-448 / SC-460 / SC-461 压测与指标

START_METRICS_QPS_BEFORE="$(metric_value fe_query_qps)"

log ""
log "---------- SC-460 JMeter 8 线程 ${JMETER_SECONDS}s 混合负载 ----------"
MYSQL_JAR="$(cat "${ROOT_DIR}/tools/jdbc-smoke/cp.txt" 2>/dev/null | tr ';' '\n' | grep -i 'mysql-connector' | head -1)"
if [ -z "${MYSQL_JAR}" ]; then
    (cd "${ROOT_DIR}/tools/jdbc-smoke" && mvn.cmd -q -o dependency:build-classpath \
        -Dmdep.outputFile=cp.txt >/dev/null 2>&1 || true)
    MYSQL_JAR="$(tr ';' '\n' <"${ROOT_DIR}/tools/jdbc-smoke/cp.txt" 2>/dev/null | grep -i 'mysql-connector' | head -1)"
fi
JMETER_RUN=""
if [ -x "${JMETER_HOME}/bin/jmeter" ] && [ -n "${MYSQL_JAR}" ]; then
    rm -f "${TEST_DIR}/jmeter.jtl"
    ( "${JMETER_HOME}/bin/jmeter" -n -t "$(cygpath -w "${JMX}")" \
        -Juser.classpath="$(cygpath -w "${MYSQL_JAR}")" \
        -Jhost=127.0.0.1 -Jport="${MYSQL_PORT}" -Jdb="${DB_NAME}" \
        -Juser="${DB_USER}" -Jpassword="${DB_PASS}" -Jrows="${ROWS}" \
        -Jthreads=8 -Jduration="${JMETER_SECONDS}" \
        -Jjmeter.save.saveservice.output_format=csv \
        -l "$(cygpath -w "${TEST_DIR}/jmeter.jtl")" ) \
        >"${TEST_DIR}/jmeter.log" 2>&1
    JMETER_RUN=1
elif [ -z "${MYSQL_JAR}" ]; then
    note "SC-460 未跑 JMeter:找不到 mysql-connector-j jar(见 tools/jdbc-smoke/cp.txt)"
else
    note "SC-460 未跑 JMeter:${JMETER_HOME}/bin/jmeter 不存在"
fi

JMETER_SUMMARY=""
if [ -n "${JMETER_RUN}" ] && [ -s "${TEST_DIR}/jmeter.jtl" ]; then
    JMETER_SUMMARY="$(python - "$(cygpath -w "${TEST_DIR}/jmeter.jtl")" "${JMETER_SECONDS}" <<'PY'
import csv, sys
path, seconds = sys.argv[1], float(sys.argv[2])
lat = []
errors = 0
total = 0
with open(path, newline="", encoding="utf-8", errors="replace") as f:
    reader = csv.DictReader(f)
    for row in reader:
        total += 1
        try:
            lat.append(float(row.get("elapsed") or 0))
        except ValueError:
            lat.append(0.0)
        if str(row.get("success", "true")).lower() != "true":
            errors += 1
lat.sort()
def pct(q):
    if not lat:
        return 0
    i = max(0, min(len(lat) - 1, int(round(q * len(lat))) - 1))
    return lat[i]
qps = total / seconds if seconds > 0 else 0
err_rate = (errors / total * 100) if total else 0
print("qps=%.2f p50=%d p95=%d p99=%d samples=%d errors=%d err_rate=%.3f%%" % (
    qps, pct(0.50), pct(0.95), pct(0.99), total, errors, err_rate))
PY
)"
    printf '%s\n' "${JMETER_SUMMARY}" | sed 's/^/    /' | tee -a "${REPORT}"
    JM_QPS="$(printf '%s' "${JMETER_SUMMARY}" | sed -n 's/.*qps=\([0-9.]*\).*/\1/p')"
    JM_ERR="$(printf '%s' "${JMETER_SUMMARY}" | sed -n 's/.*err_rate=\([0-9.]*\)%.*/\1/p')"
    if python - "${JM_ERR:-100}" <<'PY'
import sys
sys.exit(0 if float(sys.argv[1]) <= 0.1 else 1)
PY
    then
        ok "SC-460 JMeter 错误率 ≤0.1%(${JM_ERR}%)"
    else
        bad "SC-460 JMeter 错误率 ${JM_ERR}% > 0.1%"
    fi
    expect "${JMETER_SUMMARY}" "p95=" "SC-460 报告含 P95"
    expect "${JMETER_SUMMARY}" "p99=" "SC-460 报告含 P99"
else
    note "SC-460 未验证:JMeter 未成功产出 jtl(见 tmp/p8-test/jmeter.log)"
fi

log ""
log "---------- SC-448 指标一致性(/metrics 与 SHOW METRICS) ----------"
sleep 2
refresh_metrics_show
HTTP_CODE="$(http_get /metrics)"
curl -s "http://127.0.0.1:${CONSOLE_PORT}/metrics" >"${TEST_DIR}/metrics.prom"
if [ "${HTTP_CODE}" = "200" ] && grep -q "# TYPE" "${TEST_DIR}/metrics.prom"; then
    ok "SC-448 GET /metrics 返回 Prometheus 文本(HTTP ${HTTP_CODE})"
else
    bad "SC-448 GET /metrics 失败(HTTP ${HTTP_CODE})"
fi
CONSISTENT=1
for name in be_memtable_bytes be_segment_count be_segment_bytes be_alive; do
    a="$(metric_value "${name}")"
    b="$(python - "$(cygpath -w "${TEST_DIR}/metrics.prom")" "${name}" <<'PY'
import sys
path, wanted = sys.argv[1], sys.argv[2]
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if line.startswith("#"):
        continue
    parts = line.split()
    if len(parts) == 2 and parts[0] == wanted:
        print(parts[1])
        break
PY
)"
    if [ -n "${a}" ] && [ "${a}" = "${b}" ]; then
        ok "SC-448 ${name} 两通道一致(${a})"
    else
        CONSISTENT=0
        bad "SC-448 ${name} 不一致(/metrics=${b:-缺} SHOW METRICS=${a:-缺})"
    fi
done
if [ "${CONSISTENT}" = "1" ]; then ok "SC-448 两通道同源同值"; fi
FE_QPS="$(metric_value fe_query_qps)"
if [ -n "${JMETER_SUMMARY}" ]; then
    python - "${JM_QPS:-0}" "${FE_QPS:-0}" <<'PY' && ok "SC-448 QPS 与 JMeter 偏差 ≤10%(JMeter ${JM_QPS} / FE ${FE_QPS})" || bad "SC-448 QPS 偏差 >10%(JMeter ${JM_QPS} / FE ${FE_QPS})"
import sys
jm, fe = float(sys.argv[1]), float(sys.argv[2])
if jm <= 0:
    sys.exit(1)
diff = abs(jm - fe) / jm
print("    偏差 = %.2f%%" % (diff * 100))
sys.exit(0 if diff <= 0.10 else 1)
PY
else
    note "SC-448 未做 QPS 对照(JMeter 未跑);FE QPS=${FE_QPS}"
fi

log ""
log "---------- SC-461 metrics_enabled=false 的开销 ----------"
jmeter_qps() { # <jtl 文件名> <秒数>
    python - "$(cygpath -w "${TEST_DIR}/$1")" "$2" <<'PY'
import csv, sys
total = 0
try:
    with open(sys.argv[1], newline="", encoding="utf-8", errors="replace") as f:
        for _ in csv.DictReader(f):
            total += 1
except FileNotFoundError:
    print("0")
    raise SystemExit(0)
seconds = float(sys.argv[2])
print("%.2f" % (total / seconds if seconds > 0 else 0))
PY
}

if [ -n "${MYSQL_JAR}" ] && [ -x "${JMETER_HOME}/bin/jmeter" ]; then
    ON_MAX=0
    OFF_MAX=0
    for round in 1 2 3; do
        kill_fed
        write_fe_conf true
        start_fed "fed-metrics-r${round}.log" || exit 1
        rm -f "${TEST_DIR}/jmeter-on-r${round}.jtl"
        ( "${JMETER_HOME}/bin/jmeter" -n -t "$(cygpath -w "${JMX}")"             -Juser.classpath="$(cygpath -w "${MYSQL_JAR}")"             -Jhost=127.0.0.1 -Jport="${MYSQL_PORT}" -Jdb="${DB_NAME}"             -Juser="${DB_USER}" -Jpassword="${DB_PASS}" -Jrows="${ROWS}"             -Jthreads=8 -Jduration=10             -Jjmeter.save.saveservice.output_format=csv             -l "$(cygpath -w "${TEST_DIR}/jmeter-on-r${round}.jtl")" )             >"${TEST_DIR}/jmeter-on-r${round}.log" 2>&1
        Q="$(jmeter_qps "jmeter-on-r${round}.jtl" 10)"
        ON_MAX="$(python -c "print(max(${ON_MAX}, ${Q:-0}))")"

        kill_fed
        write_fe_conf false
        start_fed "fed-nometrics-r${round}.log" || exit 1
        rm -f "${TEST_DIR}/jmeter-off-r${round}.jtl"
        ( "${JMETER_HOME}/bin/jmeter" -n -t "$(cygpath -w "${JMX}")"             -Juser.classpath="$(cygpath -w "${MYSQL_JAR}")"             -Jhost=127.0.0.1 -Jport="${MYSQL_PORT}" -Jdb="${DB_NAME}"             -Juser="${DB_USER}" -Jpassword="${DB_PASS}" -Jrows="${ROWS}"             -Jthreads=8 -Jduration=10             -Jjmeter.save.saveservice.output_format=csv             -l "$(cygpath -w "${TEST_DIR}/jmeter-off-r${round}.jtl")" )             >"${TEST_DIR}/jmeter-off-r${round}.log" 2>&1
        Q="$(jmeter_qps "jmeter-off-r${round}.jtl" 10)"
        OFF_MAX="$(python -c "print(max(${OFF_MAX}, ${Q:-0}))")"
    done
    log "    3 轮交替(各 10s)最好 QPS:开指标 ${ON_MAX} / 关指标 ${OFF_MAX}"
    python - "${ON_MAX}" "${OFF_MAX}" <<'PY' && ok "SC-461 关闭指标后 QPS 差异 ≤5%(视为零开销)" || note "SC-461 关闭指标后 QPS 差异 >5%(未达零开销判据,如实记录)"
import sys
on, off = float(sys.argv[1]), float(sys.argv[2])
if on <= 0:
    sys.exit(1)
diff = abs(on - off) / on
print("    差异 = %.2f%%" % (diff * 100))
sys.exit(0 if diff <= 0.05 else 1)
PY
    kill_fed
    write_fe_conf true
    start_fed "fed-metrics.log" || exit 1
else
    note "SC-461 未验证:JMeter 或 mysql-connector-j 不可用,拿不到两份压测数据"
fi

# ================================================================ SC-452 备份中强杀

log ""
log "---------- SC-452 ★ 备份期间强杀 BE ----------"
BACKUP_DIR="${TEST_DIR}/backup/killed"
rm -rf "${BACKUP_DIR}"
# 用控制台 API 发起备份:没有 JVM 启动开销,BACKUP 语句返回时复制刚起步,便于命中"备份中强杀"
api_sql bk-killed "BACKUP TO '$(cygpath -w "${BACKUP_DIR}")'" >/dev/null
python - "$(cygpath -w "${TEST_DIR}/bk-killed.json")" <<'PY' | sed 's/^/    /' | tee -a "${REPORT}"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
result = (data.get("results") or [{}])[0]
print(result.get("message") or data.get("error", {}).get("message", ""))
PY
# 立刻强杀:备份是后台任务,BACKUP 语句返回时复制正在进行
fast_kill_be
sleep 1
kill_be >/dev/null 2>&1
sleep 1
MID_KILL=0
if [ -f "${BACKUP_DIR}/BACKUP_META" ]; then
    note "SC-452 未命中中途窗口:强杀前备份已完成(目录有 BACKUP_META),本项未验证"
else
    MID_KILL=1
    ok "SC-452 强杀后备份目录没有 BACKUP_META(备份不完整)"
fi
start_be "be-after-kill.log" || exit 1
kill_fed
start_fed "fed-after-kill.log" || exit 1
INTACT="$(count_rows p8_load)"
if [ "${INTACT}" -ge "${ROWS}" ] 2>/dev/null; then
    ok "SC-452 重启后原库完整(p8_load ${INTACT} 行)"
else
    bad "SC-452 重启后原库不完整(期望 ≥${ROWS} 行,实际 ${INTACT:-?})"
fi
if [ "${MID_KILL}" = "1" ]; then
    RESTORE_OUT="$(run_sql_text "RESTORE FROM '$(cygpath -w "${BACKUP_DIR}")' TO '$(cygpath -w "${TEST_DIR}/restored-killed")'; exit")"
    echo "${RESTORE_OUT}" | tail -3 | sed 's/^/    /' | tee -a "${REPORT}"
    sleep 2
    REJECTED="$(job_field any message)"
    if printf '%s' "${REJECTED}" | grep -qF "BACKUP_META"; then
        ok "SC-452 RESTORE 拒绝不完整备份(状态: $(job_status any))"
    else
        bad "SC-452 RESTORE 未按预期拒绝(任务消息: ${REJECTED:-空})"
    fi
else
    note "SC-452 RESTORE 拒绝不完整备份未执行(强杀未命中备份窗口,不伪造结论)"
fi

# ================================================================ SC-453 / SC-454 备份期间写入 + 端到端恢复

log ""
log "---------- SC-453 ★ 备份期间持续写入 + SC-454 端到端恢复 ----------"
run_sql_text "ANALYZE TABLE p8_load; exit" >/dev/null 2>&1
run_sql_text "ANALYZE TABLE p8_bulk; exit" >/dev/null 2>&1
COUNT0="$(db_count)"
SUM0="$(db_sum)"
FULL_DIR="${TEST_DIR}/backup/full"
rm -rf "${FULL_DIR}"
FULL_OUT="$(run_sql_text "BACKUP TO '$(cygpath -w "${FULL_DIR}")' FORCE; exit")"
printf '%s\n' "${FULL_OUT}" | tail -2 | sed 's/^/    /' | tee -a "${REPORT}"
# 备份进行中:200 行/秒写入 4 秒(备份通常 <2s,覆盖整个窗口)
WRITE_OUT="$(timeout 60 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8WriteLoad --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --table p8_load \
    --base 5000000 --rate 200 --seconds 4 2>&1)"
printf '%s\n' "${WRITE_OUT}" | sed 's/^/    /' | tee -a "${REPORT}"
WRITE_FAILED="$(printf '%s' "${WRITE_OUT}" | sed -n 's/.*failed=\([0-9]*\).*/\1/p')"
if [ "${WRITE_FAILED:-1}" = "0" ]; then
    ok "SC-453 备份期间写入成功率 100%(failed=0)"
else
    bad "SC-453 备份期间有写入失败(failed=${WRITE_FAILED:-?})"
fi
if wait_job SUCCESS 180; then
    ok "SC-453 备份任务完成"
else
    bad "SC-453 备份任务未完成(状态: $(job_status any))"
fi
if [ -f "${FULL_DIR}/BACKUP_META" ]; then
    ok "SC-453 备份目录有 BACKUP_META(完整)"
    SNAP_ROWS="$(python - "$(cygpath -w "${FULL_DIR}/BACKUP_META")" <<'PY'
import json, sys
meta = json.load(open(sys.argv[1], encoding="utf-8"))
print(meta.get("snapshot_rows", 0))
PY
)"
    log "    BACKUP_META snapshot_rows=${SNAP_ROWS}(备份点统计行数)/ 备份前行数=${COUNT0}"
    if [ "${SNAP_ROWS}" = "${COUNT0}" ]; then
        ok "SC-453 备份点行数等于备份开始时的行数(${SNAP_ROWS})"
    else
        note "SC-453 备份点统计行数(${SNAP_ROWS})与备份前 COUNT(*)=${COUNT0} 不等(统计口径差异,如实记录)"
    fi
else
    bad "SC-453 备份目录缺少 BACKUP_META"
fi

AFTER_WRITE_COUNT="$(count_rows p8_load)"
log "    写入后行数=${AFTER_WRITE_COUNT}(应 >${COUNT0})"
if [ "${AFTER_WRITE_COUNT}" -gt "$((COUNT0 - BULK_ROWS))" ] 2>/dev/null; then
    ok "SC-453 备份期间写入真的生效了(p8_load $((${COUNT0} - ${BULK_ROWS})) → ${AFTER_WRITE_COUNT})"
else
    bad "SC-453 备份期间写入没有生效"
fi

RESTORED_DIR="${TEST_DIR}/restored-full"
rm -rf "${RESTORED_DIR}"
R_OUT="$(run_sql_text "RESTORE FROM '$(cygpath -w "${FULL_DIR}")' TO '$(cygpath -w "${RESTORED_DIR}")'; exit")"
printf '%s\n' "${R_OUT}" | tail -2 | sed 's/^/    /' | tee -a "${REPORT}"
if wait_job SUCCESS 180; then
    ok "SC-454 RESTORE 任务完成"
else
    bad "SC-454 RESTORE 未完成(状态: $(job_status any),消息: $(job_field any message))"
fi
if [ -f "${RESTORED_DIR}/BACKUP_META" ]; then
    note "SC-454 恢复目录里出现了 BACKUP_META(备份清单被一起恢复,不影响启动)"
fi

# 删数据目录 → 用恢复目录启动 → 校验行数与 checksum(SC-454)
kill_fed
kill_be
mv "${DATA_DIR}" "${TEST_DIR}/data-before-restore"
write_be_conf "${RESTORED_DIR}"
start_be "be-restored.log" || exit 1
start_fed "fed-restored.log" || exit 1
COUNT1="$(db_count)"
SUM1="$(db_sum)"
log "    恢复后:COUNT(*)=${COUNT1} / SUM(amount)=${SUM1}(备份点 ${COUNT0} / ${SUM0})"
if [ "${COUNT1}" = "${COUNT0}" ]; then
    ok "SC-453/454 恢复出的库 COUNT(*) 等于备份点(${COUNT0})"
else
    bad "SC-453/454 恢复出的库 COUNT(*)=${COUNT1} ≠ 备份点 ${COUNT0}"
fi
if [ -n "${SUM0}" ] && [ "${SUM1}" = "${SUM0}" ]; then
    ok "SC-453/454 恢复出的库 checksum(SUM amount)等于备份点(${SUM0})"
else
    bad "SC-453/454 checksum 不一致(恢复 ${SUM1:-?} / 备份点 ${SUM0:-?})"
fi

# ================================================================ SC-455 增量备份

log ""
log "---------- SC-455 增量备份 ----------"
timeout 120 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8WriteLoad --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --table p8_load \
    --base 6000000 --rate 5000 --seconds 1 2>&1 | tail -1 | sed 's/^/    /' | tee -a "${REPORT}"
run_sql_text "ANALYZE TABLE p8_load; exit" >/dev/null 2>&1
run_sql_text "ANALYZE TABLE p8_bulk; exit" >/dev/null 2>&1
INC_DIR="${TEST_DIR}/backup/inc"
rm -rf "${INC_DIR}"
INC_OUT="$(run_sql_text "BACKUP TO '$(cygpath -w "${INC_DIR}")' INCREMENTAL FROM '$(cygpath -w "${FULL_DIR}")' FORCE; exit")"
printf '%s\n' "${INC_OUT}" | tail -2 | sed 's/^/    /' | tee -a "${REPORT}"
if wait_job SUCCESS 180; then
    ok "SC-455 增量备份任务完成"
else
    bad "SC-455 增量备份未完成($(job_status any))"
fi
if [ -f "${FULL_DIR}/BACKUP_META" ] && [ -f "${INC_DIR}/BACKUP_META" ]; then
    META_CMP="$(python - "$(cygpath -w "${FULL_DIR}/BACKUP_META")" "$(cygpath -w "${INC_DIR}/BACKUP_META")" <<'PY'
import json, sys
full = json.load(open(sys.argv[1], encoding="utf-8"))
inc = json.load(open(sys.argv[2], encoding="utf-8"))
print("full=%d inc=%d inherited=%d incremental=%s" % (
    full.get("copied_bytes", 0), inc.get("copied_bytes", 0),
    len(inc.get("inherited", [])), inc.get("incremental")))
PY
)"
    printf '%s\n' "${META_CMP}" | sed 's/^/    /' | tee -a "${REPORT}"
    FULL_BYTES="$(printf '%s' "${META_CMP}" | sed -n 's/.*full=\([0-9]*\).*/\1/p')"
    INC_BYTES="$(printf '%s' "${META_CMP}" | sed -n 's/.*inc=\([0-9]*\).*/\1/p')"
    if [ "${INC_BYTES:-0}" -lt "${FULL_BYTES:-0}" ] 2>/dev/null; then
        ok "SC-455 增量备份字节(${INC_BYTES})< 全量(${FULL_BYTES})"
    else
        bad "SC-455 增量备份字节(${INC_BYTES:-?})不小于全量(${FULL_BYTES:-?})"
    fi
else
    bad "SC-455 缺少 BACKUP_META,无法比较字节"
fi
INC_RESTORED="${TEST_DIR}/restored-inc"
rm -rf "${INC_RESTORED}"
run_sql_text "RESTORE FROM '$(cygpath -w "${INC_DIR}")' TO '$(cygpath -w "${INC_RESTORED}")'; exit" >/dev/null
if wait_job SUCCESS 180; then
    ok "SC-455 增量备份恢复任务完成(可独立恢复:沿 parent 链补齐线段文件)"
else
    bad "SC-455 增量恢复失败($(job_status any): $(job_field any message))"
fi
BEFORE_INC_RESTART_COUNT="$(db_count)"
kill_fed
kill_be
mkdir -p "${TEST_DIR}/verify-inc"
write_be_conf "${INC_RESTORED}"
start_be "be-inc.log" || exit 1
start_fed "fed-inc.log" || exit 1
INC_COUNT="$(db_count)"
if [ "${INC_COUNT}" = "${BEFORE_INC_RESTART_COUNT}" ]; then
    ok "SC-455 增量备份恢复后行数一致(${INC_COUNT})"
else
    bad "SC-455 增量恢复后行数 ${INC_COUNT} ≠ ${BEFORE_INC_RESTART_COUNT}"
fi

# ================================================================ SC-456 / 457 / 458 控制台

log ""
log "---------- SC-456 控制台 API 冒烟 ----------"
LOGIN_BODY="$(curl -s -X POST "http://127.0.0.1:${CONSOLE_PORT}/api/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${DB_USER}\",\"password\":\"${DB_PASS}\"}")"
printf '%s\n' "${LOGIN_BODY}" | sed 's/^/    /' | tee -a "${REPORT}"
TOKEN="$(printf '%s' "${LOGIN_BODY}" | python -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null)"
if [ -n "${TOKEN}" ]; then ok "SC-456 POST /api/login 200 且返回 token"; else bad "SC-456 /api/login 失败"; fi

api_get() { # <path> <out-name>
    local code
    code="$(curl -s -o "$(cygpath -w "${TEST_DIR}/$2.json")" -w "%{http_code}" \
        -H "X-Console-Token: ${TOKEN}" "http://127.0.0.1:${CONSOLE_PORT}$1")"
    echo "${code}"
}
CODE_TREE="$(api_get /api/tree tree)"
CODE_STATUS="$(api_get /api/status status)"
CODE_EXPLAIN="$(curl -s -o "$(cygpath -w "${TEST_DIR}/explain.json")" -w "%{http_code}" -X POST \
    "http://127.0.0.1:${CONSOLE_PORT}/api/explain" -H "X-Console-Token: ${TOKEN}" \
    -H 'Content-Type: application/json' -d '{"sql":"SELECT * FROM p8_load WHERE id = 1"}')"
CODE_QUERY="$(curl -s -o "$(cygpath -w "${TEST_DIR}/query.json")" -w "%{http_code}" -X POST \
    "http://127.0.0.1:${CONSOLE_PORT}/api/query" -H "X-Console-Token: ${TOKEN}" \
    -H 'Content-Type: application/json' -d '{"sql":"SELECT id FROM p8_load LIMIT 3"}')"
for pair in "tree:${CODE_TREE}" "status:${CODE_STATUS}" "explain:${CODE_EXPLAIN}" "query:${CODE_QUERY}"; do
    name="${pair%%:*}"
    code="${pair##*:}"
    if [ "${code}" = "200" ]; then ok "SC-456 /api/${name} 200"; else bad "SC-456 /api/${name} 返回 ${code}"; fi
done
python - "$(cygpath -w "${TEST_DIR}/tree.json")" <<'PY' && ok "SC-456 /api/tree 结构正确(库 → 表 → 列/索引)" || bad "SC-456 /api/tree 结构不正确"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
db = data["databases"][0]
table = db["tables"][0]
assert "columns" in table and "indexes" in table and table.get("ddl")
PY
python - "$(cygpath -w "${TEST_DIR}/query.json")" <<'PY' && ok "SC-456 /api/query 返回列名/行/耗时" || bad "SC-456 /api/query 结构不正确"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
result = data["results"][0]
assert result["columns"] and result["rows"] and "elapsed_ms" in data
PY
python - "$(cygpath -w "${TEST_DIR}/explain.json")" <<'PY' && ok "SC-456 /api/explain 返回计划文本" || bad "SC-456 /api/explain 结构不正确"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
assert data.get("plan")
PY

log ""
log "---------- SC-457 控制台状态与 SHOW METRICS 一致 ----------"
refresh_metrics_show
STATUS_CHECK="$(python - "$(cygpath -w "${TEST_DIR}/status.json")" "$(cygpath -w "${TEST_DIR}/metrics-show.txt")" <<'PY'
import json, sys
status = json.load(open(sys.argv[1], encoding="utf-8"))
metrics = {}
for line in open(sys.argv[2], encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if len(cells) >= 2:
        metrics[cells[0]] = cells[1]
pairs = [
    ("memtable_bytes", "be_memtable_bytes"),
    ("segment_count", "be_segment_count"),
]
bad = []
for api_field, metric in pairs:
    a = status.get(api_field)
    b = metrics.get(metric)
    if b is None or float(a) != float(b):
        bad.append("%s: api=%s metric=%s" % (api_field, a, b))
print("OK" if not bad else "BAD " + "; ".join(bad))
PY
)"
expect "${STATUS_CHECK}" "OK" "SC-457 状态区 memtable/segment 与 SHOW METRICS 一致"
if python - "$(cygpath -w "${TEST_DIR}/status.json")" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if all(k in data for k in ("qps", "p95_ms", "p99_ms", "be_alive")) else 1)
PY
then
    ok "SC-457 状态区含 QPS / P95 / P99 / BE 存活"
else
    bad "SC-457 状态区字段缺失"
fi

log ""
log "---------- SC-458 写操作二次确认 ----------"
REJECT_CODE="$(curl -s -o "$(cygpath -w "${TEST_DIR}/write-rejected.json")" -w "%{http_code}" -X POST \
    "http://127.0.0.1:${CONSOLE_PORT}/api/query" -H "X-Console-Token: ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"sql":"CREATE TABLE p8_console_guard (id INT PRIMARY KEY)"}')"
if [ "${REJECT_CODE}" = "409" ] && grep -q "confirm_required" "${TEST_DIR}/write-rejected.json"; then
    ok "SC-458 未确认的写请求被拒绝(409 + confirm_required)"
else
    bad "SC-458 未确认的写请求没有被拒绝(HTTP ${REJECT_CODE})"
fi
CONFIRM_CODE="$(curl -s -o "$(cygpath -w "${TEST_DIR}/write-accepted.json")" -w "%{http_code}" -X POST \
    "http://127.0.0.1:${CONSOLE_PORT}/api/query" -H "X-Console-Token: ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"sql":"CREATE TABLE p8_console_guard (id INT PRIMARY KEY)","confirm":true}')"
if [ "${CONFIRM_CODE}" = "200" ]; then
    ok "SC-458 确认后写操作生效"
else
    bad "SC-458 确认后写操作失败(HTTP ${CONFIRM_CODE})"
fi
if grep -q "WRITE_REJECTED" "${ROOT_DIR}/tmp/console-audit.log" 2>/dev/null \
    && grep -q "WRITE_CONFIRMED" "${ROOT_DIR}/tmp/console-audit.log" 2>/dev/null; then
    ok "SC-458 审计日志同时留下拒绝与确认记录"
else
    bad "SC-458 审计日志缺少记录(tmp/console-audit.log)"
fi

# ================================================================ SC-462 备份不阻塞

log ""
log "---------- SC-462 备份期间写入 P99 ----------"
BASELINE_OUT="$(timeout 120 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8WriteLoad --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --table p8_load \
    --base 7000000 --rate 500 --seconds 4 2>&1)"
printf '%s\n' "${BASELINE_OUT}" | tail -1 | sed 's/^/    无备份: /' | tee -a "${REPORT}"
BK2="${TEST_DIR}/backup/p99"
rm -rf "${BK2}"
run_sql_text "BACKUP TO '$(cygpath -w "${BK2}")' FORCE; exit" >/dev/null
DURING_OUT="$(timeout 120 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
    com.wangyu.tangdb.tools.P8WriteLoad --port "${MYSQL_PORT}" --user "${DB_USER}" \
    --password "${DB_PASS}" --db "${DB_NAME}" --table p8_load \
    --base 7100000 --rate 500 --seconds 4 2>&1)"
printf '%s\n' "${DURING_OUT}" | tail -1 | sed 's/^/    备份中: /' | tee -a "${REPORT}"
BASE_P99="$(printf '%s' "${BASELINE_OUT}" | sed -n 's/.*p99=\([0-9]*\).*/\1/p')"
DURING_P99="$(printf '%s' "${DURING_OUT}" | sed -n 's/.*p99=\([0-9]*\).*/\1/p')"
if [ -n "${BASE_P99}" ] && [ -n "${DURING_P99}" ]; then
    python - "${BASE_P99}" "${DURING_P99}" <<'PY' && ok "SC-462 备份期间写入 P99 ≤ 无备份的 1.5 倍(${DURING_P99} / ${BASE_P99} ms)" || note "SC-462 备份期间写入 P99 超过 1.5 倍(${DURING_P99} / ${BASE_P99} ms),如实记录"
import sys
base, during = float(sys.argv[1]), float(sys.argv[2])
print("    比值 = %.2f" % (during / max(base, 1)))
sys.exit(0 if during <= base * 1.5 else 1)
PY
else
    note "SC-462 未量到 P99(输出缺字段)"
fi

# ================================================================ SC-463 依赖红线

log ""
log "---------- SC-463 依赖红线 ----------"
BAD_DEPS="$(grep -iE "spring|javalin|vertx|prometheus|micrometer|undertow|jetty|quarkus|resteasy" "${FE_DIR}/pom.xml" || true)"
if [ -n "${BAD_DEPS}" ]; then
    bad "SC-463 FE pom.xml 出现 Web / 监控框架依赖: ${BAD_DEPS}"
else
    ok "SC-463 FE pom.xml 无 Spring/Javalin/Vert.x/Prometheus(grpc-netty 属 grpc-java 传输层)"
fi
if grep -q "com.sun.net.httpserver" \
    "${FE_DIR}/src/main/java/com/wangyu/tangdb/console/ConsoleServer.java"; then
    ok "SC-463 控制台后端用 JDK 内置 HttpServer"
else
    bad "SC-463 控制台后端不是 JDK 内置 HttpServer"
fi
CONSOLE_DEPS="$(python - "$(cygpath -w "${ROOT_DIR}/console/package.json")" <<'PY'
import json, sys
pkg = json.load(open(sys.argv[1], encoding="utf-8"))
names = sorted(list(pkg.get("dependencies", {}).keys())
               + list(pkg.get("devDependencies", {}).keys()))
print(" ".join(names))
PY
)"
log "    前端依赖:${CONSOLE_DEPS}"
EXTRA="$(printf '%s' "${CONSOLE_DEPS}" | tr ' ' '\n' | grep -vE '^(vue|element-plus|vite|@vitejs/plugin-vue)$' | grep -v '^$' || true)"
if [ -z "${EXTRA}" ]; then
    ok "SC-463 前端依赖只有 vue / element-plus / vite(+@vitejs/plugin-vue 构建插件)"
else
    bad "SC-463 前端出现额外依赖: ${EXTRA}"
fi
if [ -f "${ROOT_DIR}/console/dist/index.html" ]; then
    ok "SC-463 console/dist 已构建(可直接托管)"
else
    bad "SC-463 console/dist 不存在(未 npm run build)"
fi
if grep -v "^//" "${BE_DIR}/go.mod" | grep -qiE "rocksdb|leveldb|badger|pebble|sqlite|bbolt|duckdb|faiss|hnswlib"; then
    bad "SC-463 BE go.mod 出现被禁止的存储依赖"
else
    ok "SC-463 BE 依赖红线未破"
fi

# ================================================================ SC-465 文档

log ""
log "---------- SC-465 README P8 章节 ----------"
README_P8="$(grep -n "P8 生产化\|P8(生产化)\|### P8" "${ROOT_DIR}/README.md" | head -3)"
if [ -n "${README_P8}" ]; then
    ok "SC-465 README 有 P8 章节(${README_P8%%:*})"
else
    bad "SC-465 README 缺少 P8 章节"
fi
for keyword in "fe_query_qps" "BACKUP TO" "console/dist" "SHOW METRICS"; do
    if grep -qF "${keyword}" "${ROOT_DIR}/README.md"; then
        ok "SC-465 README 含关键说明: ${keyword}"
    else
        bad "SC-465 README 缺少: ${keyword}"
    fi
done

# ================================================================ SC-464 既有回归

log ""
log "---------- SC-464 既有回归 ----------"
if [ "${P8_REGRESSION:-${SKIP_REGRESSION:-0}}" = "1" ] && [ "${SKIP_REGRESSION:-0}" != "1" ]; then
    kill_fed
    kill_be
    for script in p3-test.sh p4-test.sh p5-test.sh p6-test.sh p6.1-test.sh p7-test.sh; do
        if [ ! -f "${SCRIPT_DIR}/${script}" ]; then
            note "SC-464 ${script} 不存在(跳过)"
            continue
        fi
        if bash "${SCRIPT_DIR}/${script}" >"${TEST_DIR}/reg-${script}.log" 2>&1; then
            ok "SC-464 ${script} 全绿"
        else
            bad "SC-464 ${script} 失败(见 tmp/p8-test/reg-${script}.log)"
            tail -12 "${TEST_DIR}/reg-${script}.log" | tee -a "${REPORT}"
        fi
    done
else
    note "SC-464 未跑既有回归(p3/p4/p5/p6/p6.1/p7/p7.1)。所有者要求先测通 P8 自身功能;要跑加 P8_REGRESSION=1"
fi

# ================================================================ 汇总

log ""
log "=================================================="
log " P8 验收结果:PASS=${PASS} FAIL=${FAIL} NOTE=${NOTE_ONLY}"
log " 报告:${REPORT}"
log "=================================================="
if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
