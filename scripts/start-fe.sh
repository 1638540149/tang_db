#!/usr/bin/env bash
# tang_db 常驻 FE(fed)启动 / 停止;同一个脚本带 `cli` 子命令起命令行客户端。
#
# 用法:
#   ./scripts/start-fe.sh                     # 前台启动常驻 FE(日志打屏,Ctrl+C 优雅退出)
#   ./scripts/start-fe.sh --daemon            # 后台启动(日志 tmp/fed.log,PID tmp/fed.pid)
#   ./scripts/start-fe.sh stop                # 停止后台启动的常驻 FE
#   ./scripts/start-fe.sh cli [客户端参数]      # 起瘦客户端 CLI(通过 MySQL 协议连 fed)
#   ./scripts/start-fe.sh cli --host 127.0.0.1 --port 3406 --user root --password tangdb --database shop
#   ./scripts/start-fe.sh --config <file>     # 指定配置文件(默认 config/fe.conf)
#
# 启动顺序:scripts/start-be.sh → 本脚本 → 客户端。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PID_FILE="${ROOT_DIR}/tmp/fed.pid"
LOG_FILE="${ROOT_DIR}/tmp/fed.log"
CP_FILE="${ROOT_DIR}/tmp/fe-cp.txt"

if [ -n "${TANGDB_FE_CONFIG:-}" ]; then
    FE_CONFIG="${TANGDB_FE_CONFIG}"
else
    FE_CONFIG="$(cygpath -w "${ROOT_DIR}/config/fe.conf")"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA="${JAVA_HOME}/bin/java"
else
    JAVA="java"
fi

# Java 17 的 System.out/err 用控制台默认编码(中文 Windows = GBK),只加 -Dfile.encoding=UTF-8
# 改不了 stdout → git-bash(mintty,UTF-8)里中文会乱码;必须同时指定 sun.stdout/stderr.encoding。
JAVA_OPTS=(-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8)

# ---------------------------------------------------------------- 停止

pid_alive() {
    # $1 = Windows PID(不是 MSYS 的 $!,两者不同);ps -W 的第 4 列才是 WINPID
    ps -W 2>/dev/null | awk -v p="$1" '$4==p {found=1} END{exit !found}'
}

msys_to_winpid() {
    ps -W 2>/dev/null | awk -v p="$1" '$1==p {print $4; exit}'
}

stop_fed() {
    if [ ! -f "${PID_FILE}" ]; then
        echo "常驻 FE 未在后台运行(没有 ${PID_FILE})"
        return 0
    fi
    local pid
    pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [ -z "${pid}" ] || ! pid_alive "${pid}"; then
        echo "常驻 FE 未在运行(PID ${pid:-?} 已不存在),清理 PID 文件"
        rm -f "${PID_FILE}"
        return 0
    fi
    echo "=== 停止常驻 FE (PID ${pid},优雅退出上限 5 秒) ==="
    taskkill /PID "${pid}" >/dev/null 2>&1 || true
    for _ in $(seq 1 12); do
        pid_alive "${pid}" || break
        sleep 0.5
    done
    if pid_alive "${pid}"; then
        echo "优雅退出未生效,强制结束(未提交事务会回滚,已提交数据在 BE 的 WAL 里不丢)"
        taskkill /F /PID "${pid}" >/dev/null 2>&1 || true
        sleep 0.5
    fi
    rm -f "${PID_FILE}"
    echo "常驻 FE 已停止"
}

# ---------------------------------------------------------------- 参数

MODE="fed"
DAEMON=0
ARGS=()
for arg in "$@"; do
    case "${arg}" in
        stop|--stop) stop_fed; exit 0 ;;
        cli) MODE="cli" ;;
        --daemon|-d) DAEMON=1 ;;
        *) ARGS+=("${arg}") ;;
    esac
done

