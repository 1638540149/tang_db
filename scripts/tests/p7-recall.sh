#!/usr/bin/env bash
# tang_db P7 召回矩阵(SC-421 / FR-789):删除率 × 更新率 × 段数 × 过滤选择率 = 81 格。
#
# 每格都真跑:建表 → 灌数据 → 删/改 → 触发 N 个 segment → 建向量索引 → ANALYZE
#             → 5 条随机查询的 ANN 结果与 exact 结果对拍,输出 Recall@10。
#
# 用法: bash scripts/tests/p7-recall.sh            # 全量 81 格
#       P7_RECALL_ROWS=4000 bash scripts/tests/p7-recall.sh
#       P7_RECALL_QUERIES=5 bash scripts/tests/p7-recall.sh
# 端口:BE 9980 / MySQL 9986 / 心跳 9991;数据目录 tmp/p7-recall/data。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p7-recall"
DATA_DIR="${TEST_DIR}/data"
BE_EXE="${TEST_DIR}/be.exe"
BE_CONF="${TEST_DIR}/be.conf"
FE_CONF="${TEST_DIR}/fe.conf"
CP_FILE="${TEST_DIR}/cp.txt"
REPORT="${TEST_DIR}/recall.txt"

BE_PORT="${P7R_BE_PORT:-9980}"
MYSQL_PORT="${P7R_MYSQL_PORT:-9986}"
HEART_PORT="${P7R_HEART_PORT:-9991}"
DB_USER=root
DB_PASS=p7recall
DIM="${P7_RECALL_DIM:-8}"
ROWS="${P7_RECALL_ROWS:-1500}"
QUERIES="${P7_RECALL_QUERIES:-5}"
K=10

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

rm -rf "${TEST_DIR}"
mkdir -p "${TEST_DIR}" "${DATA_DIR}" "${TEST_DIR}/fe-sort"
: >"${REPORT}"

log() { echo "$*" | tee -a "${REPORT}"; }

TEST_DIR_WIN="$(cygpath -w "${TEST_DIR}")"
BE_EXE_WIN="$(cygpath -w "${BE_EXE}")"

be_running() { powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='be.exe'\" | Where-Object { \$_.ExecutablePath -eq '${BE_EXE_WIN}' }) { exit 0 } else { exit 1 }" >/dev/null 2>&1; }
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

wait_port_free() { for _ in $(seq 1 "${2:-30}"); do netstat -ano | grep -q ":$1 .*LISTENING" || return 0; sleep 1; done; return 0; }

write_confs() { # <memtable>
    cat >"${BE_CONF}" <<EOF
data_dir = $(cygpath -w "${DATA_DIR}")
port = ${BE_PORT}
memtable_size = ${1:-1MB}
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
query_parallelism = 4
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
    for _ in $(seq 1 80); do grep -q "BE 已就绪" "${TEST_DIR}/be.log" 2>/dev/null && return 0; sleep 0.5; done
    log "BE 启动失败"; tail -20 "${TEST_DIR}/be.log" | tee -a "${REPORT}"; return 1
}
start_fed() {
    wait_port_free "${MYSQL_PORT}" 20
    java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.fed.Fed \
        --config="$(cygpath -w "${FE_CONF}")" >"${TEST_DIR}/fed.log" 2>&1 &
    FED_PID=$!
    for _ in $(seq 1 80); do grep -q "fed 已就绪" "${TEST_DIR}/fed.log" 2>/dev/null && return 0; sleep 0.5; done
    log "fed 启动失败"; tail -20 "${TEST_DIR}/fed.log" | tee -a "${REPORT}"; return 1
}
restart_env() { # <memtable>
    kill_fed; kill_be
    rm -rf "${DATA_DIR}"; mkdir -p "${DATA_DIR}" "${TEST_DIR}/fe-sort"
    write_confs "$1"
    start_be || return 1
    start_fed || return 1
}

