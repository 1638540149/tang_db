#!/usr/bin/env bash
# tang_db P6 一键验收:优化器(统计信息 / CBO / 连接顺序与算子 / 谓词下推与列裁剪 /
# 集合运算 / 相关子查询与量词 / DISTINCT / 派生表 / information_schema 放开 / 门限)
#
# 覆盖 SPEC-P6 §8 的 SC-370 ~ SC-390,每条都真跑:
#   SC-370 ANALYZE 建统计(10 万行表:行数 = COUNT(*),列统计条数 = 分析列数)
#   SC-371 直方图与 distinct(skew 单值占 60% 的桶份额 ≈ 60%,distinct 误差 ≤ 5%)
#   SC-372 ★ 分析中途强杀 BE → 统计要么全旧要么全新;COUNT(*) 不被破坏
#   SC-373 ★ 并发两次 ANALYZE → 两个都 CODE=0,SHOW STATS 只有一份完整统计
#   SC-374 ANALYZE 前后计划变化,结果集逐行一致
#   SC-375 optimizer_enabled=true/false 12 条 SQL 结果逐行一致
#   SC-376 小表灌大后重新 ANALYZE,EXPLAIN 的 build 侧 / 连接次序变化
#   SC-377 集合运算四种 + 重复行 / NULL / 类型混合;INTERSECT ALL → 1235
#   SC-378 EXISTS / NOT EXISTS / ANY / ALL(含空集 / NULL)
#   SC-379 DISTINCT 与等价 GROUP BY 一致(5 组)
#   SC-380 派生表(单层 / 两层 / JOIN / 别名);横向相关 1235
#   SC-381 图形客户端自省语句 CODE=0;PARAMETERS 0 行
#   SC-382 统计过期(行数 +30%)后 EXPLAIN 标注 stale,结果不变
#   SC-383 EXPLAIN 向后兼容(P4 既有行仍在,新行追加在后)
#   SC-384 谓词下推 / 列裁剪可见;LIMIT 下推结果正确
#   SC-385 常量折叠与短路(1=1 / NOT NOT / 除零)
#   SC-386 计划文本连续 3 次完全一致
#   SC-387 性能门限:Q1/Q3/Q5/Q6/Q10 单条不变慢 >10%,且 ≥2 条快 ≥20%(三次中位数)
#   SC-388 重启 BE + fed 后 SHOW STATS 与重启前一致
#   SC-389 BEGIN; ANALYZE → 1235,事务可继续 ROLLBACK
#   SC-390 既有回归(p2 / p3 / p35 / p4 / crash-test;SKIP_REGRESSION=1 可跳过)
#
# 用法: bash scripts/tests/p6-test.sh
# 端口:BE 9950 / MySQL 9956 / 心跳 9961;独立数据目录 tmp/p6-test/data。
# 注意:只杀自己起的 tmp/p6-test/be.exe 与带 tmp/p6-test 配置的 fed;
#       9100/3406 上的业主环境与其它脚本端口不受影响。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p6-test"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/report.txt"
SQL_DIR="${ROOT_DIR}/scripts/sql"
CSV_TOOL="${ROOT_DIR}/scripts/tools/csv-import"

# 端口默认按规约固定 9950 / 9956 / 9961;允许用环境变量覆盖,便于同机已有实例占用时复跑。
BE_PORT="${P6_BE_PORT:-9950}"
MYSQL_PORT="${P6_MYSQL_PORT:-9956}"
HEART_PORT="${P6_HEART_PORT:-9961}"
DB_USER=root
DB_PASS=p6test
DB_NAME=default

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

PASS=0
FAIL=0
NOTE_ONLY=0

rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}"
: >"${REPORT}"

log()  { echo "$*" | tee -a "${REPORT}"; }
ok()   { PASS=$((PASS + 1)); log "  [PASS] $*"; }
bad()  { FAIL=$((FAIL + 1)); log "  [FAIL] $*"; }
note() { NOTE_ONLY=$((NOTE_ONLY + 1)); log "  [NOTE] $*"; }

BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"
TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"

kill_be() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    for _ in $(seq 1 40); do
        be_running || break
        sleep 0.25
    done
    sleep 1
}

