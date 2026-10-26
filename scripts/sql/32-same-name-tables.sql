-- P3.5 限定名与同名表(SC-307 / 场景 4)
-- 两个库各建一张同名表 t(结构不同),限定名存取互不串;同库重名报错。

CREATE DATABASE shop;

USE default;

CREATE TABLE t (id INT PRIMARY KEY, a VARCHAR(16));

INSERT INTO t VALUES (1, 'default-row');

CREATE TABLE shop.t (id INT PRIMARY KEY, b VARCHAR(16), c INT);

INSERT INTO shop.t VALUES (1, 'shop-row', 7);

-- 限定名取到正确的表
SELECT * FROM default.t;

SELECT * FROM shop.t;

-- 同一库里重名建表报错(1007? 表已存在 → 1050;错误可读)
CREATE TABLE shop.t (id INT PRIMARY KEY);

-- 默认库当前库里不该有 b/c
DESC default.t;

DESC shop.t;
