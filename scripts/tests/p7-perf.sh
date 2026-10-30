#!/usr/bin/env bash
# tang_db P7.1 性能回归(SC-439 / SC-441 / SC-440 参照):固定 10 万行 × 128 维。
#
# 三段口径(SPEC-P7.1 K184 / FR-810):
#   导入:CSV/批量 SQL 墙钟;建图:CREATE VECTOR INDEX → 待重建归零;检索:同 JVM P50/P99。
# 另跑:--k 10 vs --k 100(SC-441 固定开销判据)+ 8 线程并发(SC-440 参照)。
#
# 用法:
#   bash scripts/tests/p7-perf.sh                 # 10 万 × 128,5 次 + 20 次 P50/P99
#   P7P_ROWS=20000 P7P_DIM=16 bash scripts/tests/p7-perf.sh
#   P7P_SECONDS=0 bash scripts/tests/p7-perf.sh   # 跳过并发段
# 端口:BE 9995 / MySQL 9998 / 心跳 9999;数据目录 tmp/p7-perf/data(每次重建)。
#
# 安静机要求(K184):跑之前确认没有别的脚本 / 编译在跑;脚本自己也会检查残留进程。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p7-perf"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/perf.txt"
SCALE_SQL="${TEST_DIR}/scale.sql"

BE_PORT="${P7P_BE_PORT:-9995}"
MYSQL_PORT="${P7P_MYSQL_PORT:-9998}"
HEART_PORT="${P7P_HEART_PORT:-9999}"
DB_USER=root
DB_PASS=p7perf
DB_NAME=default

ROWS="${P7P_ROWS:-100000}"
DIM="${P7P_DIM:-128}"
K="${P7P_K:-10}"
RUNS="${P7P_RUNS:-5}"
ITERS="${P7P_ITERS:-20}"
K2="${P7P_K2:-100}"
THREADS="${P7P_THREADS:-8}"
SECONDS_CONC="${P7P_SECONDS:-20}"
TABLE="${P7P_TABLE:-docs_scale}"
MEMTABLE="${P7P_MEMTABLE:-8MB}"

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

log() { echo "$*" | tee -a "${REPORT}"; }

# ---- 环境独占:同一时刻只跑一个脚本 ----
for name in be.exe; do
    if tasklist | grep -qi "^${name}"; then
        echo "检测到残留 ${name}:先停掉再跑 p7-perf(K184 安静机口径)" >&2
        exit 2
    fi
done

rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}" "${TEST_DIR}/fe-sort"
: >"${REPORT}"

TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"
BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"

be_running() {
    powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1
}
kill_be() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    for _ in $(seq 1 40); do be_running || break; sleep 0.25; done
    sleep 1
}
FED_PID=""
kill_fed() {
    if [ -n "${FED_PID}" ]; then taskkill /F /PID "${FED_PID}" >/dev/null 2>&1 || true; FED_PID=""; fi
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*tangdb.fed.Fed*' -and \$_.CommandLine -like '*${TEST_DIR_WIN}*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
    sleep 1
}
cleanup() { kill_fed; kill_be; }
trap cleanup EXIT

wait_port_free() {
    for _ in $(seq 1 "${2:-30}"); do
        netstat -ano | grep -q ":$1 .*LISTENING" || return 0
        sleep 1
    done
    return 0
}

write_confs() {
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = ${MEMTABLE}
fe_host = 127.0.0.1
fe_port = ${HEART_PORT}
heartbeat_interval_seconds = 3
max_txn_write_bytes = 64MB
test_crash_point =
compaction_l0_files = 100
compaction_level_ratio = 100
compaction_level_max_bytes = 1GB
compaction_bloom_enabled = true
sort_spill_bytes = 1MB
wal_buffer_bytes = 1MB
group_commit_max_batch = 64
group_commit_max_wait_ms = 5
EOF
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
optimizer_enabled = true
analyze_full_scan_max_rows = 200000
analyze_sample_stride = 20
analyze_histogram_buckets = 64
analyze_exact_distinct_max_rows = 100000
analyze_hll_precision = 14
stats_stale_row_change_percent = 10
query_parallelism = 8
pushdown_enabled = true
scan_batch_rows = 4096
vector_ef_search = 64
vector_ef_max = 512
vector_bruteforce_rows = 1
exact_fallback_rows = 1
allow_list_max_ratio = 0.3
parallel_min_rows = 50000
EOF
}

start_be() {
    wait_port_free "${BE_PORT}" 40
    "${BE_EXE}" --config="$(cygpath -w "${BE_CONF}")" >"${TEST_DIR}/be.log" 2>&1 &
    for _ in $(seq 1 120); do
        grep -q "BE 已就绪" "${TEST_DIR}/be.log" 2>/dev/null && return 0
        sleep 0.5
    done
    log "BE 启动失败:"; tail -20 "${TEST_DIR}/be.log" | tee -a "${REPORT}"; return 1
}

