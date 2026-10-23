-- P3 场景 5:事务内快照稳定(事务开始时固定水位)
BEGIN;
SELECT name FROM txn_t WHERE id = 1;
-- 另一个会话此时改值并提交;本会话再读一次仍是旧值
SELECT name FROM txn_t WHERE id = 1;
COMMIT;
SELECT name FROM txn_t WHERE id = 1;
