# SPEC-P4 · 索引:主键之外的路(二级索引 / 唯一索引 / 索引选择)

| 项 | 内容 |
|---|---|
| 规约号 | SPEC-P4 |
| 状态 | **Approved**(2026-10-26 所有者批准,可施工) |
| 版本 | 0.1i |
| 日期 | 2026-10-27 |
| 上游依据 | 《tang_db 设计方案》**v1.11** §7.1(编码布局与已知约束 I7)、§11.2(物理实现)、§12(索引体系)、§4.4(元数据族与系统表保留区)、§18(验收与性能口径)、§19-P4 |
| 约束依据 | `.specify/memory/constitution.md` v1.0.0;依赖红线(BE 只允许 grpc / protobuf,FE 不得引第三方 SQL / 索引库);"先日志后数据";"segment 落盘不可变" |
| 上一期 | SPEC-P3.5 v0.1d(Approved,已实现并通过独立核验) |
| 必验不变量 | `specs/invariants.md` **INV-10(索引一致性)** 本期从"未实现"转为"已验";INV-4/5/6/11 不得退化 |

> 本规约是**唯一的施工依据**。与设计文档冲突时以本规约为准,但冲突必须停下来报告,不得自行决断。

---

## 1. 目标

P4 一期做五件事:

1. **二级索引**:非主键列上建索引,等值/范围查询走索引,再**回表**取其它列。
2. **唯一索引**:同一列值只能存在一行;冲突在提交时被挡住,错误码与 MySQL 对齐(`1062`)。
3. **索引选择(起步版 RBO)**:没有统计信息的前提下,按固定规则在"主键点查 / 主键范围 / 索引点查 / 索引范围 / 全表扫"之间选一条路,并**能解释**为什么这么选。
4. **`EXPLAIN`**:一条 SQL 打印它实际走的访问路径与 key 范围,取代"看代码猜"。
5. **图形客户端元数据自省兼容**(2026-10-26 所有者要求并入):`SHOW` 家族补齐 + `information_schema` 变成**可查询的虚拟表**,让 Navicat / DataGrip 的树形导航、建表向导、看 DDL/索引/列信息可用 —— 这些语句**此前没有任何期次认领**(SPEC-P2/P3.5 只写了"不做"、未写归属),按"没在规划里的归 P4"的规则并入本期。

**做完能看到什么**:

```sql
CREATE TABLE orders (id INT PRIMARY KEY, category VARCHAR(32), amount INT);
CREATE INDEX idx_category ON orders (category);
-- 插入 10 万行后
EXPLAIN SELECT * FROM orders WHERE category = '数码';
--   INDEX POINT SCAN idx_category (category = '数码')  → 回表 orders

CREATE UNIQUE INDEX uk_code ON orders (id);
INSERT INTO orders VALUES (1, '数码', 100);
INSERT INTO orders (id, category, amount) VALUES (1, '图书', 200);
--   错误:主键冲突(表 orders,主键 1)          ← 主键约束
CREATE TABLE t (id INT PRIMARY KEY, code VARCHAR(16));
CREATE UNIQUE INDEX uk_code ON t (code);
INSERT INTO t VALUES (1, 'A001');
INSERT INTO t VALUES (2, 'A001');
--   错误:唯一索引 uk_code 冲突(列 code = 'A001',已有主键 1)
```

**核心收益**:唯一约束第一次成为数据库能"挡住"的东西 —— 在 FE 与 BE 的边界不变、BE 一行代码不改的前提下做到(索引是 FE 编码的普通 key,BY 只认 key 范围)。

---

## 2. 范围

### 2.1 本期做

**A 组 · 索引 DDL 与元数据**

| 编号 | 内容 |
|---|---|
| A-1 | `CREATE INDEX <idx> ON <tbl>(<col>)` / `CREATE UNIQUE INDEX <idx> ON <tbl>(<col>)`(单列) |
| A-2 | `DROP INDEX <idx> ON <tbl>`(标 dropped,不物理删数据,与删表同思路) |
| A-3 | 建表内联:`CREATE TABLE t (..., UNIQUE(<col>), INDEX(<col>))` |
| A-4 | 索引元数据持久化:`sys_indexes`(系统表 **ID 5**,按 K70 先领号) |
| A-5 | 索引 ID:每表独立编号,从 **1** 起(0 = 主键),计数器存 `sys_counters` |
| A-6 | **已有数据的回填**:`CREATE INDEX` 在非空表上必须扫全表回填索引记录,分批提交,失败可续 |

**B 组 · 写入路径的索引维护**

| 编号 | 内容 |
|---|---|
| B-1 | `INSERT`:主键记录 + 每条受影响索引记录 **同一个批记录**(P3 的原子批) |
| B-2 | `UPDATE`:改到索引列 → 旧索引项写墓碑 + 新索引项写入;未改索引列 → 不动索引 |
| B-3 | `DELETE`:主键写墓碑 + 每条索引项写墓碑 |
| B-4 | 唯一索引:`INSERT` / 改索引列时**先查重**(快照下可见),重复即拒(`1062`),且**整批不写** |
| B-5 | 索引记录与主键记录**同一 `commit_ts`**(同一次提交、同一版本) |

**C 组 · 读路径与索引选择**

| 编号 | 内容 |
|---|---|
| C-1 | 索引等值(`col = v`)→ 索引前缀扫描 + 逐行回表 |
| C-2 | 索引范围(`col > / >= / < / <= v`)→ 索引区间扫描(用户键级边界)+ 逐行回表 |
| C-3 | 回表:按**同一快照**点查主键,墓碑跳过、**二次校验**(见 §5.5) |
| C-4 | 索引选择的固定规则(K82),与 `EXPLAIN` 输出**必须一致**(不许"说一套走一套") |
| C-5 | 没有可用索引 → 全表扫(与 P3 行为完全一致) |

**D 组 · `EXPLAIN`**

| 编号 | 内容 |
|---|---|
| D-1 | `EXPLAIN SELECT / UPDATE / DELETE`(本期只解释这三类) |
| D-2 | 输出:访问路径 + 用到的索引 + 该路径的**用户键范围**(十六进制摘要)+ 残余过滤条件 |
| D-3 | 必须如实反映实际执行的路径(与 C-4 同源实现,不允许另写一份推算逻辑) |

**E 组 · 一致性与验收**

| 编号 | 内容 |
|---|---|
| E-1 | 一致性对账能力:索引路径结果 与 全表扫结果 **逐行对比**(INV-10) |
| E-2 | 崩溃一致性:写入中途 / 回填中途强杀,重启后索引与数据仍一致 |
| E-3 | `scripts/tests/p4-test.sh` 一键验收(SC-319 ~ SC-34x),含 P2 / P3 / P3.5 回归 |

**F 组 · 图形客户端元数据自省兼容(2026-10-26 并入)**

| 编号 | 内容 |
|---|---|
| F-1 | `SHOW` 家族补齐:`FULL TABLES [FROM db] [LIKE/WHERE]`、`TABLE STATUS`、`CREATE TABLE`、`CREATE DATABASE`、`COLUMNS` / `FULL COLUMNS`、`INDEX` / `KEYS`、`ENGINES`、`COLLATION`、`CHARSET`、`GRANTS`、`TRIGGERS` / `EVENTS` / `ROUTINES`(空集)、`VARIABLES` / `STATUS`(返回常用真值)、`WARNINGS` / `ERRORS`、`FULL PROCESSLIST` |
| F-2 | `information_schema` 从"整张视图原样返回"升级为**可查询虚拟表**:投影裁剪生效、`WHERE` 过滤生效、`LIMIT` 生效(是否支持 `ORDER BY` / `COUNT(*)` 见 I20) |
| F-3 | 虚拟表清单:`SCHEMATA` / `TABLES` / `COLUMNS` / `STATISTICS` / `KEY_COLUMN_USAGE` / `TABLE_CONSTRAINTS` / `REFERENTIAL_CONSTRAINTS` / `VIEWS` / `ROUTINES` / `TRIGGERS` / `EVENTS` / `PROCESSLIST` / `CHARACTER_SETS` / `COLLATIONS` / `ENGINES` / `PARTITIONS` / `USER_PRIVILEGES` —— 有数据的给真实数据,没有的给**列定义正确的空结果集**(不报错、不断连接) |
| F-4 | `KILL [CONNECTION] <id>`:断开指定会话(等于该会话的 `COM_QUIT` 语义);`id` 不存在报明确错误 |
| F-5 | 客户端连接期自检语句全覆盖:DataGrip / Navicat / JDBC 连上来发的每条元数据语句都必须**有合理响应**,不得 1064 |
| F-6 | 明确验收:Navicat 与 DataGrip **两个客户端**都完成"连上 → 看到库表树 → 看表结构/DDL/索引 → 建表 → 改数据 → 跑 SELECT"全流程 |

**G 组 · 图形客户端分页与排序补齐(2026-10-26 实机反馈并入,所有者拍板"补")**

| 编号 | 内容 |
|---|---|
| G-1 | `LIMIT <offset>, <row_count>` 与 `LIMIT <row_count> OFFSET <offset>`(SELECT):客户端翻页刚需,现在是 1064 |
| G-2 | `information_schema` 虚拟表的 `ORDER BY` 放开到**多列**(每列可 `ASC`/`DESC`);Navicat 的树查询是两列排序 |
| G-3 | 两者都**不改变**既有边界:用户表上的 `ORDER BY` / 聚合仍明确报"不支持(归 P5)" |

**H 组 · 客户端自检语句补齐(2026-10-27 实机反馈并入,所有者拍板“补”)**

