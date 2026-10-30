package com.wangyu.tangdb.backup;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.server.BackendRegistry;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P8 D 组(FR-841 ~ FR-850)FE 侧验收:
 * BACKUP / RESTORE 语法、任务行状态、BE 重启后 RUNNING 任务判 CANCELLED、
 * 恢复失败留下可见状态。
 */
class BackupServiceTest {

    /** 假通道:模拟 BE 的任务表与恢复。 */
    private static class FakeChannel implements BackupChannel {
        final List<RemoteJob> jobs = new ArrayList<>();
        final AtomicInteger restoreCalls = new AtomicInteger();
        boolean failRestore;
        int pollsBeforeSuccess;

        @Override
        public String startBackup(String backupDir, String incrementalFrom, long snapshotRows,
                                  boolean force) {
            String id = "bk_fake_" + (jobs.size() + 1);
            jobs.add(new RemoteJob(id, backupDir, "RUNNING", 0, 1024, "",
                    incrementalFrom != null && !incrementalFrom.isBlank(), 0, 0));
            return id;
        }

        @Override
        public List<RemoteJob> remoteJobs(String jobId) {
            List<RemoteJob> out = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                RemoteJob job = jobs.get(i);
                if (!job.jobId().equals(jobId)) {
                    continue;
                }
                if ("RUNNING".equals(job.status()) && pollsBeforeSuccess-- <= 0) {
                    job = new RemoteJob(job.jobId(), job.backupDir(), "SUCCESS", 1024, 1024,
                            "备份完成", job.incremental(), job.startedMillis(), 1L);
                    jobs.set(i, job);
                }
                out.add(job);
            }
            return out;
        }

        @Override
        public RestoreOutcome restore(String sourceDir, String targetDir, boolean force) {
            restoreCalls.incrementAndGet();
            if (failRestore) {
                throw new IllegalStateException("校验和不一致");
            }
            return new RestoreOutcome(7, 4096, "恢复完成");
        }
    }

    private SqlEngine engineWith(BackupService service) {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root");
        return engine.withBackupService(service);
    }

    @Test
    void backupStartsJobAndShowsProgress() {
        FakeChannel channel = new FakeChannel();
        channel.pollsBeforeSuccess = 1;
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root")
                .withBackupService(new BackupService(kv, catalog, engineStats(kv, catalog), channel));

        Result message = engine.execute(null, "BACKUP TO 'tmp/p8-bk'");
        assertTrue(message instanceof Result.Message);
        assertTrue(((Result.Message) message).text().contains("备份任务已启动"));

        Result.Table running = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
        assertEquals(BackupService.COLUMNS, running.columns());
        assertEquals(1, running.rows().size());
        assertEquals("RUNNING", running.rows().get(0)[4]);

        // 第一次刷新把 RUNNING 变成 SUCCESS
        Result.Table done = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
        assertEquals("SUCCESS", done.rows().get(0)[4]);
        assertEquals(1024L, ((Number) done.rows().get(0)[7]).longValue());
    }

    @Test
    void lostBeJobBecomesCancelled() {
        FakeChannel channel = new FakeChannel() {
            @Override
            public List<RemoteJob> remoteJobs(String jobId) {
                return List.of(); // 模拟 BE 重启后任务表为空
            }
        };
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root")
                .withBackupService(new BackupService(kv, catalog, engineStats(kv, catalog), channel));
        engine.execute(null, "BACKUP TO 'tmp/p8-bk'");
        Result.Table jobs = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
        assertEquals("CANCELLED", jobs.rows().get(0)[4], "边界 182");
        assertTrue(String.valueOf(jobs.rows().get(0)[10]).contains("BACKUP_META"));
    }

    @Test
    void restoreRunsInBackgroundAndReportsStatus() throws Exception {
        FakeChannel channel = new FakeChannel();
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root")
                .withBackupService(new BackupService(kv, catalog, engineStats(kv, catalog), channel));

        Result message = engine.execute(null, "RESTORE FROM 'tmp/p8-bk' TO 'tmp/p8-restored'");
        assertTrue(((Result.Message) message).text().contains("恢复任务已启动"));
        waitForStatus(engine, "SUCCESS");
        assertEquals(1, channel.restoreCalls.get());
    }

    @Test
    void restoreFailureIsVisible() throws Exception {
        FakeChannel channel = new FakeChannel();
        channel.failRestore = true;
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        SqlEngine engine = new SqlEngine(kv, catalog, new SessionManager(2, 0),
                new BackendRegistry(3), false, "root")
                .withBackupService(new BackupService(kv, catalog, engineStats(kv, catalog), channel));
        engine.execute(null, "RESTORE FROM 'tmp/p8-bk' TO 'tmp/p8-restored'");
        waitForStatus(engine, "FAILED");
        Result.Table jobs = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
        assertTrue(String.valueOf(jobs.rows().get(0)[10]).contains("校验和"));
    }

    @Test
    void backupSyntaxErrorsAreRejected() {
        FakeChannel channel = new FakeChannel();
        SqlEngine engine = engineWith(new BackupService(new FakeKvStore(),
                Catalog.open(new FakeKvStore()), null, channel));
        SqlError error = assertThrows(SqlError.class,
                () -> engine.execute(null, "BACKUP 'no-dir'"));
        assertEquals(SqlError.ER_SYNTAX_ERROR, error.code());
        assertTrue(error.getMessage().contains("BACKUP TO"));

        SqlError restoreError = assertThrows(SqlError.class,
                () -> engine.execute(null, "RESTORE 'x'"));
        assertEquals(SqlError.ER_SYNTAX_ERROR, restoreError.code());
        assertTrue(restoreError.getMessage().contains("RESTORE FROM"));
    }

    @Test
    void parserAcceptsAllForms() {
        BackupService.ParsedBackup full = BackupService.parseBackup("BACKUP TO 'a/b'");
        assertNotNull(full);
        assertEquals("a/b", full.directory());
        assertFalse(full.force());
        assertEquals("", full.incrementalFrom());

        BackupService.ParsedBackup inc = BackupService.parseBackup(
                "backup to 'c' incremental from 'a/b' force");
        assertNotNull(inc);
        assertEquals("c", inc.directory());
        assertEquals("a/b", inc.incrementalFrom());
        assertTrue(inc.force());

        BackupService.ParsedRestore restore = BackupService.parseRestore(
                "RESTORE FROM 'a/b' TO 'c/d' FORCE");
        assertNotNull(restore);
        assertEquals("a/b", restore.source());
        assertEquals("c/d", restore.target());
        assertTrue(restore.force());
    }

    private static com.wangyu.tangdb.stats.StatsService engineStats(FakeKvStore kv, Catalog catalog) {
        return new com.wangyu.tangdb.stats.StatsService(kv, catalog,
                com.wangyu.tangdb.planner.OptimizerConfig.defaults());
    }

    private static void waitForStatus(SqlEngine engine, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Result.Table jobs = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
            if (!jobs.rows().isEmpty() && expected.equals(jobs.rows().get(0)[4])) {
                return;
            }
            Thread.sleep(20);
        }
        Result.Table jobs = (Result.Table) engine.execute(null, "SHOW BACKUP JOBS");
        fail("等待状态 " + expected + " 超时,当前: " + (jobs.rows().isEmpty()
                ? "无任务" : jobs.rows().get(0)[4]));
    }
}