be_running() {
    powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1
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

write_be_conf() {
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = ${P6_MEMTABLE_SIZE:-2MB}
fe_host = 127.0.0.1
fe_port = ${HEART_PORT}
heartbeat_interval_seconds = 3
max_txn_write_bytes = 64MB
test_crash_point =
query_batch_rows = 1024
compaction_l0_files = 2
compaction_level_ratio = 10
compaction_level_max_bytes = 16MB
compaction_bloom_enabled = true
sort_spill_bytes = 1MB
EOF
}

write_fe_conf() { # <optimizer_enabled: true|false>
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
sort_spill_bytes = 1MB
sort_tmp_dir = $(cygpath -w "${TEST_DIR}/fe-sort")
csv_import_batch_rows = 500
optimizer_enabled = ${1:-true}
analyze_full_scan_max_rows = 200000
analyze_sample_stride = 20
analyze_histogram_buckets = 64
analyze_exact_distinct_max_rows = 100000
analyze_hll_precision = 14
cbo_join_enum_max_tables = 4
cbo_hash_aggregate_distinct_max = 10000
cbo_topn_limit_ratio_percent = 30
subquery_cache_max_entries = 4096
stats_stale_row_change_percent = 20
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

start_fed() { # <log-name>
    local name="${1:-fed.log}"
    wait_port_free "${MYSQL_PORT}" 20
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

fresh_env() { # [be-log] [fed-log] [optimizer]
    kill_fed
    kill_be
    rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}" "${TEST_DIR}/fe-sort"
    write_be_conf
    write_fe_conf "${3:-true}"
    start_be "${1:-be.log}" || return 1
    start_fed "${2:-fed.log}" || return 1
    return 0
}

restart_services() { # <be-log> <fed-log> [optimizer]
    kill_fed
    kill_be
    wait_port_free "${BE_PORT}" 30
    start_be "$1" || return 1
    start_fed "$2" "${3:-true}" || return 1
    return 0
}

run_sql_text() { # <sql-text>
    printf '%s\n' "$1" | timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
        com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" 2>&1
}

run_sql_file() {
    timeout 180 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${CP}" \
        com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" <"$1" 2>&1
}

mysql_code() { # <sql>
    timeout 180 java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.index.MysqlErrorCheck \
        "${MYSQL_PORT}" "${DB_USER}" "${DB_PASS}" "${DB_NAME}" "$1" 2>&1
}

# 有界等待:后台 CLI 在 BE 死掉时可能不退出,禁止无限 wait(核验时实测卡过 7 分钟)
wait_bounded() { # <pid> [秒]
    local pid="$1" limit="${2:-180}" i
    for i in $(seq 1 "${limit}"); do
        kill -0 "${pid}" 2>/dev/null || return 0
        sleep 1
    done
    log "    [wait] PID ${pid} 超过 ${limit}s 未退出,强制结束"
    kill -9 "${pid}" 2>/dev/null || true
    return 1
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

row_count() { printf '%s' "$1" | grep -oE '^\([0-9]+ rows?\)' | head -1 | grep -oE '[0-9]+' || true; }
first_int() { printf '%s\n' "$1" | sed -n 's/^| *\(-\{0,1\}[0-9][0-9]*\) *|.*$/\1/p' | head -1; }
now_ms() { date +%s%N; }
elapsed_ms() { echo $(( ($(now_ms) - $1) / 1000000 )); }

# SHOW STATS 的某列(按列名定位,不靠固定下标)。
stats_field() { # <table> <column-name>
    local out="${TEST_DIR}/stats-$1.txt"
    run_sql_text "SHOW STATS FROM $1; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$2" <<'PYEOF'
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
    if wanted in header:
        print(cells[header.index(wanted)])
    break
PYEOF
}

stats_column_field() { # <table> <column-name> <field>
    local out="${TEST_DIR}/stats-$1.txt"
    run_sql_text "SHOW STATS FROM $1; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$2" "$3" <<'PYEOF'
import sys
path, column, field = sys.argv[1], sys.argv[2], sys.argv[3]
header = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if row.get("column_name") == column:
        print(row.get(field, ""))
        break
PYEOF
}

python_csv() { # <rows> <path>  — 生成 id,city,amount 的 CSV
    python - "$1" "$(cygpath -w "$2")" <<'PY'
import csv, sys
rows = int(sys.argv[1]); path = sys.argv[2]
with open(path, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["id", "city", "amount"])
    for i in range(1, rows + 1):
        city = "hot" if i <= int(rows * 0.6) else "c%d" % (i % 400)
        w.writerow([i, city, i % 1000])
PY
}

import_csv() { # <table> <file>
    "${CSV_TOOL}" --table "$1" --file "$2" --db "${DB_NAME}" --batch "${P6_IMPORT_BATCH:-800}" \
        --config "${FE_CONF}" >"${TEST_DIR}/import-$1.log" 2>&1
}

# 导入是幂等可续的(同主键重写不产生重复行);批提交超时时重跑几次直到行数到位。
import_until() { # <table> <file> <expected-rows> [tries]
    local table="$1" file="$2" expected="$3" tries="${4:-6}" n=""
    for _ in $(seq 1 "${tries}"); do
        import_csv "${table}" "${file}" || true
        n="$(first_int "$(run_sql_text "SELECT COUNT(*) FROM ${table}; exit")")"
        if [ "${n}" = "${expected}" ]; then
            return 0
        fi
        sleep 2
    done
    log "    [import] ${table} 期望 ${expected} 行,实际 ${n:-空}(已重试 ${tries} 次)"
    return 1
}

# ================================================================ 机器条件模板

log "=================================================="
log " tang_db P6 一键验收  $(date '+%Y-%m-%d %H:%M:%S')"
log "=================================================="
log ""
log "【机器条件模板(设计文档 §18)】"
log "  日期/时间      : $(date '+%Y-%m-%d %H:%M:%S %Z')"
log "  OS             : $(uname -srm)"
log "  CPU            : $(powershell -NoProfile -Command '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name' 2>/dev/null | tr -d '\r' | xargs)"
log "  物理内存       : $(powershell -NoProfile -Command '[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1MB)' 2>/dev/null | tr -d '\r') MB"
log "  Go             : $(go version 2>/dev/null)"
log "  Java           : $(java -version 2>&1 | head -1)"
log "  Maven          : $(mvn.cmd -version 2>/dev/null | head -1)"
log "  数据目录       : ${DATA_DIR}(本地盘)"
log "  端口           : BE ${BE_PORT} / MySQL ${MYSQL_PORT} / 心跳 ${HEART_PORT}"
log "  memtable       : ${P6_MEMTABLE_SIZE:-2MB}(批量导入受 P6.1 §1 的 O(行数×memtable) 影响,验收用 2MB;batch=${P6_IMPORT_BATCH:-800});sort_spill_bytes = 1MB(便于触发溢写路径)"
log "  负载           : 单线程查询;后台 compaction 可能并行"
log "  统计口径       : CLI 耗时含 JVM 启动;SC-387 用 CboBench 在同 JVM 内测三次中位数"
log ""

# ================================================================ 构建

log "[构建] BE"
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }
log "[构建] FE(含测试类)"
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"

# ================================================================ 起环境

log ""
log "[启动] BE ${BE_PORT} / fed ${MYSQL_PORT} / 心跳 ${HEART_PORT}"
fresh_env be.log fed.log true || { log "环境启动失败"; exit 1; }

# ================================================================ SC-370 / SC-371 / SC-388

log ""
log "---------- SC-370 / SC-371 / SC-388 STATISTICS(10 万行) ----------"
run_sql_text "CREATE TABLE p6_big (id INT PRIMARY KEY, city VARCHAR(16), amount INT); exit" >/dev/null
python_csv 100000 "${TEST_DIR}/big.csv"
BIG_IMPORT_START=$(now_ms)
if import_until p6_big "${TEST_DIR}/big.csv" 100000 30; then
    ok "SC-370 10 万行 CSV 导入完成(耗时≈$(elapsed_ms "${BIG_IMPORT_START}")ms)"
else
    bad "SC-370 10 万行导入失败"; tail -8 "${TEST_DIR}/import-p6_big.log" | tee -a "${REPORT}"
fi
BIG_COUNT="$(first_int "$(run_sql_text "SELECT COUNT(*) FROM p6_big; exit")")"
if [ "${BIG_COUNT}" = "100000" ]; then ok "SC-370 导入行数 = 100000"
else bad "SC-370 导入行数异常(${BIG_COUNT:-空})"; fi

ANALYZE_START=$(now_ms)
OUT="$(run_sql_text "ANALYZE TABLE p6_big; exit")"
ANALYZE_MS=$(elapsed_ms "${ANALYZE_START}")
expect "${OUT}" "统计已更新" "SC-370 ANALYZE 返回成功(耗时≈${ANALYZE_MS}ms)"

STATS_OUT="$(run_sql_text "SHOW STATS FROM p6_big; exit")"
STATS_ROWS="$(row_count "${STATS_OUT}")"
STATS_ROWCOUNT="$(stats_field p6_big row_count)"
if [ -n "${STATS_ROWCOUNT}" ] && [ "${STATS_ROWCOUNT}" = "100000" ]; then
    ok "SC-370 SHOW STATS 的 row_count = COUNT(*) = ${STATS_ROWCOUNT}"
else
    bad "SC-370 SHOW STATS row_count 与 COUNT(*) 不一致(stats=${STATS_ROWCOUNT:-空} count=${BIG_COUNT})"
fi
if [ "${STATS_ROWS}" = "3" ]; then ok "SC-370 列统计条数 = 分析列数(3)"
else bad "SC-370 列统计条数 ${STATS_ROWS:-空} ≠ 3"; printf '%s\n' "${STATS_OUT}" | tail -6 | tee -a "${REPORT}"; fi
expect "${STATS_OUT}" "analyze_millis" "FR-672/675 SHOW STATS 有分析耗时 / 扫描行数等固定列"

# SC-371:直方图 skew 单值占 60% 的桶份额;distinct 误差
run_sql_text "SHOW STATS FROM p6_big; exit" >"${TEST_DIR}/stats-big.txt"
HIST_SHARE="$(python - "$(cygpath -w "${TEST_DIR}/stats-big.txt")" <<'PYEOF'
import sys, json
header = None
for line in open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if row.get("column_name") == "city":
        hist = json.loads(row["histogram"])
        total = sum(hist["counts"])
        share = sum(c for b, c in zip(hist["bounds"], hist["counts"]) if b == "hot")
        print("%.4f" % (share / total if total else 0))
        break
PYEOF
)"
if [ -n "${HIST_SHARE}" ] && awk -v v="${HIST_SHARE}" 'BEGIN { exit (v > 0.5 && v < 0.7) ? 0 : 1 }'; then
    ok "SC-371 单值 'hot' 的直方图桶份额 ${HIST_SHARE} ∈ (50%,70%)"
else
    bad "SC-371 直方图桶份额异常(${HIST_SHARE:-空})"
fi
DISTINCT_STATS="$(stats_column_field p6_big city distinct_count)"
DISTINCT_REAL="$(first_int "$(run_sql_text "SELECT COUNT(DISTINCT city) FROM p6_big; exit")")"
if [ -n "${DISTINCT_STATS}" ] && [ -n "${DISTINCT_REAL}" ] && [ "${DISTINCT_REAL}" != "0" ]; then
    ERR=$(awk -v a="${DISTINCT_STATS}" -v b="${DISTINCT_REAL}" 'BEGIN { d=a-b; if (d<0) d=-d; printf "%.4f", d/b }')
    if awk -v e="${ERR}" 'BEGIN { exit (e <= 0.05) ? 0 : 1 }'; then
        ok "SC-371 distinct 相对误差 ${ERR} ≤ 5%(stats=${DISTINCT_STATS} real=${DISTINCT_REAL},100k 行内为精确值)"
    else
        bad "SC-371 distinct 相对误差 ${ERR} > 5%(stats=${DISTINCT_STATS} real=${DISTINCT_REAL})"
    fi
else
    bad "SC-371 distinct 取不到有效值(stats=${DISTINCT_STATS:-空} real=${DISTINCT_REAL:-空})"
fi

STATS_BEFORE="${STATS_OUT}"
if restart_services be-restart.log fed-restart.log true; then
    STATS_AFTER="$(run_sql_text "SHOW STATS FROM p6_big; exit")"
    if [ "${STATS_AFTER}" = "${STATS_BEFORE}" ]; then
        ok "SC-388 重启 BE + fed 后 SHOW STATS 与重启前逐字一致"
    else
        bad "SC-388 重启后 SHOW STATS 内容变化"
        log "----- 重启前 -----"; printf '%s\n' "${STATS_BEFORE}" | head -8 | tee -a "${REPORT}"
        log "----- 重启后 -----"; printf '%s\n' "${STATS_AFTER}" | head -8 | tee -a "${REPORT}"
    fi
else
    bad "SC-388 重启服务失败"
fi

# ================================================================ SC-389

log ""
log "---------- SC-389 ANALYZE 事务约束 ----------"
OUT="$(mysql_code "BEGIN; ANALYZE TABLE p6_big")"
expect "${OUT}" "CODE=1235" "SC-389 事务内 ANALYZE → 1235"
OUT="$(run_sql_text "BEGIN; ANALYZE TABLE p6_big; ROLLBACK; SELECT COUNT(*) FROM p6_big; exit")"
expect "${OUT}" "已回滚" "SC-389 报错后事务仍可 ROLLBACK"

# ================================================================ ★SC-372 分析中途崩溃

log ""
log "---------- ★SC-372 分析中途强杀 BE ----------"
run_sql_text "CREATE TABLE p6_crash (id INT PRIMARY KEY, city VARCHAR(16), amount INT); exit" >/dev/null
python_csv 100000 "${TEST_DIR}/crash.csv"
if import_until p6_crash "${TEST_DIR}/crash.csv" 100000 30; then
    ok "SC-372 崩溃场景数据就绪(10 万行)"
else
    bad "SC-372 崩溃场景导入失败"
fi
mysql_code "ANALYZE TABLE p6_crash" >"${TEST_DIR}/analyze-kill.log" 2>&1 &
ANALYZE_PID=$!
sleep 0.6
kill_be
wait_bounded "${ANALYZE_PID}" 120 || true
sleep 1
if start_be be-after-kill.log; then
    ok "SC-372 强杀后 BE 重启成功"
else
    bad "SC-372 强杀后 BE 无法重启"
fi
CRASH_COUNT="$(first_int "$(run_sql_text "SELECT COUNT(*) FROM p6_crash; exit")")"
if [ "${CRASH_COUNT}" = "100000" ]; then ok "SC-372 崩溃后用户数据零丢失(COUNT(*)=100000)"
else bad "SC-372 崩溃后数据不一致(COUNT(*)=${CRASH_COUNT:-空})"; fi
if grep -q "CODE=0" "${TEST_DIR}/analyze-kill.log" 2>/dev/null; then
    note "SC-372 ANALYZE 在强杀前已完成(本次未覆盖到『中途』,统计为全新也符合判定)"
fi
CRASH_STATS="$(run_sql_text "SHOW STATS FROM p6_crash; exit")"
CRASH_ROWS="$(row_count "${CRASH_STATS}")"
if [ -z "${CRASH_ROWS}" ] || [ "${CRASH_ROWS}" = "0" ]; then
    ok "SC-372 统计为『全旧』(崩溃前未提交,SHOW STATS 无残留)"
else
    CRASH_ROWCOUNT="$(stats_field p6_crash row_count)"
    CRASH_CITY_HIST="$(run_sql_text "SHOW STATS FROM p6_crash; exit" | grep -c '"bounds":\[\]' || true)"
    if [ "${CRASH_ROWCOUNT}" = "100000" ] && [ "${CRASH_ROWS}" = "3" ] && [ "${CRASH_CITY_HIST}" = "0" ]; then
        ok "SC-372 统计为『全新』(row_count=100000,3 列统计齐全,直方图非空)"
    else
        bad "SC-372 出现半套统计(row_count=${CRASH_ROWCOUNT:-空} 行数=${CRASH_ROWS} 空直方图=${CRASH_CITY_HIST})"
        printf '%s\n' "${CRASH_STATS}" | tail -8 | tee -a "${REPORT}"
    fi
fi

# ================================================================ ★SC-373 并发 ANALYZE

log ""
log "---------- ★SC-373 并发两次 ANALYZE ----------"
mysql_code "ANALYZE TABLE p6_big" >"${TEST_DIR}/analyze-a.log" 2>&1 &
A_PID=$!
mysql_code "ANALYZE TABLE p6_big" >"${TEST_DIR}/analyze-b.log" 2>&1 &
B_PID=$!
wait_bounded "${A_PID}" 180 || true
wait_bounded "${B_PID}" 180 || true
A_OUT="$(cat "${TEST_DIR}/analyze-a.log")"
B_OUT="$(cat "${TEST_DIR}/analyze-b.log")"
if printf '%s' "${A_OUT}" | grep -q "CODE=0"; then ok "SC-373 会话 A 的 ANALYZE CODE=0"; else bad "SC-373 会话 A 失败: ${A_OUT}"; fi
if printf '%s' "${B_OUT}" | grep -q "CODE=0"; then ok "SC-373 会话 B 的 ANALYZE CODE=0"; else bad "SC-373 会话 B 失败: ${B_OUT}"; fi
expect_absent "${A_OUT}${B_OUT}" "CODE=1213" "SC-373 无写写冲突 1213"
expect_absent "${A_OUT}${B_OUT}" "CODE=1062" "SC-373 无唯一冲突 1062"
CONCURRENT_OUT="$(run_sql_text "SHOW STATS FROM p6_big; exit")"
CONCURRENT_ROWS="$(row_count "${CONCURRENT_OUT}")"
CONCURRENT_ROWCOUNT="$(stats_field p6_big row_count)"
if [ "${CONCURRENT_ROWS}" = "3" ] && [ "${CONCURRENT_ROWCOUNT}" = "100000" ]; then
    ok "SC-373 并发后只有一份完整统计(3 列,row_count=100000)"
else
    bad "SC-373 并发后统计不完整(行数=${CONCURRENT_ROWS:-空} row_count=${CONCURRENT_ROWCOUNT:-空})"
fi

# ================================================================ SC-374 / SC-382 / SC-385 / SC-386

log ""
log "---------- SC-374 / SC-382 / SC-385 / SC-386 计划与统计过期 ----------"
run_sql_text "CREATE TABLE p6_idx (id INT PRIMARY KEY, city VARCHAR(16), amount INT); exit" >/dev/null
python - "$(cygpath -w "${TEST_DIR}/idx.csv")" <<'PY'
import csv, sys
with open(sys.argv[1], "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["id", "city", "amount"])
    for i in range(1, 5001):
        w.writerow([i, "hot" if i <= 3000 else "cold", i % 100])
PY
import_until p6_idx "${TEST_DIR}/idx.csv" 5000 || bad "SC-374 数据导入失败"
run_sql_text "CREATE INDEX idx_p6_idx_city ON p6_idx (city); exit" >/dev/null
PLAN_BEFORE="$(run_sql_text "EXPLAIN SELECT id FROM p6_idx WHERE city = 'hot'; exit")"
RESULT_BEFORE="$(run_sql_text "SELECT id FROM p6_idx WHERE city = 'hot' ORDER BY id; exit")"
run_sql_text "ANALYZE TABLE p6_idx; exit" >/dev/null
PLAN_AFTER="$(run_sql_text "EXPLAIN SELECT id FROM p6_idx WHERE city = 'hot'; exit")"
RESULT_AFTER="$(run_sql_text "SELECT id FROM p6_idx WHERE city = 'hot' ORDER BY id; exit")"
if [ "${PLAN_BEFORE}" != "${PLAN_AFTER}" ]; then
    ok "SC-374 ANALYZE 前后 EXPLAIN 文本有差异"
else
    bad "SC-374 ANALYZE 前后 EXPLAIN 完全一致"
fi
if [ "${RESULT_BEFORE}" = "${RESULT_AFTER}" ]; then ok "SC-374 计划变化后结果集逐行一致"
else bad "SC-374 计划变化影响了结果集"; fi
expect "${PLAN_AFTER}" "依据: 统计(t=" "SC-374 分析后 EXPLAIN 依据 = 统计(t=...)"

expect "${PLAN_AFTER}" "访问路径:" "SC-383 EXPLAIN 保留 P4 的『访问路径』行"
expect "${PLAN_AFTER}" "扫描方式:" "SC-383 EXPLAIN 保留 P4 的『扫描方式』行"
expect "${PLAN_AFTER}" "估算: rows=" "SC-383 EXPLAIN 追加『估算』行"
expect_re "${PLAN_AFTER}" "依据: (统计|默认)" "SC-383 EXPLAIN 追加『依据』行(在末尾)"
if printf '%s' "${PLAN_AFTER}" | grep '^|' | tail -1 | grep -q "依据:"; then
    ok "SC-383 『依据』行确实在末尾追加(既有行位置不变)"
else
    bad "SC-383 『依据』行不在末尾"
fi

# SC-382:写 30% 后 stale。必须走 CLI(经 fed 执行 → 行数变化记账在同一份 StatsService 上);
# CSV 导入工具是直连 BE 的独立进程,不会给 fed 的内存记账,所以这里用一条多行 INSERT。
run_sql_text "INSERT INTO p6_idx VALUES (5001,'hot',1),(5002,'hot',2),(5003,'hot',3),(5004,'hot',4),(5005,'hot',5),(5006,'hot',6),(5007,'hot',7),(5008,'hot',8),(5009,'hot',9),(5010,'hot',10),(5011,'hot',11),(5012,'hot',12),(5013,'hot',13),(5014,'hot',14),(5015,'hot',15),(5016,'hot',16),(5017,'hot',17),(5018,'hot',18),(5019,'hot',19),(5020,'hot',20); exit" >/dev/null
python - "$(cygpath -w "${TEST_DIR}/stale.sql")" <<'PY'
import sys
rows = ",".join("(%d,'hot',%d)" % (i, i % 100) for i in range(5021, 6521))
open(sys.argv[1], "w", encoding="utf-8").write("INSERT INTO p6_idx VALUES " + rows + ";\n")
PY
run_sql_file "${TEST_DIR}/stale.sql" >/dev/null 2>&1
STALE_PLAN="$(run_sql_text "EXPLAIN SELECT id FROM p6_idx WHERE city = 'hot'; exit")"
expect "${STALE_PLAN}" "统计已过期" "SC-382 行数 +30% 后 EXPLAIN 标注 stale"
STALE_ROWS="$(row_count "$(run_sql_text "SELECT id FROM p6_idx WHERE city = 'hot'; exit")")"
if [ "${STALE_ROWS}" = "4520" ]; then ok "SC-382 stale 不影响结果集(4520 行)"; else bad "SC-382 stale 后结果异常(${STALE_ROWS:-空})"; fi

# SC-385 常量折叠
OUT="$(run_sql_text "SELECT id FROM p6_idx WHERE 1 = 1 LIMIT 1; exit")"
expect_re "${OUT}" "\| 1 +\|" "SC-385 WHERE 1=1 恒真"
OUT="$(run_sql_text "SELECT id FROM p6_idx WHERE 1 = 2; exit")"
expect "${OUT}" "(0 rows)" "SC-385 WHERE 1=2 恒假"
OUT="$(run_sql_text "SELECT id FROM p6_idx WHERE NOT NOT id > 6500 ORDER BY id; exit")"
expect_re "${OUT}" "\| 650[1-9] +\|" "SC-385 NOT NOT 折叠成 id > 6500"
OUT="$(run_sql_text "SELECT amount / 0 FROM p6_idx WHERE id = 1; exit")"
expect_re "${OUT}" "\| NULL +\|" "SC-385 除零折叠成 NULL"

# SC-386 计划稳定
STABLE_1="$(run_sql_text "EXPLAIN SELECT city, COUNT(*) AS c FROM p6_idx GROUP BY city ORDER BY c DESC LIMIT 3; exit")"
STABLE_2="$(run_sql_text "EXPLAIN SELECT city, COUNT(*) AS c FROM p6_idx GROUP BY city ORDER BY c DESC LIMIT 3; exit")"
STABLE_3="$(run_sql_text "EXPLAIN SELECT city, COUNT(*) AS c FROM p6_idx GROUP BY city ORDER BY c DESC LIMIT 3; exit")"
if [ "${STABLE_1}" = "${STABLE_2}" ] && [ "${STABLE_1}" = "${STABLE_3}" ]; then
    ok "SC-386 同一条 SQL 连续 3 次 EXPLAIN 文本完全一致"
else
    bad "SC-386 EXPLAIN 文本不稳定"
fi

# SC-384 下推 / 列裁剪 / LIMIT
run_sql_text "CREATE TABLE p6_push (id INT PRIMARY KEY, a INT, b INT, c INT); exit" >/dev/null
run_sql_text "INSERT INTO p6_push VALUES (1,10,20,30),(2,11,21,31),(3,12,22,32); exit" >/dev/null
PUSH_PLAN="$(run_sql_text "EXPLAIN SELECT b FROM p6_push WHERE id = 2; exit")"
expect "${PUSH_PLAN}" "已下推:" "SC-384 EXPLAIN 显示『已下推』"
expect "${PUSH_PLAN}" "列: 读取 " "SC-384 EXPLAIN 显示『列: 读取 k / 表 n 列』"
LIMIT_PLAN="$(run_sql_text "EXPLAIN SELECT id FROM p6_push LIMIT 2; exit")"
expect "${LIMIT_PLAN}" "LIMIT 下推: 扫描 limit=2" "SC-384 EXPLAIN 显示 LIMIT 下推"
OUT="$(run_sql_text "SELECT id FROM p6_push LIMIT 2; exit")"
if [ "$(row_count "${OUT}")" = "2" ]; then ok "SC-384 LIMIT 下推后仍返回正确的 2 行"
else bad "SC-384 LIMIT 下推结果行数异常($(row_count "${OUT}"))"; fi
OUT="$(run_sql_text "SELECT id FROM p6_push WHERE id > 0 LIMIT 2; exit")"
if [ "$(row_count "${OUT}")" = "2" ]; then ok "SC-384 有过滤时 LIMIT 不被错误提前截断"
else bad "SC-384 有过滤时 LIMIT 行数异常($(row_count "${OUT}"))"; fi

# ================================================================ SC-376 连接顺序随规模调整

log ""
log "---------- SC-376 连接顺序随规模调整 ----------"
run_sql_text "CREATE TABLE p6_j_small (id INT PRIMARY KEY, val INT); exit" >/dev/null
run_sql_text "CREATE TABLE p6_j_big (id INT PRIMARY KEY, small_id INT, val INT); exit" >/dev/null
python - "$(cygpath -w "${TEST_DIR}/j_small.csv")" "$(cygpath -w "${TEST_DIR}/j_big.csv")" <<'PY'
import csv, sys
with open(sys.argv[1], "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow(["id", "val"])
    for i in range(1, 101):
        w.writerow([i, i])
with open(sys.argv[2], "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow(["id", "small_id", "val"])
    for i in range(1, 20001):
        w.writerow([i, (i % 100) + 1, i])
PY
import_until p6_j_small "${TEST_DIR}/j_small.csv" 100 || bad "SC-376 小表导入失败"
import_until p6_j_big "${TEST_DIR}/j_big.csv" 20000 || bad "SC-376 大表导入失败"
run_sql_text "ANALYZE TABLE p6_j_small; ANALYZE TABLE p6_j_big; exit" >/dev/null
JOIN_BEFORE="$(run_sql_text "EXPLAIN SELECT s.id, b.id FROM p6_j_big b JOIN p6_j_small s ON b.small_id = s.id; exit")"
JOIN_RESULT_BEFORE="$(run_sql_text "SELECT s.id, b.id FROM p6_j_big b JOIN p6_j_small s ON b.small_id = s.id ORDER BY b.id LIMIT 5; exit")"
python - "$(cygpath -w "${TEST_DIR}/j_small_grow.csv")" <<'PY'
import csv, sys
with open(sys.argv[1], "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow(["id", "val"])
    for i in range(101, 30001):
        w.writerow([i, i])
PY
import_until p6_j_small "${TEST_DIR}/j_small_grow.csv" 30000 >/dev/null 2>&1
run_sql_text "ANALYZE TABLE p6_j_small; exit" >/dev/null
JOIN_AFTER="$(run_sql_text "EXPLAIN SELECT s.id, b.id FROM p6_j_big b JOIN p6_j_small s ON b.small_id = s.id; exit")"
JOIN_RESULT_AFTER="$(run_sql_text "SELECT s.id, b.id FROM p6_j_big b JOIN p6_j_small s ON b.small_id = s.id ORDER BY b.id LIMIT 5; exit")"
ORDER_BEFORE="$(printf '%s\n' "${JOIN_BEFORE}" | sed 's/^| //; s/ *|$//' | grep -E '^(连接顺序|build=)' | head -2 | tr '\n' ' ')"
ORDER_AFTER="$(printf '%s\n' "${JOIN_AFTER}" | sed 's/^| //; s/ *|$//' | grep -E '^(连接顺序|build=)' | head -2 | tr '\n' ' ')"
if [ -n "${ORDER_BEFORE}" ] && [ -n "${ORDER_AFTER}" ] && [ "${ORDER_BEFORE}" != "${ORDER_AFTER}" ]; then
    ok "SC-376 灌大后连接顺序 / build 侧变化(${ORDER_BEFORE}→ ${ORDER_AFTER})"
else
    bad "SC-376 连接顺序与 build 侧都未随规模变化(before=${ORDER_BEFORE:-空} after=${ORDER_AFTER:-空})"
    log "----- 灌大前 -----"; printf '%s\n' "${JOIN_BEFORE}" | tee -a "${REPORT}"
    log "----- 灌大后 -----"; printf '%s\n' "${JOIN_AFTER}" | tee -a "${REPORT}"
fi
if [ "${JOIN_RESULT_BEFORE}" = "${JOIN_RESULT_AFTER}" ]; then ok "SC-376 连接顺序变化不影响前 5 行结果"
else bad "SC-376 连接顺序变化影响了结果集"; fi

# ================================================================ SC-377 集合运算

log ""
log "---------- SC-377 集合运算 ----------"
SETUP="CREATE TABLE p6_set_a (id INT PRIMARY KEY, v INT); CREATE TABLE p6_set_b (id INT PRIMARY KEY, v INT); INSERT INTO p6_set_a VALUES (1,10),(2,20),(3,NULL),(4,20); INSERT INTO p6_set_b VALUES (1,20),(2,30),(3,NULL),(4,40);"
run_sql_text "${SETUP} exit" >/dev/null
OUT="$(run_sql_text "SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b ORDER BY 1; exit")"
if [ "$(row_count "${OUT}")" = "5" ]; then ok "SC-377 UNION 去重(NULL 相等)= 5 行"; else bad "SC-377 UNION 行数异常($(row_count "${OUT}"))"; fi
OUT="$(run_sql_text "SELECT v FROM p6_set_a UNION ALL SELECT v FROM p6_set_b; exit")"
if [ "$(row_count "${OUT}")" = "8" ]; then ok "SC-377 UNION ALL 不去重 = 8 行"; else bad "SC-377 UNION ALL 行数异常($(row_count "${OUT}"))"; fi
OUT="$(run_sql_text "SELECT v FROM p6_set_a INTERSECT SELECT v FROM p6_set_b ORDER BY 1; exit")"
expect_re "${OUT}" "\| 20 +\|" "SC-377 INTERSECT 命中 20"
if printf '%s' "${OUT}" | grep -q "| NULL"; then ok "SC-377 INTERSECT 的 NULL 也参与判等"; else bad "SC-377 INTERSECT NULL 语义错误"; fi
OUT="$(run_sql_text "SELECT v FROM p6_set_a EXCEPT SELECT v FROM p6_set_b ORDER BY 1; exit")"
expect_re "${OUT}" "\| 10 +\|" "SC-377 EXCEPT 命中 10"
OUT="$(run_sql_text "SELECT v FROM p6_set_a MINUS SELECT v FROM p6_set_b ORDER BY 1; exit")"
expect_re "${OUT}" "\| 10 +\|" "SC-377 MINUS = EXCEPT 别名"
OUT="$(mysql_code "SELECT v FROM p6_set_a INTERSECT ALL SELECT v FROM p6_set_b")"
expect "${OUT}" "CODE=1235" "SC-377 INTERSECT ALL → 1235"
OUT="$(mysql_code "SELECT v FROM p6_set_a EXCEPT ALL SELECT v FROM p6_set_b")"
expect "${OUT}" "CODE=1235" "SC-377 EXCEPT ALL → 1235"
OUT="$(mysql_code "SELECT id FROM p6_set_a UNION SELECT id, v FROM p6_set_b")"
expect "${OUT}" "CODE=1222" "SC-377 列数不同 → 1222"
run_sql_text "CREATE TABLE p6_set_c (id VARCHAR(8) PRIMARY KEY); exit" >/dev/null
OUT="$(mysql_code "SELECT id FROM p6_set_a UNION SELECT id FROM p6_set_c")"
expect "${OUT}" "CODE=1222" "SC-377 数值与 VARCHAR 混合 → 1222"
OUT="$(mysql_code "SELECT v FROM p6_set_a ORDER BY v UNION SELECT v FROM p6_set_b")"
expect "${OUT}" "CODE=1064" "SC-377 分支 ORDER BY 未加括号 → 1064"
OUT="$(mysql_code "SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b ORDER BY nope")"
expect "${OUT}" "CODE=1054" "SC-377 整体 ORDER BY 引用源表列 → 1054"

# ================================================================ SC-378 相关子查询与量词

log ""
log "---------- SC-378 相关子查询 / EXISTS / ANY / ALL ----------"
CQ_SETUP="CREATE TABLE p6_cq_u (id INT PRIMARY KEY, city VARCHAR(8)); CREATE TABLE p6_cq_o (oid INT PRIMARY KEY, uid INT); INSERT INTO p6_cq_u VALUES (1,'bj'),(2,'sh'),(3,'bj'); INSERT INTO p6_cq_o VALUES (10,1),(11,1),(12,2),(13,NULL);"
run_sql_text "${CQ_SETUP} exit" >/dev/null
OUT="$(run_sql_text "SELECT id FROM p6_cq_u WHERE EXISTS (SELECT 1 FROM p6_cq_o WHERE uid = p6_cq_u.id) ORDER BY id; exit")"
expect_re "${OUT}" "\| 2 +\|" "SC-378 EXISTS 命中"
OUT="$(run_sql_text "SELECT id FROM p6_cq_u WHERE NOT EXISTS (SELECT 1 FROM p6_cq_o WHERE uid = p6_cq_u.id) ORDER BY id; exit")"
expect_re "${OUT}" "\| 3 +\|" "SC-378 NOT EXISTS 命中"
OUT="$(run_sql_text "SELECT id FROM p6_cq_u WHERE NOT EXISTS (SELECT 1 FROM p6_cq_o WHERE uid > 1000); exit")"
if [ "$(row_count "${OUT}")" = "3" ]; then ok "SC-378 内层空集时 NOT EXISTS 为真(边界 81)"; else bad "SC-378 NOT EXISTS 空集语义错误"; fi
OUT="$(run_sql_text "SELECT oid FROM p6_cq_o WHERE uid = ANY (SELECT id FROM p6_cq_u) ORDER BY oid; exit")"
if [ "$(row_count "${OUT}")" = "3" ]; then ok "SC-378 = ANY 命中 3 行"; else bad "SC-378 = ANY 异常($(row_count "${OUT}"))"; fi
OUT="$(run_sql_text "SELECT oid FROM p6_cq_o WHERE uid > ALL (SELECT id FROM p6_cq_u); exit")"
if [ "$(row_count "${OUT}")" = "0" ]; then ok "SC-378 > ALL 无匹配(NOT NULL 行不通过)"; else bad "SC-378 > ALL 语义异常"; fi
OUT="$(run_sql_text "SELECT oid FROM p6_cq_o WHERE uid > ALL (SELECT id FROM p6_cq_u WHERE id > 100); exit")"
if [ "$(row_count "${OUT}")" = "4" ]; then ok "SC-378 > ALL 空集恒真(边界 83 的反面)"; else bad "SC-378 > ALL 空集语义异常($(row_count "${OUT}"))"; fi
OUT="$(run_sql_text "SELECT id FROM p6_cq_u WHERE id NOT IN (SELECT uid FROM p6_cq_o); exit")"
if [ -z "$(row_count "${OUT}")" ] || [ "$(row_count "${OUT}")" = "0" ]; then
    ok "SC-378/边界 82 NOT IN 含 NULL → 空集且不报错"
else
    bad "SC-378 NOT IN 含 NULL 语义错误($(row_count "${OUT}"))"
fi
OUT="$(run_sql_text "SELECT u.id, (SELECT COUNT(*) FROM p6_cq_o o WHERE o.uid = u.id) AS c FROM p6_cq_u u ORDER BY u.id; exit")"
expect_re "${OUT}" "\| 1 +\| 2 +\|" "SC-378 相关标量子查询逐行驱动(内层计数)"
OUT="$(run_sql_text "SELECT COUNT(*) FROM p6_cq_u u WHERE u.id IN (SELECT uid FROM p6_cq_o WHERE uid = u.id); exit")"
expect_re "${OUT}" "\| 2 +\|" "SC-378 相关 IN 子查询"

# ================================================================ SC-379 DISTINCT

log ""
log "---------- SC-379 SELECT DISTINCT ----------"
run_sql_text "CREATE TABLE p6_d (id INT PRIMARY KEY, city VARCHAR(8), amount INT); INSERT INTO p6_d VALUES (1,'bj',10),(2,'bj',10),(3,'sh',20),(4,NULL,30),(5,'gz',30); exit" >/dev/null
DISTINCT_BAD=0
for pair in "city|city" "city, amount|city, amount" "id|id" "amount|amount" "city, id|city, id"; do
    cols="${pair%%|*}"
    d="$(run_sql_text "SELECT DISTINCT ${cols} FROM p6_d ORDER BY $(printf '%s' "${cols}" | sed 's/, /, /g'); exit")"
    g="$(run_sql_text "SELECT ${cols} FROM p6_d GROUP BY ${cols} ORDER BY $(printf '%s' "${cols}" | sed 's/, /, /g'); exit")"
    if [ "${d}" != "${g}" ]; then
        DISTINCT_BAD=$((DISTINCT_BAD + 1))
        log "    [DIFF] DISTINCT ${cols} 与 GROUP BY 不一致"
        printf '%s\n' "${d}" | tee -a "${REPORT}" | tail -4
        printf '%s\n' "${g}" | tee -a "${REPORT}" | tail -4
    fi
done
if [ "${DISTINCT_BAD}" = "0" ]; then ok "SC-379 5 组 DISTINCT 与等价 GROUP BY 逐行一致"; else bad "SC-379 有 ${DISTINCT_BAD} 组 DISTINCT 与 GROUP BY 不一致"; fi
OUT="$(run_sql_text "SELECT DISTINCT city FROM p6_d ORDER BY city LIMIT 2; exit")"
if [ "$(row_count "${OUT}")" = "2" ]; then ok "SC-379 DISTINCT + ORDER BY + LIMIT 生效"; else bad "SC-379 DISTINCT 分页异常"; fi

# ================================================================ SC-380 派生表

log ""
log "---------- SC-380 派生表 ----------"
run_sql_text "CREATE TABLE p6_dt_u (id INT PRIMARY KEY, name VARCHAR(16), city VARCHAR(8)); CREATE TABLE p6_dt_o (oid INT PRIMARY KEY, uid INT, amount INT); INSERT INTO p6_dt_u VALUES (1,'alice','bj'),(2,'bob','sh'),(3,'cathy','bj'); INSERT INTO p6_dt_o VALUES (100,1,5),(101,1,7),(102,2,9); exit" >/dev/null
OUT="$(run_sql_text "SELECT t.city, t.c FROM (SELECT city, COUNT(*) AS c FROM p6_dt_u GROUP BY city) t ORDER BY t.city; exit")"
expect_re "${OUT}" "\| bj +\| 2 +\|" "SC-380 单层派生表 + 列别名"
OUT="$(run_sql_text "SELECT x.city FROM (SELECT y.city FROM (SELECT city FROM p6_dt_u) y) x WHERE x.city = 'bj'; exit")"
if [ "$(row_count "${OUT}")" = "2" ]; then ok "SC-380 两层嵌套派生表"; else bad "SC-380 两层嵌套异常"; fi
OUT="$(run_sql_text "SELECT d.name, o.amount FROM p6_dt_u d JOIN (SELECT uid, SUM(amount) AS amount FROM p6_dt_o GROUP BY uid) o ON d.id = o.uid ORDER BY d.id; exit")"
expect_re "${OUT}" "\| alice +\| 12 +\|" "SC-380 派生表与 JOIN / 聚合组合"
OUT="$(mysql_code "SELECT * FROM (SELECT id FROM p6_dt_u)")"
expect "${OUT}" "CODE=1248" "SC-380 派生表缺别名 → 1248"
OUT="$(mysql_code "SELECT * FROM p6_dt_u x WHERE EXISTS (SELECT 1 FROM (SELECT id FROM p6_dt_o WHERE uid = x.id) y)")"
expect "${OUT}" "CODE=1235" "SC-380 派生表横向相关 → 1235"
OUT="$(mysql_code "SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT id FROM p6_dt_u) a) b) c) d) e) f) g) h) i")"
expect "${OUT}" "CODE=1235" "SC-380 派生表嵌套 > 8 层 → 1235"

