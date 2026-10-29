#!/usr/bin/env bash
# tang_db P6.1 执行层吞吐 / 并行度扫参(可选工具,不参与验收判定)
#
# 在同一个 JVM 里直连 BE,对同一组 SQL 用不同 query_parallelism / pushdown 组合各跑一遍,
# 打印每条查询的耗时、返回行数与扫描载荷字节(SPEC-P6.1 SC-403 / SC-405 的口径)。
#
# 用法:
#   bash scripts/bench-exec.sh                      # 默认扫 1 / 2 / 4 / 8 三档 + 下推开/关
#   P61_BE_PORT=9940 P61_TABLE=p61_big64 bash scripts/bench-exec.sh
#   P61_SQL="Q=SELECT SUM(amount) FROM t" bash scripts/bench-exec.sh
#
# 前提:BE 已启动;FE 已执行过一次构建(mvn test-compile)。脚本只读,不写数据。

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FE_DIR="${ROOT_DIR}/fe"
OUT_DIR="${ROOT_DIR}/tmp/bench-exec"

BE_PORT="${P61_BE_PORT:-9940}"
TABLE="${P61_TABLE:-p61_big64}"
PARALLELISM="${P61_PARALLELISM:-1 2 4 8}"
SQLS=()
if [ -n "${P61_SQL:-}" ]; then
    SQLS=("${P61_SQL}")
else
    SQLS=(
        "SCAN_AGG=SELECT SUM(amount) FROM ${TABLE}"
        "GROUP_BY=SELECT city, COUNT(*) FROM ${TABLE} GROUP BY city"
        "SCAN_ALL=SELECT * FROM ${TABLE}"
    )
fi

mkdir -p "${OUT_DIR}"

echo "[构建] FE test-classes"
(cd "${FE_DIR}" && mvn.cmd -q test-compile) || { echo "FE 构建失败"; exit 1; }

CP_FILE="${OUT_DIR}/cp.txt"
(cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath \
    -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { echo "取 classpath 失败"; exit 1; }
CP="$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes");$(cat "${CP_FILE}")"

echo "=================================================="
echo " P6.1 执行层扫参  BE=${BE_PORT}  表=${TABLE}"
echo " 并行度档位: ${PARALLELISM}"
echo "=================================================="

run_one() { # <parallelism> <pushdown>
    local par="$1" push="$2"
    local args=(--mode sql --port "${BE_PORT}" --parallelism "${par}" --pushdown "${push}")
    local spec
    for spec in "${SQLS[@]}"; do
        args+=(--sql "${spec}")
    done
    echo "---- parallelism=${par} pushdown=${push}"
    java -Xmx2g -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.query.P61Bench "${args[@]}" \
        2>&1 | sed 's/^/  /'
}

for par in ${PARALLELISM}; do
    run_one "${par}" true
done
run_one 1 false

echo ""
echo "结果同时写入 ${OUT_DIR}/last-run.txt"
