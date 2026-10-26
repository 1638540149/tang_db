-- P3.5 information_schema 三张最小只读视图(§12 I9)与连接期兼容语句(§5.5)
-- 用法:把本文件喂给 CLI 的标准输入(见 scripts/p35-test.sh)
-- 注意:informationschema 的 SELECT 由 fed 的兼容层直接回答(不经过 Parser),
--       CLI 显示为结果集;官方 mysql / JDBC 客户端同样能取到。

CREATE DATABASE shop;

CREATE TABLE demo (id INT PRIMARY KEY, name VARCHAR(32));

-- 三张视图
SELECT * FROM information_schema.schemata;

SELECT * FROM information_schema.tables;

SELECT * FROM information_schema.columns;

-- 其余 information_schema 表名:明确报"不支持"(不是空结果集)
SELECT * FROM information_schema.partitions;

-- 连接期兼容语句(§5.5):必须接受
SELECT @@version;

SELECT @@version_comment;

SELECT @@character_set_client;

SELECT @@sql_mode;

SELECT DATABASE();

SHOW VARIABLES LIKE 'max_allowed_packet';

SHOW STATUS;

SET NAMES utf8mb4;

SET autocommit = 0;
