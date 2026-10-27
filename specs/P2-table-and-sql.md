# P2 规约 · 表与 SQL:能建能写能查

| 项 | 值 |
|---|---|
| 规约号 | SPEC-P2 |
| 状态 | **Approved**(2026-10-22 由项目所有者确认) |
| 版本 | 0.1d |
| 日期 | 2026-10-22 |
| 上游依据 | 《tang_db 设计方案》v1.1 §2.5-I-08 / §3.1 / §4.4 / §4.5 / §7 / §11.1 / §12 / §19-P2 / 决策 D2 D11 D12 |
| 约束依据 | `.specify/memory/constitution.md` v1.0.0 |
| 依赖规约 | SPEC-P1 v0.2(基座:链路 + 最小可用 LSM + 崩溃恢复) |
| 关联仓库 | https://github.com/1638540149/tang_db |

> **本规约的作用**:把设计文档里 P2 这一期要做的事,落成可验收、可逐条打勾的条目。
> 实现方(编码 agent)以本规约为唯一施工依据;设计文档是背景,不是施工图。
> 状态已由项目所有者确认为 **Approved**(2026-10-22),可据此施工。

---

## 1. 目标

一句话:**在命令行敲 SQL —— 建表、插数据、带条件查、改、删;元数据存在库自己肚子里(BE 的系统表),BE 每 3 秒向 FE 报一次"我还活着"。**

P2 把 P1 的"能存取 KV"抬到"能当数据库用":

1. **FE 长出 SQL 层** —— 词法/语法分析(手写递归下降,D2)、元数据(catalog)、计划与执行、结果表格化
2. **BE 长出范围扫描** —— KV 之上的有序流式 `Scan`,是 SQL 查询的执行基座
3. **元数据自我托管** —— 库/表/列的定义存进 BE 的系统表,走自己的 KV 与 WAL(D11),不用任何外部数据库
4. **BE 注册与心跳** —— BE 主动 push 自己的状态(D12),FE 据此知道 BE 死没死;这是后面所有调度与监控的地基

**P2 不做事务。** 一行数据的写入是原子的(靠 P1 的 WAL),但"多行一起生效"、"读到一致快照"、"并发写不冲突"都是 P3 的事。这个边界必须写清楚,否则 P3 会来还 P2 的债。

---

## 2. 范围

### 2.1 本期做

**BE(Go)侧**

| # | 事项 |
|---|---|
| 1 | `Scan` RPC:按 key 范围有序流式返回(服务端流式 `RowBatch`) |
| 2 | 引擎内 Scan 迭代器:memtable + 不可变表 + 多层 segment **多路归并**,同一 key 只出一次、取最新 |
| 3 | 心跳状态采集:memtable 大小、不可变表数、segment 数/大小、WAL 积压、Put/Get/Scan 计数、错误计数、最后 fsync 时间、磁盘容量 |
| 4 | BE 作为 gRPC **客户端**:启动注册 + 每 3 秒上报心跳;失败退避重试,**不影响前台读写** |
| 5 | `be_id`:首次启动生成 UUID 并持久化到数据目录,重启不变 |
| 6 | BE 单元测试:Scan 迭代器(跨内存/磁盘、边界、墓碑)、心跳采集与序列化 |

**FE(Java)侧**

| # | 事项 |
|---|---|
| 7 | 词法分析 + 语法分析(手写递归下降,D2)→ AST |
| 8 | 语义分析/绑定:解析表名与列名、类型校验、主键校验 → 逻辑计划 |
| 9 | catalog:库/表/列的定义;**存进 BE 系统表**,FE 启动时加载进内存 |
| 10 | DDL:`CREATE TABLE` / `DROP TABLE` / `TRUNCATE TABLE` / `SHOW TABLES` / `DESC <table>` |
| 11 | DML:`INSERT`(含多行)/ `UPDATE` / `DELETE`,返回影响行数 |
| 12 | 查询:`SELECT` 投影 + `WHERE` 条件 + `LIMIT`;主键条件走点查/范围,其余走全表扫描 + FE 侧过滤 |
| 13 | 值与 key 的编解码:主键保序编码、行值列式序列化(见 §5) |
| 14 | FE 侧 gRPC **服务端**:接收 BE 注册与心跳,维护 BE 存活列表 |
| 15 | CLI:多行 SQL(以 `;` 结束)、结果表格化、`SHOW BACKENDS` |
| 16 | 错误提示:语法错、表/列不存在、类型不符、主键冲突、BE 不可用 —— 全中文,不打印 Java 堆栈 |
| 17 | FE 单元测试:parser、catalog、编解码、执行计划选择、存活判定 |

**工程件**

| # | 事项 |
|---|---|
| 18 | `proto/tangdb.proto` 新增:`Scan`、`TangDbFrontend` 服务(注册 + 心跳)、对应消息 |
| 19 | `config/be.conf` 新增:`fe_host`、`fe_port`、`heartbeat_interval_seconds`;`config/fe.conf` 新增:`heartbeat_listen_port`、`be_offline_cycles` |
| 20 | 脚本:`scripts/p2-test.sh`(端到端功能测试,一条命令跑完出报告)、`scripts/sql/*.sql`(可复现的 SQL 演示) |
| 21 | README 增加一段**能跑通的 SQL 示例**(P2 范围内的语法),照 README 能复现 |

### 2.2 本期明确不做(留给后续期次)

