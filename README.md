# tang_db

> 自研 **HTAP + AI 原生**数据库 —— 以 OLAP 分析为主干,原生支持 OLTP 事务,并把向量检索与模型推理做成一等公民。

---

## 这是什么

一个从零实现的数据库。存储引擎、事务、查询执行、优化器、SQL 协议层、向量索引,**全部自己写**,不引入任何现成的存储引擎或执行引擎。

目标不是玩具:它要能建表、能跑事务、能跑分析查询、能存向量做语义检索、能让现成的 MySQL 客户端直接连上来。

## 核心设计

- **单引擎统一双负载** —— 一套 LSM 基座同时支撑 OLTP 与 OLAP:写入时走行式内存(memtable),落盘时转列式 segment。写要快、读要少,两者的诉求在不同时刻分别满足。
- **FE / BE 分层** —— FE(Java)负责 SQL 解析、优化、协议;BE(Go)负责执行与存储。两者是两个独立进程,只通过 gRPC 通信,边界不可穿透。
- **MVCC + 快照隔离** —— 读写互不阻塞;LSM 天然多版本,事务与存储共用一套版本体系。
- **向量与普通列同权** —— 向量列一样享受原子写入、MVCC 可见性、崩溃恢复与后台合并。

## 创新点

1. **HTAP + 向量 + 推理三合一,共用同一套引擎**
   事务写入、分析聚合、语义检索、模型推理,同一个库、同一份数据、同一个事务体系 —— 而不是拼四套系统加三条同步链路。

2. **HNSW 图索引做进 LSM**
   不搞常驻内存的全局大图,而是让每个 segment 自带一段图,随 compaction 增量重建。于是向量索引**天生可持久化、天然崩溃安全**,重启不需要重建。

3. **混合查询在优化器里做代价决策**
   "结构化条件 + 向量相似度"的查询,在 `先过滤后检索` 与 `先检索后过滤` 之间按选择率自动选择 —— 现成向量库普遍只能走一条路径,选错就差几十倍。

4. **模型推理下推,与向量化执行共用 batch 语义**
   `ai_embed(text)` 这样的函数下推到 BE 按批执行,一次 1024 行;模型权重不进库,库只认一个推理端点配置。

## 架构

```mermaid
flowchart LR
    C["客户端<br/>mysql / JDBC / CLI"] --> FE
    FE["FE — Java 17<br/>SQL 解析 · 优化器 · 协议 · 元数据"] -->|"gRPC :9100"| BE
    BE["BE — Go 1.27<br/>向量化执行 · 事务与 MVCC · 存储引擎"] --> D[("LSM 存储<br/>WAL · 列式 segment · HNSW 图段")]
    BE -.->|"批量推理调用<br/>(模型权重不进库)"| M["外部模型服务"]
```

## 路线图

| 期 | 主题 | 目标 |
|---|---|---|
| **P1** | 基座 | FE → BE 跨语言链路打通;最小可用 LSM;崩溃后数据不丢 |
| **P2** | 表与 SQL | DDL / DML / 条件查询,SQL 解析与元数据 |
| **P3** | 事务与 MVCC | 并发写、快照隔离、原子批写、崩溃一致性 |
| **P4** | 索引 | 主键、二级索引、唯一索引、索引选择 |
| **P5** | 分析引擎 | 列式 segment、向量化执行、聚合、JOIN、子查询 |
| **P6** | 优化器 | 统计信息、CBO、谓词下推、连接顺序 |
| **P7** | AI 能力 | `VECTOR` 类型、HNSW 分段图、混合查询、推理下推 |
| **P8** | 生产化 | MySQL 协议兼容、监控、慢查询、备份恢复 |

每期都能独立跑起来、独立演示;上一期没跑通,下一期不开工。

## 技术栈

| 层 | 技术 |
|---|---|
| FE(客户端层) | Java 17 + Maven |
| BE(数据层) | Go 1.27 |
| 服务间通信 | gRPC + Protocol Buffers 3 |
| 存储结构 | LSM-Tree(写时行式、存时列式) |
| 事务 | MVCC + 快照隔离 |
| 向量索引 | HNSW(分段式) |
| 客户端接入 | 自实现 MySQL 协议 |

## 项目结构

