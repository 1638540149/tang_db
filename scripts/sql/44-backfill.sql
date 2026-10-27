-- P4 演示 ④:非空表建索引的分批回填(SPEC-P4 场景 6 / FR-467 / FR-468 / K84)
-- 期望:CREATE INDEX 返回 ACTIVE;SHOW INDEXES 能看到 build_cursor;查询走索引。
-- 大批量(1 万行以上)与"回填中途强杀 → BUILDING → 续做"由 scripts/tests/p4-test.sh 验证。

CREATE TABLE t4 (id INT PRIMARY KEY, bucket INT);
INSERT INTO t4 (id, bucket) VALUES (1, 0), (2, 1), (3, 0), (4, 2), (5, 0);
CREATE INDEX idx_bucket ON t4 (bucket);

SHOW INDEXES FROM t4;
EXPLAIN SELECT * FROM t4 WHERE bucket = 0;
SELECT id FROM t4 WHERE bucket = 0;
