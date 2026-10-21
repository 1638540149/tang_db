#!/usr/bin/env bash
# 从 proto/tangdb.proto 生成 Go 与 Java 桩代码
# 本机 Windows 11,在 git-bash 下执行

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 本机工具路径
export PATH="/c/Program Files/Go/bin:/d/dev/gopath/bin:/d/dev/apache-maven-3.9.16/bin:${PATH}"

cd "${ROOT_DIR}"

echo "=== Generating Go stubs ==="
mkdir -p be/internal/server/pb
protoc \
  --proto_path=proto \
  --go_out=be/internal/server/pb \
  --go_opt=paths=source_relative \
  --go-grpc_out=be/internal/server/pb \
  --go-grpc_opt=paths=source_relative \
  proto/tangdb.proto

cd be
GO111MODULE=on go mod tidy
cd "${ROOT_DIR}"

echo "=== Generating Java stubs ==="
cd fe
mvn.cmd compile
cd "${ROOT_DIR}"

echo "=== Done ==="
echo "Go stubs: be/internal/server/pb/"
echo "Java stubs: fe/target/generated-sources/protobuf/java/"