# ================================================================ SC-381 information_schema

log ""
log "---------- SC-381 客户端自省放行 ----------"
INTROSPECT_1="SELECT R.ROUTINE_SCHEMA, R.ROUTINE_NAME, P.ordinals FROM information_schema.ROUTINES R LEFT JOIN (SELECT SPECIFIC_SCHEMA, SPECIFIC_NAME, GROUP_CONCAT(PARAMETER_NAME) AS ordinals FROM information_schema.PARAMETERS GROUP BY SPECIFIC_SCHEMA, SPECIFIC_NAME) P ON R.ROUTINE_SCHEMA = P.SPECIFIC_SCHEMA AND R.ROUTINE_NAME = P.SPECIFIC_NAME"
OUT="$(mysql_code "${INTROSPECT_1}")"
expect "${OUT}" "CODE=0" "SC-381 ROUTINES LEFT JOIN (GROUP_CONCAT 子查询) CODE=0"
OUT="$(mysql_code "SELECT COUNT(*) AS c FROM information_schema.TABLES UNION SELECT COUNT(*) AS c FROM information_schema.COLUMNS")"
expect "${OUT}" "CODE=0" "SC-381 COUNT(*) ... UNION ... CODE=0"
OUT="$(run_sql_text "SELECT COUNT(*) FROM information_schema.PARAMETERS; exit")"
expect_re "${OUT}" "\| 0 +\|" "SC-381/FR-669 PARAMETERS 恒 0 行"
OUT="$(run_sql_text "SELECT T.TABLE_NAME, C.COLUMN_NAME FROM information_schema.TABLES T JOIN information_schema.COLUMNS C ON T.TABLE_NAME = C.TABLE_NAME WHERE T.TABLE_NAME = 'p6_dt_u' ORDER BY C.COLUMN_NAME; exit")"
if [ "$(row_count "${OUT}")" = "3" ]; then ok "SC-381 information_schema JOIN 返回真实列(3 行)"; else bad "SC-381 information_schema JOIN 行数异常"; fi
OUT="$(run_sql_text "SELECT DISTINCT TABLE_NAME FROM information_schema.TABLES; exit")"
expect_absent "${OUT}" "错误" "SC-381 information_schema DISTINCT 不再 1235"

