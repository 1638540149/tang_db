-- P3 场景 4:两个会话抢同一行 —— 会话 A(默认心跳端口)
-- 手工复现时先在另一个终端跑 27-conflict-b.sql,注意 COMMIT 的先后顺序。
BEGIN;
UPDATE txn_t SET name = 'A' WHERE id = 1;
COMMIT;
