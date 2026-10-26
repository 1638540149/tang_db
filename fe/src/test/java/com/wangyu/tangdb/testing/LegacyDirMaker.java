package com.wangyu.tangdb.testing;

import com.wangyu.tangdb.catalog.SystemTables;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 构造一个 **P3 期间格式**的数据目录(用于 SC-309 老数据目录升级验证)。
 *
 * <p>P3 的 {@code sys_tables} 只有 4 列(无 {@code db_name}),{@code next_table_id} 从 16 起,
 * 没有 {@code sys_databases}。本工具直接经 gRPC 把这样一批元数据与数据写进 BE,
 * 然后由 P3.5 的 fed 启动并验证"宽容读"。</p>
 *
 * <p>用法:{@code java ... LegacyDirMaker <be-host> <be-port>}</p>
 */
public final class LegacyDirMaker {

    private LegacyDirMaker() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9100;
        try (TangDbClient client = new TangDbClient(host, port)) {
            client.writeBatch(legacyWrites());
        }
        System.out.println("legacy dir written to BE " + host + ":" + port
                + " (sys_tables 4 列, next_table_id=17, old_t id=16)");
    }

    /** 构造 P3 格式的整批元数据与两行数据(原子写入)。 */
    static List<KeyValue> legacyWrites() {
        List<KeyValue> writes = new ArrayList<>();

        // 1) 三张系统表自身的定义行:老 4 列形态(P3 没有 sys_databases)。
        TableMeta legacy = SystemTables.TABLES_LEGACY;
        writes.add(new KeyValue(SystemTables.tableKey(SystemTables.SYS_TABLES),
                RowCodec.encode(legacy, new Object[]{SystemTables.SYS_TABLES, "sys_tables", 0, ""})));
        writes.add(new KeyValue(SystemTables.tableKey(SystemTables.SYS_COLUMNS),
                RowCodec.encode(legacy, new Object[]{SystemTables.SYS_COLUMNS, "sys_columns", 0, ""})));
        writes.add(new KeyValue(SystemTables.tableKey(SystemTables.SYS_COUNTERS),
                RowCodec.encode(legacy, new Object[]{SystemTables.SYS_COUNTERS, "sys_counters", 0, ""})));

        // 2) 系统表的列定义(P3 下 sys_tables 只有 4 列)。
        addColumns(writes, SystemTables.SYS_TABLES, legacy.columns());
        addColumns(writes, SystemTables.SYS_COLUMNS, SystemTables.COLUMNS.columns());
        addColumns(writes, SystemTables.SYS_COUNTERS, SystemTables.COUNTERS.columns());

        // 3) next_table_id = 17(P3 起 16,已有一个用户表)。
        writes.add(new KeyValue(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID, 17)));

        // 4) 一张老用户表 old_t(id=16,归属旧语义的"唯一空间")+ 两行数据。
        List<Column> oldCols = List.of(
                new Column("id", DataType.INT, 0, false, true, 0),
                new Column("v", DataType.VARCHAR, 32, true, false, 1));
        TableMeta oldT = new TableMeta(16, "old_t", TableMeta.STATE_ACTIVE,
                "2026-10-23T00:00:00", oldCols, false);
        writes.add(new KeyValue(SystemTables.tableKey(16),
                RowCodec.encode(legacy, new Object[]{16L, "old_t", 0, "2026-10-23T00:00:00"})));
        addColumns(writes, 16, oldCols);
        writes.add(new KeyValue(KeyCodec.rowKey(16, DataType.INT, 1),
                RowCodec.encode(oldT, new Object[]{1, "legacy"})));
        writes.add(new KeyValue(KeyCodec.rowKey(16, DataType.INT, 2),
                RowCodec.encode(oldT, new Object[]{2, "ok"})));
        return writes;
    }

    private static void addColumns(List<KeyValue> writes, long tableId, List<Column> columns) {
        for (Column column : columns) {
            writes.add(new KeyValue(SystemTables.columnKey(tableId, column.ordinal()),
                    SystemTables.encodeColumnRow(column, tableId)));
        }
    }
}
