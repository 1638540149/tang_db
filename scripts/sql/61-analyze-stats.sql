-- SPEC-P6 A 组: ANALYZE + SHOW STATS + EXPLAIN 估算(演示)
-- 用法: tangdb> source scripts/sql/61-analyze-stats.sql  (或逐条粘贴)

CREATE TABLE p6_stats_demo (id INT PRIMARY KEY, city VARCHAR(16), amount INT);
INSERT INTO p6_stats_demo VALUES (1,'北京',100),(2,'北京',120),(3,'上海',300),(4,'上海',320),(5,'广州',500);
CREATE INDEX idx_p6_stats_city ON p6_stats_demo (city);

-- 分析前:依据是"默认(未 ANALYZE)"
EXPLAIN SELECT id FROM p6_stats_demo WHERE city = '北京';

-- 全列分析(也可以 ANALYZE TABLE p6_stats_demo (city) 只分析关心的列)
ANALYZE TABLE p6_stats_demo;

-- 统计口径:行数 / 总字节 / 采样率 / 是否近似 / 分析时刻 / 耗时 / 扫描行数 / stale
SHOW STATS FROM p6_stats_demo;

-- 分析后:依据变成"统计(t=...)",并给出候选路径的 rows / cost
EXPLAIN SELECT id FROM p6_stats_demo WHERE city = '北京';