start_fed() {
    wait_port_free "${MYSQL_PORT}" 20
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.fed.Fed \
        --config="$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/fed.log" 2>&1 &
    FED_PID=$!
    for _ in $(seq 1 80); do
        grep -q "fed 已就绪" "${TEST_DIR}/fed.log" 2>/dev/null && return 0
        sleep 0.5
    done
    log "fed 启动失败:"; tail -20 "${TEST_DIR}/fed.log" | tee -a "${REPORT}"; return 1
}

run_sql_text() {
    printf '%s\n' "$1" | timeout 3600 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
        -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" 2>&1
}
run_sql_file() {
    timeout 3600 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
        -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" \
        <"$1" 2>&1
}

vec_field() { # <index> <field>
    local out="${TEST_DIR}/vecidx.txt"
    run_sql_text "SHOW VECTOR INDEXES; exit" >"${out}"
    python - "$(cygpath -w "${out}")" "$1" "$2" <<'PY'
import sys
path, name, field = sys.argv[1], sys.argv[2], sys.argv[3]
header = None
for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip("|").split("|")]
    if header is None:
        header = cells
        continue
    row = dict(zip(header, cells))
    if row.get("index_name") == name:
        print(row.get(field, ""))
        break
PY
}

wait_ready() {
    for _ in $(seq 1 600); do
        local p; p="$(vec_field vidx pending_segments)"
        [ -n "${p}" ] && [ "${p}" = "0" ] && return 0
        sleep 1
    done
    return 1
}

gen_scale_sql() { # <path>
    python - "$(cygpath -w "$1")" "${ROWS}" "${DIM}" "${TABLE}" <<'PY'
import random, sys
path, rows, dim, table = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
random.seed(20261029)
with open(path, "w", encoding="utf-8", newline="\n") as f:
    chunk = 1000
    for base in range(0, rows, chunk):
        vals = []
        for i in range(base, min(rows, base + chunk)):
            rid = i + 1
            vec = ",".join("%.5f" % random.uniform(-1, 1) for _ in range(dim))
            cat = "hot" if rid % 20 == 0 else "cold"
            vals.append("(%d,'d%d','%s','[%s]')" % (rid, rid, cat, vec))
        f.write("INSERT INTO " + table + " VALUES " + ",".join(vals) + ";\n")
PY
}

bench() { # <extra args...>
    java -Dfile.encoding=UTF-8 -cp "${BENCH_CP}" com.wangyu.tangdb.query.P7VectorBench \
        --host 127.0.0.1 --port "${BE_PORT}" --table "${TABLE}" --column embedding \
        --dim "${DIM}" "$@" 2>&1
}

# ================================================================ 构建
log "=================================================================="
log " tang_db P7.1 性能回归:${ROWS} 行 × ${DIM} 维"
log " 时间: $(date '+%Y-%m-%d %H:%M:%S')  memtable=${MEMTABLE}  runs=${RUNS} iters=${ITERS}"
log "=================================================================="
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes")"
BENCH_CP="$(cygpath -w "${FE_DIR}/target/test-classes");${CP}"

write_confs
start_be || exit 1
start_fed || exit 1

# ================================================================ ① 导入
run_sql_text "DROP TABLE IF EXISTS ${TABLE};" >/dev/null 2>&1
OUT="$(run_sql_text "CREATE TABLE ${TABLE} (id INT PRIMARY KEY, title VARCHAR(64), category VARCHAR(16), embedding VECTOR(${DIM})); exit")"
printf '%s' "${OUT}" | grep -q "已创建" || { log "[FAIL] 建表失败: ${OUT}"; exit 1; }

log "[1/3 导入] 生成 ${ROWS} 行 SQL ..."
gen_scale_sql "${SCALE_SQL}"
log "  SQL 文件 $(du -m "${SCALE_SQL}" | cut -f1)MB"
T0=$(date +%s%N)
run_sql_text "$(cat "${SCALE_SQL}")" >/dev/null 2>&1
T1=$(date +%s%N)
IMPORT_MS=$(( (T1-T0)/1000000 ))
CNT="$(run_sql_text "SELECT COUNT(*) FROM ${TABLE}; exit" | grep -E '^\|' | sed -n 's/^| *\([0-9][0-9]*\) *|.*$/\1/p' | head -1)"
log "IMPORT_MS=${IMPORT_MS} ROWS=${CNT}"

# ================================================================ ② 建图
log "[2/3 建图] CREATE VECTOR INDEX ..."
T2=$(date +%s%N)
run_sql_text "CREATE VECTOR INDEX vidx ON ${TABLE} (embedding) WITH (metric='l2', m=16, ef_construction=200); exit" >/dev/null
wait_ready >/dev/null 2>&1 || log "  警告:建图未在 600s 内完成"
T3=$(date +%s%N)
BUILD_MS=$(( (T3-T2)/1000000 ))
SEGS="$(vec_field vidx segment_count)"
log "BUILD_MS=${BUILD_MS} SEGMENTS=${SEGS} PENDING=$(vec_field vidx pending_segments) GRAPH_BYTES=$(vec_field vidx graph_bytes)"
run_sql_text "ANALYZE TABLE ${TABLE}; exit" >/dev/null