| 不做 | 归属 | 说明 |
|---|---|---|
| **事务 / MVCC / 版本号 / BEGIN-COMMIT** | P3 | 本期 key 里的版本字段**写死 0 占位**(K15),P3 启用 |
| **并发写入的唯一性保证** | P3 | P2 的"主键冲突检测"是**查一次再写**,并发下可能出现两个会话都通过检查 —— 明确不做保证 |
| **二级索引 / 唯一索引 / 索引选择** | P4 | 本期只有主键索引(索引 ID 固定 0) |
| **JOIN / GROUP BY / 聚合 / 子查询 / ORDER BY / COUNT(DISTINCT)** | P5 | 本期 `SELECT` 只有投影 + WHERE + LIMIT |
| **谓词下推 / 列裁剪 / 统计分析** | P5 / P6 | 除了主键条件,过滤一律在 FE 侧做 |
| **表达式与标量函数(`ai_embed`、`l2_distance` 等)** | P7 | 本期投影只接受列名与 `*`,WHERE 只接受常量比较 |
| **列块压缩编码 / 向量化执行** | P5 | BE 只搬数据,不做算子 |
| **compaction 与物理回收** | 后续期次 | 承 P1:segment 只增不合;DROP/TRUNCATE 也不做物理删除(见 K18) |
| **`ALTER TABLE`** | P4 之后 | 设计文档里本就是"可选" |
| **`CREATE DATABASE` / 多库 / 权限** | 不做 | 只有默认库 `default`(K22) |
| **FE 元数据的独立持久化(变更日志 + 快照)** | 不做 | 设计已定:走 BE 系统表(D11)。**代价:FE 启动必须先起 BE** |
| **MySQL 协议 / EXPLAIN / 监控界面** | P8 | 本期只有自带 CLI |

### 2.3 P2 的原子性边界(必须写清楚)

| 操作 | P2 保证 | 不保证 |
|---|---|---|
| 单行 INSERT / UPDATE / DELETE | 这一行写入经 WAL + fsync,强杀后仍在(P1 的保证) | 与其它行的原子性 |
| 多行 INSERT(一条语句多组 VALUES) | 逐行写入,已写部分生效 | 整条语句全生效或全不生效 |
| DDL(CREATE/DROP/TRUNCATE) | 每条元数据记录本身落盘可靠 | 多条元数据记录之间的原子性(崩溃点可能只写了一半) |
| 读 | 读到当前已落盘的最新值 | 一致快照(读的过程中数据可能变) |

> **半套元数据怎么办**:FE 启动加载 catalog 时发现某个表的列定义缺失或没有主键 → **报错停下并指名该表**,不静默忽略、也不自动修补(K26)。P3 有了原子批写之后,这个口子自然关掉。

---

## 3. 用户场景与验收(BDD)

### 场景 1:建表并查看

```
Given  BE 与 FE 都在运行
When   执行 CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32), age INT)
Then   返回成功
And    执行 SHOW TABLES
Then   列表里出现 users
And    执行 DESC users
Then   列出 4 个列及其类型,并标出 id 是主键
```

### 场景 2:插入并点查

```
Given  表 users 已建好
When   执行 INSERT INTO users (id, name, city, age) VALUES (1, 'wangyu', '北京', 26)
Then   返回 "OK, 1 row affected"
And    执行 SELECT * FROM users WHERE id = 1
Then   返回一行,四个列值与插入时一致
```

### 场景 3:条件查询(非主键列)

```
Given  users 里有若干行
When   执行 SELECT name, city FROM users WHERE city = '北京' AND age > 25
Then   只返回满足两个条件的行,且只显示 name 与 city 两列
```

### 场景 4:更新与删除

```
Given  users 里存在 id = 1
When   执行 UPDATE users SET city = '上海' WHERE id = 1
Then   返回 "OK, 1 row affected"
And    执行 SELECT city FROM users WHERE id = 1
Then   返回 上海
When   执行 DELETE FROM users WHERE id = 1
Then   返回 "OK, 1 row affected"
And    执行 SELECT * FROM users WHERE id = 1
Then   返回 0 行(id = 1 已不存在)
```

### 场景 5:主键冲突

```
Given  users 里已有 id = 1
When   执行 INSERT INTO users (id, name) VALUES (1, '其他人')
Then   报错:主键冲突(表 users,主键 1)
And    原数据不被覆盖
```

### 场景 6:错误提示

```
Given  BE 与 FE 都在运行
When   执行 SELECT * FROM no_such_table
Then   报错:表 no_such_table 不存在
When   执行 SELECT nope FROM users
Then   报错:列 nope 不存在(表 users)
When   执行 INSERT INTO users (id, age) VALUES ('abc', 1)
Then   报错:列 id 类型不匹配(期望 BIGINT)
When   执行 CREATE TABLE t (a INT)
Then   报错:必须有一个主键
When   执行 SELEC * FROM users        (拼错的语法)
Then   报错:语法错误 + 出错位置;不打印 Java 堆栈
```

### 场景 7:★ 元数据重启不丢

```
Given  已建好 users 表并插入 10 行
When   强杀 FE 与 BE,再重新启动 BE 与 FE
Then   SHOW TABLES 仍列出 users
And    DESC users 的列定义与重启前一致
And    这 10 行仍能查出来
```

### 场景 8:★ BE 心跳与存活判定

