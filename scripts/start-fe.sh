#!/usr/bin/env bash
# 启动 tang_db FE 命令行客户端
#
# 用法: ./scripts/start-fe.sh [--config <file>] [--be-host <host>] [--be-port <port>]
#
# 配置来自 config/fe.conf;命令行参数覆盖配置文件(优先级:命令行 > 配置文件 > 内置默认)。
# 想换一份配置:./scripts/start-fe.sh --config=其他路径/fe.conf,或设环境变量 TANGDB_FE_CONFIG。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [ -n "${TANGDB_FE_CONFIG:-}" ]; then
    FE_CONFIG="${TANGDB_FE_CONFIG}"
else
    # 转成 Windows 路径,免得把 MSYS 风格的 /d/... 传给 JVM
    FE_CONFIG="$(cygpath -w "${ROOT_DIR}/config/fe.conf")"
fi

cd "${ROOT_DIR}/fe"

echo "=== Building FE ==="
mvn.cmd compile -q

# 用户自己传了 --config 就用用户的,不重复注入(exec-maven-plugin 的 -Dexec.args
# 按空白切分,配置文件路径里不要带空格)。
ARGS="$*"
EFFECTIVE_CONFIG="${FE_CONFIG}"
case " ${ARGS} " in
    *" --config="*)
        EFFECTIVE_CONFIG="${ARGS#*--config=}"
        EFFECTIVE_CONFIG="${EFFECTIVE_CONFIG%% *}"
        ;;
    *" --config "*)
        AFTER="${ARGS#*--config }"
        EFFECTIVE_CONFIG="${AFTER%% *}"
        ;;
    *) ARGS="--config=${FE_CONFIG} ${ARGS}" ;;
esac

echo "=== Starting FE CLI (config: ${EFFECTIVE_CONFIG}) ==="

# -Dfile.encoding=UTF-8:让 JVM 与 CLI 输出的中文保持 UTF-8
mvn.cmd exec:java -q \
    -Dfile.encoding=UTF-8 \
    -Dexec.mainClass="com.wangyu.tangdb.client.Cli" \
    -Dexec.args="${ARGS}"
