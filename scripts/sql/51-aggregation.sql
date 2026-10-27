-- SPEC-P5 D 组:聚合 / GROUP BY / HAVING / COUNT(DISTINCT) / STDDEV / GROUP_CONCAT
CREATE TABLE p5_orders (id INT PRIMARY KEY, city VARCHAR(16), amount INT, score DOUBLE);
INSERT INTO p5_orders VALUES (1,'北京',100,1.5),(2,'北京',200,2.5),(3,'上海',300,3.5);
INSERT INTO p5_orders VALUES (4,'上海',400,4.5),(5,'广州',NULL,NULL);
SELECT COUNT(*) AS rows_total, COUNT(amount) AS amount_non_null, SUM(amount) AS total,
       AVG(amount) AS avg_amount, MIN(amount) AS mn, MAX(amount) AS mx FROM p5_orders;
SELECT city, COUNT(*) AS c, SUM(amount) AS s FROM p5_orders GROUP BY city HAVING COUNT(*) > 1
       ORDER BY s DESC;
SELECT COUNT(DISTINCT city) AS cities, STDDEV(amount) AS sd, VARIANCE(amount) AS var
       FROM p5_orders;
SELECT GROUP_CONCAT(city) FROM p5_orders;
