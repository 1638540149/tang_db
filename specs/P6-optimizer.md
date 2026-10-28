# SPEC-P6 · 优化器:让它自己变快(统计信息 + CBO + 集合运算)

| 项 | 值 |
|---|---|
| 期次 | P6 |
| 状态 | **Approved**(2026-10-28 项目所有者确认;可施工) |
| 版本 | 0.1a |
| 起草日期 | 2026-10-28 |
| 上游 | 《tang_db 设计方案》§11(查询执行与优化器)· §19-P6 |
| 前置 | SPEC-P5 v0.1b(已验收,代码 `d6563df`) |
| 本规约认领的跨期挂账 | P5 §2.2 明确"归 P6"的 6 项 + P5 未兑现项 1 项(见 §2.4) |

---

## 1. 目标

P5 之前,计划全部是**固定规则**:单表访问路径靠"有没有可用索引"(RBO),JOIN 靠"两表都等值就建哈希、按已物化的宽度选 build 侧"(`QueryEngine.java:258-261`,`estimateWidth` 取的是内存里已有的行,不是统计),集合运算与相关子查询一律 1235。

本期把"选择"这件事做成**有据可依**:

1. **统计信息**:表级行数、列级 distinct / min / max / null 比例、等深直方图,落在 FE 的系统表里,与用户数据同一条 WAL 通道、同一事务语义,重启不丢。
2. **CBO**:基数估算 + 相对代价模型,选择访问路径、连接顺序、连接算法、聚合与排序的物理实现。
3. **补齐计划层才能做的语法**:`UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT`、相关子查询、`EXISTS` / `ANY` / `ALL`、`SELECT DISTINCT`、`FROM (子查询)` 派生表。
4. **回填 P5 欠账**:`information_schema` 上的 JOIN / 子查询 / 聚合真正放开(现在被 `SqlEngine.java:457-464` 的字符串门禁一律拒成 1235),并把已经过期的提示文案改对。

**落地判据(做完能看到什么)**:

- 同一张 SQL,`ANALYZE` 前后 `EXPLAIN` 的访问路径/连接顺序**会变**,且变的方向能被解释(选择率高走全表扫,选择率低走索引;小表建哈希;最差连接顺序被换掉)。
- 表规模变化后,同一条三表 JOIN 的连接顺序**自动调整**。
- 集合运算、相关子查询、`EXISTS`、`DISTINCT`、派生表跑出来的结果,与"逐条朴素查询拼起来"的结果**逐行一致**。
- `information_schema` 上那条导致图形客户端报错的 `ROUTINES LEFT JOIN (子查询)` 不再 1235。
- 典型查询(§3.2 口径的 TPC-H 子集 Q1/Q3/Q5/Q6/Q10)在 CBO 下的耗时不比固定规则差,且至少两条有可量化改善。

---

## 2. 范围

### 2.1 本期做(按组)

**A 组 · 统计信息采集与持久化**

- `ANALYZE TABLE [db.]t`(全列)与 `ANALYZE TABLE [db.]t (c1, c2)`(指定列)。
- 表级统计:行数、总字节、采样率、分析时的提交序号、分析时刻、是否过期。
- 列级统计:`null_count`、`distinct_count`(小表精确、大表近似)、`min` / `max`、等深直方图(64 桶)。
- 自有诊断语句 `SHOW STATS [FROM t]` 输出统计口径与新鲜度。
- 统计落 **两张新的系统表**(ID 7 / 8),随用户数据同批写入、同事务语义、崩溃后一致。
- 统计过期判定:分析后行数变化超过阈值即标记 stale,`EXPLAIN` 显式标注并回落默认估算。
- 无统计时的默认估算(保证"没 ANALYZE 也能跑",只是选择不一定最优)。

**B 组 · CBO:基数估算与代价模型**

- 选择率公式表(等值 / 范围 / `IN` / `LIKE 'x%'` / `IS NULL` / `AND` / `OR` / 连接等值)。
- 相对代价模型:扫描行、索引项解析、回表、哈希构建、哈希探测、排序比较、TopN、溢写,各给一个权重常量。
- 索引路径 vs 全表扫描的代价比较(取代 P4 的"有索引就用")。
- 估算的**可解释性**:`EXPLAIN` 给出每一步的 rows 与 cost,以及依据(统计 / 默认)。

**C 组 · 访问路径、连接顺序与物理算子**

- 连接顺序:表数 ≤ 4 时穷举 left-deep 排列;> 4 时贪心(每步选使中间结果最小的那一张)。
- 哈希连接的 build 侧按**估算行数**选,不再按已物化宽度。
- 连接算法:等值 → Hash Join;两侧都已按连接键有序 → Sort-Merge(省一次排序);非等值 → NLJ;超内存 → Sort-Merge 溢写。
- 聚合:Hash Aggregate 与 Sort-based Aggregate 按估算选择(分组键 distinct 很小走 Hash,否则小内存下走排序)。
- 排序:内存排序 vs 外部归并(超 `sort_spill_bytes`);`ORDER BY ... LIMIT n` 且 n 小于总行数 30% 时走 TopN 堆。
- **结果不变性**:CBO 只影响"怎么跑",算子级开关 `optimizer_enabled=false` 必须回落到 P5 固定规则且结果逐行一致。

**D 组 · 谓词下推与列裁剪**

- 单表谓词下推到该表扫描;JOIN 的等值对进哈希键,其余留在残余过滤。
- 无聚合引用的 `HAVING` 折成 `WHERE`。
- 常量折叠与布尔短路(`NOT NOT`、恒真/恒假、除零恒 NULL)。
- `LIMIT` 在无 JOIN / 无聚合时下推到扫描;有 `ORDER BY` 时转 TopN。
- 列裁剪:**只解码并参与求值的列**(FE 侧),`EXPLAIN` 给出"读取列数 / 表列数"。

**E 组 · 集合运算**

- `UNION`、`UNION ALL`、`INTERSECT`、`EXCEPT`(`MINUS` 作为 `EXCEPT` 的别名)。
- 列数/类型对齐与类型提升;列名取左侧第一支。
- 括号与优先级:`INTERSECT` 高于 `UNION` / `EXCEPT`;分支自带 `ORDER BY` / `LIMIT` 必须用括号包裹。
- 去重实现:Hash Distinct + 超内存溢写。

**F 组 · 相关子查询、`EXISTS` / `ANY` / `ALL`**

