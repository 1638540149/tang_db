-- P2.1 SQL 兼容性演示(反引号标识符 / INSERT 省略列名列表 / SHOW DATABASES)
-- 用法: 把本文件喂给 CLI 的标准输入(见 scripts/p2-test.sh 的 run_sql)

-- 1) SHOW DATABASES:只有默认库 default,由 FE 直接回答,不访问 BE
SHOW DATABASES;

-- 2) 反引号标识符:select / from 这类关键字可以当列名
CREATE TABLE `t_quoted` (`id` INT PRIMARY KEY, `select` VARCHAR(64), `from` INT);

INSERT INTO `t_quoted` (`id`, `select`, `from`) VALUES (1, 'quoted_value', 2);

SELECT `id`, `from` FROM `t_quoted` WHERE `id` = 1;

UPDATE `t_quoted` SET `from` = 37 WHERE `id` = 1;

SELECT `from` FROM `t_quoted` WHERE `id` = 1;

SELECT `id`, `select` FROM `t_quoted` WHERE `id` = 1;

DELETE FROM `t_quoted` WHERE `id` = 1;

SELECT * FROM `t_quoted`;

-- 3) 反引号的错误用法(按原样报语法错误,不允许用反引号绕开关键字)
`select` * from `t_quoted`;

SELECT * FROM ``;

-- 4) INSERT 省略列名列表:按表定义顺序取全部列
CREATE TABLE t_compat (id INT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32), age INT);

INSERT INTO t_compat VALUES (1, 'zhaoliu', '杭州', 19);

INSERT INTO t_compat VALUES (2, 'sunqi', '成都', 21), (3, 'zhouba', '西安', 24);

SELECT * FROM t_compat WHERE id = 2;

SELECT * FROM t_compat;

-- 省略形式必须给全所有列:少给值 → 报错并指出第几组
INSERT INTO t_compat VALUES (4, 'qianjiu');

-- 空列名列表 → 报错(空列表只有"省略形式"一种来源)
INSERT INTO t_compat () VALUES (5);

-- 5) 收尾:两种建表/写法都能删掉
DROP TABLE t_compat;

DROP TABLE `t_quoted`;

SHOW TABLES;