```
tang_db/
├── config/                     两个进程的配置(一行一项 key = value)
│   ├── be.conf                 数据层:data_dir / port / memtable_size / fe_host / fe_port /
│   │                                  heartbeat_interval_seconds / max_txn_write_bytes / test_crash_point
│   └── fe.conf                 客户端层:be_host / be_port / mode(fed|cli)/ mysql_port / user / password /
│                                  session_max / session_idle_timeout / shutdown_grace_seconds /
│                                  heartbeat_listen_port / be_offline_cycles / heartbeat_enabled
├── proto/tangdb.proto          FE 与 BE 之间唯一的接口契约
├── be/                         Go —— 数据层
│   ├── go.mod
│   ├── cmd/be/                 服务入口
│   └── internal/
│       ├── config/             配置文件解析(标准库手写,零第三方依赖)
│       ├── wal/                预写日志、批写、校验
│       ├── memtable/           跳表、版本链
│       ├── segment/            列式文件读写、编码压缩、zone map
│       ├── compaction/         分层合并、GC 水位
│       ├── manifest/           版本管理、原子切换
│       ├── store/              引擎主干:Put/Get/Scan、刷盘调度、WAL 回收
│       ├── txn/                事务、MVCC、冲突检测
│       ├── index/              主键 / 二级 / 唯一 / 分段 HNSW
│       ├── exec/               向量化算子、范围扫描迭代器
│       ├── ai/                 推理客户端、批量调度、缓存
│       └── server/             gRPC 服务实现、心跳上报
├── fe/                         Java —— 客户端层
│   ├── pom.xml
│   └── src/main/java/com/wangyu/tangdb/
│       ├── fed/                常驻 FE 进程:启动顺序、心跳归属、协议监听、优雅退出
│       ├── session/            会话层:每连接一个会话(当前库 / 事务 / 写集 / 空闲回收)
│       ├── protocol/           MySQL 协议(自研分帧 / 握手 / 认证 / 结果集;含 CLI 用的客户端侧)
│       ├── parser/             词法、语法、AST
│       ├── catalog/            元数据(存进 BE 的系统表;库命名空间在这里)
│       ├── planner/            逻辑计划、优化器
│       ├── execute/            唯一 SQL 执行入口(SqlEngine)、计划切分与调度
│       ├── storage/            key 保序编码、行值/墓碑编码
│       ├── server/             FE 侧服务:BE 注册与心跳接收
│       ├── txn/                事务协调、快照
│       └── client/             gRPC 客户端封装、瘦客户端 CLI、配置文件加载
├── tools/                      导入与基准工具
│   ├── jdbc-smoke/             JDBC 连通用例(官方 mysql-connector-j,独立模块,不进 FE 依赖)
│   ├── importer/               CSV 批量导入
│   ├── bench-tpcc/             OLTP 基准
│   ├── bench-tph/              OLAP 基准
│   └── recall-check/           向量召回率校验
└── scripts/                    启动/停止(start-be.sh、start-fe.sh)与 gen-proto.sh、演示 SQL
    ├── tests/                  一键验收与回归(p35 / p3 / p2 / crash-test.sh)
    └── sql/                    按场景编号的可复现脚本(01~35)
```

## 设计原则

**不借外力。** 存储引擎、执行引擎、优化器、向量索引、协议层,全部自己实现。以下这些以及它们的任何绑定,一律不引入:

> RocksDB · LevelDB · Badger · Pebble · SQLite · bbolt · DuckDB · Velox · hnswlib · faiss

借了外力,这个项目就没有存在意义了。

## 快速开始

### 环境要求

- Go 1.27+(Windows: 装在 `C:\Program Files\Go`)
- Java 17 + Maven 3.9+
- Windows 上执行构建命令请在 git-bash 中;**Maven 必须用 `mvn.cmd`**(POSIX 版 `mvn` 在本机不可用)

### 配置文件

两个进程的配置都在 `config/` 下,与 `fe/`、`be/` 同级,各自一份:

```
config/
├── be.conf     数据层:data_dir / port / memtable_size
└── fe.conf     客户端层:be_host / be_port / mode / mysql_port / user / password /
                session_max / session_idle_timeout / shutdown_grace_seconds /
                heartbeat_listen_port / heartbeat_enabled
```

格式是一行一项 `key = value`,`#` 开头为注释。取值优先级:**命令行参数 > 配置文件 > 内置默认值**。

```bash
./scripts/start-be.sh                      # 读 config/be.conf
./scripts/start-be.sh --port=9200          # 临时换端口,不改文件
./scripts/start-be.sh --config=其他/be.conf # 换一份配置(也可设 TANGDB_BE_CONFIG)
```