| 编号 | 内容 |
|---|---|
| H-1 | `SELECT COUNT(*) AS <alias> FROM information_schema.<t> [WHERE ...]`:接受聚合别名,别名即结果列名。现在 Navicat 连接期的 `SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'` 报 `1235` |
| H-2 | 一条 `COM_QUERY` 内的**多条语句**(`;` 分隔)按序执行;切分须感知单引号 / 双引号 / 反引号 / 注释,不切字符串里的分号。现在 Navicat 连接期把 3 条语句塞进一个包,整批报 `1235` |
| H-3 | 多结果集口径:客户端握手声明 `CLIENT_MULTI_STATEMENTS` 时逐条回结果集(非最后一条带 `SERVER_MORE_RESULTS_EXISTS`);未声明时**只回最后一条**的结果(前面的照常执行、结果丢弃)。**能力位仍不主动置**(FR-434 不变) |
| H-4 | 批量执行中某条失败:**立即停止**,只回该条的错误包,后续语句不执行,连接保持;不得回半套结果集 |
| H-5 | 不改既有边界:用户表上的 `ORDER BY` / 聚合 / `COUNT(*)`(无论带不带别名)仍明确报“不支持(归 P5)” |
| H-6 | `SHOW PROCEDURE STATUS` / `SHOW FUNCTION STATUS [LIKE '<pat>'] [WHERE ...]`(以及 `SHOW FUNC STATUS`):返回**列定义正确的空集**,不得 1235。Navicat 打开库时会按库逐个发这条(实测 `SHOW PROCEDURE STATUS WHERE Db = 'def'`) |
| H-7 | 连接期探针与 SHOW 变体补齐(客户端盘点出的剩余本期缺口):① 无 `FROM` 的**字面量投影** `SELECT 1` / `SELECT 1, 'x'`;② `SHOW COUNT(*) WARNINGS` / `SHOW COUNT(*) ERRORS`;③ `SHOW VARIABLES` / `SHOW STATUS` / `SHOW INDEX` 支持 `WHERE <cond>` |

### 2.2 本期明确不做(及归属)

| 不做 | 归属 / 理由 |
|---|---|
| **复合索引**(多列) | **P6**。多列要解决"列序 + 前缀可用性判断 + 选择率估算",与 CBO 一起做才不返工。本期 `CREATE INDEX` 收**单列** |
| **覆盖索引**(只读索引不回表) | P6。需要判断投影列是否全在索引里,属计划层优化 |
| `ALTER TABLE ... ADD INDEX` | **P4 之后**。`ALTER` 家族整体未做,不在这里开第一个口子 |
| 索引统计信息(基数 / 直方图) | **P6**。本期索引选择是 RBO,不读统计 |
| `information_schema` 上的 **JOIN / 子查询 / 多列聚合** | **P5**(通用查询能力一起做)。本期虚拟表支持**单表**投影 + `WHERE` + `LIMIT` + `ORDER BY`(单列)+ `COUNT(*)`(I20 拍板 A);除此之外的查询形态明确报不支持 |
| **`ALTER TABLE`(加列 / 改列 / 删列 / 改约束)** | **P4 之后**(SPEC-P2 §2.2、SPEC-P2.1 §2.2/§6、SPEC-P3 §2.2 三处都写"P4 之后")。因此 Navicat 的"设计表"(改结构)本期**明确报不支持**;建表 / 删表 / 改数据 / 加索引都可用。要不要提前见 **I21** |
| `SHOW` 的全量变量族(`GLOBAL` / `SESSION` 逐项)、`performance_schema` | 不做:`SHOW VARIABLES` / `SHOW STATUS` 只返回**常用变量真值**(字符集、版本、`lower_case_table_names`、`sql_mode` 等),其余返回空集;`performance_schema` 报明确不支持(不是本期目标) |
| 视图 / 存储过程 / 触发器 / 事件的**实际能力** | 不做。`information_schema.VIEWS/ROUTINES/TRIGGERS/EVENTS` 给**列定义正确的空集**(客户端据此知道"没有",不是"不支持") |
| 表达式索引 / 路径索引 / 空间索引 | P11 之后 / P12(§12 演进登记) |
| 倒排 / 全文索引 | **P10** |
| 向量列上的索引(HNSW) | **P7** |
| `DROP INDEX` 的物理回收 | **P5**。本期只标 dropped,数据留给 compaction |
| 唯一约束的 `NULL` 语义扩展(如 `NULLS NOT DISTINCT`) | 不做,见 K79 |
| 谓词 `NOT` / `IS [NOT] NULL` / `LIKE` / `BETWEEN` | **P5**。所以本期索引扫描只覆盖 `= / > / >= / < / <=` 与 `AND` / `OR` |
| 无 `FROM` 的**表达式 / 函数**投影(`SELECT 1+1`、`SELECT now()`、`SELECT version(), database()`) | **P5**(与通用表达式 / 函数一起做)。**字面量列表**(`SELECT 1`)本期已支持,见 FR-560 |
| `ORDER BY` 走索引(免排序) | **P5**(本期没有 `ORDER BY`) |
| 索引选择率估算与代价模型 | **P6** |
| 索引页 / 独立索引文件、独立 B+ 树 | 不做,永远不做:索引就是**同一引擎里的有序 key**(§7.1),不另建结构 |
| 并行回填 / 在线建索引(不阻塞写) | 不做。回填期间索引 `BUILDING` 状态不可用,查询走全表扫(K88) |

### 2.3 保证 / 不保证

**保证**

- 任意时刻:**主键记录、二级索引记录、唯一索引记录,要么全在、要么全不在**(同一批记录,同一 `commit_ts`)。
- 索引存在与否**绝不改变查询结果**,只改变访问路径(§12 三条共同约束)。
- 唯一索引在**已提交数据**上绝不出现重复值,并发插入同一值只有一个成功。
- 索引记录同样遵守 MVCC:快照读看到的是"该快照可见的索引项",与主键行一致。
- 崩溃后重启:索引从系统表重建元数据、从已有 data 文件读索引项,不需要重建索引。

**不保证**

- 索引扫描的**结果顺序**不承诺按索引列排序输出(本期没有 `ORDER BY`;索引天然有序但不在 SQL 层承诺)。
- 索引项不即时回收:改了索引列后会留下旧索引项的墓碑,直到 P5 的 compaction 才消失。
- 回填期间(`BUILDING`)索引不参与查询;查询会退化为全表扫(**结果仍正确**)。
- 索引列上的 `NULL` 行不入索引(K79),所以走索引路径查不到 `NULL` 行 —— 本期 `IS NULL` 谓词也不支持,语义闭合。
- 不做索引大小的容量管理(写放大只记录不设门限,P5 起生效)。

---

## 3. 用户场景与验收(BDD)

### 场景 1 · 二级索引的读写闭环

- **给** 表 `orders(id INT PK, category VARCHAR(32), amount INT)`,10 万行,`CREATE INDEX idx_category ON orders(category)` 已建好
- **当** `SELECT * FROM orders WHERE category = '数码'`
- **那么** 结果与全表扫逐行一致(顺序不作承诺);`EXPLAIN` 显示走 `idx_category`
- **验收方式**:脚本对拍两条路径的结果集

### 场景 2 · 索引列被 UPDATE

- **给** 同上的表与索引,某行 `category = '数码'`
- **当** `UPDATE orders SET category = '图书' WHERE id = 7`
- **那么** 旧值 `'数码'` 的索引项失效(墓碑)、新值 `'图书'` 可查到;主键行 `category` 也变了
- **反例(必须不出现)**:按旧值还能查到该行,或按新值查不到

### 场景 3 · ★ 唯一索引挡住重复(单会话)

- **给** `CREATE UNIQUE INDEX uk_code ON t(code)`,`('A001')` 已存在
- **当** 插入第二行 `code = 'A001'`
- **那么** 报 `1062`,错误信息带**索引名、列名、值、已有主键**;表里仍只有一行
- **验收方式**:断言错误码 + 行数 + 值

### 场景 4 · ★ 唯一索引挡住重复(并发两会话)

- **给** 两个会话同时插入**不同主键、相同唯一列值**
- **当** 两者都 `COMMIT`
- **那么** 恰好一个成功,另一个报 `1062`(不是 `1213`,见 K80);表里只有一行
- **反例**:两个都成功(唯一约束被并发击穿)

### 场景 5 · ★ 崩溃:索引与数据同批

- **给** 一条 INSERT 影响主键 + 1 个二级索引 + 1 个唯一索引(共 3 条用户键)
- **当** 在 `after_fsync` / `after_memtable` 强杀后重启
- **那么** 三条要么全可见、要么全不可见;不允许"主键在、索引缺"
- **验收方式**:重启后按索引路径与主键路径各查一次,结果集逐行对拍

### 场景 6 · ★ 崩溃:回填中途强杀

- **给** 10 万行的表,正在 `CREATE INDEX`
- **当** 回填到中途强杀,重启
- **那么** 索引状态为 **`BUILDING`**(不参与查询);重新执行同一条 `CREATE INDEX` 能**续做完成**并转 `ACTIVE`;期间查询走全表扫且结果正确
- **反例**:重启后把半成品索引当 `ACTIVE` 用 → 查出漏行

### 场景 7 · 索引选择与 `EXPLAIN` 一致

- **给** 表上有一个二级索引 `idx_category`
- **当** 依次 `EXPLAIN` 五种语句:主键等值、主键范围、索引等值、索引范围、无可用条件
- **那么** 分别输出 `PRIMARY POINT` / `PRIMARY RANGE` / `INDEX POINT` / `INDEX RANGE` / `TABLE SCAN`,并且**实际执行确实走该路径**(脚本交叉验证)
- **验收方式**:`EXPLAIN` 输出与实际扫描量对拍(走索引时扫描的用户键数应显著小于行数)

### 场景 8 · `DROP INDEX` 之后

- **给** 表上有 `idx_category`
- **当** `DROP INDEX idx_category ON orders`
- **那么** 查询不再走该索引(`EXPLAIN` 显示 `TABLE SCAN`),结果不变;再 `CREATE INDEX` 同名可以重建
- **口径**:索引项数据不物理删(归 P5),但**元数据 dropped 后不可见**

### 场景 9 · 老数据目录升级

- **给** 一个 **P3.5 期间产生**的数据目录(有库有表有数据,没有 `sys_indexes`)
- **当** 用 P4 的 `fed` 启动
- **那么** 启动时**幂等创建 `sys_indexes`**;所有老表可用、数据一条不丢、所有老表"无索引";`format_version` 不变、key 布局不变
- **反例**:启动报"元数据非法",或把老表当损坏表重建

### 场景 10 · 索引在事务里的可见性

- **给** 两行数据 `category='数码'`,其中一个事务改了其中一行的 `category`
- **当** 该事务未提交时,另一个会话按索引查 `'数码'`
- **那么** 看到的是**旧的两行**(快照隔离);提交后新语句看到一行 `'数码'` + 一行新值

### 场景 11 · 一致性对账(INV-10)

