package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 系统表布局(规约 §5.4)。系统表就是普通表,只是表 ID 落在登记表里。
 *
 * <pre>
 * 1 sys_tables     : key = table_id(4B BE)
 *                    列: table_id BIGINT, name VARCHAR, state INT, created_at VARCHAR,
 *                        db_name VARCHAR
 * 2 sys_columns    : key = table_id(4B BE) + ordinal(2B BE)
 *                    列: table_id BIGINT, ordinal INT, name VARCHAR, type INT,
 *                        type_len INT, nullable BOOLEAN, is_pk BOOLEAN
 * 3 sys_databases  : key = db_name(VARCHAR 保序编码)
 *                    列: db_name VARCHAR, create_ts VARCHAR, remark VARCHAR
 * 4 sys_counters   : key = name(VARCHAR 保序编码)
 *                    列: name VARCHAR, value BIGINT
 * 5 sys_indexes    : key = table_id(4B BE) + index_id(4B BE)
 *                    列: table_id BIGINT, index_id INT, name VARCHAR, column_name VARCHAR,
 *                        is_unique BOOLEAN, state INT, build_cursor VARCHAR, created_at VARCHAR
 * </pre>
 *
 * <p>系统表 ID 登记表(SPEC-P3.5 K70 / FR-426;SPEC-P4 FR-473 新增 5):</p>
 *
 * <pre>
 * | ID | 状态   | 用途        |
 * |  1 | 已分配 | sys_tables  |
 * |  2 | 已分配 | sys_columns |
 * |  3 | 已分配 | sys_databases |
 * |  4 | 已分配 | sys_counters|
 * |  5 | 已分配 | sys_indexes |
 * | 6~63 | 预留 | 后续元数据族 |
 * | ≥64 | 用户表 | 新库 next_table_id 初值 |
 * </pre>
 */
public final class SystemTables {

    public static final long SYS_TABLES = 1;
    public static final long SYS_COLUMNS = 2;
    public static final long SYS_DATABASES = 3;
    public static final long SYS_COUNTERS = 4;
    public static final long SYS_INDEXES = 5;
    /** SPEC-P5 §5.3 / FR-614:CSV 导入任务与断点状态。 */
    public static final long SYS_IMPORT_STATE = 6;
    /** SPEC-P6 §5.1 / FR-621:表级统计。 */
    public static final long SYS_TABLE_STATS = 7;
    /** SPEC-P6 §5.1 / FR-621:列级统计。 */
    public static final long SYS_COLUMN_STATS = 8;
    /** SPEC-P7 §5.3 / FR-741:向量索引元数据(段级状态在 BE MANIFEST)。 */
    public static final long SYS_VEC_INDEXES = 9;

    /** 系统表 ID 登记表(FR-426 / K70 / FR-473 / SPEC-P5 FR-614 / SPEC-P6 §5.1)。 */
    public static final Set<Long> REGISTERED_IDS =
            Set.of(SYS_TABLES, SYS_COLUMNS, SYS_DATABASES, SYS_COUNTERS, SYS_INDEXES,
                    SYS_IMPORT_STATE, SYS_TABLE_STATS, SYS_COLUMN_STATS, SYS_VEC_INDEXES);

    /** 预留区上界(6~63 预留给后续元数据族,当前未登记)。 */
    public static final long RESERVED_MAX_TABLE_ID = 63;

    public static final String COUNTER_NEXT_TABLE_ID = "next_table_id";

    /** 索引 ID 计数器的键前缀:`next_index_id:<table_id>`(FR-464 / K75)。 */
    public static final String COUNTER_NEXT_INDEX_ID_PREFIX = "next_index_id:";

    /** 新库 next_table_id 初值(FR-425)。 */
    public static final long INITIAL_NEXT_TABLE_ID = 64;

    /** 任何数据目录里 next_table_id 的下限:1~5 是系统表,不得分配(FR-426 / FR-473)。 */
    public static final long MIN_TABLE_ID = 6;