配置写错(未知项、格式错、取值非法)会**直接启动失败并指出第几行**,不会静默用默认值跑起来。

### 启动(开三个终端)

> **P3.5 起 FE 变成常驻服务 `fed`,CLI 退化为瘦客户端。**启动顺序固定为 **BE → `fed` → 客户端**;
> `fed` 持有唯一的 catalog 权威缓存与会话层,所有客户端(CLI / mysql / JDBC / DataGrip)连同一个 `fed`。

终端 A —— 起数据层:

```bash
./scripts/start-be.sh
# === Building BE ===
# === Starting BE (config: ...\config\be.conf) ===
# ... INFO BE 已就绪 listen=127.0.0.1:9100
```

终端 B —— 起常驻 FE(`fed`,监听 MySQL 协议端口 `3406`、心跳端口 `9091`):

```bash
./scripts/start-fe.sh
# === Building FE ===
# === Starting fed (config: ...\config\fe.conf) ===
# fed 已就绪:mysql_port=3406,be=127.0.0.1:9100,heartbeat=9091
# 信息: fed 已就绪 mysql_port=3406 session_max=64 idle_timeout=0s
```

终端 C —— 起命令行客户端(瘦客户端,通过 MySQL 协议连 `fed`;不自带 FE、不直连 BE)。
**同一个脚本的 `cli` 子命令**:

```bash
./scripts/start-fe.sh cli
# tang_db 命令行客户端（fed 127.0.0.1:3406）
# tangdb> CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32));
# OK, 表 t 已创建(表 ID 64)
```

CLI 也支持显式参数:`--host / --port / --user / --password / --database`(或从 `config/fe.conf` 取)。
`fed` 没起时,CLI 会明确报 **“连不上 fed 127.0.0.1:3406”** 而不是崩栈。

> 启动脚本只有两个:`scripts/start-be.sh`(起 BE)与 `scripts/start-fe.sh`(起常驻 FE;`cli` 子命令起客户端)。测试脚本在 `scripts/tests/`。
>
> 两者都支持后台与停止:
>
> ```bash
> ./scripts/start-be.sh --daemon     # 后台起 BE,日志 tmp/be.log,PID tmp/be.pid
> ./scripts/start-be.sh stop         # 停 BE(温和 → 5 秒后强制)
> ./scripts/start-fe.sh --daemon     # 后台起常驻 FE,日志 tmp/fed.log,PID tmp/fed.pid
> ./scripts/start-fe.sh stop         # 停常驻 FE(优雅退出,上限 5 秒)
> ```
>
> 不带 `--daemon` 就是前台启动,日志打屏、Ctrl+C 退出。
>
> **停止的实情**:Windows 上 `taskkill` 不带 `/F` 送不到 SIGTERM,所以 `stop` 会等约 5~6 秒后**强制结束**;想走优雅退出路径就用前台 + `Ctrl+C`(JVM shutdown hook 会按 FR-413 收尾)。强杀不丢已提交数据(WAL 已 fsync),只丢未提交事务 —— 与 `crash-test.sh` 的口径一致。

### 用 SQL:建表、写入、查询、改、删(P2)

同一个 CLI,输入 SQL 以 `;` 结束(可跨多行)。下面这段取自实际运行的输出(为便于阅读,合并了几次会话):

