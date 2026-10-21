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