- 相关子查询(标量与 `IN`)按外层行驱动、内层带参数探测,并按相关性取值做结果缓存。
- `EXISTS` / `NOT EXISTS`、`= ANY` / `> ALL` 等比较量词,含 NULL 的三值语义。
- 安全形态去相关:内层无聚合且只等值引用外层列 → 转半连接 / 内连接(其余保持逐行执行)。
- 一条语句一个快照:子查询与外层同快照,不与并发写交叉。

**G 组 · `SELECT DISTINCT` 与派生表**

- `SELECT DISTINCT`:hash 去重 + 溢写,与 `GROUP BY` 结果一致(对拍)。
- `FROM (SELECT ...) 别名`:列别名、嵌套上限 8 层、可与 JOIN / 聚合组合。
- 派生表内引用外层列(横向相关)→ 明确报 1235,不做。

**H 组 · P5 欠账回填与 EXPLAIN 扩展**

- 删掉 `information_schema` 的语句文本门禁,虚拟表查询走统一计划器:JOIN / 子查询 / 聚合 / `ORDER BY` / `DISTINCT` 全部放开;真正不支持的形态报**准确**的码与文案(不再是"归 P5")。
- 虚拟表的统计不参与 CBO(固定行数估计),避免每次客户端自省扫全量虚拟表。
- 补 `information_schema.PARAMETERS` 虚拟表(空表 0 行),让"存储过程/函数"面板不再报"表不存在"。
- `EXPLAIN` 保留 P4 既有行格式(向后兼容 SC-320 那批断言),追加 `估算` 与 `依据` 两类行。

**I 组 · 可观测与门限**

- `SHOW STATS` 给出:分析耗时、扫描行数、采样率、近似 distinct 的误差界。
- 性能门限:CBO 相对固定规则不得让 TPC-H 子集任一条变慢超过 10%,且至少两条快 20% 以上。
- 计划稳定性:同一份统计下重复 `EXPLAIN` 输出完全一致(不允许 hash 顺序之类的不确定因素影响文本)。

### 2.2 本期明确不做(及归属)

| 不做 | 归属 / 理由 |
|---|---|
| 窗口函数、`GROUPING SETS` / `ROLLUP` / `CUBE` | **P14 之后的候选**(设计文档 §11.4 已登记),"自适应档" |
| 自适应优化(运行时反馈改计划)、智能索引、慢查询根因诊断 | **P14**(设计文档 §11.4 已细化到 P14) |
| 表达式索引 / 函数索引 / 路径索引 | **P11 之后** |
| JSON 路径索引、路径级列存 | 不做(设计文档 §2.4-S-08 明文) |
| 并行执行(多线程 / 多分片流水线) | **P8 之后**;本期仍单线程,靠计划选择与算子拿收益 |
| **网络级列裁剪**(BE 只回需要的列) | 不做:BE 不认识行格式(`RowCodec` 在 FE),要做得把解码下沉 BE,踩破"BE 不懂表/列"的红线。本期只做 FE 侧"不解码用不到的列" |
| 多列统计(相关性 / 多列直方图)、采样之外的倾斜处理 | 不做:JOIN 等值按独立假设;倾斜留给 P14 的自适应 |
| `EXPLAIN ANALYZE`(实际行数回填) | **P8**(与慢查询日志同期) |
| 视图 / 存储过程 / 触发器 / CTE(`WITH`) | 不做,沿用 P4/P5 §2.2;`WITH` 未排期 |
| `ALTER TABLE` 任何形态 | 仍是"P4 之后",不提前 |
| `NOW()` / `DATE_ADD` / `TIMESTAMP` / `DATETIME` | **P12** |
| `VECTOR` 类型与 HNSW 相关的一切 | **P7**。本期只留**代价接口**(见 §7),不实现 ANN |
| 统计信息自动触发(写入达阈值自动 ANALYZE) | 不做:本期只有显式 `ANALYZE`;自动触发归 P14 的自适应档 |

### 2.3 保证 / 不保证

- **保证**
  1. **算子级结果不变性**:任意查询在 `optimizer_enabled=true/false` 下结果逐行一致(硬性验收,对拍实现见 SC-375)。
  2. 统计信息与用户数据同事务语义:分析中途崩溃,重启后统计表要么是旧值要么是新值,不存在半套。
  3. 集合运算、相关子查询、`DISTINCT`、派生表的结果与"朴素逐条执行"一致(含 NULL 的三值场景)。
  4. `information_schema` 上 P5 §2.2 之外不再有"实现拒了但规约说支持"的形态。
- **不保证**
  1. 估算精度(允许估算与真实行数差一个数量级,只要求"选择方向正确")。
  2. 计划在所有数据分布上最优(倾斜数据下可能选错,提供 `optimizer_enabled=false` 兜底)。
  3. CBO 的绝对代价可比(权重是相对单位,不映射到毫秒)。

### 2.4 本期认领的跨期挂账

| # | 来源 | 条款 | 本期落点 |
|---|---|---|---|
| 1 | P5 §2.2 | 统计信息 / CBO 代价模型 / 连接顺序选择 | A / B / C 组 |
| 2 | P5 §2.2 | `Sort-Merge Join`、超大规模外存 JOIN | C 组(FR-640 ~ FR-642) |
| 3 | P5 §2.2 | `UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT` | E 组 |
| 4 | P5 §2.2 | 相关子查询、`EXISTS` / `ANY` / `ALL` | F 组 |
| 5 | P5 §2.2 | 压缩的"可查询编码"与向量化谓词改写 | **不认领**:需 BE 侧理解列语义,与"BE 不懂表列"冲突;改归 P8 的 segment 优化(P8 再看是否值得) |
| 6 | P5 §2.2 | `SELECT DISTINCT`(P5 代码提示"归 P6") | G 组 |
| 7 | **P5 未兑现项**(验收报告 §6.2 与规约 §10 均写"本期起支持") | `information_schema` 上的 JOIN / 子查询 / 聚合 | H 组(FR-667 ~ FR-669) |
| 8 | 本次实测补充 | `FROM (子查询)` 派生表未排期 | G 组(FR-664 ~ FR-666),**需拍板 I36** |

---

## 3. BDD 场景

