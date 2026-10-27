-- SPEC-P5 A 组:列式压缩可观测(SHOW SEGMENTS 与 DATA_LENGTH 同口径)
CREATE TABLE p5_seg (id INT PRIMARY KEY, city VARCHAR(16), amount INT);
INSERT INTO p5_seg VALUES (1,'beijing',100),(2,'beijing',200),(3,'shanghai',300),(4,'shanghai',400);
SHOW SEGMENTS FROM p5_seg;
SHOW TABLE STATUS LIKE 'p5_seg';
SELECT TABLE_NAME, DATA_LENGTH FROM information_schema.TABLES WHERE TABLE_NAME = 'p5_seg';
EXPLAIN SELECT city, SUM(amount) FROM p5_seg GROUP BY city;
