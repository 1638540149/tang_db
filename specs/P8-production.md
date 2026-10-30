# SPEC-P8 · 生产化:协议兼容 / 监控 / 慢查询诊断 / 备份恢复 / Web 控制台

| 项 | 值 |
|---|---|
| 期次 | P8(**Approved**,2026-10-30 所有者选"B 全量开发所有功能") |
| 版本 | 0.1a |
| 起草日期 | 2026-10-30 |
| 上游 | `docs/tang_db-设计方案.md` §19-P8、§2.5(接口与运维)、§3.6(控制台形态) |
| 前置 | SPEC-P7.1(向量检索时延补丁) |
| 本期定位 | 从"能跑"到"别人也能用":外部客户端连得上、状态看得见、慢了自己能说清原因、数据能备份能恢复、有浏览器界面 |

---

## 1. 为什么现在做

前七期都是"引擎能力":存储、SQL、事务、索引、列存、CBO、AI/向量。但按设计文档 §19 的定义,P8 才是让它**能被别人用**的一期:

- 现在只有自研 CLI 与 MySQL 协议的一个子集;DataGrip 能连但元数据面板有缺口(P7 已补双 FROM),`getIndexInfo` 之外还有若干探测语句未覆盖;
- **没有任何监控指标**:QPS、延迟、内存表、segment、缓存命中率全看不见;
- **没有慢查询记录**:出了问题只能靠人肉复现;
- **没有备份恢复**:数据目录只能停库拷贝;
- **没有界面**:只能命令行。

---

## 2. 范围

### 2.1 本期做(六组)

**A 组 · MySQL 协议兼容收口(FR-813 ~ FR-822)**

- 补齐 `COM_STMT_PREPARE` / `COM_STMT_EXECUTE` / `COM_STMT_CLOSE`?**本期不做**二进制预处理协议(见 §2.2),但必须**明确回错误而不是断开连接**;
- 明确支持的命令:握手/认证(`mysql_native_password`)、`COM_QUERY`、`COM_PING`、`COM_INIT_DB`、`COM_QUIT`、`COM_FIELD_LIST`(可选);
- DataGrip / Navicat / Connector/J 的元数据探测语句全部 `CODE=0`:清单见 `references/gui-client-introspection-sql.md` 的 P8 增量项(含 `SHOW TABLE STATUS`、`SHOW COLLATION`、`SHOW CHARSET`、`SELECT @@xxx`、`information_schema` 补表);
- 错误码与错误包格式与 MySQL 8 对齐(已有的 1062/1213/1146/1235 保持);
- 连接与认证可配:`mysql_max_connections`、`mysql_wait_timeout`、`mysql_ssl`(**默认关闭**,见 §2.2)。

**B 组 · 监控指标(FR-823 ~ FR-830)**

- FE 暴露 `GET /metrics`(Prometheus 文本格式)与 `SHOW METRICS`(SQL 通道,便于没有抓取器时人工看);
- 指标族:查询 QPS / 延迟分位(P50/P95/P99)/ 错误码计数 / 活跃会话数 / 事务提交与冲突数 / 慢查询计数;
- BE 指标:memtable 大小与条目数 / immutable 队列 / segment 数与总字节 / compaction 次数与耗时 / WAL fsync 次数与耗时 / group commit 平均批量 / 向量图段数与命中率;
- 指标从 BE 心跳与 FE 自身统计里汇总,**不引入任何第三方监控库**(Prometheus 文本格式手写);
- 指标可开关(`metrics_enabled`),关闭后零开销。

**C 组 · 慢查询日志与诊断建议(FR-831 ~ FR-840)**