| # | 场景 | 期望(可观测) | 验收方式 |
|---|---|---|---|
| ★P6-1 | 对 10 万行表执行 `ANALYZE`,中途 `taskkill /F` 杀 BE | 重启后 `SHOW STATS` 要么显示上次的统计(含上次的分析时刻),要么显示本次完整统计;不出现 `row_count` 有值而直方图为空之类的半套 | `scripts/tests/p6-test.sh` 的 SC-372 |
| ★P6-2 | 两个会话同时对同一张表 `ANALYZE` | 两者都能结束(或后者覆盖前者),`SHOW STATS` 只有一份完整统计;无 1235 / 无主键冲突 | SC-373 |
| P6-3 | 同一 SQL,`ANALYZE` 前后各 `EXPLAIN` 一次 | 计划文本不同(至少一行)且结果集逐行一致 | SC-374 |
| P6-4 | 同一 SQL,`optimizer_enabled=true` 与 `false` 各跑一次 | 结果逐行一致(计划可以不同) | SC-375 |
| P6-5 | 三表 JOIN,把其中一张表从 100 行灌到 10 万行后重新 `ANALYZE` | `EXPLAIN` 的连接顺序变化(大表不再当 build 侧) | SC-376 |
| P6-6 | `UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT` 四种各跑一次(含重复行、含 NULL) | 行数与内容等于逐条查询手工合并的结果 | SC-377 |
| P6-7 | 相关子查询、`EXISTS` / `NOT EXISTS` / `= ANY` / `> ALL`(含内层返回 NULL 与空集) | 与朴素实现一致;`NOT EXISTS` 在内层空集时为真 | SC-378 |
| P6-8 | `SELECT DISTINCT` 与等价的 `GROUP BY` | 结果逐行一致(顺序不保证) | SC-379 |
| P6-9 | 派生表:单层、两层嵌套、与 JOIN 组合、列别名 | 结果正确;引用外层列的横向派生表报 1235 | SC-380 |
| P6-10 | 图形客户端自省那句 `information_schema.ROUTINES LEFT JOIN (子查询) ...` 按原句执行 | 不再 1235(返回 0 行或结果集) | SC-381 |
| P6-11 | 统计过期:分析后继续写入使行数增长超过阈值 | `EXPLAIN` 标注 stale 并回落默认估算;结果不变 | SC-382 |

★ 只打在并发与崩溃两类上。

---

## 4. 需求(FR)

### 4.1 A 组 · 统计信息采集与持久化(FR-615 ~ FR-625)

| 编号 | 需求 |
|---|---|
| FR-615 | 支持 `ANALYZE TABLE [db.]t`(全列)与 `ANALYZE TABLE [db.]t (c1[, c2...])`(指定列);表不存在 → 1146,列不存在 → 1054 |
| FR-616 | 表级统计至少含:`row_count`、`total_bytes`、`analyzed_commit_ts`、`last_analyze`(时间文本)、`sample_rate`(千分比)、`stale`(BOOLEAN) |
| FR-617 | 列级统计至少含:`null_count`、`distinct_count`、`distinct_is_approx`(BOOLEAN)、`min`、`max`、`histogram`(JSON) |
| FR-618 | 直方图为**等深** 64 桶,存桶上界值与桶内行数;`INT` / `BIGINT` / `DOUBLE` / `DATE` / `VARCHAR` 建直方图;`BOOLEAN` / `JSON` 只做 `null_count` 与 `distinct_count` |
| FR-619 | `distinct_count` 在扫描行数 ≤ 100,000 时用精确计数,超过时用自研 HyperLogLog(p=14,m=16384,相对误差 ≈0.81%),并在 `SHOW STATS` 标注 `approx` 与误差界 |
| FR-620 | 采集走**统一扫描通道**(FE 侧 `kv.scan` 顺序读 + 列式聚合),不在 BE 增加统计算子;≤ 200,000 行全量,超过按块抽样(每 20 个 batch 取 1),采样率写入表级统计 |
| FR-621 | 统计落在系统表 `sys_table_stats`(ID 7)与 `sys_column_stats`(ID 8);一张表的一次 `ANALYZE` 的**全部列统计**必须是一个批记录(单事务)提交,崩溃后不留半套 |
| FR-622 | `SHOW STATS [FROM t]` 输出:库名、表名、行数、总字节、采样率、是否近似、分析时刻、分析耗时、扫描行数、是否 stale |
| FR-623 | 统计过期判定:`analyzed_commit_ts` 之后的写入使**行数变化 > 20%** 或 `null_count` 相关列被 `ALTER`(本期末实现)时置 stale;`EXPLAIN` 遇到 stale 统计必须显式标注并回落默认值 |
| FR-624 | `ANALYZE` 在显式事务(BEGIN 之后)中执行 → 1235,提示"ANALYZE 不能在事务中执行" |
| FR-625 | 统计缺失时的默认估算:行数取 segment 元数据(无则 10,000);列 distinct 取 100;直方图缺失时范围选择率取 1/3;`EXPLAIN` 的"依据"行必须写 `默认(未 ANALYZE)` |

### 4.2 B 组 · CBO 基数估算与代价模型(FR-626 ~ FR-634)

| 编号 | 需求 |
|---|---|
| FR-626 | 选择率按固定公式表计算:`=` → `1/distinct`;范围 → 直方图累计;`IN` → `n/distinct`;`LIKE 'x%'` → 0.20;`IS NULL` → `null_count/row_count`;`NOT` → `1-p` |
| FR-627 | 组合选择率:`AND` 取各因子乘积(独立假设);`OR` 取容斥 `1-(1-p1)(1-p2)`;三值逻辑下 NULL 行不参与 `=` 匹配 |
| FR-628 | 连接选择率:`左 distinct × 右 distinct` 取大者为分母(`1/max(dL,dR)`);无统计时取 1/10 |
| FR-629 | 代价模型常量(相对单位,行成本):全表扫描 1.0 / 索引项扫描 1.5 / 回表 2.0 / 哈希构建 1.2 / 哈希探测 1.0 / 排序比较 0.3×log2(n) / TopN 0.5 / 溢写到磁盘 5.0;常量集中在单一类里,便于调参 |
| FR-630 | 访问路径代价 = `估算扫描行 × 扫描单位代价 + 估算回表行 × 2.0`;全表扫描代价 = `行数 × 1.0`;选择器取代价更低者,`EXPLAIN` 必须显示两条路径的估算代价 |
| FR-631 | 估算必须可解释:`EXPLAIN` 每个算子给 `估算: rows=N, cost=C`,并在末行给 `依据: 统计(t=<分析时刻>)` 或 `依据: 默认(未 ANALYZE)` |
| FR-632 | 计划稳定性:同一份统计、同一条 SQL,重复 `EXPLAIN` 的文本完全一致(集合遍历不许用 `HashMap` 直接决定顺序) |
| FR-633 | `optimizer_enabled=false` 时所有估算与选择退化为 P5 固定规则(索引存在即用、JOIN 恒 Hash、排序恒内存),用于结果对拍与故障兜底 |
| FR-634 | CBO 只在"多个候选都能得出同一结果"的前提下选择;任何会被估算改变**结果集内容或行序**的改写一律禁止(如把 LEFT JOIN 重排成 INNER) |

