#!/usr/bin/env bash
# tang_db P1 崩溃测试
# 流程:写入 N 条随机 KV -> 强杀 BE/FE -> 重启 BE -> 逐条校验 -> 输出报告

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BE_DIR="${ROOT_DIR}/be"
FE_DIR="${ROOT_DIR}/fe"
DATA_DIR="${ROOT_DIR}/data/BE"
TEST_DIR="${ROOT_DIR}/tmp/crash-test"
KV_FILE="${TEST_DIR}/kv.txt"
REPORT_FILE="${TEST_DIR}/report.txt"
BE_EXE="${ROOT_DIR}/tmp/be.exe"

# 测试参数
COUNT=${COUNT:-1000}
SEED=${SEED:-42}
BE_PORT=${BE_PORT:-9100}

export PATH="/c/Program Files/Go/bin:$PATH"

mkdir -p "${TEST_DIR}" "${ROOT_DIR}/tmp"
rm -rf "${DATA_DIR}" "${TEST_DIR}"/*

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "${REPORT_FILE}"
}

kill_be() {
    local pids
    pids=$(tasklist 2>/dev/null | grep -i "be.exe" | awk '{print $2}' || true)
    for pid in ${pids}; do
        taskkill /F /PID "${pid}" >/dev/null 2>&1 || true
    done
    sleep 1
}

wait_for_be() {
    local host="$1"
    local port="$2"
    for i in $(seq 1 60); do
        if java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.Cli --be-host="${host}" --be-port="${port}" <<<"ping" 2>/dev/null | grep -q "BE 版本"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# 清理可能残留的旧 BE
kill_be

# 构建 BE
log "=== 构建 BE ==="
cd "${BE_DIR}"
go build -o "${BE_EXE}" ./cmd/be
log "BE 可执行文件: ${BE_EXE}"

# 构建 FE
log "=== 构建 FE ==="
cd "${FE_DIR}"
mvn.cmd compile -q
mvn.cmd test-compile -q

# 预计算 classpath,避免 wait_for_be 中反复调用 mvn
mvn.cmd dependency:build-classpath -Dmdep.outputFile="../tmp/crash-test/cp.txt"
DEP_CP="$(cat "../tmp/crash-test/cp.txt")"
# Windows 上的 java 使用分号作为 classpath 分隔符。
CP="${DEP_CP};target/test-classes;target/classes"
export CP

# 启动 BE
log "=== 启动 BE (端口 ${BE_PORT}) ==="
"${BE_EXE}" --data-dir="$(cygpath -w "${DATA_DIR}")" --port="${BE_PORT}" --memtable-size=32KB >"${TEST_DIR}/be.log" 2>&1 &
BE_PID=$!
if ! wait_for_be 127.0.0.1 "${BE_PORT}"; then
    log "BE 未在预期时间内就绪"
    cat "${TEST_DIR}/be.log" || true
    kill_be
    exit 1
fi
log "BE 已就绪, PID=${BE_PID}"

# 写入随机 KV
log "=== 写入 ${COUNT} 条随机 KV ==="
java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.CrashTest write "${COUNT}" "${SEED}" "${KV_FILE}" --be-port="${BE_PORT}"

log "=== 数据目录状态(崩溃前) ==="
ls -la "${DATA_DIR}" | tee -a "${REPORT_FILE}"
ls -la "${DATA_DIR}/wal" | tee -a "${REPORT_FILE}"
ls -la "${DATA_DIR}/sst" | tee -a "${REPORT_FILE}"

# 强杀 BE
log "=== 强杀 BE ==="
kill_be
wait "${BE_PID}" 2>/dev/null || true

# 重启 BE
log "=== 重启 BE ==="
"${BE_EXE}" --data-dir="$(cygpath -w "${DATA_DIR}")" --port="${BE_PORT}" --memtable-size=32KB >"${TEST_DIR}/be-recovery.log" 2>&1 &
BE_PID=$!
if ! wait_for_be 127.0.0.1 "${BE_PORT}"; then
    log "BE 恢复后未在预期时间内就绪"
    cat "${TEST_DIR}/be-recovery.log" || true
    kill_be
    exit 1
fi
log "BE 恢复完成, PID=${BE_PID}"

# 校验
log "=== 逐条校验 ${COUNT} 条 KV ==="
if java -Dfile.encoding=UTF-8 -cp "${CP}" com.wangyu.tangdb.client.CrashTest verify "${KV_FILE}" --be-port="${BE_PORT}"; then
    log "=== 崩溃测试通过:丢失 0 条 ==="
else
    log "=== 崩溃测试失败 ==="
    kill_be
    exit 1
fi

# 清理:关闭 BE
log "=== 关闭 BE ==="
kill_be
wait "${BE_PID}" 2>/dev/null || true

log "报告已保存到 ${REPORT_FILE}"