- 超过 `slow_query_threshold_ms`(默认 200)的语句记录:时间/耗时/行数/客户端/库/表/SQL 摘要(截断 4KB);
- 记录落到 `sys_slow_query` 系统表(**表 ID 10**)与可选日志文件;
- **诊断建议**(设计文档 §19-P8 明确要求):
  - 计划摘要(访问路径、是否走索引、估算 vs 实际行数偏差);
  - 谓词建议(哪些列出现在 WHERE 但没有索引);
  - 索引建议(候选索引 + 估算收益);
  - **每条建议必须带证据**(计划行/统计数字),不许给"玄学结论";
- `SHOW SLOW QUERIES [LIMIT n]` / `SHOW SLOW QUERY <id>`;
- 建议生成**完全基于确定性规则**,不调用模型。

**D 组 · 备份与恢复(FR-841 ~ FR-850)**

- `BACKUP TO '<目录>'`:在线一致性备份 —— 先取一次 checkpoint(封 memtable、刷完 immutable),再复制 MANIFEST + 全部 segment + 图段 + WAL 尾部,最后写 `BACKUP_META`(版本、format_version、时间、校验和清单);
- 备份期间**不阻塞写入**(靠 `format_version` + MANIFEST 原子切换保证一致性);
- `RESTORE FROM '<目录>'`:目标目录必须为空(或显式 `FORCE`),校验清单与 `format_version`,恢复后可直接启动;
- 增量备份:按 segment 编号增量复制(`BACKUP ... INCREMENTAL FROM <上次备份>`);
- 备份产物带校验和,恢复前逐个校验,不一致直接失败;
- 备份/恢复进度可通过 `SHOW BACKUP JOBS` 查看。

**E 组 · Web 控制台(FR-851 ~ FR-862)**

- 技术栈:**Vue 3 + Element Plus**(所有者 2026-10-30 指定),构建产物(`dist/`)由 FE 内置 HTTP 服务直接托管,**不引入任何 Java Web 框架**;
- 四块内容(设计文档 §3.6):
  1. **左侧库/表树**:库 → 表 → 列/索引,点表出 DDL;
  2. **上方 SQL 输入**:多语句、`Ctrl+Enter` 执行、执行计划按钮;
  3. **右下结果表格**:分页、列排序、NULL 显示、一键复制 CSV;
  4. **自身状态**:BE 存活/离线、memtable 大小、segment 数与字节、QPS、P95/P99、慢查询 Top-N、向量索引状态;
- 登录:沿用 MySQL 账号(root/密码),会话 token 由 FE 签发(不引入 OAuth);
- 只读默认:**写操作(DML/DDL)需要在界面上显式确认**后才执行;
- 前端源码进仓库(`console/` 目录),`dist/` 作为构建产物由 CI/本地构建生成并提交(演示项目要求可直接跑);
- 附件里的 Node 版本:v24.19.0 / npm 11.17(npmmirror 源)。

**F 组 · 压测与验收脚本(FR-863 ~ FR-870)**

- `scripts/tests/p8-test.sh`:覆盖 SC-446~465,含 ★ 备份期间写入、★ 控制台并发;
- 复用 `verification/P7之前的测试/jmeter` 的 JMeter 计划(8 线程混合负载)作为压测口径,输出 QPS/P95/P99/错误率;
- 监控指标与压测数据必须**同一次运行**采集(否则 QPS 对不上)。

### 2.2 本期明确不做(逐条写死归属)

| 不做项 | 归属 |
|---|---|
| 二进制预处理协议(`COM_STMT_*` 的真实现) | **不做**,只回明确错误;JDBC 用文本协议即可 |
| TLS / `mysql_ssl` 的真实现 | **不做**,默认关闭(所有者明确不想上证书,内部通道用明文) |
| 主从复制 / binlog | 未排期 |
| 对象存储备份、云备份 | **不做**(依赖红线) |
| 慢查询的 AI 诊断(模型参与判断) | **不做**;P14 的自适应档才考虑,且红线是"模型不参与正确性判断" |
| Web 控制台的多用户/权限体系 | **不做**(单用户/单账号);RBAC 归 P13 |
| 控制台的 SQL 编辑器高级能力(补全、格式化) | 不做,只做基础输入 |
| 全文检索 / 多模态 / 空间 | P10 / P11 / P12 |

