#!/usr/bin/env bash
# tang_db BE 启动 / 停止
#
# 用法:
#   ./scripts/start-be.sh                 # 前台启动(日志打屏,Ctrl+C 退出)
#   ./scripts/start-be.sh --daemon        # 后台启动(日志 tmp/be.log,PID tmp/be.pid)
#   ./scripts/start-be.sh stop            # 停止后台启动的 BE
#   ./scripts/start-be.sh [--config <file>] [--data-dir <dir>] [--port <port>] [--memtable-size <size>]
#
# 配置来自 config/be.conf;命令行参数覆盖配置文件(优先级:命令行 > 配置文件 > 内置默认)。
# 想换一份配置:./scripts/start-be.sh --config=其他路径/be.conf,或设环境变量 TANGDB_BE_CONFIG。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export PATH="/c/Program Files/Go/bin:$PATH"

PID_FILE="${ROOT_DIR}/tmp/be.pid"
LOG_FILE="${ROOT_DIR}/tmp/be.log"

if [ -n "${TANGDB_BE_CONFIG:-}" ]; then
    BE_CONFIG="${TANGDB_BE_CONFIG}"
else
    # 转成 Windows 路径,免得把 MSYS 风格的 /d/... 传给原生 exe
    BE_CONFIG="$(cygpath -w "${ROOT_DIR}/config/be.conf")"
fi

# ---------------------------------------------------------------- 停止

pid_alive() {
    # $1 = Windows PID(不是 MSYS 的 $!,两者不同);ps -W 的第 4 列才是 WINPID
    ps -W 2>/dev/null | awk -v p="$1" '$4==p {found=1} END{exit !found}'
}

msys_to_winpid() {
    ps -W 2>/dev/null | awk -v p="$1" '$1==p {print $4; exit}'
}

stop_be() {
    if [ ! -f "${PID_FILE}" ]; then
        echo "BE 未在后台运行(没有 ${PID_FILE})"
        return 0
    fi
    local pid
    pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [ -z "${pid}" ] || ! pid_alive "${pid}"; then
        echo "BE 未在运行(PID ${pid:-?} 已不存在),清理 PID 文件"
        rm -f "${PID_FILE}"
        return 0
    fi
    echo "=== 停止 BE (PID ${pid}) ==="
    taskkill /PID "${pid}" >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
        pid_alive "${pid}" || break
        sleep 0.5
    done
    if pid_alive "${pid}"; then
        echo "温和停止未生效,强制结束(已提交数据不丢:WAL 已 fsync)"
        taskkill /F /PID "${pid}" >/dev/null 2>&1 || true
        sleep 0.5
    fi
    rm -f "${PID_FILE}"
    echo "BE 已停止"
}

# ---------------------------------------------------------------- 参数

DAEMON=0
ARGS=()
for arg in "$@"; do
    case "${arg}" in
        stop|--stop) stop_be; exit 0 ;;
        --daemon|-d) DAEMON=1 ;;
        *) ARGS+=("${arg}") ;;
    esac
done

# ---------------------------------------------------------------- 启动

cd "${ROOT_DIR}/be"

echo "=== Building BE ==="
go build -o be.exe ./cmd/be

if [ "${DAEMON}" -eq 1 ]; then
    mkdir -p "${ROOT_DIR}/tmp"
    if [ -f "${PID_FILE}" ] && pid_alive "$(cat "${PID_FILE}" 2>/dev/null || echo 0)"; then
        echo "BE 已在运行(PID $(cat "${PID_FILE}")),先 ./scripts/start-be.sh stop"
        exit 1
    fi
    echo "=== Starting BE in background (config: ${BE_CONFIG}, log: ${LOG_FILE}) ==="
    mkdir -p "${ROOT_DIR}/tmp"
    nohup ./be.exe --config "${BE_CONFIG}" ${ARGS[@]+"${ARGS[@]}"} >>"${LOG_FILE}" 2>&1 &
    MSYS_PID=$!
    sleep 1
    WINPID="$(msys_to_winpid "${MSYS_PID}")"
    echo "${WINPID:-${MSYS_PID}}" >"${PID_FILE}"
    if ! pid_alive "$(cat "${PID_FILE}")"; then
        echo "启动失败,最后 20 行日志:"
        tail -20 "${LOG_FILE}" || true
        rm -f "${PID_FILE}"
        exit 1
    fi
    echo "BE 已在后台启动:WINPID $(cat "${PID_FILE}"),日志 ${LOG_FILE}"
    echo "停止:./scripts/start-be.sh stop"
else
    echo "=== Starting BE (config: ${BE_CONFIG}) ==="
    exec ./be.exe --config "${BE_CONFIG}" ${ARGS[@]+"${ARGS[@]}"}
fi
