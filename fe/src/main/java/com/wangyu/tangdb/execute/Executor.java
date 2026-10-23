package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.planner.Plan;
import com.wangyu.tangdb.planner.Planner;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.ConflictException;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.TransactionStore;
import com.wangyu.tangdb.storage.Values;
import com.wangyu.tangdb.txn.ReadView;
import com.wangyu.tangdb.txn.ReadViews;
import com.wangyu.tangdb.txn.Transaction;
import com.wangyu.tangdb.txn.TxnState;
import com.wangyu.tangdb.txn.WriteSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 语句执行器:DDL 走 catalog,DML/SELECT 走 BE 的 Get/Scan + FE 侧过滤。
 *
 * <p>P3 起支持显式事务与自动提交(SPEC-P3 FR-340 ~ FR-348):</p>
 * <ul>
 *   <li>事务内 DML 只改内存写集,按 key 覆盖,同事务多次改同一行只留最终版本;</li>
 *   <li>读己之写:SELECT / UPDATE / DELETE 都先看写集再看 BE;</li>
 *   <li>事务外单条 DML 自成事务(整条语句的所有写入走同一条批记录);</li>
 *   <li>事务内语句失败不自动回滚,事务保持活动(FR-344)。</li>
 * </ul>
 */
public final class Executor {

    private final KvStore kv;
    private final Catalog catalog;

    /** null = IDLE;非 null = ACTIVE。同一会话最多一个事务(FR-341)。 */
    private Transaction active;

    /**
     * 自动提交 DML 的隐式事务:在**读取之前**取快照,读取/写入完成后再提交(FR-343)。
     * 只在单条 DML 执行期间非 null。
     */
    private Transaction implicit;
    private boolean implicitCommitted;

    public Executor(KvStore kv, Catalog catalog) {
        this.kv = kv;
        this.catalog = catalog;
    }

    public TxnState txnState() {
        return active == null ? TxnState.IDLE : TxnState.ACTIVE;
    }

    public boolean inTransaction() {
        return active != null;
    }

    public Result execute(Ast.Statement statement) {
        if (statement instanceof Ast.Begin) {
            return begin();
        }
        if (statement instanceof Ast.Commit) {
            return commit();
        }
        if (statement instanceof Ast.Rollback) {
            return rollback();
        }

        if (statement instanceof Ast.CreateTable create) {
            requireNoTransaction("CREATE TABLE");
            return createTable(create);
        }
        if (statement instanceof Ast.DropTable drop) {
            requireNoTransaction("DROP TABLE");
            catalog.dropTable(drop.table());
            return new Result.Message("OK, 表 " + drop.table().toLowerCase() + " 已删除");
        }
        if (statement instanceof Ast.TruncateTable truncate) {
            requireNoTransaction("TRUNCATE TABLE");
            TableMeta table = catalog.truncateTable(truncate.table());
            return new Result.Message("OK, 表 " + table.name() + " 已清空(新表 ID " + table.id() + ")");
        }
        if (statement instanceof Ast.ShowTables) {
            return showTables();
        }
        if (statement instanceof Ast.ShowDatabases) {
            return showDatabases();
        }
        if (statement instanceof Ast.Describe describe) {
            return describe(describe);
        }
        if (statement instanceof Ast.Insert insert) {
            return runDml(() -> insert(insert));
        }
        if (statement instanceof Ast.Update update) {
            return runDml(() -> update(update));
        }
        if (statement instanceof Ast.Delete delete) {
            return runDml(() -> delete(delete));
        }
        if (statement instanceof Ast.Select select) {
            return select(select);
        }
        throw new CatalogException("不支持的语句");
    }

    // ------------------------------------------------------------ 事务控制

    private Result begin() {
        if (active != null) {
            throw new StoreException("已有活动事务,请先 COMMIT 或 ROLLBACK");
        }
        if (kv instanceof TransactionStore store) {
            TransactionStore.TxnHandle handle = store.beginTxn();
            active = new Transaction(handle.txnId(), handle.snapshot());
        } else {
            // 内存假实现:没有 BE 句柄,快照 0 = 最新;事务语义仍由本地写集保证。
            active = Transaction.local();
        }
        return new Result.Message("OK, 事务已开始(snapshot = " + active.snapshot() + ")");
    }