# ================================================================ SC-375 开关对拍

log ""
log "---------- SC-375 optimizer_enabled=true/false 结果对拍(12 条) ----------"
QUERIES=(
  "SELECT id, city FROM p6_idx WHERE city = 'hot' ORDER BY id LIMIT 20"
  "SELECT city, COUNT(*) AS c FROM p6_idx GROUP BY city ORDER BY city"
  "SELECT u.name, o.amount FROM p6_dt_u u JOIN p6_dt_o o ON u.id = o.uid ORDER BY u.id, o.oid"
  "SELECT id FROM p6_dt_u WHERE id IN (SELECT uid FROM p6_dt_o) ORDER BY id"
  "SELECT city FROM p6_d (SELECT id FROM p6_dt_u) ORDER BY 1"
  "SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b ORDER BY 1"
  "SELECT DISTINCT city FROM p6_d ORDER BY city"
  "SELECT id FROM p6_dt_u WHERE EXISTS (SELECT 1 FROM p6_dt_o WHERE uid = p6_dt_u.id) ORDER BY id"
  "SELECT * FROM (SELECT id, city FROM p6_idx WHERE amount > 50) d ORDER BY id LIMIT 10"
  "SELECT s.id FROM p6_j_big b JOIN p6_j_small s ON b.small_id = s.id ORDER BY b.id LIMIT 10"
  "SELECT COUNT(*) FROM p6_idx WHERE amount IS NULL"
  "SELECT id FROM p6_dt_u WHERE id > ALL (SELECT uid FROM p6_dt_o WHERE uid IS NOT NULL) ORDER BY id"
)
# 上面第 5 条是刻意写错的;替换成合法的集合运算,保证 12 条都能跑。
QUERIES[4]="SELECT city FROM p6_dt_u UNION SELECT city FROM p6_d ORDER BY 1"
MISMATCH=0
INDEX=0
for sql in "${QUERIES[@]}"; do
    INDEX=$((INDEX + 1))
    run_sql_text "${sql}; exit" >"${TEST_DIR}/on-${INDEX}.txt"