```sql
tangdb> CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32), age INT);
OK, 表 users 已创建(表 ID 16)

tangdb> INSERT INTO users (id, name, city, age) VALUES (1, 'wangyu', '北京', 26);
OK, 1 row affected

tangdb> INSERT INTO users (id, name, city, age) VALUES (2, 'lisi', '北京', 20), (3, 'zhangsan', '上海', 30);
OK, 2 rows affected

tangdb> SELECT name, city FROM users WHERE city = '北京' AND age > 25;
+--------+------+
| name   | city |
+--------+------+
| wangyu | 北京 |
+--------+------+
(1 row)

tangdb> UPDATE users SET city = '上海' WHERE id = 1;
OK, 1 row affected

tangdb> DELETE FROM users WHERE id = 2;
OK, 1 row affected

tangdb> SELECT * FROM users WHERE id = 2;
+----+------+------+-----+
| id | name | city | age |
+----+------+------+-----+
+----+------+------+-----+
(0 rows)

tangdb> SHOW TABLES;
+------------+----------+---------------------+
| table_name | table_id | created_at          |
+------------+----------+---------------------+
| users      | 16       | 2026-10-22T15:44:39 |
+------------+----------+---------------------+
(1 row)

tangdb> DESC users;
+--------+-------------+-------------+----------+
| column | type        | primary_key | nullable |
+--------+-------------+-------------+----------+
| id     | BIGINT      | YES         | NO       |
| name   | VARCHAR(32) | NO          | YES      |
| city   | VARCHAR(32) | NO          | YES      |
| age    | INT         | NO          | YES      |
+--------+-------------+-------------+----------+
(4 rows)

tangdb> SHOW BACKENDS;
+--------------------------------------+----------------+----------+--------+---------------------+----------------+---------------+-----------+-----------+
| be_id                                | endpoint       | version  | status | last_heartbeat      | memtable_bytes | segment_count | put_count | get_count |
+--------------------------------------+----------------+----------+--------+---------------------+----------------+---------------+-----------+-----------+
| 46ea4a79-5017-446b-9baf-daeb7bd9b93c | 127.0.0.1:9200 | P2-0.1.0 | 可用   | 2026-10-22 15:52:45 | 0              | 1             | 17        | 17        |
+--------------------------------------+----------------+----------+--------+---------------------+----------------+---------------+-----------+-----------+
(1 row)
```

元数据(表、列)存在**库里自己的系统表**里,不依赖任何外部数据库;重启 BE 与 FE 之后 `SHOW TABLES` / `DESC` 与数据都还在。

### P2.1 补充的三类写法(反引号列名 / 省略列名 / SHOW DATABASES)

下面三段都是**实际运行**的输入与输出(合并自同一次会话)。

**① 反引号标识符** —— `select`、`from` 这类关键字可以当列名;反引号内除反引号外任意字符都合法(空格、中划线、中文、数字开头),双写反引号表示一个反引号。反引号**不改变大小写语义**,也不能用来包裹语句关键字或列类型。

```sql
tangdb> CREATE TABLE `order` (`id` INT PRIMARY KEY, `select` VARCHAR(16), `from` INT);
OK, 表 order 已创建(表 ID 16)

tangdb> INSERT INTO `order` (`id`, `select`, `from`) VALUES (1, 'first', 10);
OK, 1 row affected

tangdb> SELECT `id`, `select`, `from` FROM `order` WHERE `id` = 1;
+----+--------+------+
| id | select | from |
+----+--------+------+
| 1  | first  | 10   |
+----+--------+------+
(1 row)
```

**② INSERT 省略列名列表** —— 值按**表定义顺序**给全所有列;少给/多给会指出第几组。

```sql
tangdb> CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32), age INT);
OK, 表 users 已创建(表 ID 17)

tangdb> INSERT INTO users VALUES (1, 'wangyu', '北京', 26);
OK, 1 row affected

tangdb> INSERT INTO users VALUES (2, 'lisi', '上海', 20), (3, 'zhangsan', '北京', 30);
OK, 2 rows affected

tangdb> SELECT * FROM users;
+----+----------+------+-----+
| id | name     | city | age |
+----+----------+------+-----+
| 1  | wangyu   | 北京 | 26  |
| 2  | lisi     | 上海 | 20  |
| 3  | zhangsan | 北京 | 30  |
+----+----------+------+-----+
(3 rows)

tangdb> INSERT INTO users VALUES (5, 'sunqi');
错误：第 1 组取值个数(2)与列数(4)不一致
```

显式列名(`INSERT INTO users (id, name) VALUES (...)`)行为不变,两种写法可以混用;`INSERT INTO users () VALUES (...)` 报 `列名列表不能为空`。

**③ SHOW DATABASES** —— 只有默认库 `default`;由 FE 直接回答,**不访问 BE**,BE 停着也照常输出。

```sql
tangdb> SHOW DATABASES;
+----------+
| Database |
+----------+
| default  |
+----------+
(1 row)
```

P2 的 SQL 边界(一期一期加,别当成完整 SQL):

