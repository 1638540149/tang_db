-- P3.5 限定名在 SELECT / INSERT / UPDATE / DELETE / CREATE TABLE / DROP TABLE 里都可用(FR-419)
-- 以及库名大小写不敏感(K69)。

CREATE DATABASE Shop;

-- 库名混写:USE SHOP 与 shop 视为同一个库
USE SHOP;

CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR(16));

INSERT INTO Shop.t VALUES (1, 'a'), (2, 'b');

UPDATE shop.t SET v = 'updated' WHERE id = 1;

SELECT * FROM SHOP.T WHERE id = 1;

DELETE FROM shop.t WHERE id = 2;

SELECT * FROM shop.t;

-- TRUNCATE / DESC / DROP 也用限定名
TRUNCATE TABLE shop.t;

SELECT * FROM shop.t;

DESC shop.t;

DROP TABLE shop.t;

USE default;

SHOW DATABASES;