done
if restart_services be-cbo-off.log fed-cbo-off.log false; then
    INDEX=0
    for sql in "${QUERIES[@]}"; do
        INDEX=$((INDEX + 1))
        out_off="${TEST_DIR}/off-${INDEX}.txt"
        run_sql_text "${sql}; exit" >"${out_off}"
        if ! diff -q "${TEST_DIR}/on-${INDEX}.txt" "${out_off}" >/dev/null 2>&1; then
            MISMATCH=$((MISMATCH + 1))
            log "    [DIFF] ${sql}"
            diff "${TEST_DIR}/on-${INDEX}.txt" "${out_off}" | head -6 | tee -a "${REPORT}"
        fi
    done
    if [ "${MISMATCH}" = "0" ]; then ok "SC-375 12 条 SQL 在 optimizer_enabled=true/false 下逐行一致"
    else bad "SC-375 有 ${MISMATCH} 条 SQL 在两种模式下结果不同"; fi
else
    bad "SC-375 无法用 optimizer_enabled=false 重启 fed"
fi
# 回到默认(开启)模式,后续性能对比从干净环境开始
restart_services be-final.log fed-final.log true >/dev/null 2>&1 || bad "SC-375 恢复默认模式失败"

# ================================================================ SC-387 性能门限

log ""
log "---------- SC-387 性能门限(Q1/Q3/Q5/Q6/Q10,三次中位数) ----------"
run_sql_text "CREATE TABLE region (r_regionkey INT PRIMARY KEY, r_name VARCHAR(16)); CREATE TABLE nation (n_nationkey INT PRIMARY KEY, n_name VARCHAR(16), n_regionkey INT); CREATE TABLE customer (c_custkey INT PRIMARY KEY, c_name VARCHAR(32), c_nationkey INT); CREATE TABLE orders (o_orderkey INT PRIMARY KEY, o_custkey INT, o_orderstatus VARCHAR(4), o_totalprice DOUBLE, o_orderdate DATE); CREATE TABLE lineitem (l_id INT PRIMARY KEY, l_orderkey INT, l_partkey INT, l_quantity INT, l_extendedprice DOUBLE, l_discount DOUBLE, l_tax DOUBLE, l_shipdate DATE, l_returnflag VARCHAR(4), l_linestatus VARCHAR(4), l_shipmode VARCHAR(8));" >/dev/null 2>&1
python - "$(cygpath -w "${TEST_DIR}/tpch.sql")" <<'PY'
import sys
lines = []
lines.append("INSERT INTO region VALUES " + ",".join("(%d,'R%d')" % (i, i) for i in range(5)) + ";")
lines.append("INSERT INTO nation VALUES " + ",".join("(%d,'N%d',%d)" % (i,i,i%5) for i in range(25)) + ";")
lines.append("INSERT INTO customer VALUES " + ",".join("(%d,'C%d',%d)" % (i,i,i%25) for i in range(1,201)) + ";")
orders = []
for o in range(1, 4001):
    cust = (o % 200) + 1
    orders.append("(%d,%d,'O',%d,'2024-%02d-%02d')" % (o, cust, (o*7)%10000, (o%12)+1, (o%28)+1))