- 支持:`CREATE / DROP / TRUNCATE TABLE`、`SHOW TABLES` / `SHOW DATABASES`、`DESC`、`INSERT`(多行,可省略列名列表)、`UPDATE`、`DELETE`、`SELECT 列/*/AS` + `WHERE`(`=` `<>` `<` `<=` `>` `>=`、`AND`/`OR`、`IN`)+ `LIMIT`;标识符可用反引号包裹
- **P3 新增**:`BEGIN` / `COMMIT` / `ROLLBACK`(`BEGIN TRANSACTION` / `COMMIT WORK` 也可);事务提示符为 `tangdb(txn)>`
- 不做(留给后面几期):JOIN、聚合、GROUP BY、子查询、ORDER BY、表达式与函数、二级索引;`NOT` / `IS [NOT] NULL` / `LIKE` / `BETWEEN` 归 P5
- 主键:单列,类型限 `INT` / `BIGINT` / `VARCHAR` / `DATE`
- 一条端到端功能测试:`bash scripts/tests/p2-test.sh`(11 个场景 + P2.1 兼容性断言,52 条断言,含强杀重启、心跳判死、跨内存/磁盘)

### P3 事务与 MVCC:并发写不再静默覆盖(P3)

下面两段都是**实际运行**的输出。

**① 提交 / 回滚 / 读己之写**

```sql
tangdb> CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32));
OK, 表 t 已创建(表 ID 16)

tangdb> BEGIN;
OK, 事务已开始(snapshot = 18)

tangdb(txn)> INSERT INTO t VALUES (1, 'a');
OK, 1 row affected
tangdb(txn)> INSERT INTO t VALUES (2, 'b');
OK, 1 row affected
tangdb(txn)> SELECT * FROM t;
+----+------+
| id | name |
+----+------+
| 1  | a    |
| 2  | b    |
+----+------+
(2 rows)                      -- 读己之写:本事务刚插入的行自己可见

tangdb(txn)> COMMIT;
OK, 事务已提交(commit_ts = 19)

tangdb> BEGIN;
OK, 事务已开始(snapshot = 19)

tangdb(txn)> UPDATE t SET name = 'x' WHERE id = 1;
OK, 1 row affected
tangdb(txn)> ROLLBACK;
OK, 已回滚

tangdb> SELECT * FROM t WHERE id = 1;
+----+------+
| id | name |
+----+------+
| 1  | a    |                -- 回滚后与事务前完全一致(未提交数据从未落盘)
+----+------+
(1 row)
```

**② 两个会话抢同一行:后到的被明确拒绝,绝不静默覆盖**

两个终端都连同一个 BE;第二个及以后的会话用 `heartbeat_enabled = false` 启动(否则心跳端口会冲突)。
按时间顺序:A 先 `BEGIN; UPDATE ...;`(未提交),B 再 `BEGIN; UPDATE ...;`(未提交),A 先 `COMMIT`,B 后 `COMMIT`。

```
# 会话 A(默认 config/fe.conf,持有心跳端口 9091)
tangdb> BEGIN;
OK, 事务已开始(snapshot = 21)
tangdb(txn)> UPDATE t SET name = 'A' WHERE id = 1;
OK, 1 row affected
tangdb(txn)> COMMIT;
OK, 事务已提交(commit_ts = 22)

# 会话 B(另一份 fe.conf:heartbeat_enabled = false)
tangdb> BEGIN;
OK, 事务已开始(snapshot = 21)          -- 与 A 相同的快照
tangdb(txn)> UPDATE t SET name = 'B' WHERE id = 1;
OK, 1 row affected                      -- 写集只在 B 的内存里
tangdb(txn)> COMMIT;
错误：表 t 主键 1 写写冲突(该行在本事务开始后被其它事务修改,请重试)
提示：事务仍在进行中，可继续执行或 ROLLBACK
tangdb(txn)> exit
已丢弃未提交事务(CLI 退出)

# 再查:数据库里是 A 的值,B 没有覆盖 A
tangdb> SELECT * FROM t WHERE id = 1;
| 1  | A |
```

P3 的语义边界(详见 `specs/P3-transactions-mvcc.md` §2.3):

- 一个事务要么全生效、要么全不发生;`COMMIT` 返回 OK ⇔ 批记录已 fsync
- 快照隔离(SI):事务内反复读同一行结果稳定;读不到未来数据;不做 SSI(理论上仍有写偏斜)
- 写写冲突:**报错、整批不写、不自动重试、不排队、不静默覆盖**
- `commit_ts` 单调不回退、可跳号;未提交写集只活在 FE 内存,`ROLLBACK` 是零成本
- 不支持:`READ UNCOMMITTED` / savepoint / 嵌套事务 / 分布式事务 / 死锁检测 / 事务超时