---

## 3. BDD 场景

### 场景 1:外部客户端直连
- **给**:FE 已启动,MySQL 端口 3406
- **当**:`docker run --rm -i mysql:8.0 mysql -h host.docker.internal -P 3406 -u root -p*** -e "SELECT 1"`,以及 DataGrip 连接后展开库表树、看索引页
- **那么**:两条路都 `CODE=0`;DataGrip 的库/表/列/索引面板都有内容;`SHOW INDEX` 返回 ≥1 行
- **反例**:`COM_STMT_PREPARE` → 返回明确错误码而不是断连
- **验收**:FR-813~822,SC-446/447

### 场景 2:监控看得到
- **给**:混合负载跑 60 秒
- **当**:`curl /metrics` 与 `SHOW METRICS`
- **那么**:QPS 与压测工具统计的 QPS 偏差 ≤10%;P95/P99 有值;BE 的 memtable/segment 数字与实际一致
- **验收**:FR-823~830,SC-448/449

### 场景 3:慢查询给出可核对的建议
- **给**:一张 10 万行表,`WHERE 未建索引的列 = ?` 跑一条 300ms 的查询
- **当**:超过阈值后看 `SHOW SLOW QUERIES` 与诊断建议
- **那么**:记录里有语句摘要/耗时/行数;**建议里必须包含"该列无索引"并给出候选索引 + 计划证据**
- **反例**:索引已存在时不得再建议同一索引
- **验收**:FR-831~840,SC-450/451

### 场景 4(★崩溃):备份期间强杀
- **给**:在线备份进行中(数据量 5 万行以上)
- **当**:备份到一半强杀 BE
- **那么**:原库重启后数据完整;备份目录被标记为**不完整**(缺 `BACKUP_META`),`RESTORE` 拒绝它
- **验收**:FR-841~850,SC-452

### 场景 5(★并发):备份期间持续写入
- **给**:每秒 200 行写入
- **当**:执行 `BACKUP TO` 并等到完成
- **那么**:备份期间写入成功率 100%(无阻塞);备份完成后从备份恢复出来的库,`COUNT(*)` 等于**备份开始时**的快照行数,checksum 一致
- **验收**:FR-843/844,SC-453

### 场景 6:控制台四块内容
- **给**:浏览器打开 `http://127.0.0.1:8080`
- **当**:登录 → 展开库表树 → 写 `SELECT` 执行 → 看状态区
- **那么**:四块都有真实数据;状态区的 QPS/memtable/segment 与 `SHOW METRICS` 一致
- **验收**:FR-851~862,SC-454~458

---

## 4. 功能需求(FR,续排:上期最大 FR-812,本期从 FR-813 起)

### A 组 · 协议兼容(FR-813 ~ FR-822)

| 编号 | 需求 |
|---|---|
| FR-813 | 支持命令:握手/认证、`COM_QUERY`、`COM_PING`、`COM_INIT_DB`、`COM_QUIT`、`COM_FIELD_LIST`;其余命令返回 `ER_UNSUPPORTED`(不静默断连) |
| FR-814 | `COM_STMT_PREPARE/EXECUTE/CLOSE` 返回明确错误包(错误码 + 文案"本期未实现二进制预处理协议,请用文本协议"),连接保持 |
| FR-815 | DataGrip / Navicat / Connector/J 元数据探测语句全 `CODE=0`:清单固化进验收脚本,逐条断言 |
| FR-816 | `information_schema` 补齐客户端常用表(`COLLATIONS`、`CHARSET`、`ENGINES`、`ROUTINES` 空集不许报错) |
| FR-817 | `SHOW TABLE STATUS` / `SHOW COLLATION` / `SHOW CHARSET` / `SHOW VARIABLES LIKE` 返回结构与 MySQL 8 对齐 |
| FR-818 | 错误包格式与 MySQL 8 一致(序号/标记/错误码/SQLSTATE/消息),`1062`/`1213`/`1146`/`1235` 文案不变 |
| FR-819 | 连接参数可配:`mysql_max_connections`(默认 128)、`mysql_wait_timeout`(默认 0=不断开)、`mysql_ssl`(默认 false,置 true 时启动失败并说明未实现) |
| FR-820 | 会话变量:`SET autocommit`、`SET names utf8mb4`、`SET SESSION sql_mode` 接受但按已有语义处理(不改变行为) |
| FR-821 | 单包多语句、字符串内分号、注释(`--`、`#`、`/* */`)解析保持 P4 行为 |
| FR-822 | 协议层记录**每条语句的结果码与耗时**(供慢查询与指标使用),失败语句也记 |