- **给** 随机 5000 次 INSERT / UPDATE(含改索引列)/ DELETE
- **当** 对每一行的每条索引:按索引路径查出的主键集合 与 全表扫算出的主键集合 对比
- **那么** 完全一致(0 差异)
- **验收方式**:`p4-test.sh` 的专门一节,输出差异计数必须为 0

---

## 4. 功能需求

> 编号从 **FR-456** 起(SPEC-P3.5 用到 FR-455)。**FR 是验收核对清单**,每条都必须可执行、可观测。

### 4.1 索引 DDL(FR-456 ~ FR-472)

| 编号 | 需求 |
|---|---|
| FR-456 | `CREATE INDEX <idx> ON <tbl>(<col>)`:表/列必须存在且列类型属于可索引类型(INT / BIGINT / VARCHAR / DATE / BOOLEAN),否则报错 |
| FR-457 | `CREATE UNIQUE INDEX <idx> ON <tbl>(<col>)`:同上,且建索引时**先校验现有数据无重复**,有重复则拒绝并指出重复值与前两个主键 |
| FR-458 | 索引名**表内唯一**(K81),重名报 `1061` 类错误且信息可读 |
| FR-459 | 索引列**不得是主键列**(主键已有索引,再建即拒绝),不得对同一列重复建索引 |
| FR-460 | 索引名规则与表名一致(支持反引号、长度 ≤64、大小写不敏感统一小写) |
| FR-461 | `DROP INDEX <idx> ON <tbl>`:索引不存在报错;成功后可同名重建 |
| FR-462 | 建表内联:`CREATE TABLE t (..., UNIQUE(col), INDEX(col))` 等价于建表 + 建索引,**且与建表同一个事务/批记录**(要么表和索引都在,要么都不在) |
| FR-463 | 内联索引不写名字时自动命名:`<表>_<列>_idx` / `<表>_<列>_uk`(重名自动加序号) |
| FR-464 | 索引 ID 由 `sys_counters` 的 `next_index_id:<table_id>` 分配,每表从 1 起,单调递增,**不复用**(DROP 后也不回收) |
| FR-465 | 索引元数据写 `sys_indexes`(ID 5),与数据 DDL **同一批记录**原子生效 |
| FR-466 | 索引状态机:`BUILDING`(回填中,不可用)→ `ACTIVE`(可用)→ `DROPPED`(不可见);状态变更同样走批记录 |
| FR-467 | 非空表的 `CREATE INDEX` 必须回填:分批(K84,默认 1000 行/批)独立提交,每批都写索引记录 + 推进游标 |
| FR-468 | 回填游标必须**持久化**(否则中途强杀无法续做):存 `sys_indexes.build_cursor`(最后一个已回填的主键编码,十六进制) |
| FR-469 | 回填失败(含唯一校验冲突)时索引转 `DROPPED` 并报出原因;**不得**留下 `ACTIVE` 的半成品索引 |
| FR-470 | 回填成功后索引转 `ACTIVE`,转状态的那一条记录**最后写**(ACTIVE 是不可逆的对外承诺) |
| FR-471 | `SHOW INDEXES [FROM <tbl>]` 输出:索引名 / 列 / 是否唯一 / 状态 / 索引 ID / 已回填行数或游标 |
| FR-472 | `DESC <tbl>` 保持原样(不列索引);索引信息只走 `SHOW INDEXES` 与 `SHOW CREATE TABLE`(后者本期不做,见 §2.2) |

### 4.2 元数据与持久化(FR-473 ~ FR-482)

| 编号 | 需求 |
|---|---|
| FR-473 | `sys_indexes` 按 K70 在系统表登记表领号 **5**,并写入 `SystemTables.REGISTERED_IDS`;1/2/3/4/5 之外才是用户表 |
| FR-474 | `sys_indexes` 在首次启动时**幂等创建**(与 `sys_databases` 同样方式);老目录升级不需要数据迁移 |
| FR-475 | 启动时从 `sys_indexes` 重建 catalog 的索引缓存;`BUILDING` 的索引恢复后**仍是 `BUILDING`**,不自动重跑 |
| FR-476 | 元数据校验(K26 沿用):索引指向不存在的表 / 索引列在 `sys_columns` 里找不到 / 同一表内索引 ID 重复 → 启动**报错并指名对象**,不静默忽略 |
| FR-477 | 索引定义变更(建 / 删 / 状态流转)与数据 DDL 一样走"一次批写",中途崩溃按 P3 的原子性语义处理 |
| FR-478 | `MANIFEST.format_version` **不变**;key 布局不变(BE 与磁盘格式零改动) |
| FR-479 | `sys_indexes` 的 key:`table_id(4B BE) + index_id(4B BE)`(与其它系统表一样由 FE 编码) |
| FR-480 | 表被 `DROP`(标 dropped)时,其索引一并不可见;**不**逐个写墓碑 |
| FR-481 | `TRUNCATE` 换表 ID 后,老表 ID 的索引随老表一起不可见;新表 ID 从 `next_index_id` 重新起名 |
| FR-482 | 索引缓存与 `sys_indexes` 不一致时,以系统表为权威(catalog 重载即修复) |

### 4.3 写入路径的索引维护(FR-483 ~ FR-497)

| 编号 | 需求 |
|---|---|
| FR-483 | `INSERT` 时,主键记录 + 每条 `ACTIVE` 索引的记录,写进**同一个批记录**(P3 的 `CommitTxn` 路径),同一 `commit_ts` |
| FR-484 | `INSERT` 唯一索引列已存在(快照下可见且非墓碑)→ 整批不写、不取号,报 `1062`(K80) |
| FR-485 | `UPDATE` 改了索引列 → 旧索引项写墓碑、新索引项写入,与主键更新同批 |
| FR-486 | `UPDATE` 未改索引列 → **不产生索引写入**(避免无意义写放大) |
| FR-487 | `UPDATE` 把唯一列改成已存在的值 → 同 FR-484 的拒绝语义 |
| FR-488 | `DELETE` → 主键墓碑 + 每条索引项墓碑,同批 |
| FR-489 | 一个事务里同一行被多次改(先删后插等)时,索引只落**最终版本**(与 P3 的"最后一次操作胜出"一致) |
| FR-490 | 一个事务写多行时,索引记录与行记录**按用户键去重**后一次性提交(同键同批只留最后一次) |
| FR-491 | `DROPPED` 状态的索引**不参与**写入维护;**`ACTIVE` 与 `BUILDING` 都要维护**(I16 拍板 A:回填期间的新写入也进索引,回填只负责补历史数据) |
| FR-492 | 索引维护不得改变写路径顺序:冲突检测 → WAL 追加 → fsync → memtable → 回 OK |
| FR-493 | 索引写入失败(理论上只有超限)→ 整批失败、无副作用;`max_txn_write_bytes` 超限的错误信息要说明"含索引写入" |
| FR-494 | 一次 INSERT 的批记录大小 = 行记录 + Σ索引记录,必须给出上限控制(与 FR-493 同一开关) |
| FR-495 | 事务内读己之写:事务内按索引查询必须能查到自己刚写、尚未提交的行(P3 的本地写集叠加) |
| FR-496 | 事务内冲突:两个事务改同一行的同一索引列 → 由 P3 写写冲突拦截(不被索引逻辑掩盖) |
| FR-497 | 索引列类型与索引项编码必须由同一处代码产出(不允许读路径与写路径各编一套) |

### 4.4 读路径与索引选择(FR-498 ~ FR-512)

| 编号 | 需求 |
|---|---|
| FR-498 | 索引等值扫描:`col = 常量` → 扫 `[前缀][列值]` 的用户键区间,逐行回表 |
| FR-499 | 索引范围扫描:`col > / >= / < / <= 常量` → 按用户键级上下界扫(左闭右开,边界由 KeyCodec 统一产出) |
| FR-500 | 回表按**同一快照**:索引项与主键行必须属于同一读视图(事务内用事务快照,事务外用最新已提交) |
| FR-501 | 二次校验:回表得到的行,其索引列值必须等于索引项里编码的值;不等则**跳过**(过期索引项),不得报错 |
| FR-502 | 回表遇到墓碑(行已删)→ 跳过 |
| FR-503 | 索引扫描 + 残余过滤:`WHERE` 里除索引条件外还有其它条件时,索引路径只负责定位,其余条件照旧在 FE 过滤(结果必须与全表扫一致) |
| FR-504 | 索引选择规则(RBO,K82):① 有主键等值 → 主键点查;② 有主键范围 → 主键范围扫;③ 否则若有**单列等值**命中 `ACTIVE` 索引 → 索引点查;④ 否则若有**单列范围**命中 → 索引范围扫;⑤ 否则全表扫 |
| FR-505 | 多个索引同时可用时,按索引 ID 升序取第一个(本期无代价模型,K82 明确写死,避免"看起来随机") |
| FR-506 | `OR` 条件下不做索引合并(任何分支不满足即退化为全表扫),但**结果必须正确** |
| FR-507 | 索引路径同样支持 `LIMIT`(达到 limit 立即停止扫描) |
| FR-508 | `UPDATE` / `DELETE` 的 `WHERE` 同样可用索引定位受影响行(与 SELECT 同一套选择逻辑) |
| FR-509 | 索引不可用(`BUILDING` / `DROPPED`)时**必须**退化为全表扫,不得报错 |
| FR-510 | 索引扫描的读路径不得绕过 MVCC:必须在 FE 的读视图(`ReadView`)上取索引项,不允许直接读 BE 的最新版本 |
| FR-511 | 走索引路径的查询必须能处理"索引项存在但主键行快照不可见"的情形(跳过,不报错) |
| FR-512 | 索引扫描的进度与统计(扫描了多少索引项、回表多少次)要能写进日志/`EXPLAIN` 摘要,便于对比"到底快了多少" |

### 4.5 `EXPLAIN`(FR-513 ~ FR-518)