```
Given  BE 与 FE 都在运行,SHOW BACKENDS 显示该 BE 为"可用"
When   强杀 BE(taskkill /F)
And    等待 3 个心跳周期(默认 9 秒)
Then   SHOW BACKENDS 显示该 BE 为"不可用"
And    同时打印它最后一次心跳的时间
When   重新启动 BE
Then   无需手工操作,SHOW BACKENDS 又重新显示为"可用"
```

### 场景 9:★ 跨内存与磁盘的查询

```
Given  memtable 阈值设为较小值(如 1MB,便于测试)
And    已插入足够多的行,使部分数据已刷入 segment
When   对同一张表做全表查询与主键点查
Then   结果与"全部在内存里"时完全一致(不重、不漏、取最新值)
```

### 场景 10:★ 强杀后 SQL 视角的一致性

```
Given  某表已插入 N 行,每行都返回过 "OK, 1 row affected"
When   强杀 BE,重启
And    逐行执行 SELECT 校验这 N 行
Then   N 行全部读回,值与写入时一致,丢失 0 行
```

### 场景 11:BE 不可用时 FE 的表现

```
Given  BE 未启动,FE 已启动
When   执行 SELECT * FROM users
Then   报错:BE 不可用(明确提示地址与最后心跳时间),不挂起、不崩溃、不打印堆栈
```

---

## 4. 功能需求

### 4.1 接口(proto)

- **FR-101** 新增 `Scan`:请求携带 `start_key`、`end_key`、`limit`、`batch_rows`;返回**服务端流式** `RowBatch`。范围语义为 `[start_key, end_key)`,`end_key` 为空表示到末尾。
- **FR-102** `Scan` 返回的 key 必须**升序**,且同一 key 只出现一次(取最新值);跨 memtable / 不可变表 / 多层 segment 的合并结果必须正确。
- **FR-103** `Scan` 必须**流式**返回:不得把命中范围整体读进内存;每批行数受 `batch_rows`(默认 1024)约束。
- **FR-104** `proto/` 里**不得出现表名、列名、SQL**;FE 传给 BE 的永远是 key 范围与原始字节(宪法第一条)。
- **FR-105** `Scan` 的 `limit` 为 0 表示不限;`limit > 0` 时最多返回 limit 行后结束流。
- **FR-106** `Scan` 期间若发生内部错误,必须以带 `StatusCode` 的 `RowBatch` 或 gRPC 错误明确结束,**不得静默截断**(客户端要能区分"正常结束"与"出错结束")。
- **FR-107** 新增 FE 侧服务 `TangDbFrontend`:`RegisterBe`、`ReportHeartbeat`。BE 是这两个 RPC 的客户端。
- **FR-108** 心跳消息必须携带 §4.6 列出的字段;FE 对缺失字段按 0 / 空处理,**不得因字段缺失而拒收心跳**。
- **FR-109** P1 的 `Ping` / `Put` / `Get` 语义与行为保持不变(P2 不做破坏性变更)。

### 4.2 FE 元数据(catalog)

- **FR-110** 表、列的定义(元数据)**存进 BE 的系统表**,用普通 KV 机制实现,不引入任何外部数据库;系统表的 key 布局见 §5.4。
- **FR-111** FE 启动时必须从 BE 加载全部元数据并在内存里建立 catalog 缓存;BE 未就绪或加载失败时,FE 必须**报明确错误**并退出,禁止用空 catalog 继续服务。
- **FR-112** 任何 DDL 变更必须**立即**写入系统表(经 BE 的 Put 落盘),不得只改内存、等退出时再写。
- **FR-113** 表 ID 由 `sys_counters` 里的自增计数器分配,由 FE 分配并写回;表 ID 一经分配**永不复用**(DROP / TRUNCATE 也不例外)。
- **FR-114** 表 ID 空间:1~15 保留给系统表,用户表的表 ID 从 16 起。
- **FR-115** 系统表自身也必须能在 BE 里正常读写(它们就是普通的 KV),P2 不引入任何特殊代码路径。
- **FR-116** FE 进程内 catalog 为单例,读写加锁;DDL 变更后其它会话立即可见(本期不实现会话隔离)。
- **FR-117** 启动加载 catalog 时发现元数据**不完整或非法**(表缺列定义、没有主键、`next_table_id` 非法等)必须**报错停止并指名是哪张表/哪个键**,不得静默忽略,也不得自动修补(K26)。

### 4.3 DDL

- **FR-120** `CREATE TABLE <名> (<列定义>, ...)`,列定义含:列名、类型、`PRIMARY KEY` 标记。
- **FR-121** 类型集合:`INT`、`BIGINT`、`DOUBLE`、`VARCHAR(n)`、`DATE`、`BOOLEAN`。`VARCHAR` 必须给出长度(上限 65535)。
- **FR-122** 必须恰好有一个**单列**主键;主键类型限 `INT` / `BIGINT` / `VARCHAR` / `DATE`(见 K16)。
- **FR-123** 表名、列名在整个库内唯一;重名报错;表名/列名**大小写不敏感**(K21)。
- **FR-124** `DROP TABLE <名>`:把该表在系统表里标记为 `dropped`,数据保留但不参与任何查询;表 ID 不复用。重复 DROP 报"表不存在"。
- **FR-125** `TRUNCATE TABLE <名>`:给该表**换一个新的表 ID**(等价于一张新的空表),旧 ID 的数据不再可见;新 ID 由计数器分配。
- **FR-126** `SHOW TABLES`:列出所有 `active` 状态的表,表格化输出。
- **FR-127** `DESC <表>`:列出列名、类型、是否主键、是否允许 NULL。
- **FR-128** DDL 执行成功/失败都必须有明确的中文结果或错误信息。

