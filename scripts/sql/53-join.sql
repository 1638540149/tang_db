-- SPEC-P5 E 组:INNER / LEFT / 三表串联
CREATE TABLE p5_user (id INT PRIMARY KEY, name VARCHAR(16), city VARCHAR(8));
CREATE TABLE p5_city (city VARCHAR(8) PRIMARY KEY, region VARCHAR(8));
CREATE TABLE p5_trade (tid INT PRIMARY KEY, uid INT, amount INT);
INSERT INTO p5_user VALUES (1,'alice','bj'),(2,'bob','sh'),(3,'cathy','gz');
INSERT INTO p5_city VALUES ('bj','north'),('sh','east'),('gz','south');
INSERT INTO p5_trade VALUES (10,1,100),(11,1,200),(12,2,300),(13,99,400);
SELECT u.name, t.amount FROM p5_user u JOIN p5_trade t ON u.id = t.uid ORDER BY t.amount;
SELECT u.name, t.amount FROM p5_user u LEFT JOIN p5_trade t ON u.id = t.uid ORDER BY u.id, t.tid;
SELECT u.name, c.region, SUM(t.amount) AS total FROM p5_user u
       JOIN p5_trade t ON u.id = t.uid JOIN p5_city c ON u.city = c.city
       GROUP BY u.name, c.region ORDER BY total DESC;