| 编号 | 需求 |
|---|---|
| FR-513 | 语法:`EXPLAIN <SELECT\|UPDATE\|DELETE ...>`,输出**单列结果集**(列名 `plan`),每行一段说明 |
| FR-514 | 输出必须包含:访问路径(`PRIMARY POINT` / `PRIMARY RANGE` / `INDEX POINT` / `INDEX RANGE` / `TABLE SCAN`)、表名、索引名与列(如走索引) |
| FR-515 | 输出必须包含 key 范围的可读摘要(用户键级上下界的十六进制前缀,太长则截断并标注) |
| FR-516 | 输出必须包含残余过滤条件(索引条件之外的 `WHERE` 部分) |
| FR-517 | `EXPLAIN` 与实际执行**共用同一个计划构造入口**,禁止"解释一套、执行一套" |
| FR-518 | `EXPLAIN` 不执行写入;对 `INSERT`/DDL/事务语句明确报"不支持解释该语句" |

### 4.6 一致性与验收(FR-519 ~ FR-526)

| 编号 | 需求 |
|---|---|
| FR-519 | 提供一致性对账能力:对指定表,逐条索引比对"索引路径主键集合"与"全表扫推算的主键集合",输出**差异计数与样例** |
| FR-520 | 对账必须覆盖:改索引列、删行、反复改同一行、事务回滚后、崩溃重启后 |
| FR-521 | 回调/回填路径与正常写入路径的**编码必须一致**(同一处代码产出索引 key) |
| FR-522 | `scripts/tests/p4-test.sh` 一键跑完 SC-319 ~ SC-33x,报告写 `tmp/p4-test/report.txt` |
| FR-523 | `p4-test.sh` 必须内嵌 P2 / P3 / P3.5 回归(`scripts/tests/p2-test.sh`、`p3-test.sh`、`p35-test.sh`) |
| FR-524 | 新增 `scripts/sql/41~4x` 可复现演示脚本(建索引 / 改索引列 / 唯一冲突 / 回填 / EXPLAIN) |
| FR-525 | 性能口径:同一查询在"全表扫 vs 索引"两条路上的耗时与扫描行数都要记录(报数同报条件,§18 模板) |
| FR-526 | 写放大口径:索引写入量单列统计(行记录字节 vs 索引记录字节),P4 只记录不设门限 |

### 4.7 图形客户端元数据自省(FR-527 ~ FR-548)

| 编号 | 需求 |
|---|---|
| FR-527 | `SHOW TABLES` / `SHOW FULL TABLES [FROM <db>] [LIKE '<pat>'] [WHERE <cond>]`;`FULL` 版必须带 `Table_type` 列(值 `BASE TABLE`) |
| FR-528 | `SHOW TABLE STATUS [FROM <db>] [LIKE '<pat>']`:列齐全(Name / Engine / Version / Row_format / Rows / Avg_row_length / Data_length / Max_data_length / Index_length / Data_free / Auto_increment / Create_time / Update_time / Check_time / Collation / Checksum / Create_options / Comment);未知值给 `NULL`(K95),**不得为此扫全表** |
| FR-529 | `SHOW CREATE TABLE <t>`:输出可执行的重建 DDL(列 + 类型 + `NOT NULL` + 主键 + 索引),必须能被本项目 parser **原样吃回去**(round-trip,K96) |
| FR-530 | `SHOW CREATE DATABASE <db>` |
| FR-531 | `SHOW COLUMNS FROM <t> [FROM <db>]` / `SHOW FULL COLUMNS FROM <t>`:列 Field / Type / Collation / Null / Key / Default / Extra / Privileges / Comment |
| FR-532 | `SHOW INDEX FROM <t>` / `SHOW KEYS FROM <t>`:列 Table / Non_unique / Key_name / Seq_in_index / Column_name / Collation / Cardinality / Sub_part / Packed / Null / Index_type / Comment / Index_comment;主键用 `Key_name = PRIMARY` |
| FR-533 | `SHOW ENGINES`(单行,`Engine` 固定报 `InnoDB`,K93)、`SHOW COLLATION` / `SHOW CHARACTER SET`(返回 `utf8mb4` / `utf8mb4_general_ci` 真行,不再返回空集) |
| FR-534 | `SHOW GRANTS`(返回一行 `GRANT ALL PRIVILEGES ON *.* TO '<user>'@'%'`,本期无权限体系)、`SHOW WARNINGS` / `SHOW ERRORS`(空集)、`SHOW TRIGGERS` / `SHOW EVENTS` / `SHOW ROUTINES`(列正确的空集) |
| FR-535 | `SHOW VARIABLES [LIKE '<pat>']` / `SHOW STATUS [LIKE '<pat>']`:返回**常用变量真值**(版本、字符集、`lower_case_table_names`、`sql_mode`、`max_allowed_packet`、`autocommit`、`transaction_isolation` 等),其余给 `NULL` 或空集,**不得 1064** |
| FR-536 | `SHOW FULL PROCESSLIST` 等价 `SHOW PROCESSLIST`(列再带 `Info` / `Progress`) |
| FR-537 | `information_schema` 虚拟表必须支持**投影裁剪**(只算被查询的列)、**`WHERE` 过滤**(复用现有谓词引擎)、**`LIMIT`**、**`ORDER BY`(单列,升/降序)**、**`COUNT(*)`**(I20 拍板 A,仅限虚拟表,见 K98) |
| FR-538 | 虚拟表清单(17 张):`SCHEMATA` / `TABLES` / `COLUMNS` / `STATISTICS` / `KEY_COLUMN_USAGE` / `TABLE_CONSTRAINTS` / `REFERENTIAL_CONSTRAINTS` / `VIEWS` / `ROUTINES` / `TRIGGERS` / `EVENTS` / `PROCESSLIST` / `CHARACTER_SETS` / `COLLATIONS` / `ENGINES` / `PARTITIONS` / `USER_PRIVILEGES`;有数据的给真实数据,无数据的给**列定义正确的空集** |
| FR-539 | `STATISTICS` / `KEY_COLUMN_USAGE` / `TABLE_CONSTRAINTS` 的数据来自 P4 的索引元数据(依赖 A 组);没有索引的表在这些表里查不到行(不是报错) |
| FR-540 | 虚拟表查询**纯内存**,不得访问 BE;单次查询代价与"表数 × 列数"线性相关,不得扫数据 |
| FR-541 | `KILL [CONNECTION] <id>`:断开指定会话(等同该会话的 `COM_QUIT`,未提交事务回滚);`id` 不存在报明确错误 |
| FR-542 | 未在清单内的 `SHOW` 变体 / `information_schema` 表名:返回**明确错误**(`1235` 不支持 / `1146` 表不存在)且**不断连接** |
| FR-543 | 协议层错误日志保留 SQL 摘要(承 P3.5 FR-448),便于发现"客户端还发了哪条没覆盖的元数据语句" |
| FR-544 | 虚拟表与 `SHOW` 输出**不得泄露口令**(配置里的 password 永远不出现在任何结果集/日志) |
| FR-545 | `SHOW` 与虚拟表都要支持**库作用域**:`SHOW TABLES FROM shop`、`information_schema.TABLES WHERE table_schema='shop'` 结果一致 |
| FR-546 | 客户端实测:**Navicat** 与 **DataGrip** 各跑一遍「连接 → 库表树 → 看表结构/DDL/索引 → 建表 → 改数据 → 查询」,真实截图/回显进验收报告 |
| FR-547 | 不引入任何第三方协议/元数据库:虚拟表与 `SHOW` 全部由现有 catalog 内存视图拼装(K94) |
| FR-548 | 客户端兼容层的**范围必须可枚举**:验收报告要列出"已支持的元数据语句清单"与"明确不支持的清单",不许含糊 |

### 4.8 分页与虚拟表多列排序(FR-549 ~ FR-554,实机反馈补)

| 编号 | 需求 |
|---|---|
| FR-549 | `SELECT ... LIMIT <offset>, <row_count>`:跳过前 `offset` 行后取 `row_count` 行;`offset` / `row_count` 必须是非负整数,非法值报 `1064` |
| FR-550 | `SELECT ... LIMIT <row_count> OFFSET <offset>`:与 FR-549 完全等价(两种写法结果必须一致) |
| FR-551 | 分页语义:**先过滤(WHERE)→ 再跳过 offset → 再截断 row_count**;不允许"先取 limit 再过滤";越界(offset ≥ 结果行数)返回空集而不是报错 |
| FR-552 | `LIMIT` 在索引路径与全表扫路径上行为一致;与 `LIMIT <n>`(已有语法)共存,不破坏原语义 |
| FR-553 | 虚拟表 `ORDER BY` 支持**多列**(`ORDER BY a, b DESC`):按列序依次比较,前一列相等才看下一列;仍**只对 `information_schema.*` 生效**(K99) |
| FR-554 | `UPDATE` / `DELETE` 语句带 `LIMIT` → 明确报"不支持"(1235),不做半实现;用户表上的 `ORDER BY` / 聚合仍报"不支持(归 P5)" |

### 4.9 客户端自检语句补齐(FR-555 ~ FR-562,2026-10-27 实机反馈补)

| 编号 | 需求 |
|---|---|
| FR-555 | `SELECT COUNT(*) [AS <alias>] FROM information_schema.<t> [WHERE ...]`:`COUNT(*)` 后接受可选别名,别名即结果集列名;无别名时列名仍为 `COUNT(*)`。仅限虚拟表(用户表上照 FR-554 报 1235) |
| FR-556 | 一条 `COM_QUERY` 里的多条语句(`;` 分隔)按**书写顺序**逐条执行;切分在 SQL 入口完成,必须感知单引号 / 双引号 / 反引号与 `--` / `#` / `/* */` 注释,字符串与注释里的分号不切;空语句(连续分号、尾随分号)忽略,不报错 |
| FR-557 | 多结果集回报:**客户端握手声明了 `CLIENT_MULTI_STATEMENTS`** 时,前 N-1 条结果集 / OK 的结束包都带 `SERVER_MORE_RESULTS_EXISTS`,最后一条不带;客户端**未声明**时只在同一个 `COM_QUERY` 里回**最后一条**语句的结果,不追加多余结果集(避免协议错位)。服务端**不主动置** `CLIENT_MULTI_STATEMENTS` 能力位(FR-434 不变) |
| FR-558 | 批量中某条失败:立刻停止,只回该条的错误包(错误码与单条执行时一致),其后语句不执行、连接保持;错误包不带 `SERVER_MORE_RESULTS_EXISTS`。下一条 `COM_QUERY` 正常工作 |
| FR-559 | `SHOW PROCEDURE STATUS` / `SHOW FUNCTION STATUS` / `SHOW FUNC STATUS`,可带 `LIKE '<pat>'` 与 `WHERE <cond>`:列与 MySQL 5.7 对齐(`Db` / `Name` / `Type` / `Definer` / `Modified` / `Created` / `Security_type` / `Comment` / `character_set_client` / `collation_connection` / `Database Collation`),本期无存储过程/函数 → 恒为空集;`Type` 按语句给 `PROCEDURE` / `FUNCTION`(空集下仅为列口径,不影响行) |
| FR-560 | `SELECT <字面量>[, <字面量>...]`(无 `FROM`,`SELECT 1` 这类连接探针):按字面量原样返回一行,列名为字面量文本(如 `1`);字面量只支持整数 / 小数 / 单引号字符串 / `NULL`,其它表达式仍归 P5 报 1064 |
| FR-561 | `SHOW COUNT(*) WARNINGS` / `SHOW COUNT(*) ERRORS`:返回单列 `@@session.warning_count` / `@@session.error_count`,值 `0`(本期不产生告警) |
| FR-562 | `SHOW VARIABLES` / `SHOW STATUS` / `SHOW INDEX|KEYS|INDEXES ...` 支持 `WHERE <cond>`:走 SHOW 输出的通用过滤路径(列名与输出列名一致);`WHERE` 过滤到无匹配 → 空集不报错 |