MAIN_CLASS="com.wangyu.tangdb.fed.Fed"
LABEL="常驻 FE(fed)"
if [ "${MODE}" = "cli" ]; then
    MAIN_CLASS="com.wangyu.tangdb.client.Cli"
    LABEL="CLI"
    DAEMON=0   # 客户端是交互式,始终前台
fi

# ---------------------------------------------------------------- 构建

cd "${ROOT_DIR}/fe"

echo "=== Building FE ==="
mvn.cmd compile -q

if [ ! -s "${CP_FILE}" ] || [ "${ROOT_DIR}/fe/pom.xml" -nt "${CP_FILE}" ]; then
    mvn.cmd -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "${CP_FILE}")"
fi
CP="$(cat "${CP_FILE}");$(cygpath -w "${ROOT_DIR}/fe/target/classes")"

EFFECTIVE_CONFIG="${FE_CONFIG}"
USER_CONFIG=""
PREV=""
for arg in ${ARGS[@]+"${ARGS[@]}"}; do
    case "${PREV}" in
        --config) USER_CONFIG="${arg}" ;;
    esac
    case "${arg}" in
        --config=*) USER_CONFIG="${arg#--config=}" ;;
    esac
    PREV="${arg}"
done
if [ -n "${USER_CONFIG}" ]; then
    EFFECTIVE_CONFIG="${USER_CONFIG}"
    ARGS_STR="${ARGS[*]:-}"
else
    ARGS_STR="--config=${FE_CONFIG}"
    for arg in ${ARGS[@]+"${ARGS[@]}"}; do
        ARGS_STR="${ARGS_STR} ${arg}"
    done
fi

# ---------------------------------------------------------------- 启动

if [ "${DAEMON}" -eq 1 ]; then
    mkdir -p "${ROOT_DIR}/tmp"
    if [ -f "${PID_FILE}" ] && pid_alive "$(cat "${PID_FILE}" 2>/dev/null || echo 0)"; then
        echo "常驻 FE 已在运行(PID $(cat "${PID_FILE}")),先 ./scripts/start-fe.sh stop"
        exit 1
    fi
    echo "=== Starting ${LABEL} in background (config: ${EFFECTIVE_CONFIG}, log: ${LOG_FILE}) ==="
    OFFSET=0
    [ -f "${LOG_FILE}" ] && OFFSET=$(wc -c <"${LOG_FILE}")
    nohup "${JAVA}" "${JAVA_OPTS[@]}" -cp "${CP}" "${MAIN_CLASS}" ${ARGS_STR} >>"${LOG_FILE}" 2>&1 &
    MSYS_PID=$!
    sleep 1
    WINPID="$(msys_to_winpid "${MSYS_PID}")"
    echo "${WINPID:-${MSYS_PID}}" >"${PID_FILE}"
    for _ in $(seq 1 40); do
        if tail -c +"$((OFFSET + 1))" "${LOG_FILE}" 2>/dev/null | grep -q "fed 已就绪"; then
            break
        fi
        pid_alive "$(cat "${PID_FILE}")" || break
        sleep 0.5
    done
    if ! pid_alive "$(cat "${PID_FILE}")"; then
        echo "启动失败,本次日志:"
        tail -c +"$((OFFSET + 1))" "${LOG_FILE}" 2>/dev/null | tail -20 || true
        rm -f "${PID_FILE}"
        exit 1
    fi
    echo "常驻 FE 已在后台启动:WINPID $(cat "${PID_FILE}"),日志 ${LOG_FILE}"
    tail -c +"$((OFFSET + 1))" "${LOG_FILE}" | grep "fed 已就绪" | tail -1 || true
    echo "停止:./scripts/start-fe.sh stop"
else
    echo "=== Starting ${LABEL} (config: ${EFFECTIVE_CONFIG}) ==="
    exec "${JAVA}" "${JAVA_OPTS[@]}" -cp "${CP}" "${MAIN_CLASS}" ${ARGS_STR}
fi
