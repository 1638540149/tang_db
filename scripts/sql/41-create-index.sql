-- P4 演示 ①:二级索引的读写闭环(SPEC-P4 场景 1 / FR-456 / FR-498 / FR-500)
-- 期望:建索引成功;两条等值查询结果与全表扫一致;EXPLAIN 显示 INDEX POINT。

CREATE TABLE orders (id INT PRIMARY KEY, category VARCHAR(32), amount INT);
INSERT INTO orders (id, category, amount) VALUES (1, '数码', 100), (2, '数码', 200), (3, '图书', 300), (4, '图书', 400);
CREATE INDEX idx_category ON orders (category);

EXPLAIN SELECT * FROM orders WHERE category = '数码';
SELECT id, category, amount FROM orders WHERE category = '数码';

-- 对照:加一个主键范围条件会把 RBO 推回 PRIMARY RANGE(全表有序扫描),结果必须一致。
SELECT id FROM orders WHERE category = '数码' AND id >= -2147483648;