---

## 5. 数据与格式

### 5.1 key 布局(BE 零改动)

```
主键记录:  [表ID 4B][索引ID=0 4B][主键值保序编码][版本段 8B(BE 追加)]
二级索引:  [表ID 4B][索引ID≥1 4B][索引列值保序编码][主键值保序编码][版本段 8B(BE 追加)]
唯一索引:  [表ID 4B][索引ID≥1 4B][索引列值保序编码][版本段 8B(BE 追加)]
```

- 索引 ID **每表独立**、从 1 起(K75);`0` 永远表示主键。
- **唯一索引的用户键里故意不含主键值**:同一列值 → 同一个用户键 → 两个并发事务写它必然触发 P3 的写写冲突,唯一性因此**由引擎已验的机制保证**,不需要新的事务协议(K76)。
- 唯一索引的 value 里存**主键的保序编码**(回表与冲突报错都要用,格式见 §5.2)。

### 5.2 value 格式

| 记录 | value |
|---|---|
| 二级索引项 | 空(0 字节)。主键已在 key 里,不需要重复 |
| 唯一索引项 | 主键值保序编码(与 FE 主键编码完全一致,便于回表) |
| 索引项墓碑 | 与行墓碑同一标记(K83),BE 不解释,FE 解释 |

### 5.3 索引列值的编码(保序)

- 复用主键的保序编码规则(§7.1):`INT` 4B(符号位翻转)、`BIGINT` 8B(同上)、`DATE` 定长、`VARCHAR` 字节序 + `0x00` 终止符、`BOOLEAN` 1B。
- **每个编码段都自定界**(定长,或带终止符),因此 `[索引列值][主键值]` 的拼接**天然无歧义**,不需要额外分隔符。
- `VARCHAR` 内含 `0x00` 的极端情形按 K77 处理(转义)。

### 5.4 系统表 `sys_indexes`(ID **5**)

| 列 | 类型 | 说明 |
|---|---|---|
| `table_id` | `BIGINT` **主键①** | 所属表 |
| `index_id` | `INT` **主键②** | 表内索引号(≥1) |
| `name` | `VARCHAR(64)` | 索引名(表内唯一) |
| `column_name` | `VARCHAR(64)` | 被索引的列 |
| `is_unique` | `BOOLEAN` | 是否唯一索引 |
| `state` | `INT` | 0=BUILDING,1=ACTIVE,2=DROPPED |
| `build_cursor` | `VARCHAR(256)` | 回填游标(最后一行的主键编码,十六进制;空=从头) |
| `created_at` | `VARCHAR(64)` | 创建时间(沿用系统表用 VARCHAR 存时间的口径) |

- 复合主键 = `(table_id, index_id)`,key 编码 `table_id 4B BE + index_id 4B BE`(FR-479)。
- 系统表 ID 登记表更新:`5 = sys_indexes`;**新模块必须来登记表领号**(K70)。

### 5.5 回表与二次校验(为什么必须有)

LSM + MVCC 的组合下,索引项会"过期而未被回收":改了索引列后,旧索引项的墓碑可能还在同一个快照之外、或被 compaction 推迟处理。因此回表**必须**做二次校验:

```
扫索引项(list 的 key 里能解出 列值 + 主键) 
  → 按同一 snapshot 点查主键
      · 墓碑 / 不存在       → 跳过
      · 行的该列值 ≠ 索引项里的值 → 跳过(过期索引项)
      · 相等                → 输出
```

代价是多一次点查(N+1),换来的是"索引永不返回错行"。**这是本期的取舍,写死在 FR-501。**

### 5.6 索引状态与可用性

| 状态 | 值 | 查询可用 | 写入维护 | 说明 |
|---|---|---|---|---|
| `BUILDING` | 0 | ✗(退化全表扫) | ✗ | 回填中或回填被中断 |
| `ACTIVE` | 1 | ✓ | ✓ | 正常 |
| `DROPPED` | 2 | ✗ | ✗ | 元数据不可见;数据留待 compaction |

### 5.7 虚拟表与 `SHOW` 输出规格(客户端自省用)

**核心口径:全部来自 FE 内存里的 catalog 与会话表,不碰 BE,不建新系统表。**

**虚拟表最小列集(与 MySQL 对齐,客户端按列名取值)**

| 虚拟表 | 必须有的列 |
|---|---|
| `SCHEMATA` | `CATALOG_NAME` / `SCHEMA_NAME` / `DEFAULT_CHARACTER_SET_NAME` / `DEFAULT_COLLATION_NAME` / `SQL_PATH` |
| `TABLES` | `TABLE_CATALOG` / `TABLE_SCHEMA` / `TABLE_NAME` / `TABLE_TYPE`(= `BASE TABLE`)/ `ENGINE`(= `InnoDB`)/ `VERSION` / `ROW_FORMAT` / `TABLE_ROWS` / `TABLE_COLLATION` / `CREATE_TIME` |
| `COLUMNS` | `TABLE_SCHEMA` / `TABLE_NAME` / `COLUMN_NAME` / `ORDINAL_POSITION` / `COLUMN_DEFAULT` / `IS_NULLABLE` / `DATA_TYPE` / `COLUMN_TYPE` / `CHARACTER_MAXIMUM_LENGTH` / `COLUMN_KEY`(PRI/UNI/MUL)/ `EXTRA` |
| `STATISTICS` | `TABLE_SCHEMA` / `TABLE_NAME` / `NON_UNIQUE` / `INDEX_SCHEMA` / `INDEX_NAME` / `SEQ_IN_INDEX` / `COLUMN_NAME` / `COLLATION` / `CARDINALITY` / `SUB_PART` / `INDEX_TYPE`(= `BTREE`) |
| `KEY_COLUMN_USAGE` | `CONSTRAINT_SCHEMA` / `CONSTRAINT_NAME` / `TABLE_SCHEMA` / `TABLE_NAME` / `COLUMN_NAME` / `ORDINAL_POSITION` / `REFERENCED_TABLE_NAME`(恒 NULL) |
| `TABLE_CONSTRAINTS` | `CONSTRAINT_SCHEMA` / `CONSTRAINT_NAME` / `TABLE_SCHEMA` / `TABLE_NAME` / `CONSTRAINT_TYPE`(`PRIMARY KEY` / `UNIQUE`) |
| `PROCESSLIST` | `ID` / `USER` / `HOST` / `DB` / `COMMAND` / `TIME` / `STATE` / `INFO` |
| 其余 10 张 | 列定义按 MySQL 5.7 对齐,**空集** |

**`SHOW CREATE TABLE` 输出样例(必须能被本项目 parser 吃回去,K96)**

```sql
CREATE TABLE `orders` (
  `id` int NOT NULL,
  `category` varchar(32) NULL,
  `amount` int NULL,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  UNIQUE KEY `uk_amount` (`amount`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
```

**`SHOW INDEX FROM orders` 的行形态**

| Table | Non_unique | Key_name | Seq_in_index | Column_name | Collation | Cardinality | Sub_part | Packed | Null | Index_type | Comment |
|---|---|---|---|---|---|---|---|---|---|---|---|
| orders | 0 | PRIMARY | 1 | id | A | NULL | NULL | NULL | | BTREE | |
| orders | 1 | idx_category | 1 | category | A | NULL | NULL | NULL | YES | BTREE | |

> `Cardinality` 本期恒 `NULL`(没有统计信息,P6 才有)。**不许编一个假数字**。

---

## 6. 外部评审必答项的正面回答

### 1. 设计文档 §7.1 的 I7 约束(用户键互为前缀)在索引上怎么解?

**正面回答:给每一段都加"自定界"编码,而不是给用户键加分隔符。**

- 设计文档担心的歧义来自"两段变长字节直接拼接"。本期的索引 key 只有两种段组合,且**每一段都自带边界**:
  - `INT` / `BIGINT` / `DATE` / `BOOLEAN`:**定长**,边界天然确定;
  - `VARCHAR`:**`0x00` 终止符**(§5.3/K77)。
- 因此 `[索引列值][主键值]` 不会出现"`abc` + `d`"与"`ab` + `cd`"编出同一个 key 的情况,级联边界**由编码本身保证**,不需要 `0x00` 之外的转义层。
- 同时**明确声明不支持任意裸 key 的范围扫描**:P1 的 `crash-test.sh` 用 `key-1` / `key-10` 这类原始键,它们互为前缀 —— 那条路径只保证点查与冲突检测(已验),**原生范围扫描不保证用户键升序**。SQL 路径不受影响(保序编码保证前缀自由)。
- 这条也回答"为什么不干脆给用户键加分隔符":加了就改变了 P1~P3.5 已在用的 key 语义与磁盘格式,代价与收益不成比例;而自定界编码本来就在。

### 2. 索引一致性(INV-10)靠什么保证,而不是靠"小心"?

三层:

1. **同批原子写**:行记录与所有索引记录在**同一个批记录**里(P3 已验的机制),不存在"写了一半"的中间态。
2. **同一 `commit_ts`**:索引项与行版本的版本号来自同一次提交,所以按快照读时它们**必然同时可见或同时不可见**。
3. **对账可执行**:FR-519 的对账能力在 `p4-test.sh` 里跑(场景 11),差异计数必须为 0 —— 不是"理论上一致",而是每次验收都真跑一遍。

### 3. 唯一性在并发下怎么保证?为什么不引入新协议?

**唯一索引的用户键 = 索引列值(不含主键)**,所以"插同一列值"的两个事务写的是**同一个用户键** → 直接落到 P3 已经验的写写冲突路径上(SC-202 的语义)。后提交者拿到冲突,FE 把它翻译成 `1062`(K80),对外表现为唯一约束,对内复用的是已验证的机制。

新协议(唯一索引预占表 / 锁表)一律不引入:那会打破"BE 只认 key 范围"的边界,也让 P3 的串行区间逻辑多一份实现。

### 4. 索引扫描为什么要回表 + 二次校验,不做"索引直出"?

- 索引里只有索引列与主键,投影其它列必须回表(设计文档 §12 已写明)。
- 二次校验是因为 LSM 下**墓碑与旧索引项可能滞后**(P5 之前不做版本回收):索引项存在不等于该行现在还有这个值。少了二次校验,`UPDATE` 改索引列后会查出"幽灵行"。
- 不做覆盖索引(索引直出):归 P6,需要投影列覆盖判断与统计信息支撑。

### 5. 建索引为什么要分批回填,而且允许"只做一半"?

- 10 万行以上一次性回填会变成一个**超长事务**:WAL 膨胀、`max_txn_write_bytes` 超限、崩溃后要从头上重来。
- 分批(默认 1000 行/批,独立提交)+ **持久化游标**(`build_cursor`)意味着崩溃后能从断点续做,不需要重扫全表。
- `BUILDING` 期间索引不可用、查询退化全表扫 —— 用户看到的是"还没建好",不是"结果错了"。这是唯一正确的降级方向。

### 6. 索引选择只有 RBO,会不会选出比全表扫更慢的路?

会,而且**诚实承认**:没有统计信息时,`WHERE category = '数码'` 在 10 万行上走索引可能比全表扫慢(回表 N 次点查)。本期的取舍:

- 选择规则固定且可解释(FR-504),不假装有代价模型;
- `EXPLAIN` 让人能看见选了哪条路(FR-513~517);
- 性能口径要求两条路都量出来(FR-525),为 P6 的 CBO 留下真实基线数据;
- P6 拿到统计信息后,这里的规则会被代价模型替换,**但接口不变**。

### 7. 为什么索引不另建文件 / 不另做 B+ 树?

设计文档 §7.1 与 §12 已经定死:整个引擎只有"有序 KV",索引是**同一套 key 空间里的另一段前缀**。好处是索引与数据共享 WAL、事务、MVCC、恢复、compaction 的全部机制,一行 BE 代码都不用改;代价是索引项的失效只能靠墓碑 + 未来的 compaction 回收(P5)。这个代价本期是明账。

---

## 7. 关键技术决策(本期默认值)

| 编号 | 决策 | 说明 |
|---|---|---|
| **K74** | 索引类型范围:**单列二级索引 + 单列唯一索引**;复合索引归 P6 | 多列要解决前缀可用性与选择率,与 CBO 一起做 |
| **K75** | 索引 ID:**每表独立**、从 1 起、单调递增不复用;计数器 `next_index_id:<table_id>` 存 `sys_counters` | 让"表内索引号"成为局部概念,id 冲突不可能跨表发生 |
| **K76** | 唯一索引的用户键**不含主键值**,value 存主键编码 | 唯一性由 P3 写写冲突保证,不引入新协议(§6 第 3 条) |
| **K77** | 索引列值编码复用主键保序编码;`VARCHAR` 用 `0x00` 终止,**值内含 `0x00` 时按 `0x00 0xFF` 转义、`0x00 0x00` 表示结束** | 保证段自定界(回答 §6 第 1 条);转义只为极端数据,正常数据字节不变 |
| **K78** | 唯一索引冲突错误码统一 **`1062`**;由写写冲突(`1213`)触发的唯一冲突**也翻译成 `1062`** | 客户端/连接池把 1213 当"可重试",而唯一冲突重试也不会成功,必须给不可重试的码 |
| **K79** | 索引列 `NULL` 的行**不入索引** | `IS NULL` 谓词归 P5,语义闭合;避免"多行 NULL 在唯一索引里互斥"这种反直觉语义 |
| **K80** | 唯一冲突信息必须带:**索引名 + 列名 + 值 + 已有主键**(若是并发触发的,不带主键只说"并发写入冲突") | 排查唯一约束失败必须有可操作信息 |
| **K81** | 索引名作用域:**表内唯一**(不要求库内唯一) | MySQL 亦为表内唯一;跨表同名索引无法共存于同一 namespace 才需要更长名字 |
| **K82** | 索引选择规则(RBO):主键等值 > 主键范围 > 索引等值 > 索引范围 > 全表扫;同类多个索引取 **索引 ID 最小**者 | 固定、可解释、可复现;P6 用 CBO 替换,接口不变 |
| **K83** | 索引项墓碑沿用行墓碑的标记与语义(格式由 FE 定义,BE 只当字节) | 不新增第二套墓碑格式 |
| **K84** | 回填批量:**默认 1000 行/批**,可通过配置 `index_backfill_batch` 调整 | 小到不会撑爆 `max_txn_write_bytes`,大到不会把 WAL 刷成碎片 |
| **K85** | 回填游标持久化在 `sys_indexes.build_cursor`(十六进制主键编码),每批提交后更新 | 崩溃可续做(FR-468) |
| **K86** | 索引状态机 `BUILDING → ACTIVE`、`BUILDING → DROPPED`,**不允许** `ACTIVE → BUILDING`(要重建只能 DROP 后新建) | 状态只前进,不引入"重新校验"路径 |
| **K87** | `EXPLAIN` 输出**单列文本**(列名 `plan`),不做表格化多列 | 与 CLI/协议结果集天然兼容,也便于脚本断言 |
| **K88** | 回填期间查询**退化全表扫**并**不报错**;`SHOW INDEXES` 里能看到 `BUILDING` 与游标 | 降级方向唯一正确(见 §6 第 5 条) |
| **K89** | 写入维护的索引范围:**`ACTIVE` 与 `BUILDING` 都维护**,只有 `DROPPED` 不维护(I16 拍板 A) | 回填期间的新行由写入路径直接进索引,回填只补历史数据 → 转 `ACTIVE` 时索引天然完整,**不需要增量补扫**;代价是回填期写放大 +1 |
| **K90** | 写放大记账:行记录字节与索引记录字节**分开统计**,P4 只记录不设门限 | 为 P5 的 compaction 与压缩提供输入(§18) |
| **K91** | 本机口径:`mvn.cmd`;测试脚本在 `scripts/tests/`;`p4-test.sh` 用独立高位端口(BE 9900 / MySQL 9906 / 心跳 9991) | 与前几期一致,不碰日常端口 |
| **K92** | `SHOW` 家族在 **Parser 里加语法**、在 **Executor 里执行**;`information_schema` 虚拟表在 **`SqlEngine` 的兼容层**里拦截(与 P3.5 的 §5.5 白名单同一入口) | 前者是正式 SQL 语法,后者是"客户端专供"的兼容路径,分层清楚 |
| **K93** | `SHOW ENGINES` / `information_schema.ENGINES` 里 `Engine` 固定报 **`InnoDB`**,`Support = DEFAULT` | 图形客户端按引擎名分支(InnoDB 走事务/DDL 面板),报"tangdb"会让它们退化成只读;真实实现口径写进 README,不欺骗 |
| **K94** | 虚拟表**只读、纯内存**;数据来源 = catalog 缓存 + `SessionManager` + 配置;不新建系统表、不查 BE | 零成本、零一致性风险(权威仍在系统表) |
| **K95** | 虚拟表里"未知 / 不适用"的值一律给 **`NULL`**,不填 `0` / 空串 | 客户端把 `0` 当真实统计值会显示"0 行";`NULL` 才是"不知道" |
| **K96** | `SHOW CREATE TABLE` 必须是 **round-trip 可执行**的(输出 → 喂回 parser → 建出等价表);列入单测 | "能看 DDL"但复制出来跑不了,等于没做 |
| **K97** | `KILL` 本期**不校验权限**(无权限体系),但必须落日志:谁杀了哪个会话 | 留痕,归 P13 权限时补校验 |
| **K98** | **虚拟表专有的查询子集**:`ORDER BY`(单列,`ASC`/`DESC`)+ `COUNT(*)` 只在 `information_schema.*` 上生效;同样的语法用在**用户表**上必须明确报"不支持(归 P5)" | I20 拍板 A。这样客户端面板可用,又不会让用户以为"通用 ORDER BY/聚合已经做了";语法可解析、执行按表类型分流 |
| **K99** | 虚拟表 `ORDER BY` 由**单列放宽到多列**(每列可独立 `ASC`/`DESC`),**仍仅限虚拟表**;用户表仍归 P5 | 实机反馈:Navicat 拉表清单是 `ORDER BY TABLE_SCHEMA, TABLE_TYPE` 两列排序,单列实现直接 1235 |
| **K100** | `LIMIT <offset>, <count>` / `LIMIT <count> OFFSET <offset>` 归入**本期**(原 P2 排期写的是 P5) | 实机反馈:`SELECT * FROM db.t LIMIT 0,1000` 报 1064 —— **所有图形客户端翻页都用这个写法**,属"没规划却卡住日常使用"的缺口,按所有者口径并入 P4;通用 `ORDER BY` / 聚合仍归 P5 |
| **K101** | 多语句切分放 **`SqlEngine`(SQL 入口)**:入口把一条文本切成 N 条,逐条走原路径执行,返回结果列表;**回包与状态位(SERVER_MORE_RESULTS_EXISTS)放协议层 `ConnectionHandler`**,按客户端握手声明的 `CLIENT_MULTI_STATEMENTS` 决定回几条 | 切分要懂引号与注释,属 SQL 词汇层,不能塞进协议层(协议层不许懂 SQL,AGENTS.md 红线);而多结果集的编码是纯协议事。两侧各就各位,能力位仍不置(FR-434 不变) |
| **K102** | `COUNT(*)` 的别名与普通列别名同一套语法、同一个 AST 字段(`AS` 可省) | 少一套分支就少一类客户端兼容问题;只为 `COUNT(*)` 单开一条解析路径,下次 `SUM/AVG` 又要再来一遍 |

