package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.testing.FakeTxnStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FE 事务语义测试(SPEC-P3 §3 场景 1~5 / FR-340 ~ FR-353)。 */
class TransactionTest {

    private FakeTxnStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeTxnStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        executor.execute(Parser.parse(
                "CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), age INT)"));
    }

    private Result.Table select(Executor ex, String sql) {
        return (Result.Table) ex.execute(Parser.parse(sql));
    }

    private Result.Message dml(Executor ex, String sql) {
        return (Result.Message) ex.execute(Parser.parse(sql));
    }

    @Test
    void stateErrors() {
        assertTrue(assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("COMMIT"))).getMessage().contains("没有活动事务"));
        assertTrue(assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("ROLLBACK"))).getMessage().contains("没有活动事务"));

        executor.execute(Parser.parse("BEGIN"));
        assertEquals(com.wangyu.tangdb.txn.TxnState.ACTIVE, executor.txnState());
        assertTrue(assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("BEGIN"))).getMessage().contains("已有活动事务"));
        executor.execute(Parser.parse("ROLLBACK"));
        assertEquals(com.wangyu.tangdb.txn.TxnState.IDLE, executor.txnState());
    }

    @Test
    void readYourWritesAndRollback() {
        executor.execute(Parser.parse("BEGIN"));
        dml(executor, "INSERT INTO users (id, name) VALUES (9, 'x')");

        // 本事务自己可见。
        assertEquals(1, select(executor, "SELECT * FROM users WHERE id = 9").rows().size());
        // 另一个会话看不到未提交数据(写集从未进过 BE)。
        Executor other = new Executor(kv, catalog);
        assertEquals(0, select(other, "SELECT * FROM users WHERE id = 9").rows().size());

        assertEquals("OK, 已回滚",
                ((Result.Message) executor.execute(Parser.parse("ROLLBACK"))).text());
        assertEquals(0, select(executor, "SELECT * FROM users WHERE id = 9").rows().size());
    }

    @Test
    void commitMakesAllWritesVisibleAtomically() {
        executor.execute(Parser.parse("BEGIN"));
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'a'), (2, 'b')");
        Result.Message committed = (Result.Message) executor.execute(Parser.parse("COMMIT"));
        assertTrue(committed.text().contains("已提交"), committed.text());
        assertTrue(committed.text().contains("commit_ts"), committed.text());

        Executor other = new Executor(kv, catalog);
        assertEquals(2, select(other, "SELECT * FROM users").rows().size());
    }

    @Test
    void writeSetKeepsOnlyFinalVersionPerKey() {
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'a')");

        executor.execute(Parser.parse("BEGIN"));
        dml(executor, "UPDATE users SET name = 'b' WHERE id = 1");
        dml(executor, "UPDATE users SET name = 'c' WHERE id = 1");
        assertEquals("c", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
        executor.execute(Parser.parse("COMMIT"));

        // 同事务内多次改同一行只留最终版本,历史中间版本不出现。
        assertEquals("c", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
    }

    @Test
    void deleteThenInsertSameKeyLastOperationWins() {
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'a')");

        executor.execute(Parser.parse("BEGIN"));
        dml(executor, "DELETE FROM users WHERE id = 1");
        // 写集里可见版本是墓碑 → 允许重新插入(FR-352)。
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'reborn')");
        executor.execute(Parser.parse("COMMIT"));

        Result.Table rows = select(executor, "SELECT * FROM users WHERE id = 1");
        assertEquals(1, rows.rows().size());
        assertEquals("reborn", rows.rows().get(0)[1]);
    }

    @Test
    void snapshotIsStableWithinTransaction() {
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'v1')");

        executor.execute(Parser.parse("BEGIN"));
        assertEquals("v1", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);

        // 另一个会话在事务外自动提交改值。
        Executor other = new Executor(kv, catalog);
        dml(other, "UPDATE users SET name = 'v2' WHERE id = 1");

        // 本事务仍是 BEGIN 时固定的快照。
        assertEquals("v1", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
        executor.execute(Parser.parse("COMMIT"));

        // 提交后新开语句读最新。
        assertEquals("v2", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
    }

    @Test
    void writeWriteConflictIsTranslatedToTableAndPrimaryKey() {
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'orig')");

        Executor a = new Executor(kv, catalog);
        Executor b = new Executor(kv, catalog);
        a.execute(Parser.parse("BEGIN"));
        b.execute(Parser.parse("BEGIN"));

        dml(a, "UPDATE users SET name = 'A' WHERE id = 1");
        a.execute(Parser.parse("COMMIT"));

        dml(b, "UPDATE users SET name = 'B' WHERE id = 1");
        StoreException conflict = assertThrows(StoreException.class,
                () -> b.execute(Parser.parse("COMMIT")));
        assertTrue(conflict.getMessage().contains("表 users 主键 1 写写冲突"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("请重试"), conflict.getMessage());
        assertFalse(conflict.getMessage().contains("\\tat "), "不能带堆栈");

        // 后到者的写没有覆盖先到者。
        assertEquals("A", select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
        // 冲突后事务保持活动,可显式回滚。
        assertTrue(b.inTransaction());
        assertEquals("OK, 已回滚", ((Result.Message) b.execute(Parser.parse("ROLLBACK"))).text());
    }

    @Test
    void ddlInsideTransactionRejected() {
        executor.execute(Parser.parse("BEGIN"));
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "CREATE TABLE t2 (id INT PRIMARY KEY)")));
        assertTrue(e.getMessage().contains("DDL 不能在事务内执行"), e.getMessage());
        assertTrue(executor.inTransaction());
        executor.execute(Parser.parse("ROLLBACK"));
    }

    @Test
    void failedStatementDoesNotAutoRollback() {
        executor.execute(Parser.parse("BEGIN"));
        assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("INSERT INTO users (id) VALUES ('abc')")));
        assertTrue(executor.inTransaction(), "语句失败不应自动回滚");
        executor.execute(Parser.parse("ROLLBACK"));
    }

    @Test
    void readOnlyTransactionCommitsWithoutTakingSequence() {
        long before = kv.currentTS();
        executor.execute(Parser.parse("BEGIN"));
        Result.Message message = (Result.Message) executor.execute(Parser.parse("COMMIT"));
        assertTrue(message.text().contains("无写入"), message.text());
        assertEquals(before, kv.currentTS());
    }

    @Test
    void autocommitUpdateTakesSnapshotBeforeReading() {
        dml(executor, "INSERT INTO users (id, name) VALUES (1, 'orig')");
        TableMeta table = catalog.require("users");
        byte[] key = KeyCodec.rowKey(table.id(), DataType.BIGINT, 1L);

        // 自动提交 DML 在读取之前取快照;这里模拟"取完快照后、提交前,别人先提交了"。
        kv.setAfterBeginHook(() -> kv.put(key,
                RowCodec.encode(table, new Object[]{1L, "intruder", null})));

        StoreException e = assertThrows(StoreException.class,
                () -> dml(executor, "UPDATE users SET name = 'mine' WHERE id = 1"));
        assertTrue(e.getMessage().contains("表 users 主键 1 写写冲突"), e.getMessage());
        // 后到的自动提交 UPDATE 不得静默覆盖先提交的值。
        assertEquals("intruder",
                select(executor, "SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
    }

    @Test
    void autocommitInsertDoesNotOverwriteConcurrentInsert() {
        TableMeta table = catalog.require("users");
        byte[] key = KeyCodec.rowKey(table.id(), DataType.BIGINT, 7L);
        kv.setAfterBeginHook(() -> kv.put(key,
                RowCodec.encode(table, new Object[]{7L, "winner", null})));

        StoreException e = assertThrows(StoreException.class,
                () -> dml(executor, "INSERT INTO users (id, name) VALUES (7, 'loser')"));
        assertTrue(e.getMessage().contains("写写冲突"), e.getMessage());
        assertEquals("winner",
                select(executor, "SELECT name FROM users WHERE id = 7").rows().get(0)[0]);
    }

    @Test
    void rollbackOnDisconnectDropsActiveWriteSet() {
        executor.execute(Parser.parse("BEGIN"));
        dml(executor, "INSERT INTO users (id, name) VALUES (5, 'x')");
        assertTrue(executor.rollbackOnDisconnect().contains("已丢弃未提交事务"));
        assertFalse(executor.inTransaction());
        assertEquals(0, select(executor, "SELECT * FROM users WHERE id = 5").rows().size());
    }
}
