package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.catalog.SystemTables;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.planner.Plan;
import com.wangyu.tangdb.planner.Planner;
import com.wangyu.tangdb.query.QueryEngine;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.ColumnarCodec;
import com.wangyu.tangdb.storage.ConflictException;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(Executor.class.getName());

    private static final byte[] EMPTY = new byte[0];

    private final KvStore kv;
    private final Catalog catalog;

    /** 会话当前库(小写);默认 default。只影响本会话(FR-403 / FR-418)。 */
    private String currentDatabase = "default";

    /** null = IDLE;非 null = ACTIVE。同一会话最多一个事务(FR-341)。 */
    private Transaction active;

    /**
     * 自动提交 DML 的隐式事务:在**读取之前**取快照,读取/写入完成后再提交(FR-343)。
     * 只在单条 DML 执行期间非 null。
     */
    private Transaction implicit;
    private boolean implicitCommitted;

    // ---- P4:SHOW / information_schema / KILL 需要的服务器侧上下文(可为空) ----
    private SessionManager sessions;
    private String user = "";
    private long sessionId;
    private int indexBackfillBatch = 1000;

    /** 最近一次索引扫描的统计(FR-512):扫了多少索引项、回表多少次。 */
    private long lastIndexEntriesScanned;
    private long lastTableLookups;

    // ---- P5:查询引擎参数(§5.4)与 SHOW WARNINGS 计数 ----
    private int queryBatchRows = 1024;
    private long sortSpillBytes = 64L * 1024 * 1024;
    private java.nio.file.Path sortTmpDir = java.nio.file.Path.of("tmp", "fe-sort");
    private boolean vectorized = true;
    private final List<String> warnings = new ArrayList<>();
    /** 当前 DML 是否维护了索引(用于把 max_txn_write_bytes 错误说明成"含索引写入",FR-493)。 */
    private boolean currentStatementWritesIndexes;

    // ---- P6:优化器开关与统计信息(§5.3) ----
    private com.wangyu.tangdb.planner.OptimizerConfig optimizer =
            com.wangyu.tangdb.planner.OptimizerConfig.defaults();
    private com.wangyu.tangdb.stats.StatsService stats;

    public Executor(KvStore kv, Catalog catalog) {
        this.kv = kv;
        this.catalog = catalog;
    }

    /**
     * 注入服务器侧上下文(SPEC-P4 FR-512 / FR-541):SHOW PROCESSLIST、KILL、回填批量。
     * 单元测试里不注入也能工作(相关命令返回空集或明确报错)。
     */
    public Executor attachServer(SessionManager sessions, String user, long sessionId,
                                 int indexBackfillBatch) {
        this.sessions = sessions;
        this.user = user == null ? "" : user;
        this.sessionId = sessionId;
        if (indexBackfillBatch > 0) {
            this.indexBackfillBatch = indexBackfillBatch;
        }
        return this;
    }

    /** 配置查询引擎(§5.4:query_batch_rows / sort_spill_bytes)。 */
    public Executor withQueryOptions(int batchRows, long spillBytes, String tmpDir) {
        if (batchRows > 0) {
            this.queryBatchRows = batchRows;
        }
        if (spillBytes > 0) {
            this.sortSpillBytes = spillBytes;
        }
        if (tmpDir != null && !tmpDir.isBlank()) {
            this.sortTmpDir = java.nio.file.Path.of(tmpDir);
        }
        return this;
    }

    /** 测试用:切换向量化 / 逐行路径(FR-576 对拍)。 */
    public Executor withVectorized(boolean enabled) {
        this.vectorized = enabled;
        return this;
    }

    /** 注入优化器配置(SPEC-P6 §5.3);未注入时用规约默认值。 */
    public Executor withOptimizer(com.wangyu.tangdb.planner.OptimizerConfig config) {
        if (config != null && !config.equals(this.optimizer)) {
            this.optimizer = config;
            this.stats = null; // 统计服务的配置跟着优化器配置走
        }
        return this;
    }

    /** 注入统计服务(常驻 FE 共享一份;单测里按需创建)。 */
    public Executor withStats(com.wangyu.tangdb.stats.StatsService service) {
        this.stats = service;
        return this;
    }

    /** 虚拟表访问器(information_schema 走统一计划器,FR-667)。 */
    private com.wangyu.tangdb.execute.VirtualTables virtualTables() {
        return new com.wangyu.tangdb.execute.VirtualTables() {
            @Override
            public java.util.List<Column> columns(String tableName) {
                MetaCommands.VTable table = meta().virtual(tableName);
                return table == null ? null : table.columns();
            }

            @Override
            public java.util.List<Object[]> rows(String tableName) {
                MetaCommands.VTable table = meta().virtual(tableName);
                return table == null ? java.util.List.of() : table.rows();
            }

            @Override
            public long estimatedRows(String tableName) {
                return MetaCommands.estimateRows(tableName);
            }
        };
    }

    /** 查询引擎参数(含优化器 / 统计 / 虚拟表)。 */
    private QueryEngine.Options queryOptions() {
        return new QueryEngine.Options(currentDatabase, queryBatchRows, sortSpillBytes, sortTmpDir,
                vectorized, optimizer, stats()).withVirtualTables(virtualTables());
    }

    /** 统计服务(懒创建:单测里 new Executor(kv, catalog) 也能用 ANALYZE)。 */
    public com.wangyu.tangdb.stats.StatsService stats() {
        if (stats == null) {
            stats = new com.wangyu.tangdb.stats.StatsService(kv, catalog, optimizer);
        }
        return stats;
    }

    /** SHOW WARNINGS 的口径(边界 63)。 */
    public int warningCount() {
        return warnings.size();
    }

    public List<String> warningMessages() {
        return List.copyOf(warnings);
    }

    public TxnState txnState() {
        return active == null ? TxnState.IDLE : TxnState.ACTIVE;
    }

    public boolean inTransaction() {
        return active != null;
    }

    /** 会话当前库(小写)。 */
    public String currentDatabase() {
        return currentDatabase;
    }

    /** 切换会话当前库(FR-418)。库不存在抛 1049。 */
    public void useDatabase(String database) {
        String name = database == null ? null : database.trim().toLowerCase();
        catalog.requireDatabase(name);
        this.currentDatabase = name;
    }

    /**
     * 把限定名解析成实际库名:未限定 = 当前库。
     *
     * <p>无论是否限定都校验库存在(1049):会话当前库可能被别人 DROP 掉(边界 4),
     * 此时下一条带库语义的语句应报"库不存在",而不是"表不存在"。</p>
     */
    private String resolveDatabase(String database) {
        String db = database == null ? currentDatabase : database.toLowerCase();
        catalog.requireDatabase(db);
        return db;
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
        if (statement instanceof Ast.CreateDatabase createDb) {
            requireNoTransaction("CREATE DATABASE");
            catalog.createDatabase(createDb.name());
            return new Result.Message("OK, 库 " + createDb.name().trim().toLowerCase() + " 已创建");
        }
        if (statement instanceof Ast.DropDatabase dropDb) {
            requireNoTransaction("DROP DATABASE");
            catalog.dropDatabase(dropDb.name());
            return new Result.Message("OK, 库 " + dropDb.name().trim().toLowerCase() + " 已删除");
        }
        if (statement instanceof Ast.Use use) {
            useDatabase(use.name());
            return new Result.Message("OK, 已切换库 " + currentDatabase);
        }

        if (statement instanceof Ast.CreateTable create) {
            requireNoTransaction("CREATE TABLE");
            return createTable(create);
        }
        if (statement instanceof Ast.CreateIndex create) {
            requireNoTransaction("CREATE INDEX");
            return createIndex(create);
        }
        if (statement instanceof Ast.DropIndex drop) {
            requireNoTransaction("DROP INDEX");
            String db = resolveDatabase(drop.database());
            IndexMeta index = catalog.dropIndex(db, drop.table(), drop.name());
            return new Result.Message("OK, 索引 " + index.name() + " 已删除(表 "
                    + drop.table().toLowerCase() + ")");
        }
        if (statement instanceof Ast.DropTable drop) {
            requireNoTransaction("DROP TABLE");
            String db = resolveDatabase(drop.database());
            catalog.dropTable(db, drop.table());
            return new Result.Message("OK, 表 " + drop.table().toLowerCase() + " 已删除");
        }
        if (statement instanceof Ast.TruncateTable truncate) {
            requireNoTransaction("TRUNCATE TABLE");
            String db = resolveDatabase(truncate.database());
            TableMeta table = catalog.truncateTable(db, truncate.table());
            return new Result.Message("OK, 表 " + table.name() + " 已清空(新表 ID " + table.id() + ")");
        }
        if (statement instanceof Ast.ShowTables show) {
            return meta().showTables(show);
        }
        if (statement instanceof Ast.ShowDatabases) {
            return showDatabases();
        }
        if (statement instanceof Ast.ShowIndexes show) {
            return meta().showIndexes(show);
        }
        if (statement instanceof Ast.ShowIndexDetails show) {
            return meta().showIndexDetails(show);
        }
        if (statement instanceof Ast.ShowSimple show) {
            return meta().showSimple(show);
        }
        if (statement instanceof Ast.ShowTableStatus show) {
            return meta().showTableStatus(show);
        }
        if (statement instanceof Ast.ShowSegments show) {
            return showSegments(show);
        }
        if (statement instanceof Ast.ShowImportTasks) {
            return showImportTasks();
        }
        if (statement instanceof Ast.Analyze analyze) {
            return analyze(analyze);
        }
        if (statement instanceof Ast.ShowStats showStats) {
            return showStats(showStats);
        }
        if (statement instanceof Ast.ShowCreateTable show) {
            return meta().showCreateTable(show);
        }
        if (statement instanceof Ast.ShowCreateDatabase show) {
            return meta().showCreateDatabase(show);
        }
        if (statement instanceof Ast.ShowColumns show) {
            return meta().showColumns(show);
        }
        if (statement instanceof Ast.Explain explain) {
            return explain(explain);
        }
        if (statement instanceof Ast.Kill kill) {
            return kill(kill);
        }
        if (statement instanceof Ast.Describe describe) {
            return describe(describe);
        }
        if (statement instanceof Ast.Insert insert) {
            return insert(insert);
        }
        if (statement instanceof Ast.Update update) {
            return runDml(() -> update(update));
        }
        if (statement instanceof Ast.Delete delete) {
            return runDml(() -> delete(delete));
        }
        if (statement instanceof Ast.SelectQuery query) {
            return selectQuery(query);
        }
        if (statement instanceof Ast.SetQuery setQuery) {
            return selectSet(setQuery);
        }
        if (statement instanceof Ast.Select select) {
            return select(select);
        }
        if (statement instanceof Ast.SelectLiterals literals) {
            return selectLiterals(literals);
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

    /**
     * 把 BE 回的冲突用户键翻译成用户可读错误(FR-333 / FR-359 / K78 / K80):
     * 唯一索引冲突统一报 1062(并发触发时不带已有主键,只说"并发写入冲突")。
     */
    private StoreException translateConflict(ConflictException e) {
        byte[] key = e.conflictKey();
        if (key == null) {
            return new StoreException("写写冲突(该行在本事务开始后被其它事务修改,请重试)");
        }
        long tableId = KeyCodec.tableId(key);
        int indexId = KeyCodec.indexId(key);
        TableMeta table = catalog.findById(tableId);
        if (table != null && indexId != 0) {
            IndexMeta index = catalog.findIndex(tableId, indexId);
            if (index != null && index.unique()) {
                String valueText = "?";
                try {
                    Column column = table.column(index.columnName());
                    if (column != null) {
                        valueText = displayLiteral(IndexCodec.decodeValue(column.type(), key, 8).value());
                    }
                } catch (RuntimeException ignored) {
                    // 解码失败也不把内部 key 十六进制打给用户。
                }
                return new StoreException("唯一索引 " + index.name() + " 冲突(表 " + table.name()
                        + ",列 " + index.columnName() + " = " + valueText
                        + ",并发写入冲突)", SqlError.ER_DUP_ENTRY);
            }
        }
        if (table != null && indexId == 0) {
            try {
                Object pkValue = KeyCodec.decodePrimaryKey(table, key);
                return new StoreException("表 " + table.name() + " 主键 " + Values.display(pkValue)
                        + " 写写冲突(该行在本事务开始后被其它事务修改,请重试)",
                        SqlError.ER_LOCK_DEADLOCK);
            } catch (RuntimeException ignored) {
                // 解码失败就退回到不含主键的文案,绝不把内部 key 十六进制打给用户。
            }
        }
        return new StoreException("表 ID " + tableId
                + " 的某一行写写冲突(该行在本事务开始后被其它事务修改,请重试)",
                SqlError.ER_LOCK_DEADLOCK);
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
        List<Catalog.IndexSpec> specs = new ArrayList<>(create.inlineIndexes().size());
        for (Ast.InlineIndex inline : create.inlineIndexes()) {
            specs.add(new Catalog.IndexSpec(inline.name(), inline.column(), inline.unique()));
        }
        String db = resolveDatabase(create.database());
        TableMeta table = catalog.createTable(db, create.table(), columns, specs);
        StringBuilder text = new StringBuilder("OK, 表 " + table.name() + " 已创建(表 ID " + table.id() + ")");
        List<IndexMeta> indexes = catalog.indexes(table.id());
        if (!indexes.isEmpty()) {
            text.append(";内联索引 ");
            for (int i = 0; i < indexes.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(indexes.get(i).name());
            }
        }
        return new Result.Message(text.toString());
    }

    /** 兼容方法:MetaCommands 需要当前执行器来取会话当前库。 */
    private MetaCommands meta() {
        return new MetaCommands(catalog, this, sessions, user);
    }

    /** SHOW DATABASES:列出全部库(含 default),由 FE 内存直接回答(FR-201 / FR-422)。 */
    private Result showDatabases() {
        List<String> dbs = catalog.databases();
        List<Object[]> rows = new ArrayList<>(dbs.size());
        for (String db : dbs) {
            rows.add(new Object[]{db});
        }
        return new Result.Table(List.of("Database"), rows);
    }

    private Result describe(Ast.Describe describe) {
        TableMeta table = requireTable(describe.database(), describe.table());
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

    /** 解析表名:限定名取指定库,未限定取当前库;不存在抛 1146。 */
    private TableMeta requireTable(String database, String table) {
        return catalog.require(resolveDatabase(database), table);
    }

    // ------------------------------------------------------------ 索引 DDL 与回填

    /**
     * CREATE [UNIQUE] INDEX(FR-456 ~ FR-470):
     * 空表直接 ACTIVE;非空表先落 BUILDING 元数据,再分批回填,最后单独写 ACTIVE。
     */
    private Result createIndex(Ast.CreateIndex create) {
        String db = resolveDatabase(create.database());
        TableMeta table = catalog.require(db, create.table());
        IndexMeta index = catalog.createIndex(db, create.table(), create.name(),
                create.column(), create.unique());
        boolean resumed = false;
        if (index.state() == IndexMeta.STATE_BUILDING) {
            resumed = index.buildCursor() != null && !index.buildCursor().isBlank();
            try {
                index = backfill(index, table);
            } catch (RuntimeException e) {
                // FR-469:唯一冲突等"定义级失败"→ 索引转 DROPPED,不留半成品;
                // 但进程崩溃 / BE 不可用属于"回填中断"(FR-469 前半段),必须保持 BUILDING 以便续做。
                if (e instanceof StoreException se && se.code() == SqlError.ER_DUP_ENTRY
                        || e instanceof CatalogException) {
                    try {
                        catalog.updateIndexState(index, IndexMeta.STATE_DROPPED);
                    } catch (RuntimeException ignored) {
                        // 状态写入失败不掩盖原始错误。
                    }
                }
                throw e;
            }
        }
        return new Result.Message("OK, " + (index.unique() ? "唯一索引 " : "索引 ") + index.name()
                + " 已创建(索引 ID " + index.indexId() + ",状态 " + index.stateName()
                + (resumed ? ",从游标续做" : "") + ")");
    }

    /**
     * 分批回填(FR-467 / FR-468 / K84 / K85):默认 1000 行/批,K89 保证回填期间的新写入
     * 也进索引,因此转 ACTIVE 前不需要增量补扫。
     */
    private IndexMeta backfill(IndexMeta index, TableMeta table) {
        Column indexColumn = IndexCodec.indexColumn(table, index);
        Column pk = table.primaryKey();
        IndexMeta current = index;
        byte[] start = current.buildCursor().isBlank()
                ? KeyCodec.tableRangeStart(table.id())
                : KeyCodec.concat(KeyCodec.tableRangeStart(table.id()),
                        hexToBytes(current.buildCursor()), KeyCodec.afterAllVersions());

        int batch = Math.max(1, indexBackfillBatch);
        while (true) {
            List<KeyValue> rows = kv.scan(start, KeyCodec.tableRangeEnd(table.id()), batch);
            if (rows.isEmpty()) {
                break;
            }
            TreeMap<byte[], byte[]> staged = new TreeMap<>(Arrays::compareUnsigned);
            byte[] lastPkEncoded = null;
            for (KeyValue row : rows) {
                if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                    continue; // 索引项不是回填输入
                }
                RowCodec.Decoded decoded = RowCodec.decode(table, row.value());
                byte[] pkEncoded = Arrays.copyOfRange(row.key(), 8, row.key().length);
                lastPkEncoded = pkEncoded;
                if (decoded.tombstone()) {
                    continue;
                }
                Object[] values = decoded.values();
                Object columnValue = values[indexColumn.ordinal()];
                if (columnValue == null) {
                    continue; // K79
                }
                Object pkValue = values[pk.ordinal()];
                byte[] indexKey = IndexCodec.entryKey(table, index, columnValue, pkValue);
                if (index.unique()) {
                    byte[] previous = staged.containsKey(indexKey) ? staged.get(indexKey) : kv.get(indexKey);
                    if (previous != null && !IndexCodec.isTombstone(table, previous)) {
                        Object previousPk = KeyCodec.decodePrimaryKeyValue(pk.type(), previous);
                        if (!Objects.equals(previousPk, pkValue)) {
                            throw new StoreException("唯一索引 " + index.name() + " 回填失败:列 "
                                    + indexColumn.name() + " 的值 " + displayLiteral(columnValue)
                                    + " 重复(主键 " + Values.display(previousPk) + " 与 "
                                    + Values.display(pkValue) + ")", SqlError.ER_DUP_ENTRY);
                        }
                        continue; // 崩溃后重扫到已回填的同一行 → 幂等跳过
                    }
                    staged.put(indexKey, IndexCodec.entryValue(table, index, pkValue));
                } else {
                    staged.put(indexKey, EMPTY);
                }
            }
            List<KeyValue> writes = new ArrayList<>(staged.size());
            for (Map.Entry<byte[], byte[]> entry : staged.entrySet()) {
                writes.add(new KeyValue(entry.getKey(), entry.getValue()));
            }
            if (lastPkEncoded == null) {
                break; // 只有索引项/无主键行(理论上不会发生)
            }
            String cursorHex = toHex(lastPkEncoded);
            current = catalog.advanceBackfill(current, cursorHex, writes);
            start = KeyCodec.concat(KeyCodec.tableRangeStart(table.id()), lastPkEncoded,
                    KeyCodec.afterAllVersions());
        }
        // FR-470:转 ACTIVE 的记录最后写,ACTIVE 是不可逆的对外承诺。
        return catalog.updateIndexState(current, IndexMeta.STATE_ACTIVE);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String s = hex.trim();
        if ((s.length() & 1) != 0) {
            throw new CatalogException("回填游标不是合法十六进制: " + hex);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new CatalogException("回填游标不是合法十六进制: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ------------------------------------------------------------ SHOW SEGMENTS / DATA_LENGTH

    /** 一个列(键列 / 值列)的列式压缩统计。 */
    public record ColumnSize(String column, long rows, int chunks,
                             long rawBytes, long compressedBytes,
                             Map<String, Integer> encodings) {
    }

    /** 一张表的列式压缩统计(口径与 SHOW SEGMENTS / DATA_LENGTH 一致)。 */
    public record TableSize(long rows, List<ColumnSize> columns) {
        public long compressedBytes() {
            long n = 0;
            for (ColumnSize c : columns) {
                n += c.compressedBytes();
            }
            return n;
        }

        public long rawBytes() {
            long n = 0;
            for (ColumnSize c : columns) {
                n += c.rawBytes();
            }
            return n;
        }

        public int chunks() {
            return columns.isEmpty() ? 0 : columns.get(0).chunks();
        }
    }

    private record CachedSize(long atMillis, TableSize size) {
    }

    private final Map<Long, CachedSize> sizeCache = new HashMap<>();
    private static final long SIZE_CACHE_MILLIS = 2000L;

    /**
     * 计算一张表的列式字节统计(SPEC-P5 FR-566 / FR-567)。
     *
     * <p>BE 只暴露 KV,不暴露 segment 文件布局;FE 也不能读 BE 数据目录(边界铁律)。
     * 因此这里按 **BE 完全相同的一套编码算法**({@link ColumnarCodec},chunk = 4096 行)
     * 对同一份逻辑 key/value 字节做统计。真 per-file 统计需要新增 gRPC RPC,已在验收报告登记。</p>
     */
    public TableSize tableSize(TableMeta table) {
        long now = System.currentTimeMillis();
        CachedSize cached = sizeCache.get(table.id());
        if (cached != null && now - cached.atMillis() < SIZE_CACHE_MILLIS) {
            return cached.size();
        }

        ReadView view = currentView(null);
        List<KeyValue> kvs = view.scan(KeyCodec.tableRangeStart(table.id()),
                KeyCodec.tableRangeEnd(table.id()), 0);
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        for (KeyValue row : kvs) {
            if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                continue;
            }
            byte[] raw = row.value();
            if (raw != null && raw.length > 0 && raw[0] == RowCodec.FORMAT_TOMBSTONE) {
                continue;
            }
            keys.add(row.key());
            values.add(raw == null ? EMPTY : raw);
        }

        List<ColumnSize> columns = new ArrayList<>(2);
        columns.add(columnSize("key", keys, true));
        columns.add(columnSize("value", values, false));
        TableSize size = new TableSize(keys.size(), List.copyOf(columns));
        sizeCache.put(table.id(), new CachedSize(now, size));
        return size;
    }

    private static ColumnSize columnSize(String name, List<byte[]> values, boolean keyColumn) {
        long raw = 0;
        long comp = 0;
        Map<String, Integer> encodings = new LinkedHashMap<>();
        int chunks = 0;
        for (int from = 0; from < values.size(); from += ColumnarCodec.CHUNK_ROWS) {
            int to = Math.min(values.size(), from + ColumnarCodec.CHUNK_ROWS);
            ColumnarCodec.Encoded encoded = ColumnarCodec.chooseBest(values.subList(from, to));
            raw += encoded.rawBytes();
            comp += encoded.compressedBytes();
            encodings.merge(encoded.encoding().name(), 1, Integer::sum);
            chunks++;
        }
        // BE 的完整 key = 用户键 + 8 字节版本段;版本段近似不可压缩,按原字节计入。
        if (keyColumn) {
            long versionBytes = (long) values.size() * KeyCodec.VERSION_BYTES;
            raw += versionBytes;
            comp += versionBytes;
        }
        return new ColumnSize(name, values.size(), chunks, raw, comp, encodings);
    }

    /** SHOW SEGMENTS(FR-566):每张表 × 每列一行,数字与 DATA_LENGTH 同口径。 */
    private Result showSegments(Ast.ShowSegments show) {
        List<TableMeta> tables = new ArrayList<>();
        if (show.table() != null) {
            tables.add(requireTable(show.database(), show.table()));
        } else {
            tables.addAll(catalog.tables(resolveDatabase(show.database())));
        }
        List<Object[]> rows = new ArrayList<>();
        for (TableMeta table : tables) {
            TableSize size = tableSize(table);
            for (ColumnSize column : size.columns()) {
                double ratio = column.compressedBytes() == 0
                        ? 0.0 : (double) column.rawBytes() / column.compressedBytes();
                rows.add(new Object[]{
                        "table:" + table.id() + ":" + table.name(),
                        column.column(),
                        column.rows(),
                        column.chunks(),
                        column.rawBytes(),
                        column.compressedBytes(),
                        String.format(java.util.Locale.ROOT, "%.2f", ratio),
                        encodingText(column.encodings())
                });
            }
        }
        return new Result.Table(List.of("segment", "column", "rows", "chunks",
                "raw_bytes", "compressed_bytes", "ratio", "encodings"), rows);
    }

    /** SHOW IMPORT TASKS(FR-614):task_id / status / progress / error_summary。 */
    private Result showImportTasks() {
        List<Object[]> rows = new ArrayList<>();
        for (com.wangyu.tangdb.catalog.ImportState.Task task
                : com.wangyu.tangdb.catalog.ImportState.load(kv)) {
            rows.add(new Object[]{task.importId(), task.tableName(), task.status(),
                    task.progress(), task.errorSummary(), task.updatedAt()});
        }
        return new Result.Table(List.of("task_id", "table_name", "status", "progress",
                "error_summary", "updated_at"), rows);
    }

    // ------------------------------------------------------------ ANALYZE / SHOW STATS(SPEC-P6)

    /**
     * {@code ANALYZE TABLE [db.]t [(c1,...)]}(FR-615 ~ FR-621)。
     *
     * <p>显式事务里执行 → 1235(FR-624);扫描走统一 {@link ReadView},统计写入是
     * 一个批记录(FR-621)。表不存在 1146、列不存在 1054。</p>
     */
    private Result analyze(Ast.Analyze analyze) {
        if (active != null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ANALYZE 不能在事务中执行,请先 COMMIT 或 ROLLBACK");
        }
        String db = resolveDatabase(analyze.database());
        TableMeta table = catalog.require(db, analyze.table());
        List<Column> targets = new ArrayList<>();
        if (analyze.columns().isEmpty()) {
            targets.addAll(table.columns());
        } else {
            Set<String> seen = new HashSet<>();
            for (String name : analyze.columns()) {
                Column column = table.column(name);
                if (column == null) {
                    throw new CatalogException("列 " + name + " 不存在(表 " + table.name() + ")",
                            SqlError.ER_BAD_FIELD_ERROR);
                }
                if (seen.add(column.name())) {
                    targets.add(column);
                }
            }
        }

        long snapshot = 0;
        TransactionStore.TxnHandle handle = null;
        ReadView view;
        if (kv instanceof TransactionStore store) {
            handle = store.beginTxn();
            snapshot = handle.snapshot();
            view = ReadViews.at(kv, snapshot);
        } else {
            view = ReadViews.latest(kv);
        }
        com.wangyu.tangdb.stats.StatsService.AnalyzeResult result;
        try {
            result = stats().analyze(table, targets, view, snapshot);
        } finally {
            if (handle != null) {
                try {
                    ((TransactionStore) kv).abort(handle.txnId());
                } catch (RuntimeException ignored) {
                    // 句柄过期无害,不覆盖 ANALYZE 的真实结果/异常。
                }
            }
        }
        StringBuilder text = new StringBuilder("OK, 表 ").append(table.name())
                .append(" 统计已更新(行数 ").append(result.table().rowCount())
                .append(",列 ").append(result.columns().size())
                .append(",扫描 ").append(result.scannedRows()).append(" 行")
                .append(",耗时 ").append(result.durationMillis()).append(" ms");
        if (result.sampled()) {
            text.append(",抽样率 ").append(result.table().samplePermille()).append("‰");
        }
        return new Result.Message(text.append(')').toString());
    }

    /** {@code SHOW STATS [FROM [db.]t]}(FR-622 / FR-672 / FR-675)。 */
    private Result showStats(Ast.ShowStats show) {
        String db = resolveDatabase(show.database());
        TableMeta table = show.table() == null ? null : catalog.require(db, show.table());
        List<Object[]> rows = stats().showStats(table, db);
        return new Result.Table(com.wangyu.tangdb.stats.StatsService.showStatsColumns(), rows);
    }

    private static String encodingText(Map<String, Integer> encodings) {
        if (encodings.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : encodings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ EXPLAIN

    /** EXPLAIN 与实际执行共用 {@link #planFor},不另写一套推算逻辑(FR-517)。 */
    private Result explain(Ast.Explain explain) {
        Ast.Statement inner = explain.statement();
        TableMeta table;
        Plan plan;
        Ast.Condition where;
        if (inner instanceof Ast.SetQuery setQuery) {
            QueryEngine.Options options = new QueryEngine.Options(currentDatabase,
                    queryBatchRows, sortSpillBytes, sortTmpDir, vectorized, optimizer, stats());
            QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                    message -> warnings.add(message));
            List<Object[]> planRows = new ArrayList<>();
            for (String line : engine.explainSet(setQuery.expr())) {
                planRows.add(new String[]{line});
            }
            return new Result.Table(List.of("plan"), planRows);
        }
        if (inner instanceof Ast.SelectQuery query) {
            QueryEngine.Options options = queryOptions();
            QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                    message -> warnings.add(message));
            List<Object[]> planRows = new ArrayList<>();
            for (String line : engine.explain(query.body())) {
                planRows.add(new String[]{line});
            }
            return new Result.Table(List.of("plan"), planRows);
        }
        if (inner instanceof Ast.Select select) {
            table = requireTable(select.database(), select.table());
            where = select.where();
        } else if (inner instanceof Ast.Update update) {
            table = requireTable(update.database(), update.table());
            where = update.where();
        } else if (inner instanceof Ast.Delete delete) {
            table = requireTable(delete.database(), delete.table());
            where = delete.where();
        } else {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "不支持解释该语句(EXPLAIN 只支持 SELECT / UPDATE / DELETE)");
        }
        Planner.Choice choice = planChoice(table, where);
        plan = choice.plan();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new String[]{"访问路径: " + explainLabel(plan)});
        rows.add(new String[]{"表: " + table.name()});
        if (plan.index() != null) {
            IndexMeta index = plan.index();
            rows.add(new String[]{"索引: " + index.name() + "(列 " + index.columnName()
                    + (index.unique() ? ",唯一" : "") + ",索引 ID " + index.indexId() + ")"});
        }
        rows.add(new String[]{"key 范围: " + explainRange(plan)});
        rows.add(new String[]{"残余过滤: " + (where == null ? "无" : formatCondition(where))
                + "(由 FE 按谓词执行)"});
        rows.add(new String[]{"扫描方式: " + describeScan(plan)});
        // ---- SPEC-P6 D 组:下推 / 列裁剪 / LIMIT 下推(只追加,不改上面 P4 的既有行) ----
        if (where != null) {
            rows.add(new String[]{"已下推: " + formatCondition(where)
                    + "(单表谓词在扫描时执行,残余仍由 FE 校验)"});
        }
        int[] columnCounts = legacyColumnCounts(table, inner instanceof Ast.Select sel ? sel : null);
        if (columnCounts != null) {
            rows.add(new String[]{"列: 读取 " + columnCounts[0] + " / 表 " + table.columnCount()
                    + " 列"});
        }
        if (inner instanceof Ast.Select sel) {
            List<String> read = legacyColumnNames(table, sel);
            if (!read.isEmpty()) {
                rows.add(new String[]{"实际读取列: " + String.join(", ", read)});
            }
        }
        if (inner instanceof Ast.Select sel && sel.limit() != null && sel.where() == null) {
            rows.add(new String[]{"LIMIT 下推: 扫描 limit="
                    + (sel.limit() + (sel.offset() == null ? 0 : sel.offset()))});
        }
        // ---- SPEC-P6 §5.4:追加"候选 / 估算 / 依据"三类行(不改上面 P4 的既有行) ----
        for (Planner.Candidate candidate : choice.candidates()) {
            rows.add(new String[]{"候选: " + candidate.label() + " rows=" + candidate.rows()
                    + ", cost=" + formatCost(candidate.cost())});
        }
        Planner.Candidate chosen = choice.chosen();
        rows.add(new String[]{"估算: rows=" + chosen.rows() + ", cost="
                + formatCost(chosen.cost())});
        rows.add(new String[]{"依据: " + evidenceText(choice)});
        return new Result.Table(List.of("plan"), rows);
    }

    /** 旧路径的"读取列数":投影 + WHERE 引用到的列(去重)。 */
    private static int[] legacyColumnCounts(TableMeta table, Ast.Select select) {
        if (select == null || select.countStar()) {
            return null;
        }
        Set<Integer> indexes = new LinkedHashSet<>();
        try {
            Planner.Projection projection = Planner.projection(table, select);
            for (int index : projection.indexes()) {
                indexes.add(index);
            }
        } catch (RuntimeException e) {
            return null;
        }
        collectConditionColumns(table, select.where(), indexes);
        return new int[]{indexes.size()};
    }

    /** 旧路径的"实际读取列"名称(投影 + WHERE 列,去重保序)。 */
    private static List<String> legacyColumnNames(TableMeta table, Ast.Select select) {
        Set<String> names = new LinkedHashSet<>();
        if (select.star()) {
            for (Column column : table.columns()) {
                names.add(column.name());
            }
        } else if (!select.countStar()) {
            for (Ast.Item item : select.items()) {
                Column column = table.column(item.column());
                if (column != null) {
                    names.add(column.name());
                }
            }
        }
        collectConditionColumnNames(table, select.where(), names);
        return new ArrayList<>(names);
    }

    private static void collectConditionColumnNames(TableMeta table, Ast.Condition condition,
                                                    Set<String> out) {
        if (condition == null) {
            return;
        }
        if (condition instanceof Ast.And and) {
            collectConditionColumnNames(table, and.left(), out);
            collectConditionColumnNames(table, and.right(), out);
            return;
        }
        if (condition instanceof Ast.Or or) {
            collectConditionColumnNames(table, or.left(), out);
            collectConditionColumnNames(table, or.right(), out);
            return;
        }
        String columnName = condition instanceof Ast.Compare cmp ? cmp.column()
                : condition instanceof Ast.In in ? in.column() : null;
        if (columnName != null) {
            Column column = table.column(columnName);
            if (column != null) {
                out.add(column.name());
            }
        }
    }

    private static void collectConditionColumns(TableMeta table, Ast.Condition condition,
                                                Set<Integer> out) {
        if (condition == null) {
            return;
        }
        if (condition instanceof Ast.And and) {
            collectConditionColumns(table, and.left(), out);
            collectConditionColumns(table, and.right(), out);
            return;
        }
        if (condition instanceof Ast.Or or) {
            collectConditionColumns(table, or.left(), out);
            collectConditionColumns(table, or.right(), out);
            return;
        }
        String columnName = condition instanceof Ast.Compare cmp ? cmp.column()
                : condition instanceof Ast.In in ? in.column() : null;
        if (columnName != null) {
            Column column = table.column(columnName);
            if (column != null) {
                out.add(column.ordinal());
            }
        }
    }

    static String formatCost(double cost) {
        if (Double.isInfinite(cost)) {
            return "INF";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", cost);
    }

    static String evidenceText(Planner.Choice choice) {
        return switch (choice.evidence()) {
            case STATS -> "统计(t=" + choice.analyzedAt() + ")";
            case STALE -> "统计已过期(t=" + choice.analyzedAt() + "),回落默认";
            case DEFAULT -> "默认(未 ANALYZE)";
        };
    }

    private static String explainLabel(Plan plan) {
        return switch (plan.kind()) {
            case POINT, POINTS -> "PRIMARY POINT";
            case RANGE -> plan.fullScan() ? "TABLE SCAN" : "PRIMARY RANGE";
            case INDEX_POINT -> "INDEX POINT";
            case INDEX_RANGE -> "INDEX RANGE";
        };
    }

    private static String explainRange(Plan plan) {
        switch (plan.kind()) {
            case POINT, POINTS -> {
                StringBuilder sb = new StringBuilder();
                List<byte[]> keys = plan.pointKeys();
                for (int i = 0; i < keys.size() && i < 4; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(IndexCodec.hex(keys.get(i), 24));
                }
                if (keys.size() > 4) {
                    sb.append(" …共 ").append(keys.size()).append(" 个点");
                }
                return sb.toString();
            }
            case RANGE, INDEX_POINT, INDEX_RANGE -> {
                return "[" + IndexCodec.hex(plan.start(), 24) + ", "
                        + IndexCodec.hex(plan.end(), 24) + ")";
            }
        }
        return "";
    }

    private static String describeScan(Plan plan) {
        return switch (plan.kind()) {
            case POINT, POINTS -> "主键点查(Get)";
            case RANGE -> plan.fullScan() ? "全表有序扫描(Scan)" : "主键范围扫描(Scan)";
            case INDEX_POINT, INDEX_RANGE -> "索引区间扫描 + 逐行回表(二次校验)";
        };
    }

    private static String formatCondition(Ast.Condition condition) {
        if (condition instanceof Ast.Compare cmp) {
            return cmp.column() + " " + cmp.op().text() + " " + displayLiteral(cmp.value());
        }
        if (condition instanceof Ast.In in) {
            StringBuilder sb = new StringBuilder(in.column()).append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(displayLiteral(in.values().get(i)));
            }
            return sb.append(')').toString();
        }
        if (condition instanceof Ast.And and) {
            return "(" + formatCondition(and.left()) + " AND " + formatCondition(and.right()) + ")";
        }
        if (condition instanceof Ast.Or or) {
            return "(" + formatCondition(or.left()) + " OR " + formatCondition(or.right()) + ")";
        }
        return "?";
    }

    private static String displayLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        return Values.display(value);
    }

    // ------------------------------------------------------------ KILL

    /** KILL [CONNECTION] <id>(FR-541 / K97):回滚目标会话未提交事务并注销,且落日志。 */
    private Result kill(Ast.Kill kill) {
        if (sessions == null) {
            throw new SqlError(SqlError.ER_NO_SUCH_THREAD, "HY000",
                    "会话 id " + kill.id() + " 不存在");
        }
        Session target = sessions.get(kill.id());
        if (target == null) {
            throw new SqlError(SqlError.ER_NO_SUCH_THREAD, "HY000",
                    "会话 id " + kill.id() + " 不存在");
        }
        LOG.info("KILL 会话: killer_session=" + sessionId + " target_session=" + kill.id());
        target.kill();
        target.executor().rollbackOnDisconnect();
        sessions.remove(kill.id());
        return new Result.Message("OK, 会话 " + kill.id() + " 已断开");
    }

    // ------------------------------------------------------------ DML

    /**
     * CSV 导入专用批量插入(K110):跳过"主键已存在"的逐行检查,整批写入。
     *
     * <p>语义等价于 INSERT OR IGNORE:同一主键重复写入相同内容幂等,不会产生重复行。
     * 唯一索引仍然逐行查重(约束不能因为导入而放松)。</p>
     */
    public Result importInsert(Ast.Insert insert) {
        return runDml(() -> insertInternal(insert, true));
    }

    private Result insert(Ast.Insert insert) {
        return runDml(() -> insertInternal(insert, false));
    }

    private Result insertInternal(Ast.Insert insert, boolean bulkImport) {
        TableMeta table = requireTable(insert.database(), insert.table());
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
                    throw new CatalogException("列 " + columnName + " 不存在(表 " + table.name() + ")",
                            SqlError.ER_BAD_FIELD_ERROR);
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
        List<IndexMeta> indexes = writableIndexes(table);
        currentStatementWritesIndexes = !indexes.isEmpty();

        int written = 0;
        for (int k = 0; k < insert.rows().size(); k++) {
            List<Object> literals = insert.rows().get(k);
            if (literals.size() != targets.size()) {
                throw new StoreException("第 " + (k + 1) + " 组取值个数(" + literals.size()
                        + ")与列数(" + targets.size() + ")不一致");
            }
            Object[] values = new Object[table.columnCount()];
            for (int j = 0; j < targets.size(); j++) {
                try {
                    values[targets.get(j).ordinal()] = Values.coerce(literals.get(j), targets.get(j));
                } catch (StoreException e) {
                    throw new StoreException("第 " + (k + 1) + " 组(列 "
                            + targets.get(j).name() + "): " + e.getMessage(), e.code(), e);
                }
            }
            Object pkValue = values[pk.ordinal()];
            if (pkValue == null) {
                throw new StoreException("主键列 " + pk.name() + " 不能为 NULL");
            }
            byte[] key = KeyCodec.rowKey(table.id(), pk.type(), pkValue);
            if (!bulkImport) {
                byte[] existing = view.get(key);
                if (existing != null && !isTombstone(table, existing)) {
                    throw new StoreException("第 " + (k + 1) + " 组主键冲突(表 " + table.name()
                            + ",主键 " + Values.display(pkValue) + ")", SqlError.ER_DUP_ENTRY);
                }
            }
            // 索引项与行记录进同一个写集(最终同一条批记录,FR-483 / K89)。
            stageInsertIndexes(table, indexes, values, pkValue, view, local);
            local.put(key, RowCodec.encode(table, values));
            written++;
        }
        flushWrites(local);
        if (!bulkImport && active == null) {
            stats().recordRowDelta(table.id(), written); // FR-623:分析后行数变化用于 stale 判定
        }
        return affected(written);
    }

    /** 插入一行时维护所有 ACTIVE / BUILDING 索引;唯一索引先查重(FR-484 / K80)。 */
    private void stageInsertIndexes(TableMeta table, List<IndexMeta> indexes, Object[] values,
                                    Object pkValue, ReadView view, WriteSet local) {
        Column pk = table.primaryKey();
        for (IndexMeta index : indexes) {
            Column column = IndexCodec.indexColumn(table, index);
            Object columnValue = values[column.ordinal()];
            if (columnValue == null) {
                continue; // K79:NULL 行不入索引
            }
            byte[] indexKey = IndexCodec.entryKey(table, index, columnValue, pkValue);
            if (index.unique()) {
                checkUniqueFree(table, index, column, columnValue, indexKey, view, local);
                local.put(indexKey, IndexCodec.entryValue(table, index, pkValue));
            } else {
                local.put(indexKey, EMPTY);
            }
        }
        if (pk == null) {
            throw new StoreException("表 " + table.name() + " 没有主键");
        }
    }

    /** 唯一冲突即拒(1062),信息带索引名 + 列名 + 值 + 已有主键(K80)。 */
    private void checkUniqueFree(TableMeta table, IndexMeta index, Column column, Object columnValue,
                                 byte[] indexKey, ReadView view, WriteSet local) {
        byte[] current = local.get(indexKey);
        if (current == null) {
            current = view.get(indexKey);
        }
        if (current == null || IndexCodec.isTombstone(table, current)) {
            return;
        }
        Column pk = table.primaryKey();
        Object existingPk = KeyCodec.decodePrimaryKeyValue(pk.type(), current);
        throw new StoreException("唯一索引 " + index.name() + " 冲突(表 " + table.name()
                + ",列 " + column.name() + " = " + displayLiteral(columnValue)
                + ",已有主键 " + Values.display(existingPk) + ")", SqlError.ER_DUP_ENTRY);
    }

    private Result update(Ast.Update update) {
        if (update.limit() != null) {
            throw MetaCommands.notSupported("UPDATE ... LIMIT 不支持(不做半实现;请用 WHERE 精确限定)");
        }
        TableMeta table = requireTable(update.database(), update.table());

        int[] targetIndexes = new int[update.assignments().size()];
        Object[] targetValues = new Object[update.assignments().size()];
        for (int i = 0; i < update.assignments().size(); i++) {
            Ast.Assignment assignment = update.assignments().get(i);
            Column column = table.column(assignment.column());
            if (column == null) {
                throw new CatalogException("列 " + assignment.column() + " 不存在(表 " + table.name() + ")",
                        SqlError.ER_BAD_FIELD_ERROR);
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
        List<IndexMeta> indexes = writableIndexes(table);
        currentStatementWritesIndexes = !indexes.isEmpty();
        int[] count = {0};
        forEachMatch(planFor(table, update.where()), table, view, (key, values) -> {
            Object[] updated = values.clone();
            for (int i = 0; i < targetIndexes.length; i++) {
                updated[targetIndexes[i]] = targetValues[i];
            }
            stageUpdateIndexes(table, indexes, values, updated, view, local);
            local.put(key, RowCodec.encode(table, updated));
            count[0]++;
            return true;
        });
        flushWrites(local);
        return affected(count[0]);
    }

    /**
     * UPDATE 的索引维护(FR-485 ~ FR-487):只处理值真的变了的索引列;
     * 旧项写墓碑、新项写入,与主键更新同批。未改索引列不产生任何索引写入(FR-486)。
     */
    private void stageUpdateIndexes(TableMeta table, List<IndexMeta> indexes,
                                    Object[] oldValues, Object[] newValues,
                                    ReadView view, WriteSet local) {
        Column pk = table.primaryKey();
        Object pkValue = newValues[pk.ordinal()];
        for (IndexMeta index : indexes) {
            Column column = IndexCodec.indexColumn(table, index);
            Object oldValue = oldValues[column.ordinal()];
            Object newValue = newValues[column.ordinal()];
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (oldValue != null) {
                byte[] oldKey = IndexCodec.entryKey(table, index, oldValue, pkValue);
                local.put(oldKey, IndexCodec.tombstone(table.id()));
            }
            if (newValue == null) {
                continue; // 改成 NULL:该行从索引里消失(K79 / 边界 10)
            }
            byte[] newKey = IndexCodec.entryKey(table, index, newValue, pkValue);
            if (index.unique()) {
                checkUniqueFree(table, index, column, newValue, newKey, view, local);
                local.put(newKey, IndexCodec.entryValue(table, index, pkValue));
            } else {
                local.put(newKey, EMPTY);
            }
        }
    }

    private Result delete(Ast.Delete delete) {
        if (delete.limit() != null) {
            throw MetaCommands.notSupported("DELETE ... LIMIT 不支持(不做半实现;请用 WHERE 精确限定)");
        }
        TableMeta table = requireTable(delete.database(), delete.table());

        WriteSet local = new WriteSet();
        ReadView view = currentView(local);
        List<IndexMeta> indexes = writableIndexes(table);
        currentStatementWritesIndexes = !indexes.isEmpty();
        Column pk = table.primaryKey();
        int[] count = {0};
        forEachMatch(planFor(table, delete.where()), table, view, (key, values) -> {
            local.put(key, RowCodec.encodeTombstone(table.id()));
            for (IndexMeta index : indexes) {
                Column column = IndexCodec.indexColumn(table, index);
                Object columnValue = values[column.ordinal()];
                if (columnValue == null) {
                    continue;
                }
                byte[] indexKey = IndexCodec.entryKey(table, index, columnValue, values[pk.ordinal()]);
                local.put(indexKey, IndexCodec.tombstone(table.id()));
            }
            count[0]++;
            return true;
        });
        flushWrites(local);
        if (active == null) {
            stats().recordRowDelta(table.id(), -count[0]); // 删除使行数下降(FR-623)
        }
        return affected(count[0]);
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
        } catch (StoreException e) {
            String message = e.getMessage();
            if (currentStatementWritesIndexes && message != null
                    && message.contains("写入量超过上限") && !message.contains("含索引写入")) {
                throw new StoreException(message + "(含索引写入)", e.code());
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ SELECT

    /** P5 查询(表达式 / 聚合 / JOIN / 子查询 / 排序)。 */
    private Result selectQuery(Ast.SelectQuery query) {
        QueryEngine.Options options = queryOptions();
        QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                message -> warnings.add(message));
        return engine.execute(query.body());
    }

    /** 顶层集合运算(SPEC-P6 E 组);计划与执行共用 QueryEngine 的同一套估算。 */
    private Result selectSet(Ast.SetQuery query) {
        QueryEngine.Options options = queryOptions();
        QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                message -> warnings.add(message));
        return engine.executeSet(query.expr());
    }

    /** 无 FROM 的字面量投影(FR-560):一行,列名为字面量文本。 */
    private Result selectLiterals(Ast.SelectLiterals literals) {
        List<String> names = new ArrayList<>(literals.items().size());
        Object[] row = new Object[literals.items().size()];
        for (int i = 0; i < literals.items().size(); i++) {
            Ast.LiteralItem item = literals.items().get(i);
            names.add(item.name());
            row[i] = item.value();
        }
        return new Result.Table(names, List.<Object[]>of(row));
    }

    private Result select(Ast.Select select) {
        if (select.countStar()) {
            throw SqlError.from(new CatalogException("COUNT(*) 应由统一计划器执行"
                    + "(该入口只处理虚拟表的简单形态)"));
        }
        if (!select.orderBy().isEmpty()) {
            throw SqlError.from(new CatalogException("ORDER BY 应由统一计划器执行"
                    + "(该入口只处理虚拟表的简单形态)"));
        }
        TableMeta table = requireTable(select.database(), select.table());
        Plan plan = planFor(table, select.where());
        Planner.Projection projection = Planner.projection(table, select);

        List<Object[]> rows = new ArrayList<>();
        if (select.limit() != null && select.limit() == 0) {
            return new Result.Table(projection.names(), rows);
        }
        Integer limit = select.limit();
        int offset = select.offset() == null ? 0 : select.offset();
        // 分页语义(FR-551):先过滤(forEachMatch 已按计划 + 残余谓词过滤)→ 再跳过 offset
        // → 再截断 count。索引路径与全表扫路径都经过这里,行为天然一致(FR-552)。
        int[] skipped = {0};
        // FR-647:无谓词时把 LIMIT 下推到扫描(返回的 key 里主键在前、索引项在后)。
        int scanLimit = (limit != null && select.where() == null) ? limit + offset : 0;
        // 事务内:固定快照 + 读己之写;事务外:最新已提交(FR-343 / FR-500 / FR-510)。
        ReadView view = currentView(null);
        RowVisitor visitor = (key, values) -> {
            if (skipped[0] < offset) {
                skipped[0]++;
                return true;
            }
            Object[] projected = new Object[projection.indexes().length];
            for (int i = 0; i < projection.indexes().length; i++) {
                projected[i] = values[projection.indexes()[i]];
            }
            rows.add(projected);
            return limit == null || rows.size() < limit;
        };
        int visited = forEachMatch(plan, table, view, scanLimit, visitor);
        if (scanLimit > 0 && limit != null && rows.size() < limit) {
            // 墓碑 / 索引项占位可能让"截断的扫描"少给行 → 退回完整扫描,保证结果正确。
            rows.clear();
            skipped[0] = 0;
            forEachMatch(plan, table, view, 0, visitor);
        }
        return new Result.Table(projection.names(), rows);
    }

    /** 与执行共用同一个计划构造入口(FR-517):查询、DML、EXPLAIN 都走这里。 */
    private Plan planFor(TableMeta table, Ast.Condition where) {
        return planChoice(table, where).plan();
    }

    /** SPEC-P6:访问路径选择(含候选与估算依据),EXPLAIN 与执行共用。 */
    private Planner.Choice planChoice(TableMeta table, Ast.Condition where) {
        return Planner.choose(table, where, catalog.activeIndexes(table.id()), stats(),
                optimizer.enabled());
    }

    /** 需要写入维护的索引:ACTIVE 与 BUILDING(FR-491 / K89);DROPPED 不维护。 */
    private List<IndexMeta> writableIndexes(TableMeta table) {
        List<IndexMeta> out = new ArrayList<>();
        for (IndexMeta index : catalog.indexes(table.id())) {
            if (index.maintainedOnWrite()) {
                out.add(index);
            }
        }
        return out;
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
        return forEachMatch(plan, table, view, 0, visitor);
    }

    private int forEachMatch(Plan plan, TableMeta table, ReadView view, int scanLimit,
                             RowVisitor visitor) {
        int count = 0;
        lastIndexEntriesScanned = 0;
        lastTableLookups = 0;
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
                List<KeyValue> kvs = view.scan(plan.start(), plan.end(), scanLimit);
                for (KeyValue row : kvs) {
                    if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                        continue; // P4:同一表前缀下还有索引项,全表/主键范围扫描必须跳过它们
                    }
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
            case INDEX_POINT, INDEX_RANGE -> count = forEachIndexMatch(plan, table, view, visitor);
            default -> throw new CatalogException("未知的访问计划: " + plan.kind());
        }
        return count;
    }

    /**
     * 索引扫描 + 回表(SPEC-P4 §5.5 / FR-498 ~ FR-502 / FR-511):
     * 扫同一读视图上的索引项 → 按同一快照点查主键 → 墓碑跳过 → 二次校验(行值必须等于索引项值)。
     */
    private int forEachIndexMatch(Plan plan, TableMeta table, ReadView view, RowVisitor visitor) {
        IndexMeta index = plan.index();
        Column indexColumn = IndexCodec.indexColumn(table, index);
        Column pk = table.primaryKey();
        List<KeyValue> entries = view.scan(plan.start(), plan.end(), 0);
        lastIndexEntriesScanned = entries.size();
        int count = 0;
        for (KeyValue entry : entries) {
            IndexCodec.DecodedEntry decodedEntry = IndexCodec.decodeEntry(table, index,
                    entry.key(), entry.value());
            if (decodedEntry.tombstone()) {
                continue; // 索引项墓碑(行已删或索引列已改)
            }
            byte[] rowKey = KeyCodec.rowKeyEncoded(table.id(), decodedEntry.encodedPrimaryKey());
            byte[] data = view.get(rowKey);
            lastTableLookups++;
            if (data == null) {
                continue; // 主键行在该快照不可见(FR-511)
            }
            RowCodec.Decoded decoded = RowCodec.decode(table, data);
            if (decoded.tombstone()) {
                continue; // FR-502:行已删
            }
            Object actual = decoded.values()[indexColumn.ordinal()];
            if (actual == null || !Objects.equals(actual, decodedEntry.columnValue())) {
                continue; // FR-501:过期索引项,二次校验跳过
            }
            if (!matches(plan, decoded.values())) {
                continue; // 残余过滤(FR-503)
            }
            count++;
            if (!visitor.visit(rowKey, decoded.values())) {
                break;
            }
        }
        LOG.info("索引扫描 表=" + table.name() + " 索引=" + index.name()
                + " 索引项=" + lastIndexEntriesScanned + " 回表=" + lastTableLookups
                + " 命中=" + count);
        return count;
    }

    private static boolean matches(Plan plan, Object[] values) {
        return Boolean.TRUE.equals(plan.predicate().test(values));
    }

    private static boolean isTombstone(TableMeta table, byte[] data) {
        return data.length > 0 && data[0] == RowCodec.FORMAT_TOMBSTONE;
    }

    static Result.Message affected(int rows) {
        return new Result.Message(
                "OK, " + rows + (rows == 1 ? " row affected" : " rows affected"), rows);
    }
}
