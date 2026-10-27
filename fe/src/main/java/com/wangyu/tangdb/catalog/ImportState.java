package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 导入任务与断点状态(SPEC-P5 §5.3 / FR-607 / FR-608 / FR-614)。
 *
 * <p>权威副本在 BE 的系统表 {@code sys_import_state};这里只负责编码/解码。
 * 一个导入 = 一个可查的任务(FR-614)。</p>
 */
public final class ImportState {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private ImportState() {
    }

    /** 一条导入任务。 */
    public record Task(String importId, long tableId, String tableName, String filePath,
                       long fileSize, long fileMtime, long lastOffset, long lastRowNo,
                       long committedRows, long totalRowsEstimate, String status,
                       String errorSummary, String updatedAt) {

        /** E-04 任务模型的 progress:已提交行 / 总行估计。 */
        public String progress() {
            return committedRows + "/" + (totalRowsEstimate > 0 ? totalRowsEstimate : "?");
        }

        public Task withProgress(long offset, long rowNo, long committed) {
            return new Task(importId, tableId, tableName, filePath, fileSize, fileMtime,
                    offset, rowNo, committed, totalRowsEstimate, status, errorSummary, nowText());
        }

        public Task withStatus(String newStatus, String error) {
            return new Task(importId, tableId, tableName, filePath, fileSize, fileMtime,
                    lastOffset, lastRowNo, committedRows, totalRowsEstimate, newStatus, error,
                    nowText());
        }
    }

    /** 稳定的任务 ID:库 + 表 + 文件绝对路径 + 文件大小 + mtime(同一文件重跑即同一任务)。 */
    public static String idFor(String database, String table, Path file) {
        String path;
        long size = 0;
        long mtime = 0;
        try {
            path = file.toAbsolutePath().normalize().toString();
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            path = file.toAbsolutePath().normalize().toString();
        }
        String seed = database.toLowerCase() + "|" + table.toLowerCase() + "|" + path
                + "|" + size + "|" + mtime;
        return "imp_" + Long.toUnsignedString(fnv1a64(seed), 16);
    }

    private static long fnv1a64(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** 读取全部任务(按 updated_at 倒序)。 */
    public static List<Task> load(KvStore kv) {
        List<KeyValue> rows = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_IMPORT_STATE),
                KeyCodec.tableRangeEnd(SystemTables.SYS_IMPORT_STATE), 0);
        List<Task> tasks = new ArrayList<>(rows.size());
        for (KeyValue row : rows) {
            RowCodec.Decoded decoded = RowCodec.decode(SystemTables.IMPORT_STATE, row.value());
            if (decoded.tombstone()) {
                continue;
            }
            tasks.add(fromValues(decoded.values()));
        }
        tasks.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return tasks;
    }

    public static Task find(KvStore kv, String importId) {
        byte[] raw = kv.get(SystemTables.importStateKey(importId));
        if (raw == null) {
            return null;
        }
        RowCodec.Decoded decoded = RowCodec.decode(SystemTables.IMPORT_STATE, raw);
        if (decoded.tombstone()) {
            return null;
        }
        return fromValues(decoded.values());
    }

    public static void save(KvStore kv, Task task) {
        kv.put(SystemTables.importStateKey(task.importId()),
                SystemTables.encodeImportStateRow(task.importId(), task.tableId(),
                        task.tableName(), task.filePath(), task.fileSize(), task.fileMtime(),
                        task.lastOffset(), task.lastRowNo(), task.committedRows(),
                        task.totalRowsEstimate(), task.status(), task.errorSummary(),
                        task.updatedAt()));
    }

    private static Task fromValues(Object[] v) {
        return new Task(
                (String) v[0],
                ((Number) v[1]).longValue(),
                (String) v[2],
                (String) v[3],
                ((Number) v[4]).longValue(),
                ((Number) v[5]).longValue(),
                ((Number) v[6]).longValue(),
                ((Number) v[7]).longValue(),
                ((Number) v[8]).longValue(),
                ((Number) v[9]).longValue(),
                (String) v[10],
                (String) v[11],
                (String) v[12]);
    }

    public static String nowText() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
