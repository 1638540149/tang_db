-- P3 场景 2:回滚后数据与事务前完全一致
BEGIN;
UPDATE txn_t SET name = 'changed' WHERE id = 1;
DELETE FROM txn_t WHERE id = 2;
ROLLBACK;
SELECT * FROM txn_t;