### B 组 · 监控指标(FR-823 ~ FR-830)

| 编号 | 需求 |
|---|---|
| FR-823 | FE 提供 `GET /metrics`,Prometheus 文本格式(`# TYPE`/`# HELP` + `name{label} value`) |
| FR-824 | `SHOW METRICS [LIKE ...]` 返回同样数据(两列:metric/value),便于无抓取器时人工查看 |
| FR-825 | 查询侧指标:QPS、延迟 P50/P95/P99(滑动窗口 1 分钟)、错误码计数、活跃会话数、慢查询计数 |
| FR-826 | 事务侧指标:提交数、冲突数(1213)、唯一冲突数(1062)、平均提交耗时 |
| FR-827 | BE 指标(经心跳上报):memtable 字节与条目、immutable 队列长度、segment 数与字节、compaction 次数/耗时、WAL fsync 次数/耗时、group commit 平均批量 |
| FR-828 | 向量指标:各向量索引的段数、节点数、待重建段数、检索次数、平均候选数 |
| FR-829 | `metrics_enabled`(默认 true);false 时不采集不计时,`/metrics` 返回 404 之外的空集 |
| FR-830 | 指标名与单位写进 README 的表,命名统一 `<子系统>_<对象>_<单位>`(如 `fe_query_qps`、`be_memtable_bytes`) |

### C 组 · 慢查询与诊断(FR-831 ~ FR-840)

| 编号 | 需求 |
|---|---|
| FR-831 | 超过 `slow_query_threshold_ms`(默认 200)的语句落 `sys_slow_query`(系统表 **10**):时间/耗时/扫描行/返回行/客户端/库/表/SQL 摘要 |
| FR-832 | SQL 摘要截断到 4KB,保留首尾(中间打 `...`),并记录 SQL 指纹(去字面量后的规范化文本) |
| FR-833 | `SHOW SLOW QUERIES [LIMIT n]`、`SHOW SLOW QUERY <id>`;输出列固定 |
| FR-834 | 诊断建议:①计划摘要(访问路径/是否用索引/估算 vs 实际)②无索引谓词列 ③候选索引 + 估算收益 ④统计是否过期 |
| FR-835 | **每条建议必须带证据字段**(计划行或统计数字);无证据时输出"证据不足",不许给结论 |
| FR-836 | 建议生成纯规则,不调用任何模型;同输入同版本 → 同输出(可复现) |
| FR-837 | 索引建议去重:已存在的索引/主键列不再建议 |
| FR-838 | `slow_query_log`(默认 true)与 `slow_query_threshold_ms` 可配;关闭后不落表 |
| FR-839 | 系统表 10 的容量上限(默认 10,000 行,超出按时间淘汰最旧) |
| FR-840 | 慢查询本身的开销可忽略:阈值内语句不得因此变慢(打点只在超阈值后落库) |

### D 组 · 备份恢复(FR-841 ~ FR-850)