    public static final String DEFAULT_DATABASE = "default";

    private static final int MAX_VARCHAR = 65535;

    /** sys_tables(新 5 列形态,db_name 追加在末尾,老目录 4 列行是它的前缀)。 */
    public static final TableMeta TABLES = new TableMeta(
            SYS_TABLES, "sys_tables", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("name", DataType.VARCHAR, MAX_VARCHAR, false, false, 1),
                    new Column("state", DataType.INT, 0, false, false, 2),
                    new Column("created_at", DataType.VARCHAR, MAX_VARCHAR, false, false, 3),
                    new Column("db_name", DataType.VARCHAR, 64, false, false, 4)),
            true);

    /** sys_tables 老形态(P3 及以前,4 列,无 db_name),用于老数据目录的宽容读(FR-424)。 */
    public static final TableMeta TABLES_LEGACY = new TableMeta(
            SYS_TABLES, "sys_tables", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("name", DataType.VARCHAR, MAX_VARCHAR, false, false, 1),
                    new Column("state", DataType.INT, 0, false, false, 2),
                    new Column("created_at", DataType.VARCHAR, MAX_VARCHAR, false, false, 3)),
            true);

    public static final TableMeta COLUMNS = new TableMeta(
            SYS_COLUMNS, "sys_columns", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("ordinal", DataType.INT, 0, false, false, 1),
                    new Column("name", DataType.VARCHAR, MAX_VARCHAR, false, false, 2),
                    new Column("type", DataType.INT, 0, false, false, 3),
                    new Column("type_len", DataType.INT, 0, false, false, 4),
                    new Column("nullable", DataType.BOOLEAN, 0, false, false, 5),
                    new Column("is_pk", DataType.BOOLEAN, 0, false, false, 6)),
            true);

    public static final TableMeta DATABASES = new TableMeta(
            SYS_DATABASES, "sys_databases", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("db_name", DataType.VARCHAR, 64, false, true, 0),
                    new Column("create_ts", DataType.VARCHAR, 64, false, false, 1),
                    new Column("remark", DataType.VARCHAR, MAX_VARCHAR, true, false, 2)),
            true);

    public static final TableMeta COUNTERS = new TableMeta(
            SYS_COUNTERS, "sys_counters", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("name", DataType.VARCHAR, MAX_VARCHAR, false, true, 0),
                    new Column("value", DataType.BIGINT, 0, false, false, 1)),
            true);

    /** SPEC-P4 §5.4:索引元数据表。复合主键 (table_id, index_id) 由 key 编码表达。 */
    public static final TableMeta INDEXES = new TableMeta(
            SYS_INDEXES, "sys_indexes", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("index_id", DataType.INT, 0, false, false, 1),
                    new Column("name", DataType.VARCHAR, 64, false, false, 2),
                    new Column("column_name", DataType.VARCHAR, 64, false, false, 3),
                    new Column("is_unique", DataType.BOOLEAN, 0, false, false, 4),
                    new Column("state", DataType.INT, 0, false, false, 5),
                    new Column("build_cursor", DataType.VARCHAR, 256, false, false, 6),
                    new Column("created_at", DataType.VARCHAR, 64, false, false, 7)),
            true);

    /**
     * SPEC-P5 §5.3 / FR-614:导入任务状态表。
     *
     * <p>在规约 §5.3 的字段基础上补了 {@code status} / {@code error_summary} /
     * {@code total_rows_estimate},用于 E-04 任务模型(task_id / status / progress / error_summary)。</p>
     */
    public static final TableMeta IMPORT_STATE = new TableMeta(
            SYS_IMPORT_STATE, "sys_import_state", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("import_id", DataType.VARCHAR, 128, false, true, 0),
                    new Column("table_id", DataType.BIGINT, 0, false, false, 1),
                    new Column("table_name", DataType.VARCHAR, 64, false, false, 2),
                    new Column("file_path", DataType.VARCHAR, MAX_VARCHAR, false, false, 3),
                    new Column("file_size", DataType.BIGINT, 0, false, false, 4),
                    new Column("file_mtime", DataType.BIGINT, 0, false, false, 5),
                    new Column("last_offset", DataType.BIGINT, 0, false, false, 6),
                    new Column("last_row_no", DataType.BIGINT, 0, false, false, 7),
                    new Column("committed_rows", DataType.BIGINT, 0, false, false, 8),
                    new Column("total_rows_estimate", DataType.BIGINT, 0, false, false, 9),
                    new Column("status", DataType.VARCHAR, 16, false, false, 10),
                    new Column("error_summary", DataType.VARCHAR, MAX_VARCHAR, true, false, 11),
                    new Column("updated_at", DataType.VARCHAR, 64, false, false, 12)),
            true);

    /**
     * SPEC-P6 §5.1:表级统计。key = db_name(保序)+ table_id(4B BE)。
     *
     * <p>末尾两列 {@code analyze_millis} / {@code scanned_rows} 是 FR-672 / FR-675
     * 要求的可观测列;放这里是为了重启后 SHOW STATS 与重启前一致(SC-388)。</p>
     */
    public static final TableMeta TABLE_STATS = new TableMeta(
            SYS_TABLE_STATS, "sys_table_stats", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("db_name", DataType.VARCHAR, 64, false, true, 0),
                    new Column("table_name", DataType.VARCHAR, 64, false, false, 1),
                    new Column("row_count", DataType.BIGINT, 0, false, false, 2),
                    new Column("total_bytes", DataType.BIGINT, 0, false, false, 3),
                    new Column("analyzed_commit_ts", DataType.BIGINT, 0, false, false, 4),
                    new Column("sample_permille", DataType.INT, 0, false, false, 5),
                    new Column("last_analyze", DataType.VARCHAR, 32, false, false, 6),
                    new Column("stale", DataType.BOOLEAN, 0, false, false, 7),
                    new Column("analyze_millis", DataType.BIGINT, 0, false, false, 8),
                    new Column("scanned_rows", DataType.BIGINT, 0, false, false, 9)),
            true);

    /** SPEC-P6 §5.1:列级统计。key = table_id(4B BE)+ ordinal(2B BE)。 */
    public static final TableMeta COLUMN_STATS = new TableMeta(
            SYS_COLUMN_STATS, "sys_column_stats", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("ordinal", DataType.INT, 0, false, false, 1),
                    new Column("column_name", DataType.VARCHAR, 64, false, false, 2),
                    new Column("null_count", DataType.BIGINT, 0, false, false, 3),
                    new Column("distinct_count", DataType.BIGINT, 0, false, false, 4),
                    new Column("distinct_is_approx", DataType.BOOLEAN, 0, false, false, 5),
                    new Column("min_value", DataType.VARCHAR, 64, true, false, 6),
                    new Column("max_value", DataType.VARCHAR, 64, true, false, 7),
                    new Column("histogram", DataType.JSON, 0, false, false, 8)),
            true);

    /** SPEC-P7 §5.3:向量索引元数据权威副本(段级状态在 MANIFEST)。 */
    public static final TableMeta VEC_INDEXES = new TableMeta(
            SYS_VEC_INDEXES, "sys_vec_indexes", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("table_id", DataType.BIGINT, 0, false, true, 0),
                    new Column("index_id", DataType.INT, 0, false, false, 1),
                    new Column("name", DataType.VARCHAR, 64, false, false, 2),
                    new Column("column_name", DataType.VARCHAR, 64, false, false, 3),
                    new Column("column_ordinal", DataType.INT, 0, false, false, 4),
                    new Column("dim", DataType.INT, 0, false, false, 5),
                    new Column("metric", DataType.INT, 0, false, false, 6),
                    new Column("m", DataType.INT, 0, false, false, 7),
                    new Column("ef_construction", DataType.INT, 0, false, false, 8),
                    new Column("state", DataType.INT, 0, false, false, 9),
                    new Column("created_at", DataType.VARCHAR, 64, false, false, 10)),
            true);

    private SystemTables() {
    }

    /** sys_vec_indexes 的主键编码:table_id 4B 大端 + index_id 4B 大端。 */
    public static byte[] vecIndexKey(long tableId, int indexId) {
        return KeyCodec.systemRowKey(SYS_VEC_INDEXES,
                KeyCodec.concat(KeyCodec.be4(tableId), KeyCodec.be4(indexId)));
    }

    /** sys_vec_indexes 行(列顺序与 {@link #VEC_INDEXES} 一致)。 */
    public static byte[] encodeVecIndexRow(com.wangyu.tangdb.storage.VectorIndexMeta index) {
        return RowCodec.encode(VEC_INDEXES, new Object[]{
                index.tableId(),
                index.indexId(),
                index.name(),
                index.columnName(),
                index.columnOrdinal(),
                index.dim(),
                index.metric(),
                index.m(),
                index.efConstruction(),
                index.state(),
                index.createdAt()
        });
    }

    /** 表 ID 是否属于系统表登记表(FR-426:不在登记表内即用户表)。 */
    public static boolean isSystemTable(long tableId) {
        return REGISTERED_IDS.contains(tableId);
    }

    /** sys_table_stats 的主键编码:db_name(保序)+ table_id(4B 大端)。 */
    public static byte[] tableStatsKey(String dbName, long tableId) {
        return KeyCodec.systemRowKey(SYS_TABLE_STATS,
                KeyCodec.concat(KeyCodec.encodePrimaryKey(DataType.VARCHAR,
                        dbName == null ? "" : dbName.toLowerCase()), KeyCodec.be4(tableId)));
    }

    /** sys_column_stats 的主键编码:table_id(4B 大端)+ ordinal(2B 大端)。 */
    public static byte[] columnStatsKey(long tableId, int ordinal) {
        return KeyCodec.systemRowKey(SYS_COLUMN_STATS,
                KeyCodec.concat(KeyCodec.be4(tableId), KeyCodec.be2(ordinal)));
    }

    /** sys_table_stats 行(列顺序与 {@link #TABLE_STATS} 一致)。 */
    public static byte[] encodeTableStatsRow(String dbName, String tableName, long rowCount,
                                             long totalBytes, long analyzedCommitTs,
                                             int samplePermille, String lastAnalyze, boolean stale,
                                             long analyzeMillis, long scannedRows) {
        return RowCodec.encode(TABLE_STATS, new Object[]{
                dbName, tableName, rowCount, totalBytes, analyzedCommitTs, samplePermille,
                lastAnalyze, stale, analyzeMillis, scannedRows});
    }

    /** sys_column_stats 行(列顺序与 {@link #COLUMN_STATS} 一致)。 */
    public static byte[] encodeColumnStatsRow(long tableId, int ordinal, String columnName,
                                              long nullCount, long distinctCount,
                                              boolean distinctIsApprox, String minValue,
                                              String maxValue, String histogramJson) {
        return RowCodec.encode(COLUMN_STATS, new Object[]{
                tableId, ordinal, columnName, nullCount, distinctCount, distinctIsApprox,
                minValue, maxValue, histogramJson == null
                        ? "{}".getBytes(StandardCharsets.UTF_8)
                        : histogramJson.getBytes(StandardCharsets.UTF_8)});
    }

    /** sys_tables 的主键编码:table_id 4B 大端。 */
    public static byte[] tableKey(long tableId) {
        return KeyCodec.systemRowKey(SYS_TABLES, KeyCodec.be4(tableId));
    }

    /** sys_columns 的主键编码:table_id 4B 大端 + ordinal 2B 大端。 */
    public static byte[] columnKey(long tableId, int ordinal) {
        byte[] pk = new byte[6];
        System.arraycopy(KeyCodec.be4(tableId), 0, pk, 0, 4);
        System.arraycopy(KeyCodec.be2(ordinal), 0, pk, 4, 2);
        return KeyCodec.systemRowKey(SYS_COLUMNS, pk);
    }

    /** sys_counters 的主键编码:name 的 VARCHAR 保序编码。 */
    public static byte[] counterKey(String name) {
        return KeyCodec.systemRowKey(SYS_COUNTERS, KeyCodec.encodePrimaryKey(DataType.VARCHAR, name));
    }

    /** sys_databases 的主键编码:db_name 的 VARCHAR 保序编码。 */
    public static byte[] databaseKey(String dbName) {
        return KeyCodec.systemRowKey(SYS_DATABASES, KeyCodec.encodePrimaryKey(DataType.VARCHAR, dbName));
    }

    /** sys_indexes 的主键编码:table_id 4B 大端 + index_id 4B 大端(FR-479)。 */
    public static byte[] indexKey(long tableId, int indexId) {
        return KeyCodec.systemRowKey(SYS_INDEXES, KeyCodec.concat(KeyCodec.be4(tableId), KeyCodec.be4(indexId)));
    }

    /** sys_counters 里"某表下一个索引 ID"的计数器键(FR-464)。 */
    public static byte[] indexCounterKey(long tableId) {
        return counterKey(COUNTER_NEXT_INDEX_ID_PREFIX + tableId);
    }

    public static String indexCounterName(long tableId) {
        return COUNTER_NEXT_INDEX_ID_PREFIX + tableId;
    }

    public static byte[] encodeTableRow(long tableId, String name, int state, String createdAt, String dbName) {
        return RowCodec.encode(TABLES, new Object[]{tableId, name, state, createdAt, dbName});
    }

    public static byte[] encodeColumnRow(Column column, long tableId) {
        return RowCodec.encode(COLUMNS, new Object[]{
                tableId,
                column.ordinal(),
                column.name(),
                column.type().code(),
                column.length(),
                column.nullable(),
                column.primaryKey()
        });
    }

    public static byte[] encodeCounterRow(String name, long value) {
        return RowCodec.encode(COUNTERS, new Object[]{name, value});
    }

    public static byte[] encodeDatabaseRow(String dbName, String createTs, String remark) {
        return RowCodec.encode(DATABASES, new Object[]{dbName, createTs, remark});
    }

    /** sys_indexes 行(§5.4 的列顺序)。 */
    /** sys_import_state 的主键编码:import_id 的 VARCHAR 保序编码。 */
    public static byte[] importStateKey(String importId) {
        return KeyCodec.systemRowKey(SYS_IMPORT_STATE, KeyCodec.encodePrimaryKey(DataType.VARCHAR, importId));
    }

    /** sys_import_state 行(列顺序与 {@link #IMPORT_STATE} 一致)。 */
    public static byte[] encodeImportStateRow(String importId, long tableId, String tableName,
                                              String filePath, long fileSize, long fileMtime,
                                              long lastOffset, long lastRowNo, long committedRows,
                                              long totalRowsEstimate, String status,
                                              String errorSummary, String updatedAt) {
        return RowCodec.encode(IMPORT_STATE, new Object[]{
                importId, tableId, tableName, filePath, fileSize, fileMtime, lastOffset,
                lastRowNo, committedRows, totalRowsEstimate, status, errorSummary, updatedAt});
    }

    public static byte[] encodeIndexRow(com.wangyu.tangdb.storage.IndexMeta index) {
        return RowCodec.encode(INDEXES, new Object[]{
                index.tableId(),
                index.indexId(),
                index.name(),
                index.columnName(),
                index.unique(),
                index.state(),
                index.buildCursor(),
                index.createdAt()
        });
    }

    public static long decodeTableId(byte[] row) {
        Object[] values = RowCodec.decode(TABLES, row).values();
        return ((Number) values[0]).longValue();
    }

    public static String decodeTableName(byte[] row) {
        return (String) RowCodec.decode(TABLES, row).values()[1];
    }

    /** 把一个 UTF-8 字符串转成字节数组(测试与日志用)。 */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
