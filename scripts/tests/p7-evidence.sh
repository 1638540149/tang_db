#!/usr/bin/env bash
# SPEC-P7 FR-785 / FR-786 量化取证:流式 vs 物化的保留堆、解码复用前后的分配量/GC 次数。
#
# 口径(同 JVM、同数据,FE 侧):
#   - FR-716:同一批投影帧,分别用"每行 new Object[] + 每次新建 Decoder"与
#             "复用缓冲 Decoder" 解码,比较线程分配字节数与 GC 次数;
#   - FR-715:同样行数下,"整表物化 List<Object[]>" 与 "逐批消费只留一行" 的保留堆。
#
# 用法: bash scripts/tests/p7-evidence.sh [rows]
# 输出: DECODE_ROWS=... ALLOC_RATIO=... GC_NEW=... GC_REUSE=... HEAP_GROWTH=...

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FE_DIR="${ROOT_DIR}/fe"
TEST_DIR="${ROOT_DIR}/tmp/p7-test"
mkdir -p "${TEST_DIR}"
CP_FILE="${TEST_DIR}/evidence-cp.txt"

export PATH="/c/Program Files/Go/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

(cd "${FE_DIR}" && mvn.cmd -q test-compile >/dev/null 2>&1) || { echo "FE 编译失败"; exit 1; }
if [ ! -s "${CP_FILE}" ]; then
    (cd "${FE_DIR}" && mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")" >/dev/null) || { echo "取 classpath 失败"; exit 1; }
fi
CP="$(cat "${CP_FILE}");$(cygpath -w "${FE_DIR}/target/classes");$(cygpath -w "${FE_DIR}/target/test-classes")"

java -Dfile.encoding=UTF-8 -Xmx2g -cp "${CP}" com.wangyu.tangdb.query.P7Evidence "${1:-200000}" 2>&1