---

## 8. 验收标准

| 编号 | 验收项 | 判定 |
|---|---|---|
| **SC-319** | 二级索引读写闭环 | 场景 1 全过;索引路径与全表扫**逐行一致** |
| **SC-320** | 索引列 UPDATE 维护 | 场景 2 全过:旧值查不到、新值查得到、主键行同步 |
| **SC-321** | ★ 唯一索引挡重复(单会话) | 场景 3 全过:`1062` + 错误信息含索引名/列/值/已有主键 + 行数不变 |
| **SC-322** | ★ 唯一索引并发 | 场景 4 全过:恰好一个成功、另一个 `1062`、表里一行 |
| **SC-323** | ★ 崩溃:索引与数据同批 | 场景 5 全过:两处注入点下三条用户键同生同灭 |
| **SC-324** | ★ 崩溃:回填中断可续 | 场景 6 全过:重启后 `BUILDING`、重跑能完成、期间查询正确 |
| **SC-325** | 索引选择与 `EXPLAIN` 一致 | 场景 7 全过:五种路径输出正确且与实际扫描一致 |
| **SC-326** | `DROP INDEX` 行为 | 场景 8 全过:不再走索引、结果不变、可同名重建 |
| **SC-327** | 老数据目录升级 | 场景 9 全过:幂等建 `sys_indexes`、老表可用、`format_version` 不变 |
| **SC-328** | 事务内索引可见性 | 场景 10 全过:未提交改动不通过索引可见;读己之写可用 |
| **SC-329** | 一致性对账(INV-10) | 场景 11 全过:5000 次随机写改删后差异计数 **0** |
| **SC-330** | 索引元数据原子性 | 建表内联索引 / 建索引 / 删索引 各阶段强杀:要么完整存在、要么完全不存在,启动不报"半套元数据" |
| **SC-331** | 依赖与边界红线 | BE 直接依赖仍只有 grpc / protobuf;FE 无第三方 SQL / 索引库;`proto/` 零改动、BE 代码零改动 |
| **SC-332** | 既有回归 | `p2-test.sh` / `p3-test.sh` / `p35-test.sh` 全 PASS(端口与启动形态不变) |
| **SC-333** | 性能口径 | 同一查询"全表扫 vs 索引"的耗时与扫描行数成对记录,写进验收报告(含 §18 条件模板) |
| **SC-334** | 一键验收 | `scripts/tests/p4-test.sh` 一条命令跑完 SC-319 ~ SC-34x 并输出报告 |
| **SC-335** | `SHOW` 家族全覆盖 | FR-527~FR-536 逐条真跑:每条语句返回**非 1064** 且列名与 MySQL 5.7 对齐 |
| **SC-336** | 虚拟表可查询 | 投影裁剪生效(只查 1 列时不产出其它列)、`WHERE` 过滤生效、`LIMIT` 生效;与 `SHOW` 结果一致(FR-545) |
| **SC-337** | 虚拟表清单完整 | 17 张表全部可查;无数据的表返回**列正确的空集**,不报错 |
| **SC-338** | `SHOW CREATE TABLE` round-trip | 输出的 DDL 喂回 parser 能建出等价表(单测 + 脚本各一处) |
| **SC-339** | Navicat 全流程 | 连接 → 库表树 → 表结构/DDL/索引 → 建表 → 改数据 → 查询,全部成功;**截图与回显**进验收报告 |
| **SC-340** | DataGrip 全流程 | 同上(可与 SC-311/312 的协议验收合并跑) |
| **SC-341** | 未支持语句的降级 | 清单外的 `SHOW` / 虚拟表 / `information_schema` JOIN 查询 → 明确错误(`1235`/`1146`)**且连接不断** |
| **SC-342** | 分页三种写法一致 | `LIMIT n` / `LIMIT off,n` / `LIMIT n OFFSET off` 在同一查询上结果一致;`off` 越界返回空集;`UPDATE/DELETE ... LIMIT` 报 1235 |
| **SC-343** | 虚拟表多列排序 | `SELECT ... FROM information_schema.COLUMNS ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION` 结果有序;用户表上同样语句仍报 1235 |
| **SC-344** | `COUNT(*)` 带别名 | `SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'` 返回 1 行 1 列,列名 `support_ndb`、值 `0`;不带别名时列名仍为 `COUNT(*)`;用户表上 `SELECT COUNT(*) AS c FROM t` 仍报 1235 |
| **SC-345** | 多语句单包 | Navicat 连接期那 3 条合成一包(`SHOW VARIABLES LIKE 'lower_case_%'; SHOW VARIABLES LIKE 'sql_mode'; SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES WHERE Engine = 'ndbcluster'`)**不报错**;字符串内分号(`WHERE note = 'a;b'`)不被切;夹一条语法错时只回该条错误、后续不执行、连接仍可用 |
| **SC-346** | `SHOW PROCEDURE/FUNCTION STATUS` | `SHOW PROCEDURE STATUS`、`SHOW PROCEDURE STATUS WHERE Db = 'default'`、`SHOW FUNCTION STATUS`、`SHOW FUNC STATUS` 各返回 **11 列的空集**(列名与 MySQL 5.7 一致),`CODE=0`;连接不断 |
| **SC-347** | 客户端盘点的剩余缺口 | `SELECT 1` / `SELECT 1, 'x'` → CODE=0 一行;`SHOW COUNT(*) WARNINGS`、`SHOW COUNT(*) ERRORS` → CODE=0 单值 0;`SHOW VARIABLES WHERE Variable_name='version'`、`SHOW STATUS WHERE Variable_name='Uptime'`、`SHOW INDEX FROM t WHERE Key_name='PRIMARY'` → CODE=0 且过滤生效 |

---

## 9. 边界与异常清单

| # | 情形 | 期望行为 |
|---|---|---|
| 1 | `CREATE INDEX` 指定不存在的列 | 报错并指明列名与表名 |
| 2 | 对主键列建索引 | 拒绝("主键已有索引") |
| 3 | 同一列建第二个索引(不同名) | 拒绝(避免重复维护) |
| 4 | 索引名重复 | `1061` 类错误,信息可读 |
| 5 | 索引名含非法字符 / 超长 | 拒绝,与表名规则一致 |
| 6 | 对非空表建**唯一**索引但已有重复值 | 拒绝,报出重复值与前两个主键;不留半成品索引 |
| 7 | 回填中途客户端断开 / `fed` 重启 | 索引留在 `BUILDING`,游标可续;查询照常(全表扫) |
| 8 | 回填期间并发写入 | **I16 已拍板 A**:写入路径对 `BUILDING` 索引**也维护**,回填只补历史数据 → 回填完成后索引对**所有**行完整,不允许漏行 |
| 9 | 唯一索引列插入 `NULL` | 允许多行 `NULL`(K79),都不进索引 |
| 10 | `UPDATE` 把唯一列改成 `NULL` | 允许;该行从唯一索引里消失 |
| 11 | 一个事务里改同一行索引列 3 次 | 只落最终值(FR-489),索引项唯一 |
| 12 | 同一事务插入两行、唯一列相同 | 事务内即检测到并拒绝(不等到提交) |
| 13 | 索引项指向的主键行已被删 | 回表跳过(FR-502) |
| 14 | 索引项存在但行值已改 | 二次校验跳过(FR-501) |
| 15 | `WHERE` 里索引条件与其它条件混合 | 索引定位 + 残余过滤,结果与全表扫一致 |
| 16 | `WHERE` 只有 `OR` | 退化全表扫,结果正确 |
| 17 | 对 `BUILDING` 索引执行查询 | 退化全表扫 + 不报错(K88) |
| 18 | `DROP INDEX` 后同名重建 | 允许;新索引 ID 不复用旧号 |
| 19 | `DROP TABLE` 后 `SHOW INDEXES` | 不再列出该表索引 |
| 20 | `TRUNCATE` 后索引 | 新表 ID 上无索引;老索引不可见 |
| 21 | `EXPLAIN INSERT` / DDL / 事务语句 | 明确报"不支持解释该语句" |
| 22 | 索引列类型是 `VARCHAR` 且值内含 `0x00` | 按 K77 转义,往返一致(专门用例) |
| 23 | `max_txn_write_bytes` 因索引写入超限 | 整批失败、无副作用,错误信息说明"含索引写入" |
| 24 | 索引元数据指向不存在的表 / 列 | 启动报错并指名对象(K26 沿用) |
| 25 | `SHOW TABLES LIKE 'x%'` 无匹配 | 空集(不是报错) |
| 26 | `SHOW CREATE TABLE` 表不存在 | `1146`,信息带表名 |
| 27 | `KILL` 不存在的会话 id | 明确错误,不断自己的连接、不影响其它会话 |
| 28 | `information_schema` 上的 **JOIN / 子查询 / 聚合** 查询 | 明确报"不支持"(1235),**不得返回半对的结果**(宁可报错不给错数据) |
| 29 | 客户端查询 `information_schema` 时带 `ORDER BY` / `COUNT(*)` | **已支持**(I20 拍板 A + K99):单列**与多列** `ORDER BY`、`COUNT(*)` 在虚拟表上生效;`GROUP BY`、`DISTINCT` 仍明确报不支持 |
| 30 | 空库(无表)时 Navicat 打开表树 | `SHOW FULL TABLES` 返回空集而非报错;树显示"无表" |
| 31 | `LIMIT -1, 10` / `LIMIT 1.5` / 缺参数 | `1064`,信息指出位置 |
| 32 | `LIMIT 0, 0` | 空结果集(不是报错) |
| 33 | `LIMIT 100000, 1000` 在大表上(offset 越界) | 空结果集,不报错、不 OOM |
| 34 | 一条 `COM_QUERY` 里 3 条语句,第 2 条语法错 | 报第 2 条的错误(码与单条一致),第 3 条不执行;连接保持,下一条查询正常 |
| 35 | 语句里含分号:`INSERT ... VALUES ('a;b')` / 反引号标识符 / `-- 注释;` / `/* ; */` | 不被切开,按一条语句执行 |
| 36 | 连续分号 / 尾随分号(`SHOW TABLES;;`、`SELECT * FROM t;`) | 空语句忽略,不报 1064 |
| 37 | `SELECT COUNT(*) AS c FROM information_schema.ENGINES` 与已有列名或别名重名 | 原样输出用户给的别名,不改写、不报错 |
| 38 | `SHOW PROCEDURE STATUS` 带 `WHERE` 过滤到不存在的库 / `LIKE` 无匹配 | 空集(不是报错) |
| 39 | `SELECT 1+1` / `SELECT now()`(无 FROM 的表达式) | 仍报 1064(表达式归 P5),不得返回错值 |
| 40 | `SHOW VARIABLES WHERE Variable_name = '不存在的变量'` | 空集,不报错 |