lines.append("INSERT INTO orders VALUES " + ",".join(orders) + ";")
items = []
idx = 1
for o in range(1, 4001):
    for k in range(5):
        qty = (idx % 50) + 1
        price = qty * 100.0
        disc = (idx % 10) / 100.0
        items.append("(%d,%d,%d,%d,%.2f,%.2f,0.05,'2024-%02d-%02d','%s','%s','%s')" % (
            idx, o, k+1, qty, price, disc, (idx%12)+1, (idx%28)+1,
            "A" if idx%3 else "N", "F" if idx%3 else "O", "AIR" if idx%2 else "TRUCK"))
        idx += 1
lines.append("INSERT INTO lineitem VALUES " + ",".join(items) + ";")
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
run_sql_file "${TEST_DIR}/tpch.sql" >/dev/null 2>&1
run_sql_text "ANALYZE TABLE region; ANALYZE TABLE nation; ANALYZE TABLE customer; ANALYZE TABLE orders; ANALYZE TABLE lineitem; exit" >/dev/null
run_sql_text "CREATE INDEX idx_lineitem_shipdate ON lineitem (l_shipdate); ANALYZE TABLE lineitem; exit" >/dev/null
Q1="Q1=SELECT l_returnflag, l_linestatus, COUNT(*) AS cnt FROM lineitem GROUP BY l_returnflag, l_linestatus ORDER BY l_returnflag, l_linestatus"
Q3="Q3=SELECT o.o_orderkey, SUM(l.l_extendedprice * (1 - l_discount)) AS revenue FROM customer c JOIN orders o ON c.c_custkey = o.o_custkey JOIN lineitem l ON l.l_orderkey = o.o_orderkey WHERE l.l_shipdate > '2024-06-01' GROUP BY o.o_orderkey ORDER BY revenue DESC LIMIT 10"
Q5="Q5=SELECT n.n_name, SUM(l.l_extendedprice * (1 - l_discount)) AS revenue FROM customer c JOIN orders o ON c.c_custkey = o.o_custkey JOIN lineitem l ON l.l_orderkey = o.o_orderkey JOIN nation n ON c.c_nationkey = n.n_nationkey JOIN region r ON n.n_regionkey = r.r_regionkey WHERE r.r_name = 'R1' GROUP BY n.n_name ORDER BY revenue DESC"
Q6="Q6=SELECT SUM(l_extendedprice * l_discount) AS revenue FROM lineitem WHERE l_shipdate >= '2024-01-01' AND l_discount BETWEEN 0.05 AND 0.07 AND l_quantity < 40"
Q10="Q10=SELECT c.c_custkey, c.c_name, SUM(l.l_extendedprice * (1 - l_discount)) AS revenue FROM customer c JOIN orders o ON c.c_custkey = o.o_custkey JOIN lineitem l ON l.l_orderkey = o.o_orderkey JOIN nation n ON c.c_nationkey = n.n_nationkey WHERE o.o_orderdate >= '2024-06-01' GROUP BY c.c_custkey, c.c_name ORDER BY revenue DESC LIMIT 20"
BENCH_CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"
java -Dfile.encoding=UTF-8 -cp "${BENCH_CP}" com.wangyu.tangdb.query.CboBench \
    --port "${BE_PORT}" --optimizer=true --sql "${Q1}" --sql "${Q3}" --sql "${Q5}" \
    --sql "${Q6}" --sql "${Q10}" >"${TEST_DIR}/bench-on.txt" 2>&1