| 编号 | 需求 |
|---|---|
| FR-841 | `BACKUP TO '<dir>'`:先 checkpoint(封 memtable、刷完 immutable),再复制 MANIFEST + segment + 图段 + 必要的 WAL,写 `BACKUP_META`(含文件清单与校验和) |
| FR-842 | 备份期间**不阻塞写入**;备份点(快照)在开始时就确定,后续写入不进入本次备份 |
| FR-843 | 备份完成前不写 `BACKUP_META`;中途失败/强杀 → 目录被视为不完整,`RESTORE` 拒绝 |
| FR-844 | `RESTORE FROM '<dir>'`:目标必须为空或 `FORCE`;校验清单、`format_version`、逐文件校验和;失败即中止且不留下半恢复目录 |
| FR-845 | 增量备份:`BACKUP TO ... INCREMENTAL FROM '<上次目录>'`,只复制新增 segment 与 MANIFEST |
| FR-846 | `SHOW BACKUP JOBS`:任务 id/开始时间/状态/已复制字节/总字节 |
| FR-847 | 备份/恢复是**后台任务**,前台可继续查询;任务异常终止要留下可见状态 |
| FR-848 | 备份产物带库版本与 `format_version`;版本不匹配时 `RESTORE` 明确报错 |
| FR-849 | 备份目录结构固定:`BACKUP_META` + `manifest/` + `sst/` + `wal/`(相对路径,可整体搬走) |
| FR-850 | 备份完成后原库行为不变(不得因备份改变 compaction 策略或阻塞 flush 太久) |

### E 组 · Web 控制台(FR-851 ~ FR-862)

| 编号 | 需求 |
|---|---|
| FR-851 | 前端 **Vue 3 + Element Plus**,源码在 `console/`,构建产物 `console/dist` 由 FE 的 JDK 内置 HTTP 服务托管(零 Java 新依赖) |
| FR-852 | 路由:`/`(控制台)、`/metrics`(指标)、`/api/*`(控制台后端),静态资源与 API 同端口(默认 `console_port` 8080) |
| FR-853 | 登录:`POST /api/login`(用户名/密码走 FE 的账号体系)→ 返回 token;后续请求带 token;失败限速 |
| FR-854 | 库表树:`GET /api/tree` 返回库→表→列/索引;点击出 DDL(`SHOW CREATE TABLE`) |
| FR-855 | SQL 执行:`POST /api/query` 支持多语句,返回列名/行/NULL 标记/耗时/影响行数 |
| FR-856 | 结果表格:分页(默认 100 行/页)、列排序、NULL 显示为 `NULL`、可复制为 CSV |
| FR-857 | 执行计划:`POST /api/explain` 返回 `EXPLAIN` 文本(含 P6.1 的"并行/批/下推"行与 P7 的"向量检索"行) |
| FR-858 | 状态区:`GET /api/status` 返回 BE 存活、memtable 字节、segment 数/字节、QPS、P95/P99、慢查询 Top10、向量索引状态 |
| FR-859 | 写操作(DML/DDL)必须前端二次确认;被拒绝的写操作要在界面提示原因 |
| FR-860 | 控制台的错误展示:错误码 + 文案 + 客户端会话 id(便于对齐服务端日志) |
| FR-861 | 控制台所有请求走 FE 的同一执行入口(复用 `SqlEngine`),**不新增第二个 SQL 入口** |
| FR-862 | 控制台会话与 MySQL 会话隔离:控制台查询不得占用 MySQL 连接池额度 |

### F 组 · 脚本与门限(FR-863 ~ FR-870)

