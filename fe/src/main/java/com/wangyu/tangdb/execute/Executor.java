package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.planner.Plan;
import com.wangyu.tangdb.planner.Planner;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语句执行器:DDL 走 catalog,DML/SELECT 走 BE 的 Get/Scan + FE 侧过滤。
 *
 * <p>P2 不做事务:多行 INSERT 逐行写入,主键冲突只做"查一次再写"(K17 / §2.3),
 * 并发下的唯一性不做保证。</p>
 */
public final class Executor {

    private final KvStore kv;
    private final Catalog catalog;

    public Executor(KvStore kv, Catalog catalog) {
        this.kv = kv;
        this.catalog = catalog;
    }

    public Result execute(Ast.Statement statement) {
        if (statement instanceof Ast.CreateTable create) {
            return createTable(create);
        }
        if (statement instanceof Ast.DropTable drop) {
            catalog.dropTable(drop.table());
            return new Result.Message("OK, 表 " + drop.table().toLowerCase() + " 已删除");
        }
        if (statement instanceof Ast.TruncateTable truncate) {
            TableMeta table = catalog.truncateTable(truncate.table());
            return new Result.Message("OK, 表 " + table.name() + " 已清空(新表 ID " + table.id() + ")");
        }
        if (statement instanceof Ast.ShowTables) {
            return showTables();
        }
        if (statement instanceof Ast.Describe describe) {
            return describe(describe);
        }
        if (statement instanceof Ast.Insert insert) {
            return insert(insert);
        }
        if (statement instanceof Ast.Update update) {
            return update(update);
        }
        if (statement instanceof Ast.Delete delete) {
            return delete(delete);
        }
        if (statement instanceof Ast.Select select) {
            return select(select);
        }
        throw new CatalogException("不支持的语句");
    }

    // ------------------------------------------------------------ DDL

    private Result createTable(Ast.CreateTable create) {
        List<Column> columns = new ArrayList<>(create.columns().size());
        for (int i = 0; i < create.columns().size(); i++) {
            Ast.ColumnDef def = create.columns().get(i);
            columns.add(new Column(def.name(), def.type(), def.length(), def.nullable(),
                    def.primaryKey(), i));
        }
        TableMeta table = catalog.createTable(create.table(), columns);
        return new Result.Message("OK, 表 " + table.name() + " 已创建(表 ID " + table.id() + ")");
    }

    private Result showTables() {
        List<TableMeta> tables = catalog.tables();
        List<Object[]> rows = new ArrayList<>(tables.size());
        for (TableMeta table : tables) {
            rows.add(new Object[]{table.name(), table.id(), table.createdAt()});
        }
        return new Result.Table(List.of("table_name", "table_id", "created_at"), rows);
    }

    private Result describe(Ast.Describe describe) {
        TableMeta table = catalog.require(describe.table());
        List<Object[]> rows = new ArrayList<>(table.columnCount());
        for (Column column : table.columns()) {
            rows.add(new Object[]{
                    column.name(),
                    column.typeName(),
                    column.primaryKey() ? "YES" : "NO",
                    column.nullable() ? "YES" : "NO"
            });
        }
        return new Result.Table(List.of("column", "type", "primary_key", "nullable"), rows);
    }

    // ------------------------------------------------------------ DML

    private Result insert(Ast.Insert insert) {
        TableMeta table = catalog.require(insert.table());
        if (insert.rows().isEmpty()) {
            throw new StoreException("INSERT 至少需要一组 VALUES");
        }

        List<Column> targets = new ArrayList<>(insert.columns().size());
        Set<String> seen = new HashSet<>();
        for (String columnName : insert.columns()) {
            Column column = table.column(columnName);
            if (column == null) {
                throw new CatalogException("列 " + columnName + " 不存在(表 " + table.name() + ")");
            }
            if (!seen.add(column.name())) {
                throw new StoreException("列 " + column.name() + " 在 INSERT 列表里重复出现");
            }
            targets.add(column);
        }
        Column pk = table.primaryKey();
        if (!seen.contains(pk.name())) {
            throw new StoreException("INSERT 必须给出主键列 " + pk.name() + "(表 " + table.name() + ")");
        }

        int written = 0;
        for (int k = 0; k < insert.rows().size(); k++) {
            List<Object> literals = insert.rows().get(k);
            if (literals.size() != targets.size()) {
                throw new StoreException("第 " + (k + 1) + " 组取值个数(" + literals.size()
                        + ")与列数(" + targets.size() + ")不一致");
            }
            Object[] values = new Object[table.columnCount()];
            for (int j = 0; j < targets.size(); j++) {
                values[targets.get(j).ordinal()] = Values.coerce(literals.get(j), targets.get(j));
            }
            Object pkValue = values[pk.ordinal()];
            if (pkValue == null) {
                throw new StoreException("主键列 " + pk.name() + " 不能为 NULL");
            }
            byte[] key = KeyCodec.rowKey(table.id(), pk.type(), pkValue);
            byte[] existing = kv.get(key);
            if (existing != null && !isTombstone(table, existing)) {
                throw new StoreException("第 " + (k + 1) + " 组主键冲突(表 " + table.name()
                        + ",主键 " + Values.display(pkValue) + ");此前已写入 " + written + " 行");
            }
            kv.put(key, RowCodec.encode(table, values));
            written++;
        }
        return new Result.Message(affected(written));
    }