### 4.3 C 组 · 访问路径、连接顺序与物理算子(FR-635 ~ FR-643)

| 编号 | 需求 |
|---|---|
| FR-635 | 访问路径选择覆盖 P4 的五条路径(主键点查/主键范围/索引点查/索引范围/全表扫),每条都要给代价并按最小值选,不得"有索引就用" |
| FR-636 | 连接顺序:表数 ≤ 4 时穷举所有 left-deep 排列;> 4 时贪心(每步选使中间结果估算最小的表接上) |
| FR-637 | 哈希连接的 build 侧按**估算行数**选,不再用已物化宽度;`EXPLAIN` 显示 `build=左/右` 与两侧估算行数 |
| FR-638 | 等值连接默认 Hash Join;两侧都已在连接键上有序(主键/索引序可证明)时可选 Sort-Merge 并省去一次排序;非等值仍走 NLJ,但 NLJ 前必须把右表谓词下推 |
| FR-639 | 超内存连接:`估算 build 行 × 行宽 > 连接可用内存` 时走 Sort-Merge + 溢写;溢写路径必须复用 P5 的 `Sorter`(不许新造第二套) |
| FR-640 | 聚合算子选择:`GROUP BY` 键的估算 distinct ≤ 10,000 走 Hash Aggregate,否则在可用内存不足时改 Sort-based Aggregate;`EXPLAIN` 显示所选实现 |
| FR-641 | 排序算子选择:估算数据量 ≤ `sort_spill_bytes` 走内存排序,否则外部归并;`ORDER BY ... LIMIT n` 且 n ≤ 总行数 30% 时走 TopN 堆 |
| FR-642 | 所有"多候选"算子(扫描、连接算法、build 侧、聚合、排序)的选择都必须能通过 `EXPLAIN` 从文本看出选了哪一个、代价多少 |
| FR-643 | 空集短路:当估算行数为 0 且统计非 stale 时,后续算子直接返回空结果(不执行);`optimizer_enabled=false` 时不短路 |

### 4.4 D 组 · 谓词下推与列裁剪(FR-644 ~ FR-649)

| 编号 | 需求 |
|---|---|
| FR-644 | `WHERE` 中的单表谓词下推到该表扫描;JOIN 的等值对进哈希键,非等值部分留残余过滤 |
| FR-645 | `HAVING` 中不引用聚合函数的条件折成 `WHERE`(在聚合前执行),`EXPLAIN` 显示"已下推" |
| FR-646 | 常量折叠与短路:`NOT NOT x`、恒真/恒假、`1=1`、除零恒 NULL,均在建计划阶段折叠,不得进入执行期 |
| FR-647 | `LIMIT` 在无 JOIN、无聚合、无 `ORDER BY` 时下推到扫描(传给 BE 的 `ScanRequest.limit`);有 `ORDER BY` 时转 TopN |
| FR-648 | 列裁剪:执行期只解码与投影/谓词/连接键/聚合键相关的列;`EXPLAIN` 给出 `列: 读取 k / 表 n 列`;明确**不减少**网络传输与解压量(见 §2.2) |
| FR-649 | 子查询去相关仅限"安全形态":内层无聚合、只等值引用外层列 → 转半连接/内连接;不安全形态保持逐行执行,不许改写结果语义 |

### 4.5 E 组 · 集合运算(FR-650 ~ FR-655)

| 编号 | 需求 |
|---|---|
| FR-650 | 支持 `UNION`(去重)与 `UNION ALL`(不去重),合并前做列数对齐(不等 → 1222)与类型提升(`INT`→`BIGINT`→`DOUBLE`;数值与 `VARCHAR` 混合 → 1222) |
| FR-651 | 支持 `INTERSECT` 与 `EXCEPT`(默认 DISTINCT 语义),`MINUS` 作为 `EXCEPT` 的别名;`INTERSECT ALL` / `EXCEPT ALL` 报 1235 并写明归后续 |
| FR-652 | 优先级:`INTERSECT` 高于 `UNION` / `EXCEPT`,同级左结合;括号可改变结合;分支自带 `ORDER BY` / `LIMIT` 未加括号 → 1064 |
| FR-653 | 结果列名取左侧第一支;整体 `ORDER BY` 只能引用结果列名或列序号,引用源表列 → 1054 |
| FR-654 | 去重实现为 Hash Distinct,超 `sort_spill_bytes` 走溢写;`UNION` 的 NULL 视为相等(与 `DISTINCT` 一致) |
| FR-655 | 集合运算参与 CBO:先把每支估算了再决定是否对去重做溢写;`EXPLAIN` 显示每支的算子与去重方式 |

### 4.6 F 组 · 相关子查询、`EXISTS` / `ANY` / `ALL`(FR-656 ~ FR-661)

| 编号 | 需求 |
|---|---|
| FR-656 | 相关子查询(标量与 `IN`)按外层行驱动执行:内层每次带外层相关列的当前值做参数化探测,结果按相关性取值缓存 |
| FR-657 | 支持 `EXISTS` / `NOT EXISTS`:`EXISTS` 只看是否有行(不比较内容);内层空集时 `NOT EXISTS` 为真 |
| FR-658 | 支持 `= ANY` / `<> ALL` 等比较量词(`SOME` 作为 `ANY` 别名);含 NULL 的三值语义与 MySQL 一致:比较集合含 NULL 且无匹配 → 结果 NULL,`NOT` 之后仍为 NULL |
| FR-659 | 缓存上限:`subquery_cache_max_entries`(默认 4096),超限按 LRU 淘汰;`SHOW SESSION`/`EXPLAIN` 能看出是否命中缓存(命中数计入 `EXPLAIN` 的附加行) |
| FR-660 | 一条语句一个快照:相关子查询与外层共用同一 `ReadView`,不与并发提交交叉可见 |
| FR-661 | 相关子查询与集合运算/派生表组合时,`ORDER BY` / `LIMIT` 语义按 MySQL 执行顺序(内层先算完再参与外层) |

### 4.7 G 组 · `SELECT DISTINCT` 与派生表(FR-662 ~ FR-666)