### 4.4 DML

- **FR-130** `INSERT INTO <表> (列...) VALUES (...)[, (...)]*`:支持多行;未给出的列写 NULL。
- **FR-131** 写入前必须检查主键是否已存在(点查当前可见数据),存在则报"主键冲突"并**不覆盖**。并发场景下的唯一性不做保证(见 §2.3)。
- **FR-132** `UPDATE <表> SET 列 = 常量[, ...] WHERE <条件>`:本期**只允许改非主键列**;写主键列报错(K19)。
- **FR-133** `DELETE FROM <表> WHERE <条件>`:写入墓碑(§5.3),该行在后续所有查询中不可见。
- **FR-134** 不带 `WHERE` 的 `UPDATE` / `DELETE` 作用于全表,但必须如实返回影响行数。
- **FR-135** 所有 DML 必须返回并显示影响行数(`OK, N rows affected`);0 行也要如实显示。
- **FR-136** 值必须做类型校验与范围校验(`INT` 越界、`VARCHAR` 超长、`DATE` 格式非法 → 报错)。
- **FR-137** 字符串按 UTF-8 处理;`VARCHAR` 的长度按**字符数**还是字节数 → 见 K28(取字节数,与存储一致)。

### 4.5 查询

- **FR-140** `SELECT <列列表 | *> FROM <表> [WHERE <条件>] [LIMIT n]`。
- **FR-141** `WHERE` 支持:比较(`= != <> < <= > >=`)、`AND` / `OR`、括号、`IN (常量列表)`、主键与列的等值与范围条件。
- **FR-142** 主键**等值**条件必须走 BE 的 `Get`(点查),不得全表扫描。
- **FR-143** 主键**范围**条件必须通过 `Scan` 限定范围,不得全表扫描。
- **FR-144** 其余条件一律**全表 `Scan` + FE 侧过滤**;谓词下推是 P5/P6 的事,本期不做(K20)。
- **FR-145** `LIMIT n` 生效于最终结果集;带 WHERE 时必须先过滤再截断。
- **FR-146** 结果必须表格化输出;0 行时明确打印 `(0 rows)`;有行时打印行数。
- **FR-147** NULL 语义:未给出的列值为 NULL;`WHERE` 只保留条件为**真**的行(NULL 比较结果视为假);结果表里 NULL 显示为 `NULL`。
- **FR-148** 类型比较规则:数值类型之间可隐式提升(INT → BIGINT → DOUBLE);字符串按 UTF-8 字节序;`DATE` 按 `YYYY-MM-DD` 解析后按天数比较。
- **FR-149** 投影只接受列名与 `*`(不做表达式、函数、别名;`AS` 别名见 K29)。

### 4.6 BE 注册、心跳与存活判定

- **FR-150** BE 启动并完成恢复后,必须向 FE 注册:`be_id`、`endpoint`、`version`、`start_time`。注册失败不得导致 BE 退出,重试即可。
- **FR-151** 注册成功后每 `heartbeat_interval_seconds`(默认 3 秒)上报一次心跳;间隔来自 `config/be.conf`。
- **FR-152** 心跳必须携带:身份(be_id/endpoint/version/start_time)、存储(data_dir/磁盘总量/可用量)、内存表(memtable 字节数/不可变表数)、磁盘文件(segment 数/总字节/WAL 字节)、服务量(put/get/scan 计数与错误计数)、持久性(最后一次成功 fsync 的时间)。
- **FR-153** 心跳必须跑在独立 goroutine,失败退避重试;**任何心跳失败都不得影响前台读写**,也不得让 BE 退出。
- **FR-154** FE 维护 BE 存活列表:记录 last_seen;超过 `be_offline_cycles`(默认 3)个心跳周期未收到 → 标记"不可用";再次收到心跳 → 自动恢复"可用"。
- **FR-155** `SHOW BACKENDS` 必须显示:be_id、endpoint、version、状态(可用/不可用)、最后心跳时间、memtable 大小、segment 数、put/get 计数。
- **FR-156** BE 不可用时,FE 对 SQL 请求必须报"BE 不可用"(含地址与最后心跳时间),不得挂起。
- **FR-157** `be_id` 首次启动生成(UUID v4)并持久化到数据目录下的 `be.id`;重启后保持不变(否则 FE 会把它当成另一个 BE)。
- **FR-158** FE 的心跳服务监听端口来自 `config/fe.conf` 的 `heartbeat_listen_port`(默认 9091);端口被占用时 FE 启动失败并明确报错。

### 4.7 配置

- **FR-160** `config/be.conf` 新增:`fe_host`(默认 `127.0.0.1`)、`fe_port`(默认 `9091`)、`heartbeat_interval_seconds`(默认 `3`)。
- **FR-161** `config/fe.conf` 新增:`heartbeat_listen_port`(默认 `9091`)、`be_offline_cycles`(默认 `3`)。
- **FR-162** 配置项的读取、校验、优先级沿用 P1 规约 v0.2 FR-024 ~ FR-024d(命令行 > 配置文件 > 内置默认值);新增键同样必须拒绝未知项、拒绝非法取值。

### 4.8 CLI

