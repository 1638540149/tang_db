-- P3 场景 3:事务内读己之写(另一个会话此时读不到)
BEGIN;
INSERT INTO txn_t VALUES (9, 'x');
SELECT * FROM txn_t WHERE id = 9;
ROLLBACK;