| 编号 | 需求 |
|---|---|
| FR-662 | `SELECT DISTINCT` 对整个投影列表去重(hash + 溢写),与等价 `GROUP BY` 结果逐行一致 |
| FR-663 | `DISTINCT` 与聚合同现时先聚合后去重;`DISTINCT` + `ORDER BY` 时先排序去重、再取 `LIMIT`(与 P5 的 TopN 复用小顶堆) |
| FR-664 | 支持 `FROM (SELECT ...) [AS] 别名`:必须有别名(否则 1248),列别名可用,可与 JOIN / 聚合 / 排序组合 |
| FR-665 | 派生表嵌套上限 8 层,超过 → 1235 并写明上限;派生表内不得引用外层列(横向相关)→ 1235 |
| FR-666 | 派生表的估算:由内层计划的估算行数向上传递;`EXPLAIN` 用缩进显示层次,内层算子必须可见 |

### 4.8 H 组 · P5 欠账回填与 EXPLAIN 扩展(FR-667 ~ FR-671)

| 编号 | 需求 |
|---|---|
| FR-667 | 删除 `information_schema` 的语句文本门禁(`fe/.../execute/SqlEngine.java:457-464`),虚拟表查询进统一计划器;JOIN / 子查询 / 聚合 / `ORDER BY` / `DISTINCT` 全部放开 |
| FR-668 | 虚拟表上的估算使用固定值(每张表登记行数上限,如 `TABLES`=表数、`COLUMNS`=列数、`ROUTINES`=0),不得为估算去扫虚拟表 |
| FR-669 | 新增虚拟表 `information_schema.PARAMETERS`(列与 MySQL 对齐,数据恒为空,0 行);虚拟表总数由 17 变 18,`SHOW` 家族与自省断言同步更新 |
| FR-670 | 门禁移除后仍不支持的形态必须报**准确的码与文案**:横向派生表、`WITH`、窗口函数、`INTERSECT ALL` 各给 1235 且文案点明归属期次;禁止再出现"归 P5"这类过期文案 |
| FR-671 | `EXPLAIN` 保留 P4 的既有行(访问路径 / 表 / 索引 / key 范围 / 残余过滤 / 扫描方式),**追加** `估算` 与 `依据` 行;P4 的 SC 断言不许改判为 SKIP |

### 4.9 I 组 · 可观测与门限(FR-672 ~ FR-675)

| 编号 | 需求 |
|---|---|
| FR-672 | `SHOW STATS` 必须给出分析耗时与扫描行数,便于判断 `ANALYZE` 的成本 |
| FR-673 | 性能门限:TPC-H 子集 Q1/Q3/Q5/Q6/Q10 在 CBO 下相对 `optimizer_enabled=false` 单条**不得慢超过 10%**,且至少两条快 ≥ 20%(同机同数据,取三次中位数) |
| FR-674 | 计划文本稳定性与可 diff:`EXPLAIN` 输出行不含时间戳/对象 hash,只有"依据"行含分析时刻 |
| FR-675 | 统计信息与样本量写入 `SHOW STATS` 的固定列,不允许只在日志里 |

---

## 5. 数据与格式

### 5.1 新增系统表(登记进 `SystemTables` 的 ID 表)

| ID | 名称 | key | 列(按顺序) |
|---|---|---|---|
| 7 | `sys_table_stats` | `db_name`(保序) + `table_id`(4B BE) | `db_name VARCHAR(64)`, `table_name VARCHAR(64)`, `row_count BIGINT`, `total_bytes BIGINT`, `analyzed_commit_ts BIGINT`, `sample_permille INT`, `last_analyze VARCHAR(32)`, `stale BOOLEAN` |
| 8 | `sys_column_stats` | `table_id`(4B BE) + `ordinal`(2B BE) | `table_id BIGINT`, `ordinal INT`, `column_name VARCHAR(64)`, `null_count BIGINT`, `distinct_count BIGINT`, `distinct_is_approx BOOLEAN`, `min_value VARCHAR(64)`, `max_value VARCHAR(64)`, `histogram JSON` |

- 两张表都是**系统表**,`REGISTERED_IDS` 相应扩大;用户表 ID 仍从 64 起,不受影响。
- 一次 `ANALYZE` 的写入集合(表级 1 行 + 该表所有列统计行)必须走**同一个批记录**(与 P3 的原子批写同一条路径)。

### 5.2 直方图 JSON 布局

```json
{"buckets":64,"bounds":["10","27","55"],"counts":[120,98,101]}
```

- `bounds` 长度 ≤ 64(等深桶的上界),`counts` 与 `bounds` 等长;空表 → `{"buckets":64,"bounds":[],"counts":[]}`。
- `min_value` / `max_value` 用与列类型一致的文本表示(`DATE` 用 `YYYY-MM-DD`,`DOUBLE` 用最短往返文本)。

### 5.3 新增配置

| 配置 | 位置 | 默认 | 含义 |
|---|---|---|---|
| `optimizer_enabled` | `fe.conf` | `true` | `false` = 退回 P5 固定规则(对拍与兜底) |
| `analyze_full_scan_max_rows` | `fe.conf` | `200000` | 超过此值改抽样 |
| `analyze_sample_stride` | `fe.conf` | `20` | 抽样:每 N 个 batch 取 1 |
| `analyze_histogram_buckets` | `fe.conf` | `64` | 等深桶数 |
| `analyze_exact_distinct_max_rows` | `fe.conf` | `100000` | 精确 distinct 的行数上限 |
| `analyze_hll_precision` | `fe.conf` | `14` | HyperLogLog 精度(m=2^p) |
| `cbo_join_enum_max_tables` | `fe.conf` | `4` | 穷举连接顺序的表数上限 |
| `cbo_hash_aggregate_distinct_max` | `fe.conf` | `10000` | 走 Hash Aggregate 的分组 distinct 上限 |
| `cbo_topn_limit_ratio_percent` | `fe.conf` | `30` | TopN 的 `LIMIT/行数` 阈值 |
| `subquery_cache_max_entries` | `fe.conf` | `4096` | 相关子查询结果缓存条目上限 |
| `stats_stale_row_change_percent` | `fe.conf` | `20` | 统计过期判定的行数变化阈值 |

> 所有配置项沿用"命令行 > 配置文件 > 内置默认值"的三级优先级;写错即启动失败并指出行号。

### 5.4 `EXPLAIN` 输出(追加行,不改既有行)