- **FR-170** 支持**多行 SQL**:语句以 `;` 结束才执行;跨行的语句必须能正确拼接;`;` 出现在字符串字面量里不算结束。
- **FR-171** 支持 `--` 行注释;块注释 `/* */` 不做(K30)。
- **FR-172** CLI 命令:`SHOW TABLES`、`DESC <表>`、`SHOW BACKENDS`、`exit`;其余输入按 SQL 解析。
- **FR-173** 任何错误都只输出中文提示,不打印 Java 堆栈(沿用 P1 FR-030)。
- **FR-174** 结果表格化:`+---+` 分隔线 + 列头 + 行 + 行数统计,列宽按内容自适应。

---

## 5. 数据与文件格式

### 5.1 key 编码(整体布局,FE 侧负责编码)

沿用设计文档 §7.1 的地址空间,本期**只用主键索引**:

```
┌──────────┬────────────┬──────────────────┬────────────────┬──────────────┐
│ 表 ID    │ 索引 ID    │ 索引列值          │ 主键值          │ 版本号       │
│ 4 字节   │ 4 字节     │ 主键列的保序编码   │ (主键索引里为空) │ 8 字节       │
└──────────┴────────────┴──────────────────┴────────────────┴──────────────┘
```

- 表 ID、索引 ID 为**大端 4 字节**;主键索引的索引 ID 固定为 `0`。
- **版本号本期固定写 0**(大端 8 字节)。设计上要求倒序存放,P3 启用 MVCC 时按 `^版本号` 写入,本期占位不参与语义(K15)。
- 索引列值为主键列的**保序编码**(§5.2);主键索引里"主键值"段为空,二级索引(P4)才用它指回主键。

### 5.2 主键值的保序编码(字节序即字典序)

| 类型 | 编码 | 说明 |
|---|---|---|
| `INT` | 4 字节大端,符号位取反 | 负数在前、正数在后,字节序 = 数值序 |
| `BIGINT` | 8 字节大端,符号位取反 | 同上 |
| `DATE` | 4 字节大端(自 1970-01-01 的天数) | 天然保序 |
| `VARCHAR` | UTF-8 原始字节 + `0x00` 终止符 | UTF-8 文本不含 `0x00`,可无歧义分隔 |

> `DOUBLE` / `BOOLEAN` 不允许作为主键(K16)。保序编码的测试必须覆盖:负数、0、正数、跨字节边界值、空串、中文串(按 UTF-8 字节序)。

### 5.3 行值(value)编码

```
行:      [格式版本 1B=0x01][表 ID 4B 小端][列数 2B 小端][NULL 位图 ⌈列数/8⌉ B][列值...]
墓碑:    [格式版本 1B=0x02][表 ID 4B 小端]
```

- **NULL 位图**:1 表示该列为 NULL;列值按表定义顺序依次排列,**NULL 列不占列值空间**。
- 列值编码:`INT` 4B 小端、`BIGINT` 8B 小端、`DOUBLE` 8B IEEE754、`BOOLEAN` 1B(0/1)、`DATE` 4B 小端(天数)、`VARCHAR` 4B 长度 + UTF-8 字节。
- 格式版本用于 P3 演进(加事务/版本字段),**解码时版本不识别必须报错**,不得猜。
- **墓碑**只由 `DELETE` 产生;查询时 FE 把它当作"该行不存在"。物理清理由后续期次的 compaction 负责(§2.2)。

### 5.4 系统表布局(元数据)

系统表就是普通表,表 ID 固定占用 1~15:

| 表 ID | 表名 | 主键(key 里的索引列值) | 列 |
|---|---|---|---|
| 1 | `sys_tables` | `table_id`(4B 大端) | `table_id` BIGINT、`name` VARCHAR、`state` INT(0=active,1=dropped)、`created_at` VARCHAR |
| 2 | `sys_columns` | `table_id`(4B) + `ordinal`(2B 大端) | `table_id` BIGINT、`ordinal` INT、`name` VARCHAR、`type` INT(枚举)、`type_len` INT、`nullable` BOOLEAN、`is_pk` BOOLEAN |
| 4 | `sys_counters` | `name`(VARCHAR 保序编码) | `name` VARCHAR、`value` BIGINT |

- 系统表的 key 与用户表**用同一套布局**,只是表 ID 落在保留区;`sys_columns` 的主键是复合的(由 FE 编码),这是实现细节,不对外暴露。
- `sys_counters` 至少包含 `next_table_id`(初值 16)。
- 系统表在 BE 首次启动时**由 FE 幂等写入**(不存在才创建),保证空库也能起来。

### 5.5 proto 新增片段(示意)

```protobuf
rpc Scan (ScanRequest) returns (stream RowBatch);

message ScanRequest {
  bytes  start_key  = 1;   // 含
  bytes  end_key    = 2;   // 不含;空 = 到末尾
  uint32 limit      = 3;   // 0 = 不限
  uint32 batch_rows = 4;   // 0 = 默认 1024
}

message RowBatch {
  StatusCode    status  = 1;
  string        message = 2;
  repeated Kv   rows    = 3;
}

message Kv { bytes key = 1; bytes value = 2; }

// BE → FE:注册与心跳
service TangDbFrontend {
  rpc RegisterBe     (RegisterBeRequest) returns (RegisterBeResponse);
  rpc ReportHeartbeat(HeartbeatRequest)  returns (HeartbeatResponse);
}
```