run_sql_text() {
    printf '%s\n' "$1" | timeout 240 java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
        -cp "${CP}" com.wangyu.tangdb.client.Cli --config="$(cygpath -w "${FE_CONF}")" 2>&1
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
    for _ in $(seq 1 180); do
        local p; p="$(vec_field vidx pending_segments)"
        [ -n "${p}" ] && [ "${p}" = "0" ] && return 0
        sleep 1
    done
    return 1
}

# 生成一格的 SQL:灌 ROWS 行,按 delete%/update% 删改;filter 用 category。
gen_cell_sql() { # <path> <delete_pct> <update_pct>
    python - "$(cygpath -w "$1")" "${ROWS}" "${DIM}" "$2" "$3" <<'PY'
import random, sys
path, rows, dim, delete_pct, update_pct = (sys.argv[1], int(sys.argv[2]), int(sys.argv[3]),
                                           float(sys.argv[4]), float(sys.argv[5]))
random.seed(20261029 + int(delete_pct * 10) * 31 + int(update_pct * 10))
with open(path, "w", encoding="utf-8", newline="\n") as f:
    chunk = 1000
    for base in range(0, rows, chunk):
        vals = []
        for i in range(base, min(rows, base + chunk)):
            rid = i + 1
            vec = ",".join("%.5f" % random.uniform(-1, 1) for _ in range(dim))
            cat = "hot" if rid % 20 == 0 else "cold"   # 选择率 5%
            vals.append("(%d,'d%d','%s','[%s]')" % (rid, rid, cat, vec))
        f.write("INSERT INTO docs VALUES " + ",".join(vals) + ";\n")
    remove = int(rows * delete_pct / 100.0)
    if remove > 0:
        ids = random.sample(range(1, rows + 1), remove)
        f.write("DELETE FROM docs WHERE id IN (%s);\n" % ",".join(str(i) for i in ids))
    update = int(rows * update_pct / 100.0)
    if update > 0:
        ids = random.sample(range(1, rows + 1), min(update, rows // 1000 + update))
        for rid in ids[:update]:
            vec = ",".join("%.5f" % random.uniform(-1, 1) for _ in range(dim))
            # UPDATE 一次改 200 行,减少语句数
        for start in range(0, len(ids[:update]), 200):
            batch = ids[:update][start:start + 200]
            # 用 CASE 之外的写法:逐条 UPDATE(每行都换向量)
            for rid in batch:
                vec = ",".join("%.5f" % random.uniform(-1, 1) for _ in range(dim))
                f.write("UPDATE docs SET embedding = '[%s]' WHERE id = %d;\n" % (vec, rid))
PY
}

bench_recall() { # <table> <where> -> RECALL
    local table="$1" where="$2" out
    local args=(--mode recall --port "${BE_PORT}" --table "${table}" --column embedding
                --dim "${DIM}" --k "${K}" --queries "${QUERIES}")
    if [ -n "${where}" ]; then args+=(--where "${where}"); fi
    out="$(java -Dfile.encoding=UTF-8 -cp "${BENCH_CP}" com.wangyu.tangdb.query.P7VectorBench "${args[@]}" 2>&1)"
    printf '%s\n' "${out}" >>"${TEST_DIR}/bench.log"
    printf '%s\n' "${out}" | sed -n 's/.*RECALL=\([0-9.]*\).*/\1/p' | head -1
}

# ================================================================ 构建
log "=================================================================="
log " tang_db P7 召回矩阵(SC-421 / FR-789):删除率 × 更新率 × 段数 × 选择率"
log " 时间: $(date '+%Y-%m-%d %H:%M:%S')  rows/格=${ROWS}  dim=${DIM}  queries/格=${QUERIES}  k=${K}"
log "=================================================================="
log ""
(cd "${BE_DIR}" && go build -o "$(cygpath -w "${BE_EXE}")" ./cmd/be) || { log "BE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { log "FE 构建失败"; exit 1; }
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { log "取 classpath 失败"; exit 1; }
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"
BENCH_CP="$(cygpath -w "${FE_DIR}/target/test-classes");${CP}"

MATRIX="${TEST_DIR}/matrix.csv"
echo "delete_pct,update_pct,segment_level,segments,selectivity,recall,queries" >"${MATRIX}"

SEG_LEVELS=("128KB" "32KB" "8KB")
SEG_NAMES=("seg~1" "seg~5" "seg~20")

for si in 0 1 2; do
    MEM="${SEG_LEVELS[$si]}"
    log ""
    log "===== 段数档 ${SEG_NAMES[$si]}(memtable_size=${MEM}) ====="
    restart_env "${MEM}" || { log "环境启动失败"; exit 1; }
    for del in 0 20 40; do
        for upd in 0 20 40; do
            for sel in 0.05 0.5 1.0; do
                RID="d${del}u${upd}s${sel}"
                TABLE="docs_${del}_${upd}_$(echo "${sel}" | tr -d '.')"
                run_sql_text "DROP TABLE IF EXISTS ${TABLE};" >/dev/null 2>&1
                OUT="$(run_sql_text "CREATE TABLE ${TABLE} (id INT PRIMARY KEY, title VARCHAR(64), category VARCHAR(16), embedding VECTOR(${DIM})); exit")"
                if ! printf '%s' "${OUT}" | grep -q "已创建"; then
                    log "  [FAIL] ${RID} 建表失败"; echo "${del},${upd},${SEG_NAMES[$si]},0,${sel},0,0" >>"${MATRIX}"; continue
                fi
                gen_cell_sql "${TEST_DIR}/cell.sql" "${del}" "${upd}"
                sed -i "s/INTO docs /INTO ${TABLE} /g; s/FROM docs /FROM ${TABLE} /g; s/UPDATE docs /UPDATE ${TABLE} /g" "${TEST_DIR}/cell.sql"
                run_sql_text "$(cat "${TEST_DIR}/cell.sql")" >/dev/null
                run_sql_text "CREATE VECTOR INDEX vidx ON ${TABLE} (embedding) WITH (metric='l2', m=16, ef_construction=200); exit" >/dev/null
                wait_ready >/dev/null 2>&1 || true
                run_sql_text "ANALYZE TABLE ${TABLE}; exit" >/dev/null
                SEGS="$(vec_field vidx segment_count)"
                WHERE=""
                if [ "${sel}" != "1.0" ]; then
                    if [ "${sel}" = "0.5" ]; then WHERE="id % 2 = 0"; else WHERE="category = 'hot'"; fi
                fi
                RECALL="$(bench_recall "${TABLE}" "${WHERE}" 2>/dev/null)"
                case "${WHERE}" in
                    "") SELECTIVITY="1.0" ;;
                    "id % 2 = 0") SELECTIVITY="0.5" ;;
                    *) SELECTIVITY="0.05" ;;
                esac
                [ -z "${RECALL}" ] && RECALL="0"
                echo "${del},${upd},${SEG_NAMES[$si]},${SEGS:-0},${SELECTIVITY},${RECALL},${QUERIES}" >>"${MATRIX}"
                log "  删除${del}% 更新${upd}% 段=${SEGS:-?} 选择率=${SELECTIVITY} → Recall@${K}=${RECALL}"
                run_sql_text "DROP INDEX vidx ON ${TABLE}; DROP TABLE ${TABLE};" >/dev/null 2>&1
            done
        done
    done
done

log ""
log "===== 81 格结果 ====="
python - "$(cygpath -w "${MATRIX}")" <<'PY' | tee -a "${REPORT}"
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
print("%-8s %-8s %-10s %-8s %-8s %-8s" % ("delete", "update", "segment", "segs", "sel", "recall"))
bad = 0
for r in rows:
    print("%-8s %-8s %-10s %-8s %-8s %-8s" % (r["delete_pct"], r["update_pct"],
          r["segment_level"], r["segments"], r["selectivity"], r["recall"]))
    try:
        if float(r["recall"]) < 0.95:
            bad += 1
    except ValueError:
        bad += 1
print("")
print("CELLS=%d BELOW_95=%d" % (len(rows), bad))
PY

log ""
log "报告:${REPORT}"