# ================================================================ ③ 检索
log "[3/3 检索] k=${K} runs=${RUNS} iters=${ITERS}(同 JVM,开分段耗时)"
PERF_OUT="$(bench --mode perf --k "${K}" --runs "${RUNS}" --iters "${ITERS}" --profile)"
printf '%s\n' "${PERF_OUT}" | tee -a "${REPORT}"
MED="$(printf '%s\n' "${PERF_OUT}" | sed -n 's/.*MEDIAN_MS=\([0-9]*\).*/\1/p' | tail -1)"
P50="$(printf '%s\n' "${PERF_OUT}" | sed -n 's/.*P50_MS=\([0-9]*\).*/\1/p' | tail -1)"
P99="$(printf '%s\n' "${PERF_OUT}" | sed -n 's/.*P99_MS=\([0-9]*\).*/\1/p' | tail -1)"
log "SEARCH_MEDIAN_MS=${MED} SEARCH_P50_MS=${P50} SEARCH_P99_MS=${P99}"
if [ -n "${MED}" ] && [ "${MED}" -le 100 ]; then
    log "SC-439 ${ROWS}×${DIM} top-${K} 中位 ${MED}ms ≤ 100ms  [PASS]"
else
    log "SC-439 ${ROWS}×${DIM} top-${K} 中位 ${MED}ms > 100ms  [FAIL]"
fi

# SC-441:k=10 与 k=100 的耗时差 ≤ 20%
log "SC-441 k=${K2} 对照(同一数据、同 JVM)"
PERF2_OUT="$(bench --mode perf --k "${K2}" --runs "${RUNS}" --iters "${ITERS}")"
printf '%s\n' "${PERF2_OUT}" | tee -a "${REPORT}"
MED2="$(printf '%s
' "${PERF2_OUT}" | grep -o 'MEDIAN_MS=[0-9]*' | cut -d= -f2 | tail -1)"
P50_2="$(printf '%s
' "${PERF2_OUT}" | grep -o 'P50_MS=[0-9]*' | cut -d= -f2 | tail -1)"
log "K${K}_MEDIAN_MS=${MED} K${K2}_MEDIAN_MS=${MED2} K${K}_P50_MS=${P50} K${K2}_P50_MS=${P50_2}"
# SC-441 判据用 P50(K184 口径:报 P50/P99,不只报中位数);中位数差一并打印供对照。
if [ -n "${P50}" ] && [ -n "${P50_2}" ] && [ "${P50}" -gt 0 ]; then
    python - "${P50}" "${P50_2}" "${MED}" "${MED2}" <<'PY' | tee -a "${REPORT}"
import sys
a, b = int(sys.argv[1]), int(sys.argv[2])
ma, mb = int(sys.argv[3]), int(sys.argv[4])
ratio = abs(b - a) / max(a, 1)
ok = ratio <= 0.2 and a <= 100 and b <= 100 and ma <= 100 and mb <= 100
print("K_P50_RATIO=%.3f K_MEDIAN_DELTA=%dms  SC-441 %s"
      % (ratio, abs(mb - ma), "[PASS]" if ok else "[FAIL]"))
PY
fi

# SC-440 参照:8 线程并发 vs 空载(★ 正式判据在 p7-test.sh 的 2 万行场景)
if [ "${SECONDS_CONC}" != "0" ]; then
    log "SC-440 参照:${THREADS} 线程并发 ${SECONDS_CONC}s"
    CONC_OUT="$(bench --mode concurrent --k "${K}" --threads "${THREADS}" --seconds "${SECONDS_CONC}")"
    printf '%s\n' "${CONC_OUT}" | tee -a "${REPORT}"
    I_P99="$(printf '%s\n' "${CONC_OUT}" | sed -n 's/.*IDLE_P99=\([0-9]*\)ms.*/\1/p')"
    C_P99="$(printf '%s\n' "${CONC_OUT}" | sed -n 's/.* P99=\([0-9]*\)ms.*/\1/p')"
    if [ -n "${I_P99}" ] && [ -n "${C_P99}" ]; then
        python - "${I_P99}" "${C_P99}" <<'PY' | tee -a "${REPORT}"
import sys
i, c = int(sys.argv[1]), int(sys.argv[2])
print("IDLE_P99=%dms CONC_P99=%dms RATIO=%.2f  SC-440参照 %s"
      % (i, c, c / max(i, 1), "[PASS]" if c <= 2 * i else "[FAIL]"))
PY
    fi
fi

log ""
log "PERF_DONE  report=${REPORT}"
