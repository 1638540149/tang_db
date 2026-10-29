# tang_db 全面测试套件

本套件由当前任务自编，覆盖 BE/FE 构建与单测、JDBC 端到端功能、数据导入导出、查询效率、并发连接和崩溃恢复。测试通过独立的 BE gRPC、FE MySQL/心跳端口和本轮隔离数据目录运行，不启动或停止日常服务。

## 运行

Windows Git Bash 中执行：

```bash
bash verification/P7之前的测试/run-full.sh
```

如果 FE 的 Maven 单测与 IDE 后台编译争用 `fe/target`，可用 `TANGDB_TEST_SKIP_FE_UNIT=1` 跳过该阶段；报告会明确标成 `SKIP`，不可将其视为本轮通过。FE 服务仍会重新构建并参加 JDBC 联测。

默认使用 BE `12340`、FE MySQL `12346`、FE 心跳 `12341`。运行前检查三个端口是否空闲。可分别用 `TANGDB_TEST_BE_PORT`、`TANGDB_TEST_MYSQL_PORT`、`TANGDB_TEST_HEART_PORT` 覆盖。每轮输出到独立时间戳目录 `results/<时间>/`，不会清理其他运行或仓库现有测试目录。

服务由脚本单独构建并以独立配置启动。强杀恢复场景只终止本轮启动的 BE 进程 PID；退出清理也只处理本轮记录的 FE/BE 进程。没有通过进程名批量杀服务的逻辑。

## 自编场景

- JDBC 连接与 Catalog：建库建表、`DatabaseMetaData` 库表发现及索引元数据兼容核查、`information_schema`、EXPLAIN。
- SQL 正确性：INSERT/UPDATE/DELETE、提交/回滚、IN/NOT/范围/LIKE/NULL 谓词、排序、INNER/LEFT/CROSS/RIGHT JOIN、GROUP BY/HAVING、DISTINCT、子查询、UNION/INTERSECT/EXCEPT、函数及 JSON、唯一键冲突 1062。
- 导入/导出：生成 50,000 行 CSV，通过项目 CSV 导入命令导入；校验行数、distinct 主键和 checksum；使用 JDBC 有序导出并逐行对比输入内容。
- 性能：记录 CSV 导入墙钟速率、50,000 行全表聚合扫描三轮中位数与行吞吐、8 个 JDBC 会话并发提交吞吐。
- 并发：8 个独立 JDBC 连接同时提交共 800 行，核对总行数和唯一键数量。
- 可靠性：先确认 100 行已返回 JDBC COMMIT 成功，再强制终止 BE 并重启核对数据；另启用 `after_fsync` 注入点模拟提交中途掉电，重启核对 WAL 重放。
- 事务与状态：两个 JDBC 会话验证快照重复读及同一行写写冲突；执行 ANALYZE/SHOW STATS，并查询 segments、backends、sessions 和 processlist。
- BE Go build/vet/test、FE compile/test，以及 JDBC 测试程序自身编译。

## 输出

- `TEST-REPORT.md`：最近一轮总结与阶段状态。
- `results/<时间>/run.log`：命令、实际输出和性能数据。
- `results/<时间>/status.tsv`：逐阶段退出码。
- `results/<时间>/bulk.csv` 和 `bulk-export.csv`：导入输入及导出结果。
- `results/<时间>/data/`：测试数据目录，可用于复核崩溃恢复后的 segment/WAL 状态。

当前自动化不覆盖图形客户端人工操作、长时间 soak、峰值 RSS 采样和 `go test -race`；报告会明确列为未验证项。
