-- P3 FR-353:同一事务内先删后插,写集最后一次操作胜出
BEGIN;
DELETE FROM txn_t WHERE id = 1;
INSERT INTO txn_t VALUES (1, 'reborn');
COMMIT;
SELECT * FROM txn_t WHERE id = 1;
