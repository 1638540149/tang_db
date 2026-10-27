-- P4 演示 ②:索引列被 UPDATE(SPEC-P4 场景 2 / FR-485 / FR-501)
-- 期望:旧值查不到、新值查得到;主键行同步变化。

CREATE TABLE items (id INT PRIMARY KEY, category VARCHAR(32));
INSERT INTO items VALUES (1, '数码'), (2, '数码'), (3, '图书');
CREATE INDEX idx_category ON items (category);

UPDATE items SET category = '文具' WHERE id = 1;

SELECT id FROM items WHERE category = '数码';   -- 只剩 2
SELECT id FROM items WHERE category = '文具';   -- 1
SELECT * FROM items WHERE id = 1;               -- category = 文具
