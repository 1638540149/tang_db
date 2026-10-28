-- SPEC-P6 E 组: 集合运算(UNION / UNION ALL / INTERSECT / EXCEPT / MINUS)
-- 用法: tangdb> source scripts/sql/62-set-ops.sql

CREATE TABLE p6_set_a (id INT PRIMARY KEY, v INT);
CREATE TABLE p6_set_b (id INT PRIMARY KEY, v INT);
INSERT INTO p6_set_a VALUES (1,10),(2,20),(3,NULL),(4,20);
INSERT INTO p6_set_b VALUES (1,20),(2,30),(3,NULL),(4,40);

-- 去重合并(NULL 视为相等):10 / 20 / NULL / 30 / 40
SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b ORDER BY 1;

-- 不去重合并:8 行
SELECT v FROM p6_set_a UNION ALL SELECT v FROM p6_set_b;

-- 交集 / 差集(NULL 也参与判等)
SELECT v FROM p6_set_a INTERSECT SELECT v FROM p6_set_b ORDER BY 1;
SELECT v FROM p6_set_a EXCEPT SELECT v FROM p6_set_b ORDER BY 1;
SELECT v FROM p6_set_a MINUS SELECT v FROM p6_set_b ORDER BY 1;   -- MINUS 是 EXCEPT 的别名

-- 优先级:INTERSECT 高于 UNION;括号可以改变结合
SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b INTERSECT SELECT v FROM p6_set_b;
(SELECT v FROM p6_set_a UNION SELECT v FROM p6_set_b) INTERSECT SELECT v FROM p6_set_b;

-- 分支自带 ORDER BY / LIMIT 必须加括号
(SELECT v FROM p6_set_a ORDER BY v LIMIT 2) UNION ALL SELECT v FROM p6_set_b WHERE v = 99;
