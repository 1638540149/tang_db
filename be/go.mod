module github.com/1638540149/tang_db/be

go 1.27

// 依赖只有这两个,且只允许这两个(见 .specify/memory/constitution.md 第二条):
//   google.golang.org/grpc
//   google.golang.org/protobuf
//
// 存储引擎、执行引擎、向量索引必须自己实现,禁止引入 RocksDB / LevelDB /
// Badger / Pebble / SQLite / bbolt / hnswlib / faiss 等及其绑定。
//
// 具体版本在 P1 规约确认后由 `go mod tidy` 落定。

require (
	google.golang.org/grpc v1.84.0
	google.golang.org/protobuf v1.36.12
)

require (
	golang.org/x/net v0.57.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.40.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20260706201446-f0a921348800 // indirect
)
