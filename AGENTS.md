# tang_db 项目协作规则

> 本文件是项目层规则,叠加在全局规则之上。**动手前必读。**

## 这个项目是什么

tang_db —— 自研 **HTAP + AI 原生**数据库。FE(Java)与 BE(Go)两个进程,通过 gRPC 通信。

- 设计文档(项目定位、总体架构、创新点、八期路线):`docs/tang_db-设计方案.md`
- 分期规约(施工依据):`specs/`,索引见 `specs/README.md`
- 项目宪法(护栏):`.specify/memory/constitution.md`

> `docs/`、`specs/`、`.specify/` 均**不入库**(见 `.gitignore`),但它们是本地必读文档。

## 动手前的顺序

1. 读 `specs/README.md`,找到**当前期**的规约(状态必须是 `Approved` 才可施工)
2. 读该规约全文 —— 它是唯一的施工依据,设计文档只是背景
3. 读 `.specify/memory/constitution.md` —— 里面有硬约束
4. 只加载任务相关的文档,不要无差别读全部

**规约状态只能由项目所有者变更。编码代理不得自行把 Draft 改成 Approved,也不得在编码中发现范围不足时反向用代码覆盖规约 —— 应当先提出来。**

## 硬约束(违反即返工)

### 1. 依赖红线

BE 侧只允许这两个第三方依赖:

```
google.golang.org/grpc
google.golang.org/protobuf
```

**严禁**引入任何现成存储引擎或嵌入式数据库,包括但不限于:
RocksDB、LevelDB、Badger、Pebble、SQLite、bbolt、DuckDB、Velox、hnswlib、faiss,以及它们的任何绑定。

FE 侧允许 `grpc-java`、`protobuf-java` 与常规构建/日志工具库。

存储引擎、执行引擎、向量索引、协议层 **全部自己实现** —— 这是这个项目存在的全部意义。

### 2. FE / BE 边界不可穿透

- FE **绝不**直接读写 BE 的数据目录下的任何文件,必须走 gRPC
- BE **绝不**知道"表名""列名""SQL"这些概念
- `proto/tangdb.proto` 里不得出现表/列/SQL 语义

### 3. 先日志后数据(顺序不可调换)

一次写入的处理顺序固定为:

```
① 追加 WAL 记录 → ② fsync → ③ 写 memtable → ④ 返回 OK
```

不允许先返回 OK 再落盘;不允许以"后台异步刷盘"为由跳过 fsync。

### 4. 段文件不可变

segment 文件一旦落盘就不允许修改。所有变更通过写新文件 + 原子更新 MANIFEST 完成。

## 构建与验证(本机环境)

开发机:Windows 11,命令在 git-bash 下执行。

**BE(Go 1.27)**

```bash
cd be
go build ./...
go vet ./...
go test ./...
```

- Go 代理已配置为 `https://goproxy.cn,direct`(已写入 `go env`,无需再设)
- protoc 插件 `protoc-gen-go` / `protoc-gen-go-grpc` 在 `D:/dev/gopath/bin`

**FE(Java 17 + Maven 3.9.16)**

```bash
cd fe
mvn.cmd compile          # 必须用 mvn.cmd
mvn.cmd test
```

> 本机的 `mvn`(POSIX sh 脚本版)**不可用**,会报 `ClassNotFoundException`。一律用 `mvn.cmd`。

## 最小验证口径

每次改动至少保证:

- BE:`go build ./...` 与 `go vet ./...` 无错,相关单测通过
- FE:`mvn.cmd compile` 无错
- 涉及写路径的改动:跑一次崩溃测试(`scripts/crash-test.sh`)

**交付时必须列出实际执行的命令与真实输出。禁止把未运行的检查描述为通过。** 没有跑的项要明说"未验证"。

## 提交与范围

- **不要自动 `git commit` / `git push`** —— 改动留在工作区,由项目所有者审后提交
- 严格按规约条目施工,不夹带无关重构、重命名或"顺手优化"
- 发现规约缺口或与设计文档冲突时,**停下来提出来**,不要自行决断
