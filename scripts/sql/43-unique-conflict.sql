-- P4 演示 ③:唯一索引挡住重复(SPEC-P4 场景 3 / FR-457 / K78 / K80)
-- 期望:第二条插入报 1062,信息含索引名、列名、值与已有主键;表里仍只有一行。

CREATE TABLE codes (id INT PRIMARY KEY, code VARCHAR(16));
CREATE UNIQUE INDEX uk_code ON codes (code);
INSERT INTO codes VALUES (1, 'A001');
INSERT INTO codes VALUES (2, 'A001');   -- 错误:唯一索引 uk_code 冲突(列 code = 'A001',已有主键 1)

SELECT * FROM codes;                    -- 只有 1 行
INSERT INTO codes VALUES (2, NULL);     -- NULL 不入索引,K79:允许多行 NULL
SELECT * FROM codes;                    -- 2 行