    private Result commit() {
        if (active == null) {
            throw new StoreException("没有活动事务");
        }
        Transaction txn = active;
        List<KeyValue> writes = txn.writes().entries();
        if (writes.isEmpty()) {
            // 只读事务:直接成功,不取号(§9)。
            if (kv instanceof TransactionStore store) {
                store.abort(txn.txnId());
            }
            active = null;
            return new Result.Message("OK, 事务已提交(无写入)");
        }

        long commitTs;
        try {
            commitTs = commitWrites(txn.txnId(), txn.snapshot(), writes);
        } catch (ConflictException e) {
            // 冲突时事务保持活动,由用户决定重试还是 ROLLBACK(FR-344)。
            throw translateConflict(e);
        }
        active = null;
        if (commitTs > 0) {
            return new Result.Message("OK, 事务已提交(commit_ts = " + commitTs + ")");
        }
        return new Result.Message("OK, 事务已提交");
    }

    private Result rollback() {
        if (active == null) {
            throw new StoreException("没有活动事务");
        }
        abortQuietly(active);
        active = null;
        return new Result.Message("OK, 已回滚");
    }

    /**
     * CLI 断开 / exit 时调用:丢弃未提交写集并释放 BE 句柄(FR-345)。
     *
     * @return 丢弃了事务时返回提示文本,否则 null
     */
    public String rollbackOnDisconnect() {
        if (active == null) {
            return null;
        }
        abortQuietly(active);
        active = null;
        return "已丢弃未提交事务(CLI 退出)";
    }

    private void abortQuietly(Transaction txn) {
        if (kv instanceof TransactionStore store) {
            try {
                store.abort(txn.txnId());
            } catch (RuntimeException e) {
                // 断开时 BE 可能已经不可用;句柄过期无害(§9),不打断退出流程。
            }
        }
    }

    private void requireNoTransaction(String ddl) {
        if (active != null) {
            throw new StoreException("DDL 不能在事务内执行(" + ddl + "),请先 COMMIT 或 ROLLBACK");
        }
    }

    private long commitWrites(long txnId, long snapshot, List<KeyValue> writes) {
        if (kv instanceof TransactionStore store) {
            return store.commit(txnId, snapshot, writes);
        }
        kv.writeBatch(writes);
        return 0;
    }

    /** 把 BE 回的冲突用户键翻译成"表 t 主键 x 写写冲突"(FR-333 / FR-359)。 */
    private StoreException translateConflict(ConflictException e) {
        byte[] key = e.conflictKey();
        if (key == null) {
            return new StoreException("写写冲突(该行在本事务开始后被其它事务修改,请重试)");
        }
        long tableId = KeyCodec.tableId(key);
        TableMeta table = catalog.findById(tableId);
        if (table != null) {
            try {
                Object pkValue = KeyCodec.decodePrimaryKey(table, key);
                return new StoreException("表 " + table.name() + " 主键 " + Values.display(pkValue)
                        + " 写写冲突(该行在本事务开始后被其它事务修改,请重试)");
            } catch (RuntimeException ignored) {
                // 解码失败就退回到不含主键的文案,绝不把内部 key 十六进制打给用户。
            }
        }
        return new StoreException("表 ID " + tableId
                + " 的某一行写写冲突(该行在本事务开始后被其它事务修改,请重试)");
    }

    // ------------------------------------------------------------ 读视图

    /**
     * 自动提交 DML 的外壳:先向 BE 取快照(隐式事务),再执行读取与写入。
     *
     * <p>顺序很关键:快照必须在**任何读取之前**固定,否则"读完之后、提交之前"别人提交的
     * 修改会被本语句的 snapshot 包含进去,导致静默覆盖(FR-343 的"取快照 → 写 → 提交")。</p>
     */
    private Result runDml(Supplier<Result> body) {
        if (active != null || !(kv instanceof TransactionStore store)) {
            return body.get();
        }
        TransactionStore.TxnHandle handle = store.beginTxn();
        implicit = new Transaction(handle.txnId(), handle.snapshot());
        implicitCommitted = false;
        try {
            return body.get();
        } finally {
            // 成功提交时 BE 已释放句柄;只有没走到提交(出错 / 0 行 / 冲突)才需要 abort。
            if (!implicitCommitted) {
                try {
                    store.abort(handle.txnId());
                } catch (RuntimeException ignored) {
                    // 句柄过期无害,不覆盖原始异常。
                }
            }
            implicit = null;
            implicitCommitted = false;
        }
    }

