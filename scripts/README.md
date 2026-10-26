# scripts 目录

启动脚本只有**两个**;测试脚本在 **`tests/`** —— 分开是为了让「怎么把服务起起来」和「怎么验一遍」不混在同一层。

## 启动(日常用)

| 脚本 | 作用 |
|---|---|
| `start-be.sh` | 起 BE(`config/be.conf`,默认 gRPC 9100);`--daemon` 后台,`stop` 停止 |
| `start-fe.sh` | 起**常驻 FE**(`fed`):MySQL 协议 3406 + 心跳 9091;`--daemon` 后台,`stop` 停止 |
| `start-fe.sh cli` | 起瘦客户端 CLI,通过协议连已启动的 `fed` |
| `gen-proto.sh` | 重新生成 gRPC 桩代码(改过 `proto/` 才需要) |
| `sql/` | 可复现演示脚本(`01~35`),按场景编号 |

```bash
./scripts/start-be.sh --daemon         # 终端 A:后台起 BE(日志 tmp/be.log,PID tmp/be.pid)
./scripts/start-fe.sh --daemon         # 终端 B:后台起常驻 FE(日志 tmp/fed.log,PID tmp/fed.pid)
./scripts/start-fe.sh cli -D shop      # 客户端(参数 --host/--port/-u/-p/-D)
./scripts/start-fe.sh stop             # 停常驻 FE(优雅退出,上限 5 秒)
./scripts/start-be.sh stop             # 停 BE
```

不带 `--daemon` 时两个脚本都是前台启动(日志打屏、Ctrl+C 退出)。

**停止的实情**:Windows 上 `taskkill`(不带 `/F`)送不到 SIGTERM,`stop` 会在约 5 秒后强制结束;要优雅退出就用前台 + `Ctrl+C`(JVM shutdown hook 按 FR-413 收尾)。强杀不丢已提交数据(WAL 已 fsync),只丢未提交事务。

## 测试(`tests/`,一键验收)

| 脚本 | 覆盖 |
|---|---|
| `tests/p35-test.sh` | **P3.5 一键验收**:SC-301 ~ SC-318(常驻/多会话、跨会话 DDL、多库、同名表、库级 DDL、老目录升级、表 ID、协议正确性、依赖红线 + P2/P3 回归) |
| `tests/p3-test.sh` | P3:SC-201 ~ SC-211(事务、并发写写冲突、4 个崩溃注入点、序号不回退、快照稳定、DDL 原子性)+ P2/P1 回归 |
| `tests/p2-test.sh` | P2 + P2.1:11 个场景 + SQL 兼容性断言 |
| `tests/crash-test.sh` | P1 崩溃基线:1000 条随机 KV,强杀重启后丢失 0 条 |

```bash
bash scripts/tests/p35-test.sh                # 报告 → tmp/p35-test/report.txt
bash scripts/tests/p3-test.sh                 # 报告 → tmp/p3-test/report.txt
bash scripts/tests/p2-test.sh                 # 报告 → tmp/p2-test/report.txt
COUNT=5000 bash scripts/tests/crash-test.sh   # 报告 → tmp/crash-test/report.txt
```

**注意**:这些脚本会用独立高位端口与独立数据目录(`tmp/<脚本名>/`),但都会 `taskkill` 掉所有 `be.exe` / `fed` 进程 —— **不要与其它会话(包括编码 agent)同时跑同一套脚本**,否则互相踩。