`HeartbeatRequest` 字段按 FR-152 列出(身份 / 存储 / 内存表 / 磁盘文件 / 服务量 / 持久性)。**注意方向**:这两个 RPC 的**服务端在 FE**,BE 是客户端。

---

## 6. 关键技术决策(本期默认值)

已按推荐取值,写进规约作为施工默认。**有异议请指出,没异议就照办。**

| # | 决策点 | 取值 | 理由 |
|---|---|---|---|
| K13 | 表 ID 分配 | `sys_counters.next_table_id` 自增,由 FE 分配并写回;永不复用 | 元数据已托付给自己的引擎,不再引外部序列 |
| K14 | 表 ID 空间划分 | 1~15 保留给系统表,用户表从 16 起 | 系统表与用户表用同一套 KV 机制,只靠 ID 区分 |
| K15 | 版本号字段 | 本期固定写 `0`(8B 占位) | 提前把 key 布局钉死,P3 启用 MVCC 时不动编码层 |
| K16 | 主键类型 | 仅 `INT` / `BIGINT` / `VARCHAR` / `DATE` | 这四种能写出严格保序编码;浮点做键语义易出坑 |
| K17 | 主键冲突检测 | 写入前 `Get` 点查当前可见数据 | P2 无事务,只能"查一次再写";并发不保证(§2.3) |
| K18 | DROP / TRUNCATE 的实现 | 标 `dropped` / **换新表 ID**;不扫描删除、不做物理回收 | 扫全表删既慢又不原子;换 ID 是 O(1),物理回收留给 compaction |
| K19 | UPDATE 的列范围 | 只允许改非主键列 | 改主键 = 删 + 插,涉及多行原子性(两处 key),留给 P3 |
| K20 | 过滤与投影位置 | 全部在 FE 侧;只有主键条件走 BE 点查/范围 | 谓词下推要等 P5/P6;BE 本期只提供 Scan 与 Get |
| K21 | 表名/列名大小写 | 不敏感(内部统一转小写) | 贴近 MySQL 习惯,少一类"为什么报不存在"的坑 |
| K22 | 库的概念 | 只有默认库 `default`,不做 `CREATE DATABASE` | 元数据结构保留库字段,但不实现多库,免得范围膨胀 |
| K23 | 心跳节奏 | 间隔 3 秒,连续 3 个周期(9 秒)未收到即判不可用,均可配 | 设计文档 §4.5 的默认值;判死窗口 = 间隔 × 周期数 |
| K24 | FE 心跳服务端口 | 默认 `9091`(可配) | 与 BE 的 9100 明确分开,避免端口混淆 |
| K25 | be_id | 首次启动生成 UUID v4,存于 `<data-dir>/be.id` | 重启身份不变,FE 才能把两次心跳认成同一个 BE |
| K26 | 半套元数据 | FE 启动时发现即**报错停止**并指名表 | 静默忽略= 数据看起来丢了;自动修补= 猜。宁可停下让人看 |
| K27 | catalog 并发 | 进程内单例 + 读写锁;DDL 提交后对所有会话立即可见 | P2 不做会话隔离与快照,越简单越好 |
| K28 | `VARCHAR` 长度口径 | 按 **UTF-8 字节数**校验 | 与底层存储一致,避免"字符数通过、字节数超限"的隐性不一致 |
| K29 | 列别名 `AS` | **支持**(只做输出列名改写,不做表达式) | 成本极低,`SELECT id AS 编号` 这类用法很常见 |
| K30 | 注释语法 | 只支持 `--` 行注释 | 块注释要处理嵌套与多行状态,收益低 |

---

## 7. 验收标准

| 编号 | 验收项 | 判定 |
|---|---|---|
| **SC-101** | DDL 全流程 | 建表 / `SHOW TABLES` / `DESC` / `TRUNCATE` / `DROP` 行为与 §3 场景一致,结果可复现 |
| **SC-102** | DML 全流程 | INSERT(含多行)/ UPDATE / DELETE 成功,影响行数如实;主键冲突被挡且不覆盖 |
| **SC-103** | 主键点查与范围查询 | 与期望值逐行一致;点查路径**走 Get**(日志或用例可证),不是全表扫描 |
| **SC-104** | 条件查询(全表扫描) | 多条件 AND/OR/IN/NULL 组合结果正确 |
| **SC-105** | 元数据重启不丢 | 强杀 FE+BE → 重启 → `SHOW TABLES` / `DESC` 与重启前一致,数据仍可查(场景 7) |
| **SC-106** | 心跳与存活判定 | 强杀 BE 后 9 秒内 `SHOW BACKENDS` 显示不可用;重启 BE 后自动恢复可用(场景 8) |
| **SC-107** | 崩溃一致性(SQL 视角) | 写入 N 行 → 强杀 BE → 重启 → 逐行校验:**丢失 0 行**(场景 10) |
| **SC-108** | 跨内存与磁盘 | memtable 阈值调小触发刷盘后,查询结果与内存态一致(场景 9) |
| **SC-109** | 依赖红线 | `be/go.mod` 除 grpc / protobuf 外无第三方依赖;FE 侧无 SQL 解析类第三方库 |
| **SC-110** | 单元测试 | parser、catalog、key/value 编解码、执行路径选择、Scan 迭代器、存活判定均有测试且全绿 |
| **SC-111** | 一键功能验收 | `scripts/p2-test.sh` 一条命令跑完:起服务 → 跑 `scripts/sql/*.sql` → 比对期望输出 → 出报告 |
| **SC-112** | 参考性能(本机,记录机器配置) | 单行 INSERT P99 < 10ms;10 万行表全表 `SELECT` 扫描 < 2s;主键点查 < 1ms |
| **SC-113** | BE 心跳开销 | 心跳开启前后,写入吞吐下降 < 5%(10 万行量级对比) |