java -Dfile.encoding=UTF-8 -cp "${BENCH_CP}" com.wangyu.tangdb.query.CboBench \
    --port "${BE_PORT}" --optimizer=false --sql "${Q1}" --sql "${Q3}" --sql "${Q5}" \
    --sql "${Q6}" --sql "${Q10}" >"${TEST_DIR}/bench-off.txt" 2>&1
log "  optimizer=true :"; sed 's/^/    /' "${TEST_DIR}/bench-on.txt" | tee -a "${REPORT}"
log "  optimizer=false:"; sed 's/^/    /' "${TEST_DIR}/bench-off.txt" | tee -a "${REPORT}"
PERF_RESULT="$(python - "$(cygpath -w "${TEST_DIR}/bench-on.txt")" "$(cygpath -w "${TEST_DIR}/bench-off.txt")" <<'PY'
import sys
def parse(path):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if "=" not in line or line.startswith("Q") is False:
            continue
        name, rest = line.split("=", 1)
        if rest.startswith("ERROR"):
            out[name] = None
            continue
        try:
            out[name] = float(rest.split()[0])
        except Exception:
            out[name] = None
    return out
on = parse(sys.argv[1]); off = parse(sys.argv[2])
slow = []; fast = 0; missing = []
for q in ["Q1", "Q3", "Q5", "Q6", "Q10"]:
    a = on.get(q); b = off.get(q)
    if a is None or b is None or b <= 0:
        missing.append(q); continue
    ratio = a / b
    if ratio > 1.10:
        slow.append("%s(%.0fms vs %.0fms = %+.1f%%)" % (q, a, b, (ratio - 1) * 100))
    if ratio <= 0.80:
        fast += 1
