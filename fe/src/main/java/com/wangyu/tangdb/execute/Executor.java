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
import com.wangyu.tangdb.storage.VectorIndexMeta;
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
    /** SPEC-P6.1:扫描/算子并行度;1 = 与 P6 完全一致的执行路径。 */
    private int queryParallelism = 1;
    /** SPEC-P6.1:列投影 + 谓词下推总开关。 */
    private boolean pushdownEnabled = false;
    /** SPEC-P6.1:扫描批行数(自适应起点)。 */
    private int scanBatchRows = 4096;
    /** SPEC-P6.1 FR-715:结果集流式写协议(仅 fed 服务端开启;单测默认物化)。 */
    private boolean streamingResults = false;
    private java.nio.file.Path sortTmpDir = java.nio.file.Path.of("tmp", "fe-sort");
    private boolean vectorized = true;
    private final List<String> warnings = new ArrayList<>();
    /** 当前 DML 是否维护了索引(用于把 max_txn_write_bytes 错误说明成"含索引写入",FR-493)。 */
    private boolean currentStatementWritesIndexes;

    // ---- P6:优化器开关与统计信息(§5.3) ----
    private com.wangyu.tangdb.planner.OptimizerConfig optimizer =
            com.wangyu.tangdb.planner.OptimizerConfig.defaults();
    private com.wangyu.tangdb.stats.StatsService stats;
    /** 后端注册表(SHOW SEGMENTS 的剪枝计数来自心跳,SPEC-P6.1 FR-706)。 */
    private com.wangyu.tangdb.server.BackendRegistry backends;

    // ---- SPEC-P7:向量检索参数(会话级 exact / path 开关)+ 推理算子 ----
    private com.wangyu.tangdb.query.P7Options p7 = com.wangyu.tangdb.query.P7Options.defaults();
    /** 会话级 SET vector_exact / vector_path 生效后,不再被 SqlEngine 的默认值覆盖。 */
    private boolean p7SessionOverride;
    /** SPEC-P7.1 K181:SET vector_profile = ON 时打印五段耗时(默认关闭)。 */
    private boolean vectorProfile;
    private com.wangyu.tangdb.ai.AiService ai;

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

    /** SPEC-P6.1:并行度 / 下推开关 / 扫描批大小(FR-711 / FR-718)。 */
    public Executor withExecution(int parallelism, boolean pushdown, int scanBatchRows) {
        this.queryParallelism = parallelism > 0 ? parallelism : 1;
        this.pushdownEnabled = pushdown;
        if (scanBatchRows > 0) {
            this.scanBatchRows = scanBatchRows;
        }
        return this;
    }

    /** SPEC-P6.1 FR-715:开启结果集流式输出(协议层逐批写 socket)。 */
    public Executor withStreamingResults(boolean enabled) {
        this.streamingResults = enabled;
        return this;
    }

    /** 最近一次 SELECT 的向量规划(SPEC-P7.1 K181 打点 / EXPLAIN 复用)。 */
    public QueryEngine.VectorPlan lastVectorPlan() {
        return lastVectorPlan;
    }

    /** 测试用:强制关闭下推 / 并行(等价 P6 对拍档)。 */
    public Executor withLegacyExecution() {
        return withExecution(1, false, queryBatchRows);
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

    /** 注入后端注册表(心跳里有 chunk 剪枝计数)。 */
    public Executor withBackends(com.wangyu.tangdb.server.BackendRegistry registry) {
        this.backends = registry;
        return this;
    }

    /** 注入统计服务(常驻 FE 共享一份;单测里按需创建)。 */
    /** SPEC-P7 §5.4:注入向量检索参数(门限与会话开关 exact / path)。 */
    public Executor withP7(com.wangyu.tangdb.query.P7Options options) {
        if (options != null) {
            this.p7 = options;
        }
        return this;
    }

    /** 同时注入向量检索参数与推理算子(SqlEngine 的装配入口)。 */
    public Executor withP7(com.wangyu.tangdb.query.P7Options options,
                           com.wangyu.tangdb.ai.AiService service) {
        if (options != null && !p7SessionOverride) {
            this.p7 = options;
        }
        this.ai = service;
        return this;
    }

    public com.wangyu.tangdb.query.P7Options p7() {
        return p7;
    }

    /** SPEC-P7 FR-760 / SC-422:会话级开关(由 SET vector_exact / SET vector_path 驱动)。 */
    public Executor setVectorExact(boolean exact) {
        this.p7 = p7.withExact(exact);
        this.p7SessionOverride = true;
        return this;
    }

    public Executor setVectorPath(String path) {
        this.p7 = p7.withPath(path);
        this.p7SessionOverride = true;
        return this;
    }

    /** 最近一次 SELECT 的向量规划(SPEC-P7.1 K181)。 */
    private QueryEngine.VectorPlan lastVectorPlan;

    /** SPEC-P7.1 K181:五段耗时开关(与 exact / path 独立,不受 p7SessionOverride 影响)。 */
    public Executor setVectorProfile(boolean on) {
        this.vectorProfile = on;
        return this;
    }

    public boolean vectorProfile() {
        return vectorProfile;
    }

    /** 清掉会话级覆盖(测试 / 重连时用)。 */
    public Executor resetP7Overrides() {
        this.p7SessionOverride = false;
        return this;
    }

    /** SPEC-P7 E 组:注入推理算子(端点未配置时为 null,使用 ai_embed 会明确报错)。 */
    public Executor withAi(com.wangyu.tangdb.ai.AiService service) {
        this.ai = service;
        return this;
    }

    public com.wangyu.tangdb.ai.AiService ai() {
        return ai;
    }

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
                vectorized, optimizer, stats()).withVirtualTables(virtualTables())
                .withExecution(queryParallelism, pushdownEnabled, scanBatchRows)
                .withP7(p7.withProfile(vectorProfile));
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
        if (statement instanceof Ast.CreateVectorIndex create) {
            requireNoTransaction("CREATE VECTOR INDEX");
            return createVectorIndex(create);
        }
        if (statement instanceof Ast.RebuildVectorIndex rebuild) {
            requireNoTransaction("REBUILD VECTOR INDEX");
            return rebuildVectorIndex(rebuild);
        }
        if (statement instanceof Ast.ShowVectorIndexes show) {
            return showVectorIndexes(show);
        }
        if (statement instanceof Ast.ShowAiStats) {
            return showAiStats();
        }
        if (statement instanceof Ast.DropIndex drop) {
            requireNoTransaction("DROP INDEX");
            String db = resolveDatabase(drop.database());
            return dropAnyIndex(db, drop.table(), drop.name());
        }
        if (statement instanceof Ast.DropTable drop) {
            requireNoTransaction("DROP TABLE");
            String db = resolveDatabase(drop.database());
            TableMeta table = catalog.require(db, drop.table());
            dropTableVectorIndexes(table);   // SC-429:旧图文件随表一起消失
            catalog.dropTable(db, drop.table());
            return new Result.Message("OK, 表 " + drop.table().toLowerCase() + " 已删除");
        }
        if (statement instanceof Ast.TruncateTable truncate) {
            requireNoTransaction("TRUNCATE TABLE");
            String db = resolveDatabase(truncate.database());
            TableMeta old = catalog.require(db, truncate.table());
            TableMeta table = catalog.truncateTable(db, truncate.table());
            // TRUNCATE 换来一个新表 ID:旧表上的向量索引连同图段一起作废
            dropTableVectorIndexes(old);
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
        if (statement instanceof Ast.SelectVectorInfo query) {
            return selectVectorInfo(query);
        }
        if (statement instanceof Ast.InsertSelect insertSelect) {
            return runDml(() -> insertSelect(insertSelect));
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

    // ------------------------------------------------------------ SPEC-P7:向量索引 DDL / 观测

    /** 表被删/清空时,先把它的向量索引删掉(BE 删图文件 + 元数据标 DROPPED)。 */
    private void dropTableVectorIndexes(TableMeta table) {
        for (VectorIndexMeta index : catalog.vectorIndexes(table.id())) {
            com.wangyu.tangdb.storage.VectorStore store = vectorStore();
            if (store != null) {
                try {
                    store.dropVectorIndex(index.beId());
                } catch (RuntimeException e) {
                    // BE 不可用不该阻止删表;图文件由后续启动的孤儿清理兜底
                    warnings.add("删除表 " + table.name() + " 的向量索引 " + index.name()
                            + " 图段失败: " + e.getMessage());
                }
            }
            catalog.updateVectorIndexState(index, VectorIndexMeta.STATE_DROPPED);
        }
    }

    /** 某向量索引的段级状态(MetaCommands / SHOW VECTOR INDEXES 用)。 */
    public com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus vectorIndexStatus(long indexId) {
        com.wangyu.tangdb.storage.VectorStore store = vectorStore();
        if (store == null) {
            return com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty();
        }
        com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus status = store.vectorIndexStatus(indexId);
        return status == null
                ? com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty() : status;
    }

    /** 向量索引的 BE 通道(内存实现没有该能力 → 元数据照建,图段不可用,检索退化精确)。 */
    private com.wangyu.tangdb.storage.VectorStore vectorStore() {
        return kv instanceof com.wangyu.tangdb.storage.VectorStore store ? store : null;
    }

    /**
     * SPEC-P7 FR-736:CREATE VECTOR INDEX。
     *
     * <p>顺序:元数据(system table 9)→ 把定义下发给 BE(BE 后台为每个 segment 建图)。
     * 非空表先 BUILDING;空表直接 READY(没有段要建图)。</p>
     */
    private Result createVectorIndex(Ast.CreateVectorIndex create) {
        String db = resolveDatabase(create.database());
        TableMeta table = catalog.require(db, create.table());
        VectorIndexMeta index = catalog.createVectorIndex(db, create.table(), create.name(),
                create.column(), create.metric(), create.m(), create.efConstruction());
        com.wangyu.tangdb.storage.VectorStore store = vectorStore();
        if (store != null) {
            store.registerVectorIndex(definition(table, index));
            if (index.ready()) {
                index = catalog.updateVectorIndexState(index, VectorIndexMeta.STATE_READY);
            }
        } else if (index.state() == VectorIndexMeta.STATE_BUILDING) {
            // 没有 BE 通道(单测 / 内存实现):无法建图,明确退化而不是假装 READY。
            warnings.add("向量索引 " + index.name() + " 已建元数据,但当前存储不支持图段,检索将退化精确暴力");
        }
        return new Result.Message("OK, 向量索引 " + index.name() + " 已创建(索引 ID "
                + index.indexId() + ",列 " + index.columnName() + ",维度 " + index.dim()
                + ",度量 " + index.metricName() + ",m=" + index.m()
                + ",ef_construction=" + index.efConstruction()
                + ",状态 " + index.stateName() + ")");
    }

    /** 把 FE 的定义翻成"纯字节 + 数字"(BE 不认表名 / 列名 / 类型名,K161)。 */
    private com.wangyu.tangdb.storage.VectorStore.VectorIndexDefinition definition(
            TableMeta table, VectorIndexMeta index) {
        com.wangyu.tangdb.storage.ProjectedCodec.Plan plan =
                com.wangyu.tangdb.storage.ProjectedCodec.plan(table,
                        allColumns(table));
        byte[] slots = com.wangyu.tangdb.storage.ProjectedCodec.encodeSlots(table);
        byte[] frame = plan == null ? null : plan.rowFrame();
        if (frame == null) {
            byte[] raw = new byte[5];
            raw[0] = (byte) ((table.columnCount() + 7) / 8);
            raw[1] = (byte) table.columnCount();
            raw[2] = (byte) (table.columnCount() >>> 8);
            raw[3] = (byte) com.wangyu.tangdb.storage.ProjectedCodec.ROW_PREFIX_BYTES;
            frame = raw;
        }
        return new com.wangyu.tangdb.storage.VectorStore.VectorIndexDefinition(
                index.beId(), table.id(),
                KeyCodec.prefix(table.id(), KeyCodec.INDEX_ID_PRIMARY),
                KeyCodec.prefix(table.id(), 1),
                slots, frame, index.columnOrdinal(), index.dim(), index.metric(), index.m(),
                index.efConstruction());
    }

    private static boolean[] allColumns(TableMeta table) {
        boolean[] out = new boolean[table.columnCount()];
        java.util.Arrays.fill(out, true);
        return out;
    }

    /** SPEC-P7 FR-750:REBUILD VECTOR INDEX(旧图删除 + 重新排队建图)。 */
    private Result rebuildVectorIndex(Ast.RebuildVectorIndex rebuild) {
        String db = resolveDatabase(rebuild.database());
        TableMeta table = catalog.require(db, rebuild.table());
        VectorIndexMeta index = catalog.findVectorIndexByName(table.id(), rebuild.name());
        if (index == null) {
            throw new CatalogException("向量索引 " + rebuild.name() + " 不存在(表 "
                    + table.name() + ")", SqlError.ER_CANT_DROP_FIELD_OR_KEY);
        }
        com.wangyu.tangdb.storage.VectorStore store = vectorStore();
        if (store != null) {
            store.dropVectorIndex(index.beId());
            store.registerVectorIndex(definition(table, index));
        }
        catalog.updateVectorIndexState(index, VectorIndexMeta.STATE_BUILDING);
        return new Result.Message("OK, 向量索引 " + index.name() + " 已重新排队建图(表 "
                + table.name() + ")");
    }

    /** DROP INDEX:普通索引与向量索引同一命名空间,按名字分派。 */
    private Result dropAnyIndex(String db, String tableName, String rawName) {
        TableMeta table = catalog.require(db, tableName);
        VectorIndexMeta vector = catalog.findVectorIndexByName(table.id(), rawName);
        if (vector != null) {
            com.wangyu.tangdb.storage.VectorStore store = vectorStore();
            if (store != null) {
                store.dropVectorIndex(vector.beId());
            }
            catalog.dropVectorIndex(db, tableName, rawName);
            return new Result.Message("OK, 向量索引 " + vector.name() + " 已删除(表 "
                    + table.name() + ",图段文件已清理)");
        }
        IndexMeta index = catalog.dropIndex(db, tableName, rawName);
        return new Result.Message("OK, 索引 " + index.name() + " 已删除(表 "
                + table.name() + ")");
    }

    /**
     * SPEC-P7 FR-787:SHOW VECTOR INDEXES。
     *
     * <p>索引级元数据来自 system table 9;段数 / 节点数 / 待重建段数 / 图段字节数来自 BE 的
     * MANIFEST(单一权威,不双写)。BE 报告待重建段数为 0 时顺手把状态推进到 READY。</p>
     */
    private Result showVectorIndexes(Ast.ShowVectorIndexes show) {
        List<VectorIndexMeta> indexes = new ArrayList<>();
        if (show.table() != null) {
            TableMeta table = requireTable(show.database(), show.table());
            indexes.addAll(catalog.vectorIndexes(table.id()));
        } else {
            String db = resolveDatabase(show.database());
            for (TableMeta table : catalog.tables(db)) {
                indexes.addAll(catalog.vectorIndexes(table.id()));
            }
        }
        com.wangyu.tangdb.storage.VectorStore store = vectorStore();
        List<Object[]> rows = new ArrayList<>(indexes.size());
        for (VectorIndexMeta index : indexes) {
            TableMeta table = catalog.findById(index.tableId());
            com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus status =
                    store == null ? com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty()
                            : store.vectorIndexStatus(index.beId());
            if (status == null) {
                status = com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty();
            }
            if (index.state() == VectorIndexMeta.STATE_BUILDING && status.pendingSegments() == 0
                    && store != null) {
                index = catalog.updateVectorIndexState(index, VectorIndexMeta.STATE_READY);
            }
            rows.add(new Object[]{
                    table == null ? "" : table.name(),
                    index.name(),
                    index.columnName(),
                    (long) index.dim(),
                    index.metricName(),
                    (long) index.m(),
                    (long) index.efConstruction(),
                    (long) status.segmentCount(),
                    status.nodeCount(),
                    index.stateName(),
                    (long) status.pendingSegments(),
                    status.graphBytes()
            });
        }
        return new Result.Table(List.of("table_name", "index_name", "column_name", "dim", "metric",
                "m", "ef_construction", "segment_count", "node_count", "state",
                "pending_segments", "graph_bytes"), rows);
    }

    /** SPEC-P7 FR-780:SHOW AI STATS。 */
    private Result showAiStats() {
        com.wangyu.tangdb.ai.AiService service = ai;
        com.wangyu.tangdb.ai.AiService.Stats stats = service == null
                ? new com.wangyu.tangdb.ai.AiService.Stats(0, 0, 0, 0, 0, 0, 0, 0, false)
                : service.stats();
        com.wangyu.tangdb.ai.AiService.Config config = service == null
                ? com.wangyu.tangdb.ai.AiService.Config.disabled() : service.config();
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"ai_endpoint", config.endpoint().isEmpty() ? "(未配置)" : config.endpoint()});
        rows.add(new Object[]{"ai_model", config.model().isEmpty() ? "(未配置)" : config.model()});
        rows.add(new Object[]{"ai_dim", (long) config.dim()});
        rows.add(new Object[]{"calls", stats.calls()});
        rows.add(new Object[]{"cache_hits", stats.cacheHits()});
        rows.add(new Object[]{"cache_misses", stats.cacheMisses()});
        rows.add(new Object[]{"cache_hit_rate", stats.cacheHitRate()});
        rows.add(new Object[]{"failures", stats.failures()});
        rows.add(new Object[]{"retries", stats.retries()});
        rows.add(new Object[]{"average_batch_size", stats.averageBatch()});
        rows.add(new Object[]{"average_latency_ms", stats.averageLatencyMillis()});
        rows.add(new Object[]{"cached_entries", (long) stats.cachedEntries()});
        rows.add(new Object[]{"breaker_open", stats.breakerOpen()});
        return new Result.Table(List.of("metric", "value"), rows);
    }

    // ------------------------------------------------------------ SPEC-P7:E 组推理下推

    /**
     * SELECT ... WITH VECTOR INFO(K164):在结果末尾追加四个自描述列。
     */
    private Result selectVectorInfo(Ast.SelectVectorInfo query) {
        QueryEngine.Options options = queryOptions();
        QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                message -> warnings.add(message));
        Result.Table table = executeBody(engine, query.body());
        if (table == null) {
            table = engine.execute(query.body());
        }
        QueryEngine.VectorPlan plan = engine.lastVectorPlan();
        List<String> columns = new ArrayList<>(table.columns());
        columns.add("approximate");
        columns.add("ef_used");
        columns.add("candidates_examined");
        columns.add("partial");
        List<Object[]> rows = new ArrayList<>(table.rows().size());
        for (Object[] row : table.rows()) {
            Object[] extended = java.util.Arrays.copyOf(row, row.length + 4);
            extended[row.length] = plan != null && plan.approximate;
            extended[row.length + 1] = plan == null ? 0L : (long) plan.efUsed;
            extended[row.length + 2] = plan == null ? 0L : (long) plan.candidatesExamined;
            extended[row.length + 3] = plan != null && plan.partial;
            rows.add(extended);
        }
        return new Result.Table(columns, rows);
    }

    /**
     * SPEC-P7 FR-772:INSERT ... SELECT。
     *
     * <p>先执行查询(投影里的 {@code ai_embed} 按批求值,失败整条语句不写),再按列映射
     * 走与 VALUES 完全相同的写路径(同一批 WAL 记录,FR-777)。</p>
     */
    private Result insertSelect(Ast.InsertSelect select) {
        TableMeta table = requireTable(select.database(), select.table());
        QueryEngine.Options options = queryOptions();
        QueryEngine engine = new QueryEngine(kv, catalog, currentView(null), options,
                message -> warnings.add(message));
        Result.Table result = executeBody(engine, select.query());
        List<String> columns = select.columns();
        if (columns.isEmpty()) {
            columns = new ArrayList<>();
            for (Column column : table.columns()) {
                columns.add(column.name());
            }
            if (result.columns().size() != table.columnCount()) {
                throw new StoreException("INSERT ... SELECT 的列数(" + result.columns().size()
                        + ")与表 " + table.name() + " 的列数(" + table.columnCount()
                        + ")不一致(未给列名列表时按表定义顺序)");
            }
        } else if (columns.size() != result.columns().size()) {
            throw new StoreException("INSERT ... SELECT 的列名个数(" + columns.size()
                    + ")与查询投影列数(" + result.columns().size() + ")不一致");
        }
        List<List<Object>> rows = new ArrayList<>(result.rows().size());
        for (Object[] row : result.rows()) {
            rows.add(new ArrayList<>(java.util.Arrays.asList(row)));
        }
        Ast.Insert insert = new Ast.Insert(select.database(), select.table(), columns, rows);
        return insertInternal(insert, false);
    }

    /**
     * 执行查询体;若投影里有 {@code ai_embed},走"批量推理前处理"(EmbedOp)。
     *
     * @return 含推理的结果;投影里没有 ai_embed 时返回 null(调用方走原路径)
     */
    private Result.Table executeBody(QueryEngine engine, Ast.QueryBody body) {
        List<Integer> embedItems = new ArrayList<>();
        for (int i = 0; i < body.items().size(); i++) {
            Ast.SelectItem item = body.items().get(i);
            if (item.expr() instanceof Ast.Func func
                    && com.wangyu.tangdb.query.Functions.isAiFunction(func.name())) {
                embedItems.add(i);
            }
        }
        if (embedItems.isEmpty()) {
            return null;
        }
        // 1) 把 ai_embed(x) 换成 NULL 占位,并把 x 追加成额外投影列(拿回它的取值)
        List<Ast.SelectItem> items = new ArrayList<>(body.items());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < body.items().size(); i++) {
            Ast.SelectItem item = body.items().get(i);
            if (embedItems.contains(i)) {
                Ast.Func func = (Ast.Func) item.expr();
                if (func.args().size() != 1) {
                    throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                            "ai_embed() 需要且只需要一个文本表达式参数");
                }
                names.add(item.alias() != null ? item.alias()
                        : com.wangyu.tangdb.query.ExprPrinter.print(func));
                items.set(i, new Ast.SelectItem(new Ast.Lit(null, "NULL"), null, false, null));
            } else {
                names.add(item.alias() != null ? item.alias()
                        : item.expr() == null ? null
                        : com.wangyu.tangdb.query.ExprPrinter.print(item.expr()));
            }
        }
        for (int k = 0; k < embedItems.size(); k++) {
            Ast.Func func = (Ast.Func) body.items().get(embedItems.get(k)).expr();
            items.add(new Ast.SelectItem(func.args().get(0), "__ai_arg_" + k, false, null));
        }
        Ast.QueryBody rewritten = new Ast.QueryBody(items, body.from(), body.where(),
                body.groupBy(), body.having(), body.orderBy(), body.limit(), body.offset(),
                body.distinct());
        Result.Table raw = engine.execute(rewritten);

        // 2) 按批求值(缓存 / 幂等键 / 重试 / 熔断都在 AiService 里)
        com.wangyu.tangdb.ai.AiService service = ai;
        if (service == null) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "ai_embed() 需要推理端点配置(缺少 ai_endpoint / ai_model / ai_dim,FR-779)");
        }
        int base = body.items().size();
        List<String> texts = new ArrayList<>(raw.rows().size() * embedItems.size());
        for (Object[] row : raw.rows()) {
            for (int k = 0; k < embedItems.size(); k++) {
                Object value = row[base + k];
                if (!(value instanceof String s)) {
                    throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                            "ai_embed() 的参数必须是文本(实际 " + (value == null ? "NULL"
                            : value.getClass().getSimpleName()) + ",FR-782)");
                }
                texts.add(s);
            }
        }
        List<float[]> vectors = service.embedAll(texts);

        // 3) 把向量填回占位列,丢掉追加的参数列
        List<String> finalNames = new ArrayList<>(names);
        List<Object[]> out = new ArrayList<>(raw.rows().size());
        int at = 0;
        for (Object[] row : raw.rows()) {
            Object[] projected = java.util.Arrays.copyOf(row, base);
            for (int k = 0; k < embedItems.size(); k++) {
                projected[embedItems.get(k)] = vectors.get(at++);
            }
            out.add(projected);
        }
        return new Result.Table(finalNames, out);
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

        // SPEC-P6.1 FR-706:新增"剪枝命中 / 跳过 chunk 数"。数值来自 BE 心跳上报的
        // chunks_skipped / chunks_read 累计值 —— 口径是整个 BE 进程(不是单表),
        // 因此列名带 (be_total);BE 不可用时为 0。
        long pruned = 0;
        long scanned = 0;
        if (backends != null) {
            for (com.wangyu.tangdb.server.BackendRegistry.Backend backend : backends.all()) {
                com.wangyu.tangdb.proto.HeartbeatRequest hb = backend.lastHeartbeat();
                if (hb != null) {
                    pruned += hb.getChunksSkipped();
                    scanned += hb.getChunksRead();
                }
            }
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
                        encodingText(column.encodings()),
                        pruned,
                        scanned
                });
            }
            // SPEC-P7 FR-752:图段字节数单独成行(不计入列存压缩率统计)。
            for (VectorIndexMeta vector : catalog.vectorIndexes(table.id())) {
                com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus status =
                        vectorIndexStatus(vector.beId());
                rows.add(new Object[]{
                        "vector:" + table.id() + ":" + vector.name(),
                        "(graph)",
                        status.nodeCount(),
                        (long) status.segmentCount(),
                        0L,
                        status.graphBytes(),
                        "-",
                        "HNSW(" + vector.metricName() + ")",
                        pruned,
                        scanned
                });
            }
        }
        return new Result.Table(List.of("segment", "column", "rows", "chunks",
                "raw_bytes", "compressed_bytes", "ratio", "encodings",
                "pruned_chunks(be_total)", "skipped_chunks(be_total)"), rows);
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
        // SPEC-P6.1 FR-719:执行层参数进 EXPLAIN(既有行不变)
        // SPEC-P7 FR-784 / SC-435:扫描层并行阈值(与 QueryEngine 同口径)
        long legacyEstimate = choice.chosen().rows();
        String scanParallel;
        if (queryParallelism <= 1) {
            scanParallel = "未并行(并行度=" + queryParallelism + ")";
        } else if (legacyEstimate < p7.parallelMinRows()) {
            scanParallel = "未并行(估算 rows=" + legacyEstimate + " < parallel_min_rows="
                    + p7.parallelMinRows() + ",FR-784)";
        } else {
            scanParallel = "并行(并行度=" + queryParallelism + ",估算 rows=" + legacyEstimate + ")";
        }
        rows.add(new String[]{"扫描并行: " + scanParallel});
        rows.add(new String[]{"并行/批/下推: 并行度=" + queryParallelism
                + ", 扫描批=" + scanBatchRows
                + ", 下推=" + (pushdownEnabled ? "开(列投影 + 谓词字节码)" : "关(全行回退,P6 行为)")});
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
        try {
            // SPEC-P7 FR-772:投影里的 ai_embed 走批量推理前处理(EmbedOp)
            Result.Table withAiResult = executeBody(engine, query.body());
            if (withAiResult != null) {
                return withAiResult;
            }
            if (streamingResults) {
                // SPEC-P6.1 FR-715:简单单表查询直接给流式结果;其余形态退回物化路径
                Result streamed = engine.stream(query.body());
                if (streamed != null) {
                    return streamed;
                }
            }
            return engine.execute(query.body());
        } finally {
            lastVectorPlan = engine.lastVectorPlan();
        }
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
        // 事务内:固定快照 + 读己之写;事务外:最新已提交(FR-343 / FR-500 / FR-510)。
        ReadView view = currentView(null);
        // SPEC-P6.1 FR-715:单表范围扫描 + 最新视图 + 可游标存储 ⇒ 流式结果集。
        // 这里不把 LIMIT 下推给扫描,而是"取够就关游标"(上游立即停止扫描)。
        if (streamingResults && plan.kind() == Plan.Kind.RANGE && view.isLatest()
                && kv instanceof com.wangyu.tangdb.storage.CursoredStore cursored) {
            return new Result.StreamTable(projection.names(), new ScanRowStream(
                    cursored.openScan(plan.start(), plan.end(), 0, 0, null), table, plan,
                    projection, limit, offset));
        }
        // FR-647:无谓词时把 LIMIT 下推到扫描(返回的 key 里主键在前、索引项在后)。
        int scanLimit = (limit != null && select.where() == null) ? limit + offset : 0;
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

    /**
     * 单表范围扫描的流式行源(SPEC-P6.1 FR-715):逐批解码 → 残余谓词 → 跳 offset → 截 limit,
     * 取够或读完后 close 游标,把"整表物化"换成"一批行"。
     */
    private final class ScanRowStream implements RowStream {

        private final com.wangyu.tangdb.storage.ScanCursor cursor;
        private final TableMeta table;
        private final Plan plan;
        private final Planner.Projection projection;
        private final Integer limit;
        private final int offset;
        private int skipped;
        private int emitted;
        private boolean closed;

        ScanRowStream(com.wangyu.tangdb.storage.ScanCursor cursor, TableMeta table, Plan plan,
                      Planner.Projection projection, Integer limit, int offset) {
            this.cursor = cursor;
            this.table = table;
            this.plan = plan;
            this.projection = projection;
            this.limit = limit;
            this.offset = offset;
        }

        @Override
        public List<String> columns() {
            return projection.names();
        }

        @Override
        public List<Object[]> nextBatch() {
            while (!closed) {
                if (limit != null && emitted >= limit) {
                    close();
                    return null;
                }
                List<KeyValue> batch = cursor.nextBatch();
                if (batch == null) {
                    close();
                    return null;
                }
                List<Object[]> out = new ArrayList<>();
                int[] indexes = projection.indexes();
                for (KeyValue kv : batch) {
                    if (KeyCodec.indexId(kv.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                        continue; // 同一表前缀下的索引项
                    }
                    RowCodec.Decoded decoded = RowCodec.decode(table, kv.value());
                    if (decoded.tombstone() || !matches(plan, decoded.values())) {
                        continue;
                    }
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    if (limit != null && emitted >= limit) {
                        break;
                    }
                    Object[] row = new Object[indexes.length];
                    for (int i = 0; i < indexes.length; i++) {
                        row[i] = decoded.values()[indexes[i]];
                    }
                    out.add(row);
                    emitted++;
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
            return null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            cursor.close();
        }
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
