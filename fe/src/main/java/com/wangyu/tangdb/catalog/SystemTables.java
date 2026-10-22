package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 系统表布局(规约 §5.4)。系统表就是普通表,只是表 ID 落在保留区 1~15。
 *
 * <pre>
 * 1 sys_tables   : key = table_id(4B BE)
 *                  列: table_id BIGINT, name VARCHAR, state INT, created_at VARCHAR
 * 2 sys_columns  : key = table_id(4B BE) + ordinal(2B BE)
 *                  列: table_id BIGINT, ordinal INT, name VARCHAR, type INT,
 *                      type_len INT, nullable BOOLEAN, is_pk BOOLEAN
 * 4 sys_counters : key = name(VARCHAR 保序编码)
 *                  列: name VARCHAR, value BIGINT
 * </pre>
 */
public final class SystemTables {

    public static final long SYS_TABLES = 1;
    public static final long SYS_COLUMNS = 2;
    public static final long SYS_COUNTERS = 4;

    /** 预留的表 ID 上界(1~15)。 */
    public static final long RESERVED_MAX_TABLE_ID = 15;

    public static final String COUNTER_NEXT_TABLE_ID = "next_table_id";
    public static final long INITIAL_NEXT_TABLE_ID = 16;

    private static final int MAX_VARCHAR = 65535;

    public static final TableMeta TABLES = new TableMeta(
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

    public static final TableMeta COUNTERS = new TableMeta(
            SYS_COUNTERS, "sys_counters", TableMeta.STATE_ACTIVE, "",
            List.of(
                    new Column("name", DataType.VARCHAR, MAX_VARCHAR, false, true, 0),
                    new Column("value", DataType.BIGINT, 0, false, false, 1)),
            true);

    private SystemTables() {
    }

    public static boolean isSystemTable(long tableId) {
        return tableId >= 1 && tableId <= RESERVED_MAX_TABLE_ID;
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

    public static byte[] encodeTableRow(long tableId, String name, int state, String createdAt) {
        return RowCodec.encode(TABLES, new Object[]{tableId, name, state, createdAt});
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
