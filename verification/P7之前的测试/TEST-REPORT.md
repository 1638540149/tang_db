# tang_db 全面测试报告

> 本轮由自编 Java JDBC 测试代码直连独立 MySQL 协议端口；不复用项目现有验收脚本。具体运行数据位于 /d/projects/tang_db/verification/P7之前的测试/results/20261029-160256。

## 本轮环境

- 时间：2026-10-29T16:06:04+08:00
- 操作系统：Microsoft Windows NT 10.0.26200.0
- Go / Java：go version go1.27.0 windows/amd64; java version "17.0.12" 2024-07-16 LTS
- BE gRPC：12340；FE MySQL：12346；FE 心跳：12341
- 隔离数据目录：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/data
- 导入数据：50,000 CSV 行；并发写：8 个 JDBC 会话；强杀：仅测试进程的精确 PID

## 阶段状态

| 阶段 | 退出码/状态 |
|---|---:|
| `be-build` | 0 |
| `be-vet` | 0 |
| `be-unit` | 0 |
| `fe-build` | 0 |
| `fe-unit` | SKIP |
| `jdbc-client-build` | 0 |
| `jdbc-functional` | 0 |
| `csv-import` | 0 |
| `csv-import-idempotent-rerun` | 0 |
| `jdbc-import-export-perf` | 0 |
| `jdbc-concurrency` | 0 |
| `jdbc-crash-seed` | 0 |
| `jdbc-crash-recovery` | 0 |
| `jdbc-after-fsync-write` | 0 |
| `jdbc-fsync-replay` | 0 |
| `jmeter-load` | 0 |

## 自编 JDBC 用例覆盖

- DDL/DML、事务提交与回滚、快照重复读、同一行写写冲突、IN/NOT/范围/LIKE/NULL 谓词、排序分页、INNER/LEFT/CROSS JOIN 和 RIGHT JOIN 声明能力核查、GROUP BY/HAVING、DISTINCT、子查询、UNION/INTERSECT/EXCEPT、函数/JSON、information_schema、JDBC 元数据、EXPLAIN、唯一索引重复错误码 1062。
- ANALYZE TABLE/SHOW STATS；SHOW SEGMENTS、SHOW BACKENDS、SHOW SESSIONS、SHOW PROCESSLIST。
- CSV 工具导入 50,000 行；核对总行数、主键去重、金额 checksum；JDBC 有序导出 CSV 并逐行校验，再核对导出行数。
- 测量 CSV 导入耗时、JDBC 全表聚合扫描中位耗时与行吞吐、8 连接写入吞吐；原始值见 run.log 与阶段日志。
- 8 个连接并发提交 800 行，验证提交成功、总数和主键唯一数。
- BE 收到 JDBC COMMIT 成功后被强制终止并重启，核对 100 行及 checksum；再通过 after_fsync 崩溃注入，重启后核对 WAL 重放。

## 本轮实测结果

- CSV 导入：csv_import_rows_per_sec=2158 elapsed_ms=23172；重复执行识别为已完成，未重复写入。
- 全表聚合扫描：jdbc_scan_50k_median_ms=75 rows_per_sec=666667 samples_ms=75,82,52。三轮均返回 checksum 24,975,000。
- JDBC CSV 导出：jdbc_export_50k_ms=20612 rows_per_sec=2426。耗时包含查询、JDBC 传输、排序读取和本地文件写入。
- 并发写入：concurrent_connections=8 committed_rows=800 elapsed_ms=889 rows_per_sec=900；800/800 行提交且主键均唯一。
- 数据完整性：导入行数、distinct 主键数均为 50,000；金额 checksum 为 24,975,000；导出 50,000 行逐行与生成规则核对。
- 崩溃恢复：已确认提交的 100 行重启后完整（checksum 14,850）；after_fsync 注入时客户端收到预期连接错误，未确认写在重启后由 WAL 恢复。
- 事务一致性：并发会话读到稳定快照；同一行写入时后提交事务报告写写冲突，未覆盖先提交值。
- 统计和状态：ANALYZE 后 SHOW STATS 有数据；segments 16 段、backends 1 个；sessions/processlist 均返回 JDBC 会话。
- JDBC DatabaseMetaData.getIndexInfo() 生成的 SHOW 查询被 FE 拒绝（错误码 1235）；项目原生 SHOW INDEX 能发现二级索引。该 Connector/J 元数据兼容项是本轮发现的问题。
- JMeter 5.6.3，8 线程/10 秒爬坡/60 秒：21,438 个 sampler，355.4 samples/s，平均 20 ms，最小 6 ms，最大 304 ms，错误 0%。
- JMeter 样本通过数：主键读 7,147/7,147；过滤聚合 7,145/7,145；唯一键插入 7,142/7,142。JTL 中包含 4 个成功的数据库建表/种子准备样本。
- JMeter 测试库：verify_jmeter_20261029_160456；脚本在结果目录生成对应清理 SQL。
- JMeter 5.6.3: summary =  21438 in 00:01:00 =  355.4/s Avg:    20 Min:     6 Max:   304 Err:     0 (0.00%)
- JMeter report: HTML report: D:\projects\tang_db\verification\full\jmeter\results\20261029-160456\html-report\index.html
- JMeter schema: Database: verify_jmeter_20261029_160456 (cleanup SQL saved to D:\projects\tang_db\verification\full\jmeter\results\20261029-160456\cleanup-db.sql)

## 功能断言失败项

[FAIL] RIGHT JOIN correctness expected=5 query_error=语法错误: 第 1 行第 31 列 本期只支持 INNER / LEFT / CROSS JOIN(RIGHT/FULL 未实现)

## 结果与限制

各阶段退出码以阶段状态表为准；功能断言失败项在上节逐条列出，不能因测试程序继续运行而视为通过。FE 单测曾在 20261029-150935 成功运行 392 项、0 失败；本轮按显式参数跳过 FE 单测，BE 全部 Go 单测通过。后续重复 FE 单测时，Maven 与 IntelliJ 后台编译同时使用 fe/target，测试运行出现 HyperLogLog 的 NoClassDefFoundError，因此本轮 FE 单测保持 SKIP 状态。性能值是该环境下的一次观测，不构成容量承诺。GUI 客户端人工操作、长时间耐久性 soak、内存峰值采样、超过 8 个连接的阶梯压测、Go race 检测、CSV 中断续传/坏行定位、KILL 会话、pushdown/query_parallelism 开关及每个函数的全参数边界、故障发生在 WAL fsync 之前的写入边界不属于本轮覆盖。

## 可复核材料

- 执行命令：TANGDB_TEST_SKIP_FE_UNIT=1 TANGDB_TEST_RUN_JMETER=1 bash verification/P7之前的测试/run-full.sh（Windows Git Bash）
- 总日志：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/run.log
- 阶段状态：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/status.tsv
- JDBC 导入输入：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/bulk.csv
- JDBC 导出结果：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/bulk-export.csv
- 数据目录：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/data
- JMeter HTML 报告：[index.html](D:/projects/tang_db/verification/P7之前的测试/jmeter/results/20261029-160456/html-report/index.html)
- JMeter JTL：/d/projects/tang_db/verification/P7之前的测试/jmeter/results/20261029-160456/samples.jtl
- JMeter 原始日志：/d/projects/tang_db/verification/P7之前的测试/results/20261029-160256/jmeter-load.log
- JMeter schema 清理脚本：[cleanup-db.sql](D:/projects/tang_db/verification/P7之前的测试/jmeter/results/20261029-160456/cleanup-db.sql)