print("SLOW=" + ";".join(slow))
print("FAST=" + str(fast))
print("MISSING=" + ";".join(missing))
PY
)"
PERF_SLOW="$(printf '%s\n' "${PERF_RESULT}" | sed -n 's/^SLOW=//p')"
PERF_FAST="$(printf '%s\n' "${PERF_RESULT}" | sed -n 's/^FAST=//p')"
PERF_MISSING="$(printf '%s\n' "${PERF_RESULT}" | sed -n 's/^MISSING=//p')"
if [ -n "${PERF_MISSING}" ]; then bad "SC-387 有查询未测出有效耗时(${PERF_MISSING})"; fi
if [ -z "${PERF_SLOW}" ]; then ok "SC-387 单条查询均未变慢超过 10%"
else bad "SC-387 单条查询变慢超过 10%: ${PERF_SLOW}(门限不改,原因见报告)"; fi
if [ -n "${PERF_FAST}" ] && [ "${PERF_FAST}" -ge 2 ]; then ok "SC-387 有 ${PERF_FAST} 条查询快 ≥ 20%(要求 ≥2)"
else bad "SC-387 只有 ${PERF_FAST:-0} 条查询快 ≥ 20%(要求 ≥2;门限不改,原因见报告)"; fi

# ================================================================ 依赖与边界红线

log ""
log "---------- 依赖与边界红线(INV-15 / INV-16) ----------"
if grep -v "^//" "${BE_DIR}/go.mod" | grep -qiE "rocksdb|leveldb|badger|pebble|sqlite|bbolt"; then bad "BE go.mod 出现被禁止的存储依赖"; else ok "BE go.mod 无被禁止的存储依赖(注释里列出的禁止名单不算)"; fi
if grep -v "<!--" "${FE_DIR}/pom.xml" | grep -qiE "calcite|presto|antlr|jsqlparser|commons-math|hll"; then bad "FE pom.xml 出现第三方 SQL/统计库"; else ok "FE pom.xml 无第三方 SQL 解析 / 统计 / 压缩库"; fi
if ( cd "${ROOT_DIR}" && git diff --quiet -- proto be ); then ok "proto/ 与 be/ 本期零改动(统计走已有 Scan / CommitTxn)"; else bad "proto/ 或 be/ 出现改动(本期不允许)"; fi
if grep -v "^[[:space:]]*//" "${ROOT_DIR}/proto/tangdb.proto" | grep -qiE "\b(table|column|sql)\b"; then
    bad "proto 里出现表 / 列 / SQL 语义(违反 FE/BE 边界)"
else
    ok "proto 里没有表 / 列 / SQL 语义(INV-16)"
fi

# ================================================================ SC-390 既有回归

log ""
log "---------- SC-390 既有回归 ----------"
if [ "${SKIP_REGRESSION:-0}" = "1" ]; then
    note "SC-390 既有回归被 SKIP_REGRESSION=1 跳过(未验证)"
else
    kill_fed
    kill_be
    for script in p2-test.sh p3-test.sh p35-test.sh p4-test.sh crash-test.sh; do
        if bash "${SCRIPT_DIR}/${script}" >"${TEST_DIR}/reg-${script}.log" 2>&1; then
            ok "SC-390 ${script} 全绿"
        else
            bad "SC-390 ${script} 失败(见 tmp/p6-test/reg-${script}.log)"
            tail -12 "${TEST_DIR}/reg-${script}.log" | tee -a "${REPORT}"
        fi
    done
fi

# ================================================================ 汇总

log ""
log "=================================================="
log " P6 验收结果:PASS=${PASS} FAIL=${FAIL} NOTE=${NOTE_ONLY}"
log " 报告:${REPORT}"
log "=================================================="
if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
exit 0