```
访问路径: INDEX RANGE
表: orders
索引: idx_orders_date(列 order_date,索引 ID 2)
key 范围: [...]
残余过滤: status = 'paid'(由 FE 按谓词执行)
扫描方式: 索引区间扫描 + 逐行回表(二次校验)
估算: rows=1832, cost=5496          <- 新增
依据: 统计(t=2026-10-28 10:40:12)   <- 新增
```

---

## 6. K 决策表

| # | 决策点 | 推荐值 | 理由 |
|---|---|---|---|
| K120 | 统计信息暴露方式 | 只用自有 `SHOW STATS`,**不动** `information_schema` 的既有 17 张表(仅因 FR-669 增加 `PARAMETERS`) | P4 的 SC 断言按虚拟表数量与列清单写死,新增统计表会连带改一批验收;`SHOW STATS` 是自有诊断语句,口径自由 |
| K121 | 统计存放位置 | FE 系统表(ID 7 / 8),与 catalog 同一通道 | 复用 P2 起的系统表机制与 P3 的原子批写,不需要新存储结构,也不会让 BE 懂表/列 |
| K122 | 分析粒度 | 表级 + 列级,列级按需(`ANALYZE ... (c1,c2)` 可只分析关心的列) | 全列分析在宽表上代价高;JOIN/过滤列才是 CBO 真正需要的 |
| K123 | distinct 精度 | ≤ 10 万行精确,超过用自研 HLL(p=14) | 精确集合在千万行会撑爆内存;HLL 自研约 100 行,误差 0.81% 对"选路"足够 |
| K124 | 直方图形态 | 等深 64 桶,存桶上界 + 桶内计数 | 等深对倾斜分布比等宽稳;64 桶在估算精度与统计体积间平衡(单列 ~1KB) |
| K125 | 采集路径 | FE 侧走统一 `Scan` + 列式聚合,不新增 BE 算子 | 守住 FE/BE 边界:统计需要列语义,只有 FE 懂 |
| K126 | 全量 vs 采样 | ≤ 20 万行全量,超过每 20 batch 取 1 | 实测本机扫描+聚合吞吐约 9 万行/秒(ecom 库四张表合计 26.8 万行、四次全表扫描/聚合共约 3 秒),20 万行全量分析在数秒内;再多就抽样 |
| K127 | 统计过期口径 | 行数变化 > 20% 即 stale | 比"每次写入都更新统计"便宜得多,也比永不失效更贴近真实分布 |
| K128 | CBO 开关 | `optimizer_enabled` 默认 true,false 退回 P5 规则 | 结果不变性对拍必须有开关;线上出问题也能一键回退 |
| K129 | 连接顺序算法 | ≤ 4 表穷举 left-deep,> 4 贪心 | 穷举是最坏 24 个排列,代价可忽略;bushy 树收益在本项目规模下不明显,复杂度却翻倍 |
| K130 | 溢写实现 | 复用 P5 `Sorter` 的外部归并 | 已有实现经过 P5 验收,新造第二套会增加崩溃面 |
| K131 | 集合运算去重 | Hash Distinct + 溢写,`UNION` 的 NULL 视为相等 | 与 `DISTINCT` / 分组一致,避免"同一语义两套判等" |
| K132 | 相关子查询策略 | 逐行驱动 + 结果缓存,只对"安全形态"去相关 | 完整 decorrelation 是 P14 自适应档的量级;安全形态收益最大且易验证 |
| K133 | 派生表 | 纳入本期(嵌套上限 8 层),不做横向相关 | 计划层天然产物,`information_schema` 自省语句里就在用;横向相关是另一套语义(归 P8 之后) |
| K134 | `information_schema` 放开的范围 | 放开 JOIN/子查询/聚合/ORDER BY/DISTINCT;虚拟表统计用固定值 | 客户端自省要的就是这些形态;固定值避免每次刷新都扫虚拟表 |
| K135 | `PARAMETERS` 虚拟表 | 补空表(0 行) | 客户端"存储过程/函数"面板先问这张表;补空表成本极低,收益是面板不再报错 |
| K136 | 列裁剪口径 | 只做 FE 侧"不解码用不上的列",不减少网络与解压 | 真正的网络级投影要让 BE 解行格式,踩破铁律;写清口径免得下一期被当 bug 报 |
| K137 | 代价常量存放 | 单一 `CostModel` 类 + 配置可覆盖 | 调参集中一处;规约只要求"方向正确",不锁定具体数字 |

---

## 7. 与 P7 的接口约定(外部评审"混合查询语义先行"的 P6 侧回答)

外部评审曾指出:"顺序定为 精确向量检索 → ANN → CBO 选路"。P6 只落**前半句中的代价框架**,不实现任何向量能力:

| 事项 | P6 结论 |
|---|---|
| 路径候选的登记 | P6 的访问路径选择表留一个"向量索引扫描"槽位(代价函数由 P7 注册),本期该槽位永远不被选中(没有 `VECTOR` 类型) |
| 估算来源 | P7 的 HNSW 段需要自己的统计(图规模、层数、删除率),P6 只要求它按同一套 `CostModel` 接口上报,不预留字段实现 |
| 混合查询的路径决策 | **不在 P6 做**:它依赖 ANN 的召回语义,归 P7;P6 只保证单表结构化谓词的代价估算足够准 |
| 红线 | P6 不许为 P7 提前引入 `VECTOR` 类型、`proto` 字段或磁盘格式变化 |

---

## 8. 验收标准(SC)

