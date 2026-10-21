#!/usr/bin/env bash
# 启动 tang_db FE 命令行客户端
# 用法: ./scripts/start-fe.sh [--be-host <host>] [--be-port <port>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}/fe"

echo "=== Building FE ==="
mvn.cmd compile -q

echo "=== Starting FE CLI ==="
# 注意:Windows 控制台默认编码可能不是 UTF-8,强制使用 UTF-8 保证中文提示正常显示。
mvn.cmd exec:java -q \
  -Dfile.encoding=UTF-8 \
  -Dexec.mainClass="com.wangyu.tangdb.client.Cli" \
  -Dexec.args="$*"