**升级到 P3 必须清空数据目录。** P3 起数据目录格式版本 = 2(版本段语义从 P2 的固定 0 改成 `^commit_ts`);
老目录(无 `format_version` 或 = 1)会**明确拒绝启动**:

```
错误：打开存储引擎失败: 数据目录格式版本 1 与本期(2)不兼容:请清空数据目录后重启
```

```bash
taskkill /F /IM be.exe
rm -rf data/BE          # 日常开发目录;测试脚本各自用独立目录,不受影响
./scripts/start-be.sh
```

**验收开关(默认关闭,仅验收使用)**

- `config/be.conf` 的 `test_crash_point`:取值 `before_wal` / `after_fsync` / `after_memtable` / `before_ok`,
  命中一次即让 BE 进程立即退出(模拟强杀)。启动日志会打印 `WARN 崩溃注入已启用:...`。
  **风险**:打开后任何一次提交都会杀掉进程,绝不能用于日常运行。
- `config/fe.conf` 的 `heartbeat_enabled`:默认 `true`(P2 行为不变:心跳端口被占用即启动失败);
  设为 `false` 时不绑心跳端口、不启动 FE 侧 gRPC 服务,SQL 照常 —— 这是同机开多个 CLI 会话做并发验收的前提。
- `config/be.conf` 的 `max_txn_write_bytes`:单事务写入量上限(默认 `64MB`),超限报
  `事务写入量超过上限(64MB)` 且不写任何数据。
- 一键验收:`bash scripts/tests/p3-test.sh`(SC-201 ~ SC-211,报告在 `tmp/p3-test/report.txt`),
  其中会顺带跑 `p2-test.sh` 与 `crash-test.sh` 做回归。

### 最强的那个动作:强杀后重启,数据还在

```bash
taskkill /F /IM be.exe      # 强杀,不给它正常退出的机会
./scripts/start-be.sh
# 恢复完成 segments_loaded=2 wal_replayed=37
# 再查,数据一条没少
```

### 崩溃测试(一键)

```bash
bash scripts/tests/crash-test.sh                # 默认写入 1000 条随机 KV
COUNT=5000 bash scripts/tests/crash-test.sh     # 自定义条数
```

流程:写入 N 条 → 打印数据目录状态 → 强杀 BE → 重启 → 逐条校验 → 输出报告到 `tmp/crash-test/report.txt`。

### 重新生成接口桩代码

```bash
bash scripts/gen-proto.sh
```

### P3.5 常驻 FE + 多库命名空间 + MySQL 协议

**一个 `fed` 服务所有客户端。** 起 BE → 起 `fed` → 任意客户端连接。下面两段都是**实际运行**的输出。

**① 多库:CREATE DATABASE / USE / 限定名**

```sql
tangdb> CREATE DATABASE shop;
OK, 库 shop 已创建

tangdb> USE shop;
OK, 已切换库 shop

tangdb> CREATE TABLE orders (id INT PRIMARY KEY, amount INT);
OK, 表 orders 已创建(表 ID 65)

tangdb> INSERT INTO orders VALUES (10, 99);
OK, 1 row affected

tangdb> USE default;
OK, 已切换库 default

tangdb> SHOW TABLES;
+------------+----------+---------------------+
| table_name | table_id | created_at          |
+------------+----------+---------------------+
| users      | 64       | 2026-10-26T10:40:47 |
+------------+----------+---------------------+
(1 row)

tangdb> SELECT * FROM shop.orders;     -- 限定名跨库访问
+----+--------+
| id | amount |
+----+--------+
| 10 | 99     |
+----+--------+
(1 row)
```

- 表名唯一性 = **库内唯一**;表 ID 仍**全局唯一**(新库从 64 起,不按库分段 → BE 与 key 布局零改动)
- 库名**大小写不敏感**(统一按小写存储);`default` 始终存在、不可删;非空库拒删(`1451`)
- 库名规则与表名一致(含反引号);重名库 `1007`、未知库 `1049`

**② 用官方 MySQL 客户端 / JDBC 直连**

`fed` 监听 MySQL 协议端口(`mysql_port`,默认 `3406`),明文口径(`useSSL=false`),认证为
`mysql_native_password`。连接串样例(K66):