> SC-112 的数字是**判断标准**,不是"达不到就重做":哪一项差得远,说明瓶颈在哪一层。

---

## 8. 边界与异常清单

| 情形 | 期望行为 |
|---|---|
| `SELECT` 的表不存在 / 已 dropped | 报"表 X 不存在" |
| 列不存在 | 报"列 X 不存在(表 Y)" |
| 主键列值为 NULL | 报错(主键必须有值) |
| 主键冲突 | 报"主键冲突",不覆盖原值 |
| `VARCHAR` 超长(按字节) | 报错,指出列名与限长 |
| `INT` 超出范围 | 报错 |
| `DATE` 格式非法 | 报错,提示 `YYYY-MM-DD` |
| 类型不匹配的常量 | 报错,指出列与期望类型 |
| 数值列与字符串比较 | 能隐式转换则转换,否则报类型不匹配 |
| `WHERE` 里出现 NULL | 该行不满足条件(三值逻辑取真) |
| `UPDATE` 试图改主键列 | 报错,提示本期不允许改主键 |
| `UPDATE` / `DELETE` 命中 0 行 | 返回 `OK, 0 rows affected` |
| `INSERT` 多行中有一行主键冲突 | 报错;已写入的行保留(§2.3),错误信息说明"第 k 组" |
| `LIMIT 0` | 返回 0 行 |
| 空表上的查询 | 返回 `(0 rows)`,不报错 |
| SQL 语句未以 `;` 结束就换行 | 继续等待输入(多行语句);`exit` 时可放弃 |
| 空输入 / 只有分号 | 忽略,不报错 |
| BE 未启动时 FE 启动 | FE **启动失败并明确报错**(catalog 必须从 BE 加载,FR-111) |
| BE 运行中挂掉 | 后续 SQL 报"BE 不可用(最后心跳 …)",不挂起;BE 恢复后自动可继续用 |
| 心跳端口被占用 | FE 启动失败,退出码非 0 |
| `be.id` 文件损坏/丢失 | 重新生成一个新 be_id 并在日志里警告(旧 ID 在 FE 侧自然过期) |
| 元数据半套(缺列 / 无主键) | FE 启动失败并指名表(K26) |
| DDL 过程中崩溃 | 下次启动按 FR-117 判定;可能需人工处理,错误信息必须说清是哪张表 |
| 表 ID 计数器被写坏(非数字/小于 16) | FE 启动报错,不自动重置(避免表 ID 复用导致数据错乱,FR-117) |

---

## 9. 交付物清单

| # | 交付物 | 位置 |
|---|---|---|
| 1 | 接口契约(P2 新增部分) | `proto/tangdb.proto` |
| 2 | BE 实现 | `be/internal/{exec,server,store}/`(Scan 迭代器、心跳采集、注册客户端) |
| 3 | FE 实现 | `fe/src/main/java/com/wangyu/tangdb/{parser,catalog,planner,execute,client}/` |
| 4 | 单元测试 | 与各自实现同目录 |
| 5 | 配置 | `config/be.conf`、`config/fe.conf`(新增键) |
| 6 | 脚本 | `scripts/p2-test.sh`、`scripts/sql/*.sql` |
| 7 | 构建与使用说明 | README 的「快速开始」补一段能跑的 SQL 示例 |
| 8 | 验收报告 | `scripts/p2-test.sh` 的真实输出 + 崩溃回归(`scripts/crash-test.sh`)输出 |
| 9 | 《读代码地图·P2》 | 由本规约发起方(我)在实现完成后交付,见 §10 |

---

## 10. 关于代码由谁写(沿用 D9,已拍板)

| 角色 | 负责 |
|---|---|
| 编码 agent | 按本规约实现全部代码与测试,并**真跑**测试、给出真实输出 |
| 我 | 起草与维护本规约、评审实现、**核验产物**(自己跑一遍端到端脚本与崩溃测试,不看它的自述)、交付《读代码地图·P2》 |
| 你 | 审规约、看验收报告、读《读代码地图》 |

**《读代码地图·P2》会讲清**:SQL 从字符串到结果的完整一跳(词法 → 语法 → 绑定 → 计划 → 执行 → 表格化)、catalog 怎么从 BE 系统表长出来、Scan 的多路归并怎么保证不重不漏、心跳这条链路怎么闭环。目标是你能自己讲明白"一条 SELECT 在库里走过哪些地方"。

---

## 11. 待补充(实现过程中发现的规约缺口记这里)