| 编号 | 需求 |
|---|---|
| FR-863 | `scripts/tests/p8-test.sh` 覆盖 SC-446~465,含 ★ 备份期间写入、★ 备份中强杀、★ 控制台并发 |
| FR-864 | JMeter 计划(8 线程混合负载)纳入脚本,输出 QPS/P95/P99/错误率 |
| FR-865 | 监控指标与压测在同一次运行内采集并对照 |
| FR-866 | 协议探测语句清单固化为脚本断言(逐条 `CODE=0`) |
| FR-867 | 备份恢复的端到端:备份 → 破坏原库(删数据目录)→ 恢复 → 校验行数与 checksum |
| FR-868 | 控制台冒烟:脚本只用 `curl` 验证 API(HTML 渲染为人工项) |
| FR-869 | 全部 SC 门限在报告中带机器条件模板 |
| FR-870 | 依赖红线:FE 不引 Web 框架/监控库;控制台前端依赖只允许 Vue 3 + Element Plus + 构建工具 |

---

## 5. 数据与格式

### 5.1 系统表与目录

| 表 ID | 名称 | 内容 |
|---|---|---|
| **10** | `sys_slow_query` | 慢查询记录(FR-831) |
| **11** | `sys_backup_jobs` | 备份/恢复任务状态(FR-846) |

系统表登记表更新(`SystemTables.REGISTERED_IDS`),`next_table_id` 下限不变。

### 5.2 备份目录

```
<backup-dir>/
  BACKUP_META          版本、format_version、时间、快照行数、文件清单+校验和
  manifest/CURRENT, MANIFEST-*
  sst/<seg>.seg, <seg>-<idx>.hnsw
  wal/0000xx.wal       (仅备份点之后仍需要的尾部)
```

### 5.3 新增配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `console_port` / `console_enabled` | 8080 / true | 控制台 HTTP 服务 |
| `metrics_enabled` | true | 指标采集 |
| `slow_query_log` / `slow_query_threshold_ms` | true / 200 | 慢查询 |
| `slow_query_max_rows` | 10000 | 记录上限 |
| `mysql_max_connections` / `mysql_wait_timeout` / `mysql_ssl` | 128 / 0 / false | 协议侧 |
| `backup_dir` | 空 | 默认备份根目录 |

非法值启动失败并指出行号(与既有 12 项同口径)。

---

## 6. 关键技术决策(K,续排:上期最大 K184,本期从 K185 起)

| 编号 | 决策点 | 推荐值 | 理由 |
|---|---|---|---|
| K185 | 控制台技术栈 | **Vue 3 + Element Plus**(所有者指定) | 组件齐全、体积可控、国内源快 |
| K186 | 控制台后端 | **FE 内置 JDK `com.sun.net.httpserver`**,零 Java 新依赖 | 依赖红线;不引 Spring/Javalin/Vert.x |
| K187 | 前端产物是否入库 | **入**(`console/dist`),源码同仓 | 演示项目要求"拉下来就能跑",不要求现场 npm build |
| K188 | 指标暴露方式 | **Prometheus 文本 `/metrics` + `SHOW METRICS` 双通道** | 有抓取器时用前者,没有时用 SQL |
| K189 | 延迟分位算法 | 滑动窗口(1 分钟)直方图,P50/P95/P99 | 简单可复现,不引 HDR 库 |
| K190 | 慢查询指纹 | 规范化(去字面量/折叠空白)后 hash | 同族语句聚合 |
| K191 | 诊断建议 | **纯规则 + 必须带证据** | 与设计文档红线一致:模型不参与正确性判断 |
| K192 | 备份一致性 | **checkpoint + 复制 + 校验和清单**,不阻塞写 | 在线备份必须不锁写 |
| K193 | 备份产物格式 | 目录 + `BACKUP_META`(JSON) | 可整体搬走、可人工检查 |
| K194 | 恢复目标 | 必须空目录或 `FORCE` | 防误删已有数据 |
| K195 | 控制台认证 | 用户名/密码 → token(FE 签发,内存态) | 单用户场景,不上 OAuth |
| K196 | 写操作确认 | 前端二次确认 + 服务端记录审计 | 防误操作 |
| K197 | TLS | **不做**,`mysql_ssl=true` 启动失败并说明 | 所有者明确不想维护证书 |
| K198 | 压测口径 | JMeter 8 线程 60 秒 + 指标同次采集 | 与 P7 的验证口径一致 |
| K199 | 控制台端口与 MySQL 端口隔离 | 8080 vs 3406 | 互不影响 |
| K200 | 前端构建工具 | **Vite**(node v24 / npm 11 / npmmirror) | 构建快、配置少 |

