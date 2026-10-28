-- SPEC-P6 G 组: SELECT DISTINCT / 派生表
-- 用法: tangdb> source scripts/sql/64-derived-table.sql

CREATE TABLE p6_dt_user (id INT PRIMARY KEY, name VARCHAR(16), city VARCHAR(8), score INT);
CREATE TABLE p6_dt_order (oid INT PRIMARY KEY, uid INT, amount INT);
INSERT INTO p6_dt_user VALUES (1,'alice','bj',10),(2,'bob','sh',20),(3,'cathy','bj',NULL),(4,'dave',NULL,40);
INSERT INTO p6_dt_order VALUES (100,1,5),(101,1,7),(102,2,9),(103,99,11);

-- DISTINCT 与等价 GROUP BY 结果一致
SELECT DISTINCT city FROM p6_dt_user ORDER BY city;
SELECT city FROM p6_dt_user GROUP BY city ORDER BY city;

-- 单层派生表 + 列别名
SELECT t.city, t.c FROM (SELECT city, COUNT(*) AS c FROM p6_dt_user GROUP BY city) t ORDER BY t.city;

-- 两层嵌套
SELECT x.city FROM (SELECT y.city FROM (SELECT city FROM p6_dt_user) y) x WHERE x.city = 'bj';

-- 派生表与 JOIN 组合
SELECT d.name, o.total FROM p6_dt_user d
JOIN (SELECT uid, SUM(amount) AS total FROM p6_dt_order GROUP BY uid) o ON d.id = o.uid
ORDER BY d.id;