| 编号 | 验收项 | 判定(可观测) |
|---|---|---|
| SC-370 | `ANALYZE` 建统计 | 10 万行表分析后 `SHOW STATS` 行数等于 `COUNT(*)`,列统计条数等于分析列数 |
| SC-371 | 直方图与 distinct | 制造 skew 数据(单值占 60%),`SHOW STATS` 里该值的直方图桶计数占比 ≈ 60%(±10%);近似 distinct 与精确值相对误差 ≤ 5% |
| ★SC-372 | 分析中途崩溃 | 强杀 BE 后重启,`SHOW STATS` 要么全旧要么全新;`COUNT(*)` 与统计行数的一致性不被破坏 |
| ★SC-373 | 并发两次 `ANALYZE` | 两个会话都拿到 `CODE=0`;`SHOW STATS` 一份完整统计;无 1213 / 1062 |
| SC-374 | `ANALYZE` 前后计划变化 | 同一 SQL 两次 `EXPLAIN` 至少一行不同,且两次结果集逐行一致 |
| SC-375 | CBO 结果不变性 | 12 条代表性 SQL(含 JOIN / 聚合 / 子查询 / 集合运算)在 `optimizer_enabled=true/false` 下结果逐行一致 |
| SC-376 | 连接顺序随规模调整 | 小表灌大后重新 `ANALYZE`,`EXPLAIN` 的 `build=左/右` 或连接次序发生变化 |
| SC-377 | 集合运算 | 四种各 3 组用例(重复行 / NULL / 类型混合),行数与内容等于手工合并结果;`INTERSECT ALL` 报 1235 |
| SC-378 | 相关子查询与量词 | `EXISTS` / `NOT EXISTS` / `= ANY` / `> ALL` 各 2 组(含内层空集、含 NULL),与朴素实现一致 |
| SC-379 | `DISTINCT` | 5 组用例,与等价 `GROUP BY` 逐行一致 |
| SC-380 | 派生表 | 单层 / 两层 / 与 JOIN 组合 / 列别名各一组;横向相关报 1235 |
| SC-381 | 客户端自省放行 | `information_schema` 上 JOIN / 子查询 / 聚合各 1 条真实自省语句 `CODE=0`;`PARAMETERS` 返回 0 行 |
| SC-382 | 统计过期 | 写入使行数变化 > 20% 后 `EXPLAIN` 出现 stale 标注;结果不变 |
| SC-383 | `EXPLAIN` 向后兼容 | P4 的 5 类访问路径、`SHOW SEGMENTS` 八列等既有断言全部仍通过;新增行只在末尾追加 |
| SC-384 | 谓词下推/列裁剪可见 | `EXPLAIN` 显示"已下推"与 `列: 读取 k / 表 n 列`;`LIMIT` 场景下 `ScanRequest.limit` 生效(结果行数正确) |
| SC-385 | 常量折叠与短路 | `WHERE 1=1` / `NOT NOT id > 1` / `amount/0` 三类结果正确,且 `EXPLAIN` 里残留下推形态为折叠后的样子 |
| SC-386 | 计划稳定性 | 同一条 SQL 连续 3 次 `EXPLAIN` 文本完全一致 |
| SC-387 | 性能门限 | Q1/Q3/Q5/Q6/Q10 单条相对 `optimizer_enabled=false` 不变慢 > 10%,且 ≥ 2 条快 ≥ 20%(三次中位数) |
| SC-388 | 统计持久化 | 重启 BE/fed 后 `SHOW STATS` 内容与重启前一致 |
| SC-389 | `ANALYZE` 事务约束 | `BEGIN; ANALYZE TABLE t;` → 1235 且事务可继续 `ROLLBACK` |
| SC-390 | 既有回归 | `p2-test.sh` / `p3-test.sh` / `p35-test.sh` / `p4-test.sh` 单跑全绿;`crash-test.sh` 丢失 0 条 |

---

## 9. 边界与异常清单

| # | 场景 | 期望 |
|---|---|---|
| 65 | `ANALYZE TABLE` 不存在的表 | 1146 |
| 66 | `ANALYZE TABLE t (不存在的列)` | 1054 |
| 67 | `ANALYZE` 空表 | 成功,`row_count=0`,直方图为空数组 |
| 68 | `ANALYZE` 只有 1 行的表 | 成功,直方图 1 个桶 |
| 69 | 表在 `ANALYZE` 期间被 `DROP` | 统计写入失败不返回半条;`SHOW STATS` 不残留该表 |
| 70 | 列值全为 NULL | `null_count=row_count`,`min`/`max` 为 NULL,直方图为空 |
| 71 | `VARCHAR` 超长值进 `min`/`max` | 截断到 64 字节并保持可比较前缀,不报错 |
| 72 | JSON 列 | 只统计 `null_count` 与 `distinct_count`,不建直方图 |
| 73 | `UNION` 两支列数不同 | 1222,文案点明两侧列数 |
| 74 | `UNION` 数值与 `VARCHAR` 混合 | 1222(不做隐式转字符串) |
| 75 | `UNION` 分支带 `ORDER BY` 未加括号 | 1064 |
| 76 | `ORDER BY` 引用源表列 | 1054 |
| 77 | `INTERSECT ALL` / `EXCEPT ALL` | 1235,文案写明归后续 |
| 78 | 派生表缺别名 | 1248 |
| 79 | 派生表嵌套超过 8 层 | 1235,文案写明上限 |
| 80 | 派生表内引用外层列 | 1235 |
| 81 | `NOT EXISTS` 内层空集 | 返回真 |
| 82 | `NOT IN` 子查询含 NULL | 返回空集(三值语义),不报错 |
| 83 | `= ANY` 内层含 NULL 且无匹配 | 结果 NULL |
| 84 | 相关子查询缓存超限 | 按 LRU 淘汰,结果不变 |
| 85 | `optimizer_enabled=false` | 所有选择退回固定规则,结果不变 |
| 86 | 统计 stale + 选择率极端值 | 回落默认值,不抛异常 |
| 87 | 代价比较出现相等 | 取固定优先级(点查 > 范围 > 索引 > 全表),保证计划稳定 |
| 88 | `information_schema` 上的 JOIN | 正常返回;不动 BE |
| 89 | `information_schema.PARAMETERS` | 0 行,列与 MySQL 对齐 |
| 90 | `EXPLAIN` 一条集合运算 | 显示每支的扫描算子与去重方式 |
| 91 | 超大排序/连接溢写 | 溢出到 `sort_tmp_dir`,失败时报 1114 并保留临时文件名 |
| 92 | `ANALYZE` 期间 BE 不可用 | 报"连不上 BE",不写半套统计 |

---

## 10. 交付物清单

| 类别 | 内容 |
|---|---|
| 代码 | 统计采集(ANALYZE / HyperLogLog / 直方图)、两张系统表、`CostModel`、选择器(扫描/连接顺序/算子)、集合运算、相关子查询与量词、`DISTINCT`、派生表、`information_schema` 门禁移除 —— **全部自研**;`proto/` **不改**(统计走已有 `Scan`/`CommitTxn`) |
| 配置 | §5.3 的 11 项 |
| 脚本 | `scripts/tests/p6-test.sh`(一键验收,SC-370 ~ SC-390)、`scripts/sql/61~6x`(统计/集合运算/相关子查询/派生表演示) |
| 文档(编码方) | `README.md` 的分析查询用法补 `ANALYZE` / `SHOW STATS` / 集合运算 |
| 文档(核验方) | `docs/验收报告/P6-验收报告.md`、`docs/读代码地图/读代码地图-P6.md` |
| 索引 | `specs/README.md` 增 P6 行;`specs/invariants.md` 增 INV-19 / INV-20 |

