package com.wangyu.tangdb.backup;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.SystemTables;
import com.wangyu.tangdb.stats.StatsService;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 备份 / 恢复任务编排(SPEC-P8 D 组 FR-841 ~ FR-850)。
 *
 * <p>任务状态落在系统表 {@code sys_backup_jobs}(表 ID 11),备份在 BE 后台执行、恢复在 FE 后台线程执行,
 * 两者都不阻塞前台查询(FR-847)。BE 重启后旧任务在 BE 侧消失,{@code SHOW BACKUP JOBS} 会把
 * 仍标 RUNNING 的任务改判为 CANCELLED(边界 182),状态始终可见。</p>
 */
public final class BackupService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final List<String> COLUMNS = List.of("job_id", "kind", "backup_dir",
            "target_dir", "status", "started_at", "finished_at", "copied_bytes", "total_bytes",
            "incremental", "message");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 一条任务(与 sys_backup_jobs 的列一一对应)。 */
    public record Job(String jobId, String kind, String backupDir, String targetDir, String status,
                      String startedAt, String finishedAt, long copiedBytes, long totalBytes,
                      boolean incremental, String message) {
    }

    private final KvStore kv;
    private final Catalog catalog;
    private final StatsService stats;
    private final BackupChannel channel;
    /** SPEC-P8 §5.3 backup_dir:相对路径的备份目录默认落在这里(空 = 相对当前工作目录)。 */
    private final String defaultRoot;
    private final Map<String, Job> pendingRestores = new ConcurrentHashMap<>();

    public BackupService(KvStore kv, Catalog catalog, StatsService stats, BackupChannel channel) {
        this(kv, catalog, stats, channel, "");
    }

    public BackupService(KvStore kv, Catalog catalog, StatsService stats, BackupChannel channel,
                         String defaultRoot) {
        this.kv = kv;
        this.catalog = catalog;
        this.stats = stats;
        this.channel = channel;
        this.defaultRoot = defaultRoot == null ? "" : defaultRoot.trim();
    }

    /** 相对目录拼到 backup_dir 之下;绝对目录原样使用。 */
    private String resolveDir(String dir) {
        String trimmed = dir == null ? "" : dir.trim();
        if (trimmed.isEmpty() || defaultRoot.isEmpty() || isAbsolute(trimmed)) {
            return trimmed;
        }
        return java.nio.file.Path.of(defaultRoot).resolve(trimmed).toString();
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() > 1 && path.charAt(1) == ':');
    }

    // ------------------------------------------------------------ 备份

    /**
     * {@code BACKUP TO '<dir>' [INCREMENTAL FROM '<dir>'] [FORCE]}(FR-841 / FR-845)。
     *
     * @return 任务 id + 给用户看的提示
     */
    public String backup(String dir, String incrementalFrom, boolean force) {
        if (channel == null) {
            throw new IllegalStateException("备份通道不可用(未连接 BE)");
        }
        String trimmed = dir == null ? "" : dir.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("BACKUP 需要一个目标目录");
        }
        long snapshotRows = snapshotRows();
        String target = resolveDir(trimmed);
        String remoteId = channel.startBackup(target,
                incrementalFrom == null ? "" : resolveDir(incrementalFrom),
                snapshotRows, force);
        String jobId = remoteId == null || remoteId.isBlank()
                ? "bk_" + System.currentTimeMillis() : remoteId;
        Job job = new Job(jobId, "BACKUP", target,
                incrementalFrom == null ? "" : incrementalFrom, STATUS_RUNNING,
                TIME_FMT.format(Instant.now()), "", 0, 0,
                incrementalFrom != null && !incrementalFrom.isBlank(),
                "备份进行中(备份点快照行数=" + snapshotRows + ")");
        save(job);
        return "OK, 备份任务已启动(job " + jobId + ",目录 " + target
                + ",快照行数 " + snapshotRows + ");用 SHOW BACKUP JOBS 查看进度";
    }

    /** 备份点行数:按 FE 统计口径汇总用户表(不扫表,不阻塞写)。 */
    private long snapshotRows() {
        long total = 0;
        for (TableMeta table : catalog.tables()) {
            if (table.system()) {
                continue;
            }
            total += stats.estimatedRowCount(table);
        }
        return total;
    }

    // ------------------------------------------------------------ 恢复

    /**
     * {@code RESTORE FROM '<src>' [TO '<dst>'] [FORCE]}(FR-844 / K194)。
     *
     * <p>恢复是后台任务(FR-847):立即返回 job id,进度与结果看 {@code SHOW BACKUP JOBS}。
     * 未给 TO 时默认落到备份目录旁边的 {@code <src>-restored-<时间戳>}。</p>
     */
    public String restore(String source, String target, boolean force) {
        if (channel == null) {
            throw new IllegalStateException("备份通道不可用(未连接 BE)");
        }
        String src = source == null ? "" : source.trim();
        if (src.isEmpty()) {
            throw new IllegalArgumentException("RESTORE 需要一个备份目录");
        }
        String dst = target == null || target.isBlank()
                ? src.replaceAll("[/\\\\]+$", "") + "-restored-"
                + TIME_FMT.format(Instant.now()).replaceAll("[-: ]", "")
                : target.trim();
        String jobId = "rs_" + System.currentTimeMillis();
        Job job = new Job(jobId, "RESTORE", src, dst, STATUS_RUNNING,
                TIME_FMT.format(Instant.now()), "", 0, 0, false, "恢复进行中");
        save(job);
        Thread worker = new Thread(() -> runRestore(job, src, dst, force), "backup-restore");
        worker.setDaemon(true);
        worker.start();
        return "OK, 恢复任务已启动(job " + jobId + ",源 " + src + ",目标 " + dst
                + ");用 SHOW BACKUP JOBS 查看进度,完成后用该目录启动 BE";
    }

    private void runRestore(Job job, String source, String target, boolean force) {
        try {
            BackupChannel.RestoreOutcome outcome = channel.restore(source, target, force);
            save(new Job(job.jobId(), job.kind(), job.backupDir(), target, STATUS_SUCCESS,
                    job.startedAt(), TIME_FMT.format(Instant.now()), outcome.bytes(),
                    outcome.bytes(), false, outcome.message() + "(文件 " + outcome.files()
                    + " 个,目标 " + target + ")"));
        } catch (RuntimeException e) {
            save(new Job(job.jobId(), job.kind(), job.backupDir(), target, STATUS_FAILED,
                    job.startedAt(), TIME_FMT.format(Instant.now()), 0, 0, false,
                    "恢复失败: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage())));
        } finally {
            pendingRestores.remove(job.jobId());
        }
    }

    // ------------------------------------------------------------ 查询

    /** {@code SHOW BACKUP JOBS}(FR-846):每次读表,并把 RUNNING 的备份任务向 BE 刷新一次。 */
    public List<Job> jobs() {
        List<Job> jobs = load();
        boolean changed = false;
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (!STATUS_RUNNING.equals(job.status()) || !"BACKUP".equals(job.kind())
                    || channel == null) {
                continue;
            }
            Job refreshed = refresh(job);
            if (refreshed != null) {
                jobs.set(i, refreshed);
                save(refreshed);
                changed = true;
            }
        }
        if (changed) {
            jobs = load();
        }
        jobs.sort(Comparator.comparing(Job::startedAt).reversed());
        return jobs;
    }

    private Job refresh(Job job) {
        try {
            List<BackupChannel.RemoteJob> remote = channel.remoteJobs(job.jobId());
            if (remote.isEmpty()) {
                // 边界 182:BE 重启/任务被强杀 → 状态标 CANCELLED,半成品目录没有 BACKUP_META。
                return new Job(job.jobId(), job.kind(), job.backupDir(), job.targetDir(),
                        STATUS_CANCELLED, job.startedAt(), TIME_FMT.format(Instant.now()),
                        job.copiedBytes(), job.totalBytes(), job.incremental(),
                        "任务在 BE 侧已丢失(进程重启或被强杀),状态标 CANCELLED;"
                                + "备份目录不含 BACKUP_META,不可恢复");
            }
            BackupChannel.RemoteJob state = remote.get(0);
            String finished = STATUS_RUNNING.equals(state.status())
                    ? "" : TIME_FMT.format(Instant.now());
            return new Job(state.jobId(), job.kind(), state.backupDir(), job.targetDir(),
                    state.status(), job.startedAt(), finished, state.copiedBytes(),
                    state.totalBytes(), state.incremental(), state.message());
        } catch (RuntimeException e) {
            return null; // BE 暂时连不上:保持原状态,下次再刷
        }
    }

    // ------------------------------------------------------------ 持久化

    private List<Job> load() {
        List<Job> jobs = new ArrayList<>();
        if (kv == null) {
            return jobs;
        }
        try {
            List<KeyValue> rows = kv.scan(
                    KeyCodec.tableRangeStart(SystemTables.SYS_BACKUP_JOBS),
                    KeyCodec.tableRangeEnd(SystemTables.SYS_BACKUP_JOBS), 0);
            for (KeyValue row : rows) {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.BACKUP_JOBS, row.value());
                if (decoded.tombstone()) {
                    continue;
                }
                jobs.add(fromValues(decoded.values()));
            }
        } catch (RuntimeException e) {
            // 系统表不可读(老目录等)不影响其它功能
        }
        return jobs;
    }

    private void save(Job job) {
        if (kv == null) {
            return;
        }
        try {
            kv.put(SystemTables.backupJobKey(job.jobId()),
                    SystemTables.encodeBackupJobRow(job.jobId(), job.kind(), job.backupDir(),
                            job.targetDir(), job.status(), job.startedAt(),
                            job.finishedAt() == null ? "" : job.finishedAt(),
                            job.copiedBytes(), job.totalBytes(), job.incremental(),
                            job.message()));
        } catch (RuntimeException ignored) {
            // 记账失败不影响备份本身;进程内状态仍可查
        }
    }

    private static Job fromValues(Object[] v) {
        return new Job((String) v[0], (String) v[1], (String) v[2], (String) v[3],
                (String) v[4], (String) v[5], v[6] == null ? "" : (String) v[6],
                ((Number) v[7]).longValue(), ((Number) v[8]).longValue(),
                Boolean.TRUE.equals(v[9]), v[10] == null ? "" : (String) v[10]);
    }

    /** 解析 {@code BACKUP TO ...} 语句;/不匹配返回 null。 */
    public static ParsedBackup parseBackup(String statement) {
        java.util.regex.Matcher matcher = BACKUP_PATTERN.matcher(statement.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedBackup(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2),
                matcher.group(3) != null);
    }

    /** 解析 {@code RESTORE FROM ...} 语句;/不匹配返回 null。 */
    public static ParsedRestore parseRestore(String statement) {
        java.util.regex.Matcher matcher = RESTORE_PATTERN.matcher(statement.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedRestore(matcher.group(1),
                matcher.group(2) == null ? "" : matcher.group(2), matcher.group(3) != null);
    }

    public record ParsedBackup(String directory, String incrementalFrom, boolean force) {
    }

    public record ParsedRestore(String source, String target, boolean force) {
    }

    private static final java.util.regex.Pattern BACKUP_PATTERN = java.util.regex.Pattern.compile(
            "(?is)^BACKUP\\s+TO\\s+'([^']*)'(?:\\s+INCREMENTAL\\s+FROM\\s+'([^']*)')?"
                    + "(?:\\s+(FORCE))?\\s*$");
    private static final java.util.regex.Pattern RESTORE_PATTERN = java.util.regex.Pattern.compile(
            "(?is)^RESTORE\\s+FROM\\s+'([^']*)'(?:\\s+TO\\s+'([^']*)')?(?:\\s+(FORCE))?\\s*$");

    /** 供控制台 / 状态区:当前任务数(不触发刷新)。 */
    public int size() {
        return load().size() + pendingRestores.size();
    }

    /** 任务 id 集合(测试用)。 */
    public Set<String> pendingRestoreIds() {
        return new LinkedHashSet<>(pendingRestores.keySet());
    }

    /** 小写 kind 归一(诊断用)。 */
    public static String normalizeKind(String kind) {
        return kind == null ? "" : kind.toUpperCase(Locale.ROOT);
    }
}