---

## 7. 验收标准(SC,续排:上期最大 SC-445,本期从 SC-446 起)

| 编号 | 项 | 判定 |
|---|---|---|
| SC-446 | 官方 mysql 客户端连通 | `docker mysql:8.0` 连上并 `SELECT 1` 成功 |
| SC-447 | 图形客户端元数据 | 探测语句清单逐条 `CODE=0`;`SHOW INDEX` ≥1 行;`information_schema` 新表可查 |
| SC-448 | 指标一致性 | `/metrics` 与 `SHOW METRICS` 数值一致;QPS 与 JMeter 统计偏差 ≤10% |
| SC-449 | 指标完整性 | 查询/事务/BE/向量四族至少各 3 个指标有值 |
| SC-450 | 慢查询落库 | 300ms 语句出现在 `SHOW SLOW QUERIES`,耗时与实测误差 ≤20% |
| SC-451 | 诊断建议可核对 | 建议包含"无索引列 + 候选索引 + 计划证据";索引已存在时不再建议 |
| SC-452 | ★备份中强杀 | 强杀后原库完整;备份目录无 `BACKUP_META`,被 `RESTORE` 拒绝 |
| SC-453 | ★备份期间写入 | 备份全程写入成功率 100%;恢复出的库 `COUNT(*)`/checksum 等于备份点快照 |
| SC-454 | 备份恢复端到端 | 备份 → 删数据目录 → `RESTORE` → 行数与 checksum 与备份点一致 |
| SC-455 | 增量备份 | 增量目录可独立恢复;总字节 < 全量 |
| SC-456 | 控制台 API 冒烟 | `/api/login`、`/api/tree`、`/api/query`、`/api/explain`、`/api/status` 全部 200 且结构正确 |
| SC-457 | 控制台状态一致 | 状态区的 QPS/memtable/segment 与 `SHOW METRICS` 一致 |
| SC-458 | 控制台写确认 | 未确认的写请求被拒绝;确认后生效并留审计 |
| SC-459 | 协议错误不断连 | `COM_STMT_PREPARE` 返回错误码后连接仍可执行 `SELECT 1` |
| SC-460 | 压测报告 | JMeter 8 线程 60 秒:QPS/P95/P99/错误率齐全,错误率 ≤0.1% |
| SC-461 | 指标开销 | `metrics_enabled=false` 时 QPS 相对开启状态提升不明显(≤5% 差异即视为零开销) |
| SC-462 | 备份不阻塞 | 备份期间写入 P99 不超过无备份时的 1.5 倍 |
| SC-463 | 依赖红线 | FE 无 Web/监控第三方 Java 库;前端仅 Vue3 + Element Plus + Vite |
| SC-464 | 既有回归 | `p3`/`p4`/`p5`/`p6`/`p6.1`/`p7`/`p7.1` 单跑全绿(逐个单跑) |
| SC-465 | 文档 | README 增 P8 章节(指标名表、备份用法、控制台截图说明) |

---

## 8. 边界与异常(续排:上期最大 165,本期从 166 起)

