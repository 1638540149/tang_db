package com.wangyu.tangdb.stats;

/**
 * 表级统计(SPEC-P6 FR-616 / §5.1 表 ID 7 {@code sys_table_stats})。
 *
 * <p>列顺序与系统表一致:{@code db_name, table_name, row_count, total_bytes,
 * analyzed_commit_ts, sample_permille, last_analyze, stale}。末尾两列
 * ({@code analyze_millis} / {@code scanned_rows})是 FR-672 / FR-675 要求的
 * "分析耗时 / 扫描行数",必须落库才能在重启后与 SHOW STATS 保持一致(SC-388)。</p>
 */
public record TableStats(long tableId, String dbName, String tableName, long rowCount,
                         long totalBytes, long analyzedCommitTs, int samplePermille,
                         String lastAnalyze, boolean stale, long analyzeMillis,
                         long scannedRows) {

    public TableStats withStale(boolean value) {
        return new TableStats(tableId, dbName, tableName, rowCount, totalBytes, analyzedCommitTs,
                samplePermille, lastAnalyze, value, analyzeMillis, scannedRows);
    }
}
