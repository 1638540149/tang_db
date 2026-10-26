package com.wangyu.tangdb.session;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 会话上限、空闲超时回收、优雅退出(FR-411 / FR-412 / FR-413)。 */
class SessionManagerTest {

    private static Session newSession(SessionManager manager, KvStore kv, Catalog catalog, String source) {
        return manager.create(source, new Executor(kv, catalog));
    }

    @Test
    void enforcesSessionLimit() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SessionManager manager = new SessionManager(2, 0);

        newSession(manager, kv, catalog, "a");
        newSession(manager, kv, catalog, "b");
        assertEquals(2, manager.size());

        SqlError e = assertThrows(SqlError.class,
                () -> newSession(manager, kv, catalog, "c"));
        assertEquals(SessionManager.ER_CON_COUNT_ERROR, e.code());
        assertTrue(e.getMessage().contains("上限"), e.getMessage());
    }

    @Test
    void removeFreesSlot() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SessionManager manager = new SessionManager(1, 0);

        Session s = newSession(manager, kv, catalog, "a");
        assertEquals(1, manager.size());
        manager.remove(s.id());
        assertEquals(0, manager.size());
        assertDoesNotThrow(() -> newSession(manager, kv, catalog, "b"));
    }

    @Test
    void evictsIdleSessions() throws InterruptedException {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SessionManager manager = new SessionManager(4, 1); // 1 秒空闲超时

        Session s1 = newSession(manager, kv, catalog, "a");
        Session s2 = newSession(manager, kv, catalog, "b");
        Thread.sleep(1200);
        s2.touch(); // s2 刚活跃过,不应被回收

        int evicted = manager.evictIdle();
        assertEquals(1, evicted);
        assertNull(manager.get(s1.id()));
        assertNotNull(manager.get(s2.id()));
    }

    @Test
    void closeAllRollsBackAndClears() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SessionManager manager = new SessionManager(4, 0);

        Session s = newSession(manager, kv, catalog, "a");
        s.executor().execute(com.wangyu.tangdb.parser.Parser.parse("BEGIN"));
        assertTrue(s.inTransaction());

        manager.closeAll();
        assertFalse(s.inTransaction(), "关闭时丢弃未提交事务");
        assertEquals(0, manager.size());
    }
}
