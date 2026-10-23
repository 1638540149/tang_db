-- P3 场景 1:事务提交后数据可见(SPEC-P3 §3)
CREATE TABLE txn_t (id INT PRIMARY KEY, name VARCHAR(32));
BEGIN;
INSERT INTO txn_t VALUES (1, 'a');
INSERT INTO txn_t VALUES (2, 'b');
COMMIT;
SELECT * FROM txn_t;
