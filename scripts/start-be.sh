#!/usr/bin/env bash
# 启动 tang_db BE 服务
#
# 用法: ./scripts/start-be.sh [--config <file>] [--data-dir <dir>] [--port <port>] [--memtable-size <size>]
#
# 配置来自 config/be.conf;命令行参数覆盖配置文件(优先级:命令行 > 配置文件 > 内置默认)。
# 想换一份配置:./scripts/start-be.sh --config=其他路径/be.conf,或设环境变量 TANGDB_BE_CONFIG。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export PATH="/c/Program Files/Go/bin:$PATH"

if [ -n "${TANGDB_BE_CONFIG:-}" ]; then
    BE_CONFIG="${TANGDB_BE_CONFIG}"
else
    # 转成 Windows 路径,免得把 MSYS 风格的 /d/... 传给原生 exe
    BE_CONFIG="$(cygpath -w "${ROOT_DIR}/config/be.conf")"
fi

cd "${ROOT_DIR}/be"

echo "=== Building BE ==="
go build -o be.exe ./cmd/be

echo "=== Starting BE (config: ${BE_CONFIG}) ==="
./be.exe --config "${BE_CONFIG}" "$@"