> 边界 8 已由 **I16 拍板(选 A)** 收口:回填期间的新写入也维护索引,回填只补历史数据。剩余待拍板见 §12 的 I15 / I17~I22。

---

## 10. 交付物清单

| 交付物 | 内容 |
|---|---|
| 代码 | 索引元数据(`sys_indexes` + catalog 缓存)、索引 key 编码、写入维护、回填(含游标)、索引选择、`EXPLAIN`、一致性对账 —— **全部在 FE**;BE 预期零改动 |
| 配置 | `config/fe.conf` 新增 `index_backfill_batch`(默认 1000);BE 无改动 |
| 脚本 | `scripts/tests/p4-test.sh`(一键验收)、`scripts/sql/41~4x`(建索引 / 改索引列 / 唯一冲突 / 回填 / EXPLAIN 演示) |
| 文档 | `docs/验收报告/P4-验收报告.md`、`docs/读代码地图/读代码地图-P4.md`、`README.md`(索引与 `EXPLAIN` 用法) |
| 规约回填 | SPEC-P2 的"不做二级索引"、SPEC-P3 的"索引与主键同批"承诺在此兑现;INV-10 转"已验" |

---

## 11. 关于代码由谁写(沿用 D9,已拍板)

| 角色 | 职责 |
|---|---|
| 编码 agent(opencode + DeepSeek) | 按本规约实现全部代码与测试,并**真跑**测试、给出真实输出 |
| 我 | 起草与维护本规约、评审实现、**独立核验**(自己重跑全部脚本与单测,不看自述)、交付验收报告与《读代码地图》 |
| 你 | 审规约(**只有你能把 Draft 改成 Approved**)、看验收报告、读《读代码地图》 |

---

## 12. 待拍板事项(施工前需要你拍)

| 编号 | 事项 | 我的建议 |
|---|---|---|
| **I15** | 索引范围要不要在本期就做**复合索引**? | **已拍板(2026-10-26,按建议)**:不做(K74),复合索引归 P6 |
| **I16** | **回填期间的新写入**怎么保证不丢索引?(边界 8 的口径) | **已拍板 A(2026-10-26)**:写入路径对 `BUILDING` 索引**也维护**,回填补历史数据 → 转 `ACTIVE` 时索引天然完整,**不需要增量补扫**;代价是回填期写放大 +1。已落 FR-491 / K89 / 边界 8 |
| **I17** | 唯一索引的 `NULL` 语义(K79) | **已拍板(2026-10-26,按建议)**:**不索引 `NULL` 行**,允许多行 `NULL`(K79 / 边界 9、10) |
| **I18** | 一致性对账的形态 | **已拍板(2026-10-26,按建议)**:脚本内 SQL 对拍(索引路径 vs 全表扫),**不新增** `CHECK INDEX` 语法(FR-519 / FR-522) |
| **I19** | 索引选择在"等式命中但行数可能很多"时是否仍强行走索引 | **已拍板(2026-10-26,按建议)**:本期不做代价判断,一律按 K82 规则走;诚实承认可能变慢,基线数据留给 P6 |
| **I20** | `information_schema` 虚拟表上要不要支持 `ORDER BY` 与 `COUNT(*)`?(通用版归 P5) | **已拍板 A(2026-10-26)**:支持,**仅限虚拟表**(单列 `ORDER BY` + `COUNT(*)`);用户表上同样的语法明确报"不支持(归 P5)"。已落 FR-537 / K98 / 边界 29 |
| **I21** | `ALTER TABLE` 要不要提前到 P4? | **已拍板(2026-10-26,按建议)**:不提前,维持"P4 之后";Navicat"设计表"改结构本期明确报不支持。若需要,另立 `P4.5 ALTER TABLE` 补丁规约(不在本期范围内) |
| **I22** | `SHOW ENGINES` 报 `InnoDB` 算不算"欺骗"? | **已拍板(2026-10-26,按建议)**:报 `InnoDB`(K93),`README` 写清"仅为客户端兼容,真实引擎是自研 LSM" |

---

## 13. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1i | 2026-10-27 | **H 组补第 7 条**(客户端语句全量盘点:98 条 P4 范围内语句 91 过 7 败,逐条定性后 4 条属本期):① `SELECT 1` 等无 `FROM` 字面量投影(FR-560);② `SHOW COUNT(*) WARNINGS/ERRORS`(FR-561);③ `SHOW VARIABLES/STATUS/INDEX ... WHERE`(FR-562)。新增 H-7 / FR-560~562 / SC-347 / 边界 39~40;`SELECT 1+1` 等表达式仍归 P5 |
| 0.1h | 2026-10-27 | **H 组补第 6 条**(所有者口径:只要属 P4 的缺口就在 P4 补完):`SHOW PROCEDURE STATUS` / `SHOW FUNCTION STATUS`(含 `LIKE` / `WHERE`)返回列正确的空集 —— 实机 Navicat 按库逐个发 `SHOW PROCEDURE STATUS WHERE Db = 'def'`,此前 1235。新增 H-6 / FR-559 / SC-346 / 边界 38 |
| 0.1g | 2026-10-27 | **实机反馈补丁 H 组**(所有者拍板"补",Navicat 实测报错):① `SELECT COUNT(*) AS <alias> FROM information_schema.*` 接受聚合别名(Navicat 的 `support_ndb` 探测报 1235);② 一条 `COM_QUERY` 内的多语句按顶层分号切分逐条执行,并按客户端 `CLIENT_MULTI_STATEMENTS` 声明回多结果集(Navicat 把 3 条自检语句塞进一个包,整批 1235)。新增 FR-555~FR-558、K101~K102、SC-344~SC-345、边界 34~37;§2.2 补记「无 `FROM` 常量投影归 P5」。**能力位仍不置**(FR-434 不变),用户表上的 `ORDER BY` / 聚合边界不变 |
| 0.1f | 2026-10-26 | **实机反馈补丁 G 组**(所有者拍板"补"):① `LIMIT <off>,<n>` / `LIMIT <n> OFFSET <off>` 并入本期(**原先排 P5**,K100 —— 图形客户端翻页刚需,现在报 1064);② `information_schema` 虚拟表 `ORDER BY` 由单列放宽到**多列**(K99,客户端树查询是两列排序)。新增 FR-549~FR-554、SC-342~SC-343、边界 31~33。用户表上的 `ORDER BY`/聚合仍归 P5 |
| 0.1e | 2026-10-26 | **所有者批准(2026-10-26)**:状态 Draft → **Approved**,可施工。正文与 §12 八项结论未改 |
| 0.1d | 2026-10-26 | **§12 全部待拍板收口**(所有者口径"都按你推荐的来"):I15 不做复合索引、I17 不索引 NULL、I18 脚本对拍不加 `CHECK INDEX` 语法、I19 按 K82 规则不做代价判断、I21 `ALTER TABLE` 不提前、I22 `SHOW ENGINES` 报 `InnoDB`。至此 **I15~I22 八项全部拍死,规约无待确认项**,仅剩状态待所有者批准后转 Approved |
| 0.1c | 2026-10-26 | **I20 拍板(所有者选 A)**:`information_schema` 虚拟表支持单列 `ORDER BY` 与 `COUNT(*)`;同样的语法用在**用户表**上明确报"不支持(归 P5)"。新增 **K98**,同步 FR-537 / §2.2 / 边界 29 |
| 0.1b | 2026-10-26 | **并入"图形客户端元数据自省兼容"**(所有者口径:已规划期次的等那时做,**没在规划里的放进 P4**)。新增 §1 第 5 件事、**F 组**(F-1~F-6)、**§4.7 FR-527~FR-548**(SHOW 家族全集 / `information_schema` 可查询虚拟表 17 张 / `KILL` / 客户端全流程验收)、**§5.7 虚拟表与 SHOW 输出规格**(含 `SHOW CREATE TABLE` round-trip 样例);新增 **K92~K97**、**SC-335~SC-341**、**边界 25~30**、**待拍板 I20~I22**。§2.2 增补四条不做项及归属(`information_schema` 上的 JOIN/聚合 → P5;`ALTER TABLE` → P4 之后 + I21)。起因:实机 `SHOW FULL TABLES WHERE Table_type != 'VIEW'` 报 1064 |
| 0.1a | 2026-10-26 | **I16 拍板(所有者选 A)**:回填期间的新写入也维护索引(`ACTIVE` 与 `BUILDING` 都维护,只有 `DROPPED` 不维护),回填只补历史数据、转 `ACTIVE` 前不做增量补扫。同步 FR-491 / K89 / 边界 8。其余未改 |
| 0.1 | 2026-10-26 | 初稿(**Draft**):按设计方案 **v1.11** §7.1 / §11.2 / §12 / §4.4 / §18 / §19-P4 起草。范围 2.1 做(A 组 DDL 与元数据 / B 组写入维护 / C 组读路径与索引选择 / D 组 EXPLAIN / E 组一致性)与 2.2 不做及归属;2.3 保证 / 不保证;11 个 BDD 场景(含 4 个 ★);**71 条 FR**(FR-456~FR-526);6 节数据与格式(key 布局 / value / 索引列编码 / `sys_indexes` / 回表二次校验 / 状态机);**7 条外部评审必答项的正面回答**(含设计文档 §7.1 的 I7 前缀自由问题);K74~K91;16 条验收标准(SC-319~SC-334);24 条边界情形;5 项待拍板(I15~I19)。上游:设计方案 §19-P4、§12 索引体系、§7.1 编码布局 |