```
jdbc:mysql://127.0.0.1:3406/<db>?useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

```bash
# 官方 mysql 客户端(本机需 Docker 或已安装 mysql)
mysql -h 127.0.0.1 -P 3406 -u root -ptangdb -D default
```

JDBC 冒烟测试在 `tools/jdbc-smoke/`(独立 Maven 工程,**不进 `fe/pom.xml`**):

```bash
cd tools/jdbc-smoke && mvn.cmd compile
# 然后按 JdbcSmoke 的用法连到 fed 的 mysql_port,覆盖建表/插入/事务/切库/错误码(1146)
```

支持的可测子集(不是完整 MySQL):握手 v10 + `mysql_native_password` + `COM_QUERY` / `COM_INIT_DB` /
`COM_PING` / `COM_QUIT`、文本结果集、`SET` / `SELECT @@xxx` 等连接期白名单、`information_schema`
的 `SCHEMATA` / `TABLES` / `COLUMNS` 三张最小只读视图。
**不做**:TLS、`caching_sha2_password`、服务端预编译(`COM_STMT_*` → `1295`,连接串请用
`useServerPrepStmts=false`)、权限、跨库单条 SQL、查询超时 / 慢查询。

**③ 新增配置(`config/fe.conf`)**

| 项 | 默认 | 说明 |
|---|---|---|
| `mode` | `fed` | `fed`(常驻服务)/ `cli`(瘦客户端) |
| `mysql_port` | `3406` | MySQL 协议监听端口(3306 留给真 MySQL) |
| `session_max` | `64` | 会话上限,超限拒绝连接 |
| `session_idle_timeout` | `0` | 空闲超时(秒),0 = 不超时 |
| `shutdown_grace_seconds` | `5` | 优雅退出等在途语句的上限 |
| `user` / `password` | `root` / `tangdb` | 认证凭据;口令只在配置文件,启动时派生哈希存内存 |
| `heartbeat_listen_port` / `heartbeat_enabled` | `9091` / `true` | 沿袭 P3(心跳端口由 `fed` 独占) |

**升级说明:P3.5 不需要清空数据目录。** P3 期间产生的老目录会被**宽容读**:老表一律归 `default`、
数据一条不丢、key 布局与 `MANIFEST.format_version` 都不变;`sys_tables` 缺的 “所属库” 列在启动时幂等补齐。
(这与 P3 必须清库的情况不同 —— P3 改了版本段语义,所以格式版本从 1 升到 2。)

一键验收:`bash scripts/tests/p35-test.sh`(SC-301 ~ SC-318,报告在 `tmp/p35-test/report.txt`),
它会顺带跑 `p2-test.sh` 与 `p3-test.sh` 做回归。

## 状态

**P1(基座)、P2(表与 SQL)、P2.1(SQL 兼容性补齐)、P3(事务与 MVCC)、P3.5(常驻 FE + 多库 + MySQL 协议)均已实现。**

P3.5 带来什么:

- **常驻 FE `fed`**:唯一 catalog 权威缓存 + 会话层;一个会话建的表,另一个已连接的会话**立刻可见**(关掉 P2/P3 遗留的跨进程 DDL 不可见缺口),心跳端口不再被 CLI 抢占
- **CLI 瘦客户端**:通过 MySQL 协议连 `fed`,不再自带 FE / 不直连 BE
- **多库命名空间**:`CREATE / DROP DATABASE`、`USE`、限定名 `db.tbl`;表名库内唯一、表 ID 全局唯一,BE 与 key 布局零改动,老数据目录宽容读
- **MySQL 协议**:官方 `mysql` 客户端、JDBC、图形客户端可直连执行 SQL(可测子集:握手 v10 + `mysql_native_password` + `COM_QUERY/INIT_DB/PING/QUIT` + 文本结果集 + 连接期兼容白名单 + information_schema 三视图)
- **零第三方协议库**:分帧、握手、认证、结果集编码全部用 JDK `java.net` 手写

核验口径(真实执行,不看实现方自述):

- `bash scripts/tests/p35-test.sh` —— SC-301 ~ SC-318(常驻/多会话、跨会话 DDL、多库、同名表、库级 DDL、老目录升级、表 ID、协议正确性、依赖红线 + P2/P3 回归)
- `bash scripts/tests/p3-test.sh` —— SC-201 ~ SC-211(事务语义、并发写写冲突、4 个崩溃场景、序号不回退、快照稳定、DDL 原子性、P2/P1 回归、依赖红线)
- `bash scripts/tests/p2-test.sh` —— 11 个场景 + P2.1 断言组
- `bash scripts/tests/crash-test.sh` —— P1 回归:1000 条随机 KV,强杀重启后**丢失 0 条**
- `cd be && go build ./... && go vet ./... && go test ./...` —— 全绿
- `cd fe && mvn.cmd test` —— 全绿
- 单行 INSERT 延迟(n=1000,自动提交,含一次 WAL fsync):
  **avg 3.5–6.2ms / p50 2.5–7.3ms / p99 9.0–12.0ms**
  (对比 SPEC-P2 SC-112 基线 `< 10ms`;瓶颈是每次提交的一次 `fsync`,不是 P3/P3.5 新增逻辑)

P3 带来什么:

- `BEGIN` / `COMMIT` / `ROLLBACK`;事务内写集只活在 FE 内存,读己之写,`ROLLBACK` 零成本
- 快照隔离:事务开始时固定 `snapshot`,事务内反复读同一行结果稳定
- 原子批写:一个事务的全部写入是**同一条 WAL 批记录**,一次 fsync,要么全在要么全无;
  事务外的单条 DML(含多行 `INSERT`)是一个自动提交事务,任一组失败则整条语句不写任何行
- 写写冲突:快照之后被改过 → `CONFLICT`,整批不写,FE 报"表 t 主键 x 写写冲突"
- `commit_ts` 单调不回退(允许跳号);`MANIFEST.last_commit_ts` 保证数据只在 segment 里也不回退
- DDL 的表记录 + 全部列记录 + 计数器在同一条批记录里原子生效
- 同机多 CLI 会话:`heartbeat_enabled = false` 不绑心跳端口;未知 `be_id` 心跳隐式注册

已知限制 / 规格冲突(不影响本期验收,已记录):

- **SC-203 对 `after_fsync` 的字面要求与提交协议冲突**:SPEC-P3 SC-203 写"after_fsync → 不可见",
  但 FR-313/315(记录完整 + CRC 通过 = 已提交)、场景 6 ③ 与设计方案 §14 都要求"WAL 已刷盘但 memtable 未写崩 → 重放后全有"。
  实现按 FR/设计文档执行:`after_fsync` 后重启该事务**可见**;`p3-test.sh` 的报告中已标注该 NOTE。
- **范围扫描要求用户键两两不互为前缀**:P3 的 key 布局是"用户键 + 8 字节版本段",当用户键 A 是用户键 B 的前缀时
  (`key-1` / `key-10`),完整 key 的字节序与用户键序不一致。FE 的主键编码(定长数值 / `0x00` 终止的 VARCHAR)
  保证用户键两两不互为前缀,所以 SQL 路径不受影响;点查与冲突检测已额外处理前缀关系(P1 的 `crash-test.sh` 用
  `key-1`/`key-10` 验证过)。原生的**范围扫描**在互为前缀的原始键上不保证用户键升序 —— 这是 P3 的格式约束,
  记在 `specs/P3-transactions-mvcc.md` §12 待补充。
- `put` / `get` 裸命令不恢复(K59:CLI = SQL 控制台 + `ping`)
- 不做 SSI / 间隙锁 / savepoint / 嵌套事务 / 分布式事务 / 死锁检测 / 事务超时 / version GC(归 P5)

已完成:

- 仓库初始化、README、`.gitignore`
- 项目结构骨架:`config/` `proto/` `be/` `fe/` `tools/` `scripts/`
- **配置**:两个进程的配置放进 `config/be.conf`、`config/fe.conf`,命令行参数可覆盖
- 接口契约 `proto/tangdb.proto`(`Ping` / `Put` / `Get` / `Scan` / `BeginTxn` / `CommitTxn` / `AbortTxn`)
- **BE LSM**:WAL(CRC32C + fsync + 批记录)、跳表 memtable、列式 segment、MANIFEST 原子版本管理
- **崩溃恢复**:载入 segment + 重放 WAL,尾部坏记录物理截断;数据目录格式版本守卫
- **FE**:gRPC 客户端封装 + SQL 控制台 + catalog(元数据存在 BE 系统表)
- **事务与 MVCC**:commit_ts、快照读与版本分组、原子批写、写写冲突、序号持久化
- **一键验收**:`p3-test.sh` / `p2-test.sh` / `crash-test.sh`

下一步:**P4 · 索引** —— 主键/二级/唯一索引与索引选择;版本回收与 compaction 归 P5。

---

本仓库只包含源码。设计文档、规约等过程产物保留在本地,不入库。
