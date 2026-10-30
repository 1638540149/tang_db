package com.wangyu.tangdb.slow;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P8 C 组(FR-831 ~ FR-840)慢查询验收:
 * 落库 / 指纹 / 阈值门禁 / 上限淘汰 / 诊断建议必须带证据且不重复建议已有索引。
 */
class SlowQueryTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private SqlEngine engine;
    private SlowQueryLog log;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        SessionManager sessions = new SessionManager(4, 0);
        engine = new SqlEngine(kv, catalog, sessions, new BackendRegistry(3), false, "root");
        engine.execute(null, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32))");
        engine.execute(null, "INSERT INTO t VALUES (1, 'a', 'bj'), (2, 'b', 'sh')");
        log = new SlowQueryLog(kv, catalog, engine.stats(), true, 200, 5);
        engine.withSlowQueryLog(log);
    }

    @Test
    void slowStatementIsRecordedWithEvidence() {
        log.record(null, "SELECT * FROM t WHERE city = 'bj'", 300, new Result.Message("OK", -1), null);

        Result.Table table = (Result.Table) engine.execute(null, "SHOW SLOW QUERIES");
        assertEquals(SlowQueryLog.COLUMNS, table.columns());
        assertEquals(1, table.rows().size());
        Object[] row = table.rows().get(0);
        assertEquals(300L, ((Number) row[2]).longValue(), "耗时与实测一致(SC-450)");
        assertEquals("SELECT * FROM t WHERE city = 'bj'", row[8]);
        assertEquals("SELECT * FROM t WHERE city = ?", row[9], "指纹去字面量");
        String advice = String.valueOf(row[11]);
        assertTrue(advice.contains("计划摘要"), "建议必须含计划证据: " + advice);
        assertTrue(advice.contains("证据:"), "建议必须带证据字段(FR-835): " + advice);
        assertTrue(advice.contains("列 city"), "必须指出无索引谓词列(SC-451): " + advice);
        assertTrue(advice.contains("候选索引: CREATE INDEX"), "必须给出候选索引(SC-451): " + advice);
        assertTrue(advice.contains("现有索引=PRIMARY(id)"), "必须列出证据索引清单: " + advice);
    }

    @Test
    void existingIndexIsNotSuggestedAgain() {
        engine.execute(null, "CREATE INDEX idx_city ON t (city)");
        log.record(null, "SELECT * FROM t WHERE city = 'bj'", 260, new Result.Message("OK", -1), null);
        String advice = String.valueOf(
                ((Result.Table) engine.execute(null, "SHOW SLOW QUERIES")).rows().get(0)[11]);
        assertTrue(advice.contains("已有索引 idx_city(city)"), advice);
        assertFalse(advice.contains("候选索引: CREATE INDEX idx_t_city"), "FR-837:不得重复建议: " + advice);

        log.record(null, "SELECT * FROM t WHERE id = 1", 260, new Result.Message("OK", -1), null);
        String pkAdvice = String.valueOf(
                ((Result.Table) engine.execute(null, "SHOW SLOW QUERY 2")).rows().get(0)[11]);
        assertTrue(pkAdvice.contains("已有索引 PRIMARY"), pkAdvice);
    }

    @Test
    void belowThresholdIsNotRecorded() {
        log.record(null, "SELECT * FROM t", 10, new Result.Message("OK", -1), null);
        assertEquals(0, log.size(), "阈值内不落库(FR-840)");
        assertTrue(((Result.Table) engine.execute(null, "SHOW SLOW QUERIES")).rows().isEmpty());
    }

    @Test
    void zeroThresholdDisablesRecording() {
        SlowQueryLog disabled = new SlowQueryLog(kv, catalog, engine.stats(), true, 0, 5);
        assertFalse(disabled.enabled(), "边界 175:阈值 0 等价于关闭");
        disabled.record(null, "SELECT * FROM t", 9999, new Result.Message("OK", -1), null);
        assertEquals(0, disabled.size());
    }

    @Test
    void maxRowsEvictsOldest() {
        for (int i = 1; i <= 8; i++) {
            log.record(null, "SELECT * FROM t WHERE city = '" + i + "'", 200 + i,
                    new Result.Message("OK", -1), null);
        }
        assertEquals(5, log.size(), "FR-839:上限淘汰");
        List<SlowQueryLog.Entry> recent = log.recent(0);
        assertEquals(8, recent.get(0).id());
        assertEquals(4, recent.get(recent.size() - 1).id());
        assertNull(log.find(1));
    }

    @Test
    void recordsSurviveRestart() {
        log.record(null, "SELECT * FROM t WHERE city = 'x'", 250, new Result.Message("OK", -1), null);
        SlowQueryLog reloaded = new SlowQueryLog(kv, catalog, engine.stats(), true, 200, 5);
        assertEquals(1, reloaded.size(), "重启后从 sys_slow_query 读回");
        assertEquals(1, reloaded.recent(0).get(0).id());
        // 新记录继续用递增 id,不覆盖历史
        reloaded.record(null, "SELECT * FROM t WHERE city = 'y'", 250,
                new Result.Message("OK", -1), null);
        assertEquals(2, reloaded.size());
    }

    @Test
    void sqlSummaryKeepsHeadAndTail() {
        String longSql = "SELECT * FROM t WHERE city = '" + "x".repeat(8000) + "'";
        String summary = SqlText.summary(longSql);
        assertTrue(summary.length() <= 4100, "摘要不超过 4KB 太多");
        assertTrue(summary.contains("..."), "边界 189:中间要有省略号");
        assertTrue(summary.startsWith("SELECT"), "保留首部");
        assertTrue(summary.endsWith("'"), "保留尾部");
    }

    @Test
    void errorStatementsAreRecordedToo() {
        Result result;
        try {
            result = engine.execute(null, "SELECT * FROM no_such_table WHERE a = 1");
        } catch (RuntimeException e) {
            result = null;
        }
        assertNull(result);
        // 慢查询针对失败语句同样落库(FR-822 口径);这里直接调用记录入口验证落库路径
        log.record(null, "SELECT * FROM no_such_table WHERE a = 1", 400, null,
                new com.wangyu.tangdb.execute.SqlError(1146, "42S02", "表不存在"));
        assertEquals(1, log.size());
        String advice = String.valueOf(
                ((Result.Table) engine.execute(null, "SHOW SLOW QUERIES")).rows().get(0)[11]);
        assertTrue(advice.contains("证据不足"), "拿不到证据时必须写证据不足(FR-835): " + advice);
    }
}