---

## 11. 关于代码由谁写(沿用 D9)

- 编码由 **opencode** 按本规约实现并真跑测试;核验方独立重跑、不看自述。
- 编码方**不写**验收报告与读代码地图。
- 编码方不得引入第三方库(依赖红线不变):统计、HLL、直方图、CBO 全部手写。

---

## 12. 待拍板事项(施工前需要你拍,每项已给推荐值)

> **结论(2026-10-28,项目所有者回复"全按推荐值"):十项全部按推荐值定稿,下表"结论"列即施工口径,不再二次确认。**

| # | 问题 | 推荐值 | 备选 | 结论 |
|---|---|---|---|---|
| I29 | 统计信息暴露方式:`SHOW STATS` 还是塞进 `information_schema` | 只用 `SHOW STATS`(不动既有 17 张虚拟表的断言) | 新增 `COLUMN_STATISTICS` 虚拟表(要连带改 P4 的虚拟表数量断言) | **采纳 A:只用 `SHOW STATS`,虚拟表只按 FR-669 增加 `PARAMETERS`** |
| I30 | CBO 要不要给"开关" | 给 `optimizer_enabled`,默认开,关掉退回 P5 固定规则(结果对拍与兜底都靠它) | 不给开关(省事,但出问题没法一键回退) | **采纳 A:加 `optimizer_enabled`,默认 `true`** |
| I31 | `ANALYZE` 是否自动触发 | 只显式执行,自动触发归 P14 自适应档 | 写入达阈值自动分析(会在写路径上埋统计任务,P14 本来就要做) | **采纳 A:只支持显式 `ANALYZE`** |
| I32 | 采样策略 | ≤ 20 万行全量、超过每 20 batch 取 1 | 一律采样(快,但小表统计会不准) | **采纳 A:全量阈值 20 万行 + stride 20 抽样** |
| I33 | 是否本期补 `information_schema.PARAMETERS` | 补空表(客户端面板不再报"表不存在") | 不补(面板保持报错,虚拟表总数不变) | **采纳 A:补空表,虚拟表 17 → 18** |
| I34 | 集合运算范围 | `UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT` 四个全做,`ALL` 变体归后续 | 只做 `UNION` / `UNION ALL`(其余留 P8) | **采纳 A:四个全做;`INTERSECT ALL` / `EXCEPT ALL` 报 1235** |
| I35 | 相关子查询实现深度 | 逐行驱动 + 结果缓存 + 仅"安全形态"去相关 | 完整 decorrelation(收益更大,但等于多半个月的活) | **采纳 A:逐行驱动 + LRU 缓存 + 仅安全形态去相关** |
| I36 | `FROM (子查询)` 派生表是否纳入本期 | 纳入(客户端自省语句里就在用,且是计划层自然产物;上限 8 层,不做横向相关) | 不纳入(推迟到 P8 之后,`ROUTINES` 那句继续靠"客户端容错") | **采纳 A:纳入,嵌套上限 8 层,横向相关报 1235** |
| I37 | 列裁剪口径 | 只做 FE 侧"不解码用不上的列",网络与解压量不降(`EXPLAIN` 里写清) | 改 `proto` 让 BE 支持列投影(踩破"BE 不懂表/列") | **采纳 A:`proto` 不改,只做 FE 侧裁剪** |
| I38 | 性能门限严格度 | 单条不变慢 > 10%,且至少两条快 ≥ 20%(够证明 CBO 有收益,又不至于为达标调参) | 改成"平均快 20%"(松,但可能掩盖单条退化) | **采纳 A:单条 ≤ +10%,另需 ≥ 2 条 ≥ -20%(快 20%)** |

---

## 13. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.1a | 2026-10-28 | **所有者批准(2026-10-28)**:状态 Draft → **Approved**,可施工。§12 十项(I29~I38)按推荐值一次写死(见该节"结论"列):`SHOW STATS` 单一暴露 / `optimizer_enabled` 开关 / 只显式 `ANALYZE` / 20 万行全量 + stride 20 抽样 / 补 `PARAMETERS`(虚拟表 17→18)/ 四个集合运算全做 / 逐行驱动 + LRU 缓存 + 仅安全形态去相关 / 派生表纳入(8 层)/ 列裁剪只做 FE 侧(`proto` 不改)/ 性能门限单条 ≤ +10% 且 ≥ 2 条快 20% |
| 0.1 | 2026-10-28 | 初稿(**Draft**):按设计文档 §11 / §19-P6 起草。范围 2.1 九组(A 统计 / B CBO / C 连接与算子 / D 下推与裁剪 / E 集合运算 / F 相关子查询与量词 / G DISTINCT 与派生表 / H P5 欠账回填与 EXPLAIN / I 门限),2.2 逐条写死不做项与归属;61 条 FR(FR-615~675)、18 条 K(K120~137)、21 条 SC(SC-370~390)、28 条边界(65~92)、10 项待拍板(I29~I38);§7 正面回答外部评审挂给 P6 的"混合查询语义先行";§2.4 认领 P5 §2.2 挂账 7 项、明确不认领 1 项(压缩的可查询编码,改归 P8) |

---

## 附:本规约钉在哪些现有实现上(起草前读码结论)

| 现状 | 位置 | 本期怎么改 |
|---|---|---|
| 访问路径只有 RBO(有索引就用) | `fe/.../planner/Planner.java:74` `chooseIndexPath` | 换代价比较(FR-630 / FR-635) |
| JOIN 的 build 侧按已物化宽度 | `fe/.../query/QueryEngine.java:258-261` | 改按估算行数(FR-637) |
| JOIN 恒 Hash(等值)/ NLJ(非等值),无 Sort-Merge | `QueryEngine.java:169-183` | 加算法选择(FR-638~639) |
| EXPLAIN 无代价与估算 | `QueryEngine.java:871-910`、`Executor.java:818-880` | 追加估算/依据行(FR-631 / FR-671) |
| 无任何统计信息与 `ANALYZE` | 全库 grep 无 `ANALYZE` / statistics 实现 | A 组全新增 |
| `information_schema` 文本门禁 | `fe/.../execute/SqlEngine.java:457-464` | 删除并走统一计划器(FR-667) |
| 集合运算 / 相关子查询 / `DISTINCT` / 派生表 | 解析期即 1235 / 1064 | E/F/G 组 |
| 系统表 ID 7、8 空着 | `fe/.../catalog/SystemTables.java:30-48` | 登记两张统计表(§5.1) |
