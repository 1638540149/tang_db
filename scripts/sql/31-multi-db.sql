-- P3.5 多库基本闭环(SC-306 / 场景 3)
-- 用法:把本文件喂给 CLI 的标准输入(见 scripts/p35-test.sh 的 run_sql)

CREATE DATABASE shop;

USE shop;

CREATE TABLE orders (id INT PRIMARY KEY, amount INT);

INSERT INTO orders VALUES (1, 99), (2, 199);

SELECT * FROM orders;

-- 切回 default:看不到 shop 里的 orders
USE default;

SHOW TABLES;

SHOW DATABASES;

-- 用限定名访问 shop.orders
SELECT * FROM shop.orders;
