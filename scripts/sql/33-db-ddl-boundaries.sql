-- P3.5 库级 DDL 边界(SC-308 / 场景 5)
-- 非空库拒删、default 不可删、未知库 1049、重名库 1007、dropping a missing db。

CREATE DATABASE shop;

USE shop;

CREATE TABLE orders (id INT PRIMARY KEY);

INSERT INTO orders VALUES (1);

-- 非空库拒删(1451)
DROP DATABASE shop;

-- default 不可删
DROP DATABASE default;

-- 未知库(1049)
USE nosuchdb;

-- 重名库(1007)
CREATE DATABASE shop;

-- 删表后空库可删
DROP TABLE orders;

USE default;

DROP DATABASE shop;

SHOW DATABASES;