    /** 当前语句的读视图:显式事务 / 隐式事务按快照 + 写集;都没有时读最新。 */
    private ReadView currentView(WriteSet local) {
        Transaction txn = active != null ? active : implicit;
        ReadView base = txn == null
                ? ReadViews.latest(kv)
                : ReadViews.ofTransaction(kv, txn);
        return local == null ? base : ReadViews.layered(base, local);
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

    /**
     * SHOW DATABASES:只有默认库(K22),静态回答,不访问 BE(FR-200 / FR-201)。
     */
    private Result showDatabases() {
        return new Result.Table(List.of("Database"), List.<Object[]>of(new Object[]{"default"}));
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

        // 空列名列表 = 省略写法:按表定义顺序取全部列(FR-190 / K35)。
        List<Column> targets = new ArrayList<>(
                insert.columns().isEmpty() ? table.columnCount() : insert.columns().size());
        Set<String> seen = new HashSet<>();
        if (insert.columns().isEmpty()) {
            for (Column column : table.columns()) {
                targets.add(column);
                seen.add(column.name());
            }
        } else {
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
        }
        Column pk = table.primaryKey();
        if (!seen.contains(pk.name())) {
            throw new StoreException("INSERT 必须给出主键列 " + pk.name() + "(表 " + table.name() + ")");
        }

        WriteSet local = new WriteSet();
        ReadView view = currentView(local);

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
            byte[] existing = view.get(key);
            if (existing != null && !isTombstone(table, existing)) {
                throw new StoreException("第 " + (k + 1) + " 组主键冲突(表 " + table.name()
                        + ",主键 " + Values.display(pkValue) + ")");
            }
            local.put(key, RowCodec.encode(table, values));
            written++;
        }
        flushWrites(local);
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

        WriteSet local = new WriteSet();
        ReadView view = currentView(local);
        int[] count = {0};
        forEachMatch(Planner.access(table, update.where()), table, view, (key, values) -> {
            Object[] updated = values.clone();
            for (int i = 0; i < targetIndexes.length; i++) {
                updated[targetIndexes[i]] = targetValues[i];
            }
            local.put(key, RowCodec.encode(table, updated));
            count[0]++;
            return true;
        });
        flushWrites(local);
        return new Result.Message(affected(count[0]));
    }

    private Result delete(Ast.Delete delete) {
        TableMeta table = catalog.require(delete.table());

        WriteSet local = new WriteSet();
        ReadView view = currentView(local);
        int[] count = {0};
        forEachMatch(Planner.access(table, delete.where()), table, view, (key, values) -> {
            local.put(key, RowCodec.encodeTombstone(table.id()));
            count[0]++;
            return true;
        });
        flushWrites(local);
        return new Result.Message(affected(count[0]));
    }

    /** 把语句的临时写集并入事务,或作为自动提交事务一次性提交。 */
    private void flushWrites(WriteSet local) {
        if (local.isEmpty()) {
            return;
        }
        if (active != null) {
            active.writes().putAll(local);
            return;
        }
        try {
            if (implicit != null) {
                commitWrites(implicit.txnId(), implicit.snapshot(), local.entries());
                implicitCommitted = true;
            } else {
                commitWrites(0, 0, local.entries());
            }
        } catch (ConflictException e) {
            throw translateConflict(e);
        }
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
        // 事务内:固定快照 + 读己之写;事务外:最新已提交(FR-343)。
        ReadView view = currentView(null);
        forEachMatch(plan, table, view, (key, values) -> {
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

    /** 行回调:返回 false 表示停止扫描。key 是用户键(不含版本段)。 */
    private interface RowVisitor {
        boolean visit(byte[] key, Object[] values);
    }

    /**
     * 按计划读取满足 WHERE 的行(墓碑跳过、三值逻辑只保留 TRUE),回调给调用方。
     *
     * @return 实际回调的行数
     */
    private int forEachMatch(Plan plan, TableMeta table, ReadView view, RowVisitor visitor) {
        int count = 0;
        switch (plan.kind()) {
            case POINT, POINTS -> {
                for (byte[] key : plan.pointKeys()) {
                    byte[] data = view.get(key);
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
                List<KeyValue> kvs = view.scan(plan.start(), plan.end(), 0);
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