    private Result update(Ast.Update update) {
        TableMeta table = catalog.require(update.table());

        int[] targetIndexes = new int[update.assignments().size()];
        Object[] targetValues = new Object[update.assignments().size()];
        for (int i = 0; i < update.assignments().size(); i++) {
            Ast.Assignment assignment = update.assignments().get(i);
            Column column = table.column(assignment.column());
            if (column == null) {
                throw new CatalogException("列 " + assignment.column() + " 不存在(表 " + table.name() + ")");
            }
            if (column.primaryKey()) {
                throw new StoreException("不允许修改主键列 " + column.name()
                        + "(本期只支持改非主键列)");
            }
            targetIndexes[i] = column.ordinal();
            targetValues[i] = Values.coerce(assignment.value(), column);
        }
        int[] count = {0};
        forEachMatch(Planner.access(table, update.where()), table, (key, values) -> {
            Object[] updated = values.clone();
            for (int i = 0; i < targetIndexes.length; i++) {
                updated[targetIndexes[i]] = targetValues[i];
            }
            kv.put(key, RowCodec.encode(table, updated));
            count[0]++;
            return true;
        });
        return new Result.Message(affected(count[0]));
    }

    private Result delete(Ast.Delete delete) {
        TableMeta table = catalog.require(delete.table());
        int[] count = {0};
        forEachMatch(Planner.access(table, delete.where()), table, (key, values) -> {
            kv.put(key, RowCodec.encodeTombstone(table.id()));
            count[0]++;
            return true;
        });
        return new Result.Message(affected(count[0]));
    }

    // ------------------------------------------------------------ SELECT

    private Result select(Ast.Select select) {
        TableMeta table = catalog.require(select.table());
        Plan plan = Planner.access(table, select.where());
        Planner.Projection projection = Planner.projection(table, select);

        List<Object[]> rows = new ArrayList<>();
        if (select.limit() != null && select.limit() == 0) {
            return new Result.Table(projection.names(), rows);
        }
        int limit = select.limit() == null ? Integer.MAX_VALUE : select.limit();
        forEachMatch(plan, table, (key, values) -> {
            if (rows.size() >= limit) {
                return false;
            }
            Object[] projected = new Object[projection.indexes().length];
            for (int i = 0; i < projection.indexes().length; i++) {
                projected[i] = values[projection.indexes()[i]];
            }
            rows.add(projected);
            return rows.size() < limit;
        });
        return new Result.Table(projection.names(), rows);
    }

    // ------------------------------------------------------------ 行访问

    /** 行回调:返回 false 表示停止扫描。 */
    private interface RowVisitor {
        boolean visit(byte[] key, Object[] values);
    }

    /**
     * 按计划读取满足 WHERE 的行(墓碑跳过、三值逻辑只保留 TRUE),回调给调用方。
     *
     * @return 实际回调的行数
     */
    private int forEachMatch(Plan plan, TableMeta table, RowVisitor visitor) {
        int count = 0;
        switch (plan.kind()) {
            case POINT, POINTS -> {
                for (byte[] key : plan.pointKeys()) {
                    byte[] data = kv.get(key);
                    if (data == null) {
                        continue;
                    }
                    RowCodec.Decoded decoded = RowCodec.decode(table, data);
                    if (decoded.tombstone() || !matches(plan, decoded.values())) {
                        continue;
                    }
                    count++;
                    if (!visitor.visit(key, decoded.values())) {
                        return count;
                    }
                }
            }
            case RANGE -> {
                List<KeyValue> kvs = kv.scan(plan.start(), plan.end(), 0);
                for (KeyValue row : kvs) {
                    RowCodec.Decoded decoded = RowCodec.decode(table, row.value());
                    if (decoded.tombstone() || !matches(plan, decoded.values())) {
                        continue;
                    }
                    count++;
                    if (!visitor.visit(row.key(), decoded.values())) {
                        return count;
                    }
                }
            }
            default -> throw new CatalogException("未知的访问计划: " + plan.kind());
        }
        return count;
    }

    private static boolean matches(Plan plan, Object[] values) {
        return Boolean.TRUE.equals(plan.predicate().test(values));
    }

    private static boolean isTombstone(TableMeta table, byte[] data) {
        return data.length > 0 && data[0] == RowCodec.FORMAT_TOMBSTONE;
    }

    static String affected(int rows) {
        return "OK, " + rows + (rows == 1 ? " row affected" : " rows affected");
    }
}