| # | 边界 | 期望行为 |
|---|---|---|
| 166 | `COM_STMT_PREPARE` | 返回明确错误、连接保持 |
| 167 | 超过 `mysql_max_connections` | 拒绝新连接并给错误码,已有连接不受影响 |
| 168 | 空闲超时 `mysql_wait_timeout>0` | 到期断开,日志可见 |
| 169 | `mysql_ssl=true` | 启动失败并说明未实现(不静默降级) |
| 170 | 控制台端口被占用 | 启动失败并指出端口(不静默换端口) |
| 171 | 控制台 token 过期/伪造 | 401,不泄露内部信息 |
| 172 | 控制台 SQL 语法错误 | 返回错误码+文案+会话 id,界面不崩 |
| 173 | 控制台返回超大结果(>10 万行) | 分页 + 上限(默认 1 万行),超出提示"请加 LIMIT" |
| 174 | 慢查询表达到上限 | 按时间淘汰最旧,不报错 |
| 175 | 阈值设为 0 | 等价于关闭慢查询记录 |
| 176 | 备份目录已存在且非空 | 报错要求换目录或 `FORCE` |
| 177 | 备份磁盘空间不足 | 明确失败,删半成品,原库不受影响 |
| 178 | 备份期间 compaction | 正常,备份清单以快照时的 segment 为准 |
| 179 | 恢复时 `format_version` 不匹配 | 拒绝,给出两边版本 |
| 180 | 恢复目录含未知文件 | 忽略并警告,不失败 |
| 181 | 增量备份的父目录被删 | 退化为全量并日志说明 |
| 182 | 备份任务被 KILL | 状态标 CANCELLED,半成品目录不留 `BACKUP_META` |
| 183 | 指标窗口内无请求 | 分位返回 0,不报错 |
| 184 | 指标名查询不存在 | `SHOW METRICS LIKE` 返回空集 |
| 185 | 控制台并发 8 会话 | 不互相阻塞;超出会话上限给明确错误 |
| 186 | 控制台与 MySQL 同时压测 | 双方指标独立计数,不串号 |
| 187 | 备份文件名冲突 | 追加序号,不覆盖 |
| 188 | 恢复中途失败 | 目标目录保持空(或回滚已复制的文件) |
| 189 | 慢查询 SQL 超 4KB | 截断且保留首尾 |
| 190 | 控制台在 BE 离线时 | 状态区显示离线,查询返回明确错误(不是空白页) |

---

## 9. 交付物

1. 代码:FE(协议补齐、metrics、slow query、backup、console 后端)、BE(备份支持、指标上报)、`console/`(Vue3 + Element Plus)
2. 单测 + `scripts/tests/p8-test.sh` + JMeter 计划纳入
3. README 的 P8 章节(指标名表、备份用法、控制台说明)
4. **不写** `docs/验收报告`、不改 `specs/`

## 10. 待拍板事项(**已全部拍板**,2026-10-30 所有者"B,全量开发所有功能")

| # | 决策 | 结论(已写死) |
|---|---|---|
| D1 | 控制台后端(K186) | **FE 内置 JDK HttpServer,零 Java 新依赖** |
| D2 | 前端产物是否入库(K187) | **入 `console/dist`**(拉下来就能跑) |
| D3 | 备份一致性(K192) | **checkpoint + 复制 + 校验和清单,不阻塞写** |
| D4 | 慢查询诊断深度(K191) | **计划摘要 + 无索引列 + 候选索引 + 统计过期,每条带证据** |
| D5 | 控制台认证(K195) | **用户名/密码 → token(FE 签发)** |
| D6 | TLS(K197) | **不做;`mysql_ssl=true` 启动失败并说明** |

## 11. 变更记录

| 版本 | 日期 | 变更 | 确认人 |
|---|---|---|---|
| 0.1a | 2026-10-30 | **状态改 Approved**(所有者选"B 全量开发所有功能")。D1~D6 按推荐值写死;控制台 Vue3 + Element Plus + FE 内置 HttpServer;前端 dist 入库;备份 checkpoint 不阻塞写;慢查询建议纯规则带证据;TLS 不做 | 王禹 |
| 0.1 | 2026-10-30 | 初稿(Draft):六组范围、FR-813~870、K185~200、SC-446~465、边界 166~190;控制台按所有者要求定为 Vue 3 + Element Plus | 待所有者确认 |