| 编号 | 内容 | 状态 |
|---|---|---|
| **G1** | **P1 FR-028 与 P2 CLI 的冲突**:P2 把 CLI 变成 SQL 控制台后,`put` / `get` 裸命令不再可用(只留 `ping`),而 P1 规约 FR-028 明确要求这四个命令。`scripts/crash-test.sh` 的探活靠 `ping`,所以没炸,但**规约与实现对不上了**。→ 需二选一:① 恢复 `put`/`get` 作为调试命令;② 修订 P1 规约 v0.3,把 CLI 定位改成"SQL 控制台 + ping 探活"。 | 待项目所有者拍板 |
| **G2** | **BE 不会重新注册**:注册成功后若 FE 侧心跳服务消失再回来(如 FE/CLI 进程重启),BE 的心跳无人接收或被拒,`SHOW BACKENDS` 长期为空,只有重启 BE 才能恢复。这与 FR-154"再次收到心跳即自动恢复可用"的意图不符。→ 修法:FE 收到**未知 be_id** 的心跳时做隐式注册(自动登记),或 BE 在心跳连续失败 N 次后重走注册流程。 | 待修(P2 收尾或 P3 开头) |
| **G3** | **FE 心跳端口是硬绑定**:`heartbeat_listen_port` 被占用时 FE 直接启动失败(FR-158 定的行为),后果是**同时只能有一个 FE/CLI 在跑**,任何残留进程都会挡住新的 FE。→ 建议加一个显式开关(如 `heartbeat_enabled = false`,用于纯查询会话/测试),并在 README 里写明"一个 FE 实例一个端口"。 | 待项目所有者拍板 |
| **G4** | **谓词(`NOT` / `IS [NOT] NULL` / `LIKE` / `BETWEEN`)一直没有归属期次**:本规约 §4.5 FR-141 只列了「比较 + `AND`/`OR` + 括号 + `IN`」,§2.2 又只把 `ORDER BY` / 聚合 / 子查询排给 P5,**谓词这一批漏了归属**,导致所有者在实机测试里连撞 10 条(测试记录第 5~14 条)。→ 2026-10-23 由补丁规约 SPEC-P2.1 §6 / K37 **正式定死:全部归 P5**;P5 立规约时必须逐条给出:三值真值表、`IS NULL` 与 `= NULL` 的区别(并在 README 说明"查 NULL 行必须用 `IS NULL`")、`LIKE` 的通配符与转义、`BETWEEN` 两端**都包含**、`IN` 列表含 NULL 时的结果。 | **归属已定(P5 认领)**,登记备查 |
| **G5** | **K22「不做多库」已被所有者拍板覆盖**(2026-10-23,设计文档 **决策 D19**:引入库命名空间)。新语义:`CREATE / DROP DATABASE`、`USE <db>`、限定名 `db.tbl`、`SHOW DATABASES`;默认库 `default` 始终存在;**表名唯一性由"全库唯一"收紧为"库内唯一"**;**表 ID 仍全局唯一**(key 布局、BE、proto 全不变 —— 这是它便宜的原因,只动 FE 的元数据 + 名字解析 + 会话当前库);`DROP DATABASE` 沿用"标 dropped、不物理删"的思路。归属期次 **P3.5**(先于 MySQL 协议),**正面重写 K22 由 P3.5 规约负责**。 | **已定(P3.5 认领)**;K22 待 P3.5 规约重写 |

---

## 12. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1 | 2026-10-22 | 初稿(Draft):P2 范围、11 个 BDD 场景、61 条功能需求、5 节数据与格式、18 项施工默认(K13~K30)、13 条验收标准、23 条边界情形 |
| 0.1a | 2026-10-22 | 状态由项目所有者确认为 **Approved**;确认时明确认可三点:P2 不做事务的原子性边界(半套元数据启动报错,K26/FR-117)、DROP/TRUNCATE 用换表 ID 实现(K18)、本期 SELECT 的能力边界(K16/K19/K20) |
| 0.1b | 2026-10-22 | 实现完成并**通过独立核验**(核验方:规约起草方,不看实现方自述):`bash scripts/p2-test.sh` —— 11 个场景 / 40 条断言全 PASS(含 4 个 ★);`bash scripts/crash-test.sh` P1 回归 —— 1000 条随机 KV 强杀重启**丢失 0 条**;`cd be && go build ./... && go vet ./... && go test ./...` 全绿;`cd fe && mvn.cmd test` 105 个用例全绿。核验中发现 3 个缺口(G1 CLI 命令与 P1 FR-028 冲突、G2 BE 不会重新注册、G3 FE 心跳端口硬绑定),记入 §11 待补充 |
| 0.1c | 2026-10-23 | §11 待补充区**登记 G4**(谓词 `NOT`/`IS [NOT] NULL`/`LIKE`/`BETWEEN` 一直没有归属期次,来源:所有者实机测试记录第 5~14 条),归属由补丁规约 SPEC-P2.1 §6 / K37 定为 **P5**,并要求 P5 逐条给出谓词语义。**本次只登记缺口,FR 与验收标准一字未改**;来源 SPEC-P2.1 §12 的 H1,经项目所有者 2026-10-23 确认回填 |
| 0.1d | 2026-10-23 | §11 待补充区**登记 G5**:**K22「只有默认库 default,不做 CREATE DATABASE」被所有者拍板覆盖** —— 设计文档新增 **决策 D19:引入库命名空间**(`CREATE/DROP DATABASE`、`USE`、限定名 `db.tbl`,表名唯一性改为"库内唯一",**表 ID 仍全局唯一**故 key/BE/proto 零改动);归属 **P3.5**(先于 MySQL 协议),K22 的正面重写由 P3.5 规约负责。**本次只登记,FR 与验收标准一字未改** |
| | | **实现与独立核验记录**见 SPEC-P2 的 v0.1b 条目、后续补丁规约 SPEC-P2.1 与 SPEC-P3 |
