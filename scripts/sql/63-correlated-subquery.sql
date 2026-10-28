-- SPEC-P6 F 组: 相关子查询 / EXISTS / ANY / ALL
-- 用法: tangdb> source scripts/sql/63-correlated-subquery.sql

CREATE TABLE p6_cq_user (id INT PRIMARY KEY, name VARCHAR(16), city VARCHAR(8));
CREATE TABLE p6_cq_order (oid INT PRIMARY KEY, uid INT, amount INT);
INSERT INTO p6_cq_user VALUES (1,'alice','bj'),(2,'bob','sh'),(3,'cathy','bj'),(4,'dave',NULL);
INSERT INTO p6_cq_order VALUES (100,1,50),(101,1,70),(102,2,90),(103,99,110),(104,NULL,130);

-- 相关 IN:按外层行驱动内层探测
SELECT id FROM p6_cq_user WHERE id IN (SELECT uid FROM p6_cq_order WHERE uid = p6_cq_user.id) ORDER BY id;

-- 每行一个标量子查询(内层按相关性取值缓存)
SELECT u.id, (SELECT COUNT(*) FROM p6_cq_order o WHERE o.uid = u.id) AS orders
FROM p6_cq_user u ORDER BY u.id;

-- EXISTS / NOT EXISTS(内层空集时 NOT EXISTS 为真)
SELECT id FROM p6_cq_user WHERE EXISTS (SELECT 1 FROM p6_cq_order o WHERE o.uid = p6_cq_user.id) ORDER BY id;
SELECT id FROM p6_cq_user WHERE NOT EXISTS (SELECT 1 FROM p6_cq_order o WHERE o.uid = p6_cq_user.id) ORDER BY id;

-- 量词:= ANY / > ALL(SOME 是 ANY 的别名);含 NULL 的三值语义
SELECT oid FROM p6_cq_order WHERE uid = ANY (SELECT id FROM p6_cq_user) ORDER BY oid;
SELECT oid FROM p6_cq_order WHERE uid > ALL (SELECT id FROM p6_cq_user) ORDER BY oid;

-- NOT IN 子查询含 NULL → UNKNOWN → 空集(不报错)
SELECT id FROM p6_cq_user WHERE id NOT IN (SELECT uid FROM p6_cq_order);
