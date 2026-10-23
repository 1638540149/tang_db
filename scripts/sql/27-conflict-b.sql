-- P3 场景 4:两个会话抢同一行 —— 会话 B(heartbeat_enabled = false)
BEGIN;
UPDATE txn_t SET name = 'B' WHERE id = 1;
COMMIT;
