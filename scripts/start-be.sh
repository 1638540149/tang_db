#!/usr/bin/env bash
# 启动 tang_db BE 服务
# 用法: ./scripts/start-be.sh [--data-dir <dir>] [--port <port>] [--memtable-size <size>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export PATH="/c/Program Files/Go/bin:$PATH"

cd "${ROOT_DIR}/be"

echo "=== Building BE ==="
go build -o be.exe ./cmd/be

echo "=== Starting BE ==="
./be.exe "$@"
