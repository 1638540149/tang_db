package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.planner.CostModel;
import com.wangyu.tangdb.planner.Estimator;
import com.wangyu.tangdb.planner.OptimizerConfig;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.txn.ReadView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P5/P6 查询引擎(SPEC-P6 B ~ G 组)。
 *
 * <p>执行顺序:FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT/OFFSET。</p>
 *
 * <p>P6 在这条链上加了"有据可依的选择":访问路径由 {@code Planner} 的代价比较决定(单表),
 * 连接顺序与物理算子(哈希 / 排序归并 / NLJ、聚合 Hash / Sort、TopN / 外部归并)由估算决定;
 * {@code optimizer_enabled=false} 时全部退回 P5 的固定规则与同一份结果。</p>
 *
 * <p><b>结果不变性(FR-634)</b>:所有连接实现都把输出整理成"按 SQL 书写顺序的嵌套循环序"
 * (canonical order),因此换连接顺序 / 换 build 侧 / 换算法都只改"怎么跑",不改行序。</p>
 */
public final class QueryEngine {

    /** 运行参数(来自配置)。 */
    public record Options(String currentDatabase, int batchRows, long sortSpillBytes,
                          Path sortTmpDir, boolean vectorized,
                          com.wangyu.tangdb.planner.OptimizerConfig optimizer,
                          com.wangyu.tangdb.stats.StatsService stats,
                          com.wangyu.tangdb.execute.VirtualTables virtualTables,
                          int parallelism, boolean pushdown, int scanBatchRows) {

        public Options {
            if (optimizer == null) {
                optimizer = com.wangyu.tangdb.planner.OptimizerConfig.defaults();
            }
            if (virtualTables == null) {
                virtualTables = com.wangyu.tangdb.execute.VirtualTables.NONE;
            }
            if (parallelism < 1) {
                parallelism = 1;
            }
            if (scanBatchRows < 1) {
                scanBatchRows = batchRows;
            }
        }

        /** P6 之前形态的 7 参构造(虚拟表为空)。 */
        public Options(String currentDatabase, int batchRows, long sortSpillBytes,
                       Path sortTmpDir, boolean vectorized,
                       com.wangyu.tangdb.planner.OptimizerConfig optimizer,
                       com.wangyu.tangdb.stats.StatsService stats) {
            this(currentDatabase, batchRows, sortSpillBytes, sortTmpDir, vectorized, optimizer,
                    stats, com.wangyu.tangdb.execute.VirtualTables.NONE);
        }

        /** P5 形态的构造(单测使用):优化器默认开启、统计服务按需创建。 */
        public Options(String currentDatabase, int batchRows, long sortSpillBytes,
                       Path sortTmpDir, boolean vectorized) {
            this(currentDatabase, batchRows, sortSpillBytes, sortTmpDir, vectorized,
                    com.wangyu.tangdb.planner.OptimizerConfig.defaults(), null,
                    com.wangyu.tangdb.execute.VirtualTables.NONE);
        }

        /** 8 参构造(虚拟表非空;P6.1 的执行层参数取默认值)。 */
        public Options(String currentDatabase, int batchRows, long sortSpillBytes,
                       Path sortTmpDir, boolean vectorized,
                       com.wangyu.tangdb.planner.OptimizerConfig optimizer,
                       com.wangyu.tangdb.stats.StatsService stats,
                       com.wangyu.tangdb.execute.VirtualTables virtualTables) {
            this(currentDatabase, batchRows, sortSpillBytes, sortTmpDir, vectorized, optimizer,
                    stats, virtualTables, 1, false, batchRows);
        }

        /** 附带虚拟表访问器(SPEC-P6 FR-667)。 */
        public Options withVirtualTables(com.wangyu.tangdb.execute.VirtualTables tables) {
            return new Options(currentDatabase, batchRows, sortSpillBytes, sortTmpDir, vectorized,
                    optimizer, stats, tables == null
                    ? com.wangyu.tangdb.execute.VirtualTables.NONE : tables,
                    parallelism, pushdown, scanBatchRows);
        }

        /** 设置 SPEC-P6.1 的执行层参数(并行度 / 下推开关 / 扫描批)。 */
        public Options withExecution(int parallelism, boolean pushdown, int scanBatchRows) {
            return new Options(currentDatabase, batchRows, sortSpillBytes, sortTmpDir, vectorized,
                    optimizer, stats, virtualTables, parallelism, pushdown, scanBatchRows);
        }

        public static Options defaults(String database) {
            return new Options(database, 1024, Sorter.DEFAULT_SPILL_BYTES,
                    Path.of("tmp", "fe-sort"), true);
        }

        public boolean cbo() {
            return optimizer.enabled();
        }
    }

    private final KvStore kv;
    private final Catalog catalog;
    private final ReadView view;
    private final Options options;
    private final Evaluator.Warnings warnings;
    private final Estimator estimator;

    private final Map<Ast.Expr, Object> subqueryValues = new IdentityHashMap<>();
    private final Map<Ast.Expr, Object> aggregateValues = new IdentityHashMap<>();
    private Map<Ast.Expr, Integer> aggregateColumns = Map.of();
    private final Evaluator evaluator;
    private final BatchEvaluator batchEvaluator;
    private final Sorter sorter;

    /** 供相关子查询报错时判断"外层是否存在该列"。 */
    private Schema outerSchema;
    /** 相关子查询逐行驱动时的外层当前行;非 null 表示本引擎是"带参数的内层执行"。 */
    private Object[] outerRow;
    /** 相关子查询额外注入到内层行前面的列数(投影 `*` 时不得包含它们)。 */
    private int outerPrefix;

    public QueryEngine(KvStore kv, Catalog catalog, ReadView view, Options options,
                       Evaluator.Warnings warnings) {
        this.kv = kv;
        this.catalog = catalog;
        this.view = view;
        this.options = options == null ? Options.defaults("default") : options;
        this.warnings = warnings == null ? Evaluator.Warnings.NONE : warnings;
        this.estimator = new Estimator(this.options.stats(), this.options.optimizer());
        this.evaluator = new Evaluator(aggregateValues, subqueryValues, this.warnings);
        this.evaluator.withSubqueryResolver(this::subqueryValueForEval);
        this.batchEvaluator = new BatchEvaluator(subqueryValues);
        // SPEC-P6.1 FR-714:并行度 1 时 Sorter 走原单线程路径(逐行同序)
        this.sorter = new Sorter(this.options.sortSpillBytes(), this.options.sortTmpDir(),
                this.options.parallelism());
    }

    public QueryEngine withOuterSchema(Schema schema) {
        this.outerSchema = schema;
        return this;
    }

    /** 相关子查询:内层执行时绑定的外层当前行(SPEC-P6 FR-656)。 */
    public QueryEngine withOuterRow(Schema schema, Object[] row) {
        this.outerSchema = schema;
        this.outerRow = row;
        return this;
    }

    private boolean cbo() {
        return options.cbo();
    }

    private OptimizerConfig cfg() {
        return options.optimizer();
    }

    // ------------------------------------------------------------ 入口

    /** 执行一条 SELECT,返回结果表。 */
    public Result.Table execute(Ast.QueryBody body) {
        Relation relation = run(body);
        return new Result.Table(relation.schema.names(), relation.rows);
    }

    // ------------------------------------------------------------ 流式结果(FR-715)

    /**
     * 把"简单单表查询"做成流式结果集(SPEC-P6.1 FR-715)。
     *
     * <p>满足以下条件才走流式,否则返回 null 由调用方退回物化路径
     * (结果逐行一致,只是内存占用不同):</p>
     * <ul>
     *   <li>单表、无 JOIN / 派生表 / 虚拟表、无 DISTINCT / GROUP BY / HAVING / ORDER BY;</li>
     *   <li>投影与 WHERE 里没有聚合、没有子查询(不需要预求值);</li>
     *   <li>最新已提交视图 + 存储在 {@link com.wangyu.tangdb.storage.CursoredStore}
     *       上(真实 BE 客户端;事务视图与内存实现退回物化);</li>
     *   <li>访问路径是全表范围(点查 / 索引路径本来就只取少量行)。</li>
     * </ul>
     *
     * <p>{@code LIMIT} 不再下推给扫描,而是"取够就 close 游标"—— 上游不再继续扫描,
     * 内存只与一批行数相关。</p>
     */
    public Result.StreamTable stream(Ast.QueryBody body) {
        if (body.from() == null || body.distinct() || !body.groupBy().isEmpty()
                || body.having() != null || !body.orderBy().isEmpty()
                || !body.from().joins().isEmpty()) {
            return null;
        }
        for (Ast.SelectItem item : body.items()) {
            if (item.expr() != null
                    && (containsAggregate(item.expr()) || containsSubquery(item.expr()))) {
                return null;
            }
        }
        if (containsSubquery(body.where())) {
            return null; // 子查询要预求值,交给物化路径
        }
        if (!view.isLatest()
                || !(kv instanceof com.wangyu.tangdb.storage.CursoredStore cursored)) {
            return null;
        }
        FromPlan plan = buildFromPlan(body);
        if (plan.sqlOrder.size() != 1) {
            return null;
        }
        SourcePlan source = plan.sqlOrder.get(0);
        if (source.table == null || source.derived() || source.virtualName != null) {
            return null;
        }
        com.wangyu.tangdb.planner.Plan access = source.access == null ? null : source.access.plan();
        if (access != null && access.kind() != com.wangyu.tangdb.planner.Plan.Kind.RANGE) {
            return null;
        }
        TableMeta table = source.table;
        byte[] start = access == null ? KeyCodec.tableRangeStart(table.id()) : access.start();
        byte[] end = access == null ? KeyCodec.tableRangeEnd(table.id()) : access.end();

        com.wangyu.tangdb.storage.ProjectedCodec.Plan projection = projectionFor(source, table);
        com.wangyu.tangdb.storage.ScanOptions scanOptions = null;
        if (projection != null) {
            byte[] predicate = PredicateCodec.compile(source.pushed, table, source.qualifier);
            scanOptions = new com.wangyu.tangdb.storage.ScanOptions(options.scanBatchRows(),
                    projection.mask(), projection.columnSlots(), projection.rowFrame(), predicate,
                    true);
        }
        // 列名先算出来(空结果也要能给出正确的列定义)
        Schema columnsOnly = project(List.of(), source.schema, body.items(), false, body,
                Map.of()).schema;
        com.wangyu.tangdb.storage.ScanCursor cursor = cursored.openScan(start, end, 0, 0,
                scanOptions);
        return new Result.StreamTable(columnsOnly.names(),
                new StreamingRows(cursor, source, table, projection, body));
    }

    /** 游标 → 结果批的行源:逐批解码、过滤、投影,并按 LIMIT 提前收尾。 */
    private final class StreamingRows implements com.wangyu.tangdb.execute.RowStream {

        private final com.wangyu.tangdb.storage.ScanCursor cursor;
        private final SourcePlan source;
        private final TableMeta table;
        private final com.wangyu.tangdb.storage.ProjectedCodec.Plan projection;
        private final com.wangyu.tangdb.storage.ProjectedCodec.Decoder decoder;
        private final Ast.QueryBody body;
        private final int offset;
        private final Integer limit;
        private int skipped;
        private int emitted;
        private boolean closed;

        StreamingRows(com.wangyu.tangdb.storage.ScanCursor cursor, SourcePlan source,
                      TableMeta table, com.wangyu.tangdb.storage.ProjectedCodec.Plan projection,
                      Ast.QueryBody body) {
            this.cursor = cursor;
            this.source = source;
            this.table = table;
            this.projection = projection;
            this.decoder = projection == null ? null
                    : new com.wangyu.tangdb.storage.ProjectedCodec.Decoder(table, projection);
            this.body = body;
            this.offset = body.offset() == null ? 0 : body.offset();
            this.limit = body.limit();
        }

        @Override
        public List<String> columns() {
            return project(List.of(), source.schema, body.items(), false, body, Map.of())
                    .schema.names();
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
                List<Object[]> out = projectBatch(batch);
                if (!out.isEmpty()) {
                    return out;
                }
            }
            return null;
        }

        private List<Object[]> projectBatch(List<KeyValue> batch) {
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (KeyValue kv : batch) {
                if (KeyCodec.indexId(kv.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                    continue; // 同一表前缀下的索引项
                }
                if (projection != null) {
                    rows.add(decoder.decodeInto(kv.value(), new Object[table.columnCount()]));
                    continue;
                }
                byte[] raw = kv.value();
                if (raw != null && raw.length > 0 && raw[0] == RowCodec.FORMAT_TOMBSTONE) {
                    continue;
                }
                RowCodec.Decoded decoded = source.needed == null
                        ? RowCodec.decode(table, raw) : RowCodec.decode(table, raw, source.needed);
                if (decoded.tombstone()) {
                    continue;
                }
                rows.add(decoded.values());
            }
            Relation relation = new Relation(source.schema, rows, rows.size(), null);
            if (source.pushed != null) {
                relation = filter(relation, source.pushed);
            }
            if (body.where() != null) {
                relation = filter(relation, body.where());
            }
            Projection projected = project(relation.rows, source.schema, body.items(), false, body,
                    Map.of());
            List<Object[]> out = new ArrayList<>(projected.rows.size());
            for (Object[] row : projected.rows) {
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                if (limit != null && emitted >= limit) {
                    break;
                }
                out.add(row);
                emitted++;
            }
            return out;
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

    /** 行结果(单测 / 子查询复用)。 */
    public Relation run(Ast.QueryBody body) {
        if (body.from() == null) {
            return runWithoutFrom(body);
        }
        FromPlan plan = buildFromPlan(body);
        Relation source = executeFromPlan(plan, body);
        return postProcess(source, body, plan);
    }

    /** 结果集:列名表 + 行(+ 估算行数 + 每行的"来源行号",用于稳定行序)。 */
    public static final class Relation {
        public final Schema schema;
        public final List<Object[]> rows;
        public final long estimatedRows;
        /** 与 rows 等长;每行是各来源在各自扫描序里的行号(用于 canonical order)。 */
        public final List<long[]> seq;

        public Relation(Schema schema, List<Object[]> rows) {
            this(schema, rows, rows.size(), null);
        }

        public Relation(Schema schema, List<Object[]> rows, long estimatedRows,
                        List<long[]> seq) {
            this.schema = schema;
            this.rows = rows;
            this.estimatedRows = estimatedRows;
            this.seq = seq;
        }
    }

    private Relation runWithoutFrom(Ast.QueryBody body) {
        Schema empty = Schema.of(List.of(), List.of());
        Relation source = new Relation(empty, List.<Object[]>of(new Object[0]));
        return postProcess(source, body, null);
    }

    // ------------------------------------------------------------ FROM 计划

    /** 一个 FROM 来源(表 / 派生表)。 */
    private static final class SourcePlan {
        final Ast.FromItem item;
        final String qualifier;
        final Schema schema;
        final TableMeta table;      // 派生表 / 虚拟表为 null
        final String virtualName;   // 非 null = information_schema 虚拟表
        final Ast.Expr pushed;      // 已下推到该来源的单表谓词
        final long estimatedRows;
        final boolean[] needed;     // 列裁剪:true = 需要解码
        final int neededCount;
        final com.wangyu.tangdb.planner.Planner.Choice access; // 表来源的访问路径(FR-635)

        SourcePlan(Ast.FromItem item, String qualifier, Schema schema, TableMeta table,
                   String virtualName, Ast.Expr pushed, long estimatedRows, boolean[] needed,
                   int neededCount, com.wangyu.tangdb.planner.Planner.Choice access) {
            this.item = item;
            this.qualifier = qualifier;
            this.schema = schema;
            this.table = table;
            this.virtualName = virtualName;
            this.pushed = pushed;
            this.estimatedRows = estimatedRows;
            this.needed = needed;
            this.neededCount = neededCount;
            this.access = access;
        }

        boolean derived() {
            return item.subquery() != null;
        }
    }

    /** FROM 的执行计划:SQL 书写顺序 + 连接顺序 + 顺序映射。 */
    private static final class FromPlan {
        final List<SourcePlan> sqlOrder;
        final List<SourcePlan> joinOrder;
        /** joinOrder 下标 → sqlOrder 下标(按 SQL 顺序排列的投影)。 */
        final int[] perm;
        final List<Ast.Expr> joinPredicates;
        final long estimatedRows;
        final boolean reordered;

        FromPlan(List<SourcePlan> sqlOrder, List<SourcePlan> joinOrder, int[] perm,
                 List<Ast.Expr> joinPredicates, long estimatedRows, boolean reordered) {
            this.sqlOrder = sqlOrder;
            this.joinOrder = joinOrder;
            this.perm = perm;
            this.joinPredicates = joinPredicates;
            this.estimatedRows = estimatedRows;
            this.reordered = reordered;
        }
    }

    private FromPlan buildFromPlan(Ast.QueryBody body) {
        Ast.FromClause from = body.from();
        List<Ast.FromItem> items = new ArrayList<>();
        items.add(from.first());
        List<Ast.Expr> onPredicates = new ArrayList<>();
        boolean allInner = true;
        for (Ast.Join join : from.joins()) {
            items.add(join.right());
            if (join.on() != null) {
                flattenAnd(join.on(), onPredicates);
            }
            if (join.type() == Ast.JoinType.LEFT) {
                allInner = false;
            }
        }
        boolean single = items.size() == 1;

        Map<String, Ast.Expr> pushedByQualifier = singleSourcePushdown(body, items, single);
        Map<String, boolean[]> needed = neededColumns(body, items, single);

        List<SourcePlan> sqlOrder = new ArrayList<>(items.size());
        for (Ast.FromItem item : items) {
            String qualifier = qualifierOf(item);
            Ast.Expr pushed = pushedByQualifier.get(qualifier.toLowerCase(Locale.ROOT));
            if (item.subquery() != null) {
                Schema schema = describe(item.subquery());
                long estimate = estimateQuery(item.subquery());
                sqlOrder.add(new SourcePlan(item, qualifier, schema, null, null, pushed, estimate,
                        null, 0, null));
                continue;
            }
            if (isVirtual(item)) {
                String virtualName = item.table().toLowerCase(Locale.ROOT);
                Schema schema = virtualSchema(qualifier, virtualName);
                if (schema == null) {
                    throw new SqlError(SqlError.ER_NO_SUCH_TABLE, "42S02",
                            "information_schema." + virtualName + " 不存在");
                }
                long estimate = options.virtualTables().estimatedRows(virtualName);
                sqlOrder.add(new SourcePlan(item, qualifier, schema, null, virtualName, pushed,
                        estimate, null, 0, null));
                continue;
            }
            TableMeta table = tableOf(item);
            Schema schema = Schema.ofTable(qualifier, table.columns());
            boolean[] want = needed.get(qualifier.toLowerCase(Locale.ROOT));
            int count = 0;
            if (want == null) {
                want = allColumns(table);
            }
            for (boolean w : want) {
                if (w) {
                    count++;
                }
            }
            // 访问路径选择(FR-630/635):把下推到该表的谓词交给 Planner 做代价比较。
            // 谓词不是"列 op 常量"形态时退回全表扫描(估算用整条 WHERE)。
            Ast.Condition pushCondition = toCondition(pushed);
            com.wangyu.tangdb.planner.Planner.Choice access = null;
            long estimate;
            if (pushCondition != null) {
                access = com.wangyu.tangdb.planner.Planner.choose(table, pushCondition,
                        catalog.activeIndexes(table.id()), options.stats(), cbo());
                estimate = access.chosen().rows();
            } else {
                estimate = estimator.estimateRows(table, pushed == null ? body.where() : pushed);
            }
            sqlOrder.add(new SourcePlan(item, qualifier, schema, table, null, pushed, estimate,
                    want, count, access));
        }

        // 多来源 WHERE 里的等值谓词也参与连接键推导(FR-644)
        List<Ast.Expr> joinPredicates = new ArrayList<>(onPredicates);
        if (!single && body.where() != null) {
            List<Ast.Expr> conjuncts = new ArrayList<>();
            flattenAnd(body.where(), conjuncts);
            for (Ast.Expr conjunct : conjuncts) {
                if (qualifiersOf(conjunct).size() > 1) {
                    joinPredicates.add(conjunct);
                }
            }
        }

        List<SourcePlan> joinOrder = sqlOrder;
        boolean reordered = false;
        if (allInner && sqlOrder.size() > 1 && cbo()) {
            joinOrder = chooseJoinOrder(sqlOrder, joinPredicates);
            reordered = !sameOrder(sqlOrder, joinOrder);
        }
        int[] perm = permutation(sqlOrder, joinOrder);
        long estimated = estimateJoin(joinOrder, joinPredicates);
        return new FromPlan(sqlOrder, joinOrder, perm, joinPredicates, estimated, reordered);
    }

    private static boolean sameOrder(List<SourcePlan> a, List<SourcePlan> b) {
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static int[] permutation(List<SourcePlan> sqlOrder, List<SourcePlan> joinOrder) {
        int[] perm = new int[joinOrder.size()];
        for (int i = 0; i < joinOrder.size(); i++) {
            for (int j = 0; j < sqlOrder.size(); j++) {
                if (joinOrder.get(i) == sqlOrder.get(j)) {
                    perm[i] = j;
                }
            }
        }
        return perm;
    }

    /** 单表谓词下推(FR-644):只推"只引用一个来源且不含子查询"的合取项。 */
    private Map<String, Ast.Expr> singleSourcePushdown(Ast.QueryBody body,
                                                       List<Ast.FromItem> items,
                                                       boolean single) {
        Map<String, Ast.Expr> out = new LinkedHashMap<>();
        if (body.where() == null) {
            return out;
        }
        List<Ast.Expr> conjuncts = new ArrayList<>();
        flattenAnd(body.where(), conjuncts);
        for (Ast.Expr conjunct : conjuncts) {
            if (containsSubquery(conjunct)) {
                continue;
            }
            Set<String> quals = qualifiersOf(conjunct);
            String target = null;
            if (quals.size() == 1) {
                target = quals.iterator().next();
            } else if (quals.isEmpty() && single) {
                target = qualifierOf(items.get(0));
            }
            if (target == null) {
                continue;
            }
            String key = target.toLowerCase(Locale.ROOT);
            Ast.Expr existing = out.get(key);
            out.put(key, existing == null ? conjunct : new Ast.LogicalAnd(existing, conjunct));
        }
        return out;
    }

    /** 列裁剪:计算每个来源真正用到的列(FR-648);star / 未限定列在多表时保守全取。 */
    private Map<String, boolean[]> neededColumns(Ast.QueryBody body, List<Ast.FromItem> items,
                                                 boolean single) {
        Map<String, Set<String>> used = new LinkedHashMap<>();
        Set<String> allNeeded = new LinkedHashSet<>();
        boolean everything = items.size() > 1;

        List<Ast.Expr> exprs = new ArrayList<>();
        for (Ast.SelectItem item : body.items()) {
            if (item.star()) {
                if (item.starQualifier() == null) {
                    everything = true;
                } else {
                    allNeeded.add(item.starQualifier().toLowerCase(Locale.ROOT));
                }
                continue;
            }
            exprs.add(item.expr());
        }
        exprs.add(body.where());
        exprs.add(body.having());
        exprs.addAll(body.groupBy());
        for (Ast.OrderItem order : body.orderBy()) {
            exprs.add(order.expr());
        }
        for (Ast.Join join : body.from().joins()) {
            exprs.add(join.on());
        }
        for (Ast.Expr expr : exprs) {
            if (containsSubquery(expr)) {
                // 子查询里可能引用本表的列(相关子查询),静态看不到 → 保守全取(FR-648 允许)
                everything = true;
            }
        }
        boolean unqualified = false;
        for (Ast.Expr expr : exprs) {
            for (Ast.Col col : columnsOf(expr)) {
                if (col.qualifier() == null) {
                    unqualified = true;
                    continue;
                }
                used.computeIfAbsent(col.qualifier().toLowerCase(Locale.ROOT),
                        k -> new LinkedHashSet<>()).add(col.name().toLowerCase(Locale.ROOT));
            }
        }
        if (unqualified && !single) {
            everything = true;
        }
        Map<String, boolean[]> out = new LinkedHashMap<>();
        for (Ast.FromItem item : items) {
            String qualifier = qualifierOf(item).toLowerCase(Locale.ROOT);
            if (item.subquery() != null || isVirtual(item)) {
                continue; // 派生表 / 虚拟表不做 FE 列裁剪(本身就在内存里)
            }
            TableMeta table = tableOf(item);
            boolean[] want = new boolean[table.columnCount()];
            boolean all = everything || allNeeded.contains(qualifier) || (unqualified && single);
            for (int i = 0; i < want.length; i++) {
                want[i] = all || used.getOrDefault(qualifier, Set.of())
                        .contains(table.columns().get(i).name());
            }
            out.put(qualifier, want);
        }
        return out;
    }

    private static boolean[] allColumns(TableMeta table) {
        boolean[] want = new boolean[table.columnCount()];
        Arrays.fill(want, true);
        return want;
    }

    // ------------------------------------------------------------ 连接顺序(FR-636)

    /** ≤ N 表穷举 left-deep;> N 贪心(每步选使中间结果最小的表)。 */
    private List<SourcePlan> chooseJoinOrder(List<SourcePlan> sources, List<Ast.Expr> predicates) {
        int max = cfg().joinEnumMaxTables();
        if (sources.size() <= max) {
            List<List<SourcePlan>> best = new ArrayList<>();
            permute(sources, new ArrayList<>(), new boolean[sources.size()], best);
            List<SourcePlan> bestOrder = null;
            double bestCost = Double.MAX_VALUE;
            for (List<SourcePlan> order : best) {
                double cost = estimateJoinCost(order, predicates);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestOrder = order;
                }
            }
            return bestOrder == null ? sources : bestOrder;
        }
        List<SourcePlan> remaining = new ArrayList<>(sources);
        List<SourcePlan> order = new ArrayList<>();
        order.add(remaining.remove(0));
        while (!remaining.isEmpty()) {
            SourcePlan pick = null;
            double pickCost = Double.MAX_VALUE;
            for (SourcePlan candidate : remaining) {
                double cost = estimateJoinCost(append(order, candidate), predicates);
                if (cost < pickCost) {
                    pickCost = cost;
                    pick = candidate;
                }
            }
            if (pick == null) {
                break;
            }
            remaining.remove(pick);
            order.add(pick);
        }
        return order;
    }

    private static List<SourcePlan> append(List<SourcePlan> order, SourcePlan extra) {
        List<SourcePlan> out = new ArrayList<>(order.size() + 1);
        out.addAll(order);
        out.add(extra);
        return out;
    }

    private static void permute(List<SourcePlan> input, List<SourcePlan> current,
                                boolean[] used, List<List<SourcePlan>> out) {
        if (current.size() == input.size()) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = 0; i < input.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            current.add(input.get(i));
            permute(input, current, used, out);
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    /** left-deep 代价:每步的中间结果行数 + 哈希构建/探测(FR-636 / FR-637)。 */
    private double estimateJoinCost(List<SourcePlan> order, List<Ast.Expr> predicates) {
        long rows = order.get(0).estimatedRows;
        double cost = rows;
        for (int i = 1; i < order.size(); i++) {
            long rightRows = order.get(i).estimatedRows;
            long joined = estimateJoinRows(order.subList(0, i), order.get(i), rows, rightRows,
                    predicates);
            long build = Math.min(rows, rightRows);
            long probe = Math.max(rows, rightRows);
            cost += CostModel.hashJoin(build, probe) + joined;
            rows = joined;
        }
        return cost;
    }

    private long estimateJoin(List<SourcePlan> order, List<Ast.Expr> predicates) {
        if (order.isEmpty()) {
            return 0;
        }
        long rows = order.get(0).estimatedRows;
        for (int i = 1; i < order.size(); i++) {
            rows = estimateJoinRows(order.subList(0, i), order.get(i), rows,
                    order.get(i).estimatedRows, predicates);
        }
        return rows;
    }

    /** 连接输出行数:等值按 1/max(dL,dR)(FR-628),无统计按 1/10;非等值按 1/10。 */
    private long estimateJoinRows(List<SourcePlan> left, SourcePlan right, long leftRows,
                                  long rightRows, List<Ast.Expr> predicates) {
        Set<String> leftQualifiers = new LinkedHashSet<>();
        for (SourcePlan source : left) {
            leftQualifiers.add(source.qualifier.toLowerCase(Locale.ROOT));
        }
        List<EquiSpec> specs = equiSpecs(predicates, leftQualifiers,
                right.qualifier.toLowerCase(Locale.ROOT), left, right);
        if (specs.isEmpty()) {
            return Math.max(1, leftRows * rightRows / 10);
        }
        double selectivity = 1.0 / 10.0;
        boolean first = true;
        for (EquiSpec spec : specs) {
            long dl = distinctOf(spec.leftSource, spec.leftColumn);
            long dr = distinctOf(spec.rightSource, spec.rightColumn);
            double s = 1.0 / Math.max(1, Math.max(dl, dr));
            selectivity = first ? s : selectivity * s;
            first = false;
        }
        return Math.max(0, Math.round(leftRows * rightRows * selectivity));
    }

    private long distinctOf(SourcePlan source, String column) {
        if (source.table == null || column == null) {
            return 10;
        }
        return estimator.distinct(source.table, column);
    }

    /** 连接等值对(用于估算与执行)。 */
    private record EquiSpec(SourcePlan leftSource, String leftColumn,
                            SourcePlan rightSource, String rightColumn) {
    }

    private List<EquiSpec> equiSpecs(List<Ast.Expr> predicates, Set<String> leftQualifiers,
                                     String rightQualifier, List<SourcePlan> leftSources,
                                     SourcePlan rightSource) {
        List<EquiSpec> out = new ArrayList<>();
        for (Ast.Expr predicate : predicates) {
            if (!(predicate instanceof Ast.Cmp cmp) || cmp.op() != Ast.Op.EQ) {
                continue;
            }
            if (!(cmp.left() instanceof Ast.Col a) || !(cmp.right() instanceof Ast.Col b)) {
                continue;
            }
            String qa = a.qualifier() == null ? null : a.qualifier().toLowerCase(Locale.ROOT);
            String qb = b.qualifier() == null ? null : b.qualifier().toLowerCase(Locale.ROOT);
            SourcePlan sa = findSource(leftSources, rightSource, qa);
            SourcePlan sb = findSource(leftSources, rightSource, qb);
            if (sa == null || sb == null || sa == sb) {
                continue;
            }
            boolean aLeft = leftQualifiers.contains(qa);
            boolean bLeft = leftQualifiers.contains(qb);
            if (aLeft && qb.equals(rightQualifier)) {
                out.add(new EquiSpec(sa, a.name(), sb, b.name()));
            } else if (bLeft && qa.equals(rightQualifier)) {
                out.add(new EquiSpec(sb, b.name(), sa, a.name()));
            }
        }
        return out;
    }

    private static SourcePlan findSource(List<SourcePlan> left, SourcePlan right, String qualifier) {
        if (qualifier == null) {
            return null;
        }
        for (SourcePlan source : left) {
            if (source.qualifier.equalsIgnoreCase(qualifier)) {
                return source;
            }
        }
        if (right.qualifier.equalsIgnoreCase(qualifier)) {
            return right;
        }
        return null;
    }

    // ------------------------------------------------------------ FROM 执行

    private Relation executeFromPlan(FromPlan plan, Ast.QueryBody body) {
        List<SourcePlan> sqlOrder = plan.sqlOrder;
        // 单表 + 无 JOIN + 无聚合 + 无 ORDER BY 时把 LIMIT 下推到扫描(FR-647)
        // LIMIT 下推只在"扫描侧没有任何过滤"时成立:谓词在 FE 侧执行,先截断会漏行。
        boolean limitPushdown = sqlOrder.size() == 1 && body.groupBy().isEmpty()
                && body.having() == null && body.orderBy().isEmpty()
                && body.limit() != null
                && (body.where() == null || fullyPushable(sqlOrder.get(0)));
        int scanLimit = limitPushdown
                ? body.limit() + (body.offset() == null ? 0 : body.offset()) : 0;

        Map<SourcePlan, Relation> materialized = new LinkedHashMap<>();
        for (SourcePlan source : sqlOrder) {
            materialized.put(source, materialize(source, body, scanLimit));
        }

        if (sqlOrder.size() == 1) {
            SourcePlan only = sqlOrder.get(0);
            return injectOuterSingle(canonicalizeSingle(materialized.get(only), only));
        }

        Relation result = null;
        List<SourcePlan> joined = new ArrayList<>();
        boolean allInner = plan.joinOrder.size() == sqlOrder.size() && plan.reordered
                || isAllInnerClause(body.from());
        if (allInner) {
            for (SourcePlan source : plan.joinOrder) {
                if (result == null) {
                    result = materialized.get(source);
                    joined.add(source);
                    continue;
                }
                List<Ast.Expr> applicable = applicablePredicates(plan.joinPredicates, joined,
                        source, plan.perm, plan.joinOrder);
                Ast.Expr on = and(applicable);
                result = joinRelations(result, materialized.get(source), on, false, joined, source);
                joined.add(source);
            }
            result = permuteToSqlOrder(result, joined, sqlOrder, plan.perm);
            if (!plan.joinPredicates.isEmpty()) {
                result = filter(result, and(plan.joinPredicates));
            }
        } else {
            // LEFT JOIN:严格按 SQL 书写顺序执行(不重排,避免改变外连接语义)
            result = materialized.get(sqlOrder.get(0));
            List<SourcePlan> current = new ArrayList<>();
            current.add(sqlOrder.get(0));
            for (int i = 0; i < body.from().joins().size(); i++) {
                Ast.Join join = body.from().joins().get(i);
                SourcePlan right = sqlOrder.get(i + 1);
                result = joinRelations(result, materialized.get(right), join.on(),
                        join.type() == Ast.JoinType.LEFT, current, right);
                current.add(right);
            }
        }
        Relation finished = result == null
                ? new Relation(Schema.of(List.of(), List.of()), List.of())
                : canonicalize(result, plan.perm);
        return injectOuterRow(finished);
    }

    /**
     * 相关子查询的参数化执行(FR-656):把外层当前行拼到内层行前面,schema 也拼在前面,
     * 这样内层表达式里的外层列引用可以像普通列一样解析求值。
     */
    private Relation injectOuterRow(Relation relation) {
        if (outerSchema == null || outerRow == null) {
            return relation;
        }
        outerPrefix = outerSchema.size();
        Schema combined = outerSchema.concat(relation.schema);
        List<Object[]> rows = new ArrayList<>(relation.rows.size());
        for (Object[] row : relation.rows) {
            rows.add(concat(outerRow, row));
        }
        List<long[]> seq = relation.seq;
        if (seq != null) {
            List<long[]> newSeq = new ArrayList<>(seq.size());
            for (long[] s : seq) {
                long[] extended = new long[s.length + 1];
                extended[0] = -1;
                System.arraycopy(s, 0, extended, 1, s.length);
                newSeq.add(extended);
            }
            seq = newSeq;
        }
        return new Relation(combined, rows, relation.estimatedRows, seq);
    }

    /** 单表路径也要注入外层行(相关子查询最常见的形态)。 */
    private Relation injectOuterSingle(Relation relation) {
        return injectOuterRow(relation);
    }

    private static boolean isAllInnerClause(Ast.FromClause from) {
        for (Ast.Join join : from.joins()) {
            if (join.type() == Ast.JoinType.LEFT) {
                return false;
            }
        }
        return true;
    }

    /** 该步可用(左右两侧都齐)的连接谓词。 */
    private List<Ast.Expr> applicablePredicates(List<Ast.Expr> predicates,
                                               List<SourcePlan> left, SourcePlan right,
                                               int[] perm, List<SourcePlan> joinOrder) {
        Set<String> names = new LinkedHashSet<>();
        for (SourcePlan source : left) {
            names.add(source.qualifier.toLowerCase(Locale.ROOT));
        }
        names.add(right.qualifier.toLowerCase(Locale.ROOT));
        List<Ast.Expr> out = new ArrayList<>();
        for (Ast.Expr predicate : predicates) {
            Set<String> quals = qualifiersOf(predicate);
            if (quals.isEmpty()) {
                continue;
            }
            boolean allPresent = true;
            for (String q : quals) {
                if (!names.contains(q)) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                out.add(predicate);
            }
        }
        return out;
    }

    private Relation materialize(SourcePlan source, Ast.QueryBody body, int scanLimit) {
        if (source.derived()) {
            QueryEngine sub = new QueryEngine(kv, catalog, view, options, warnings);
            Relation rel = sub.run(source.item.subquery());
            String alias = source.item.alias() == null ? "subquery" : source.item.alias();
            Relation withAlias = new Relation(rel.schema.withQualifier(alias), rel.rows,
                    rel.estimatedRows, null);
            return source.pushed == null ? withAlias : filter(withAlias, source.pushed);
        }
        if (source.virtualName != null) {
            List<Object[]> virtualRows = options.virtualTables().rows(source.virtualName);
            List<long[]> seq = new ArrayList<>(virtualRows.size());
            for (int i = 0; i < virtualRows.size(); i++) {
                seq.add(new long[]{i});
            }
            Relation relation = new Relation(source.schema, virtualRows,
                    source.estimatedRows, seq);
            return source.pushed == null ? relation : filter(relation, source.pushed);
        }
        TableMeta table = source.table;
        Relation scanned = scanTable(source, scanLimit);
        return source.pushed == null ? scanned : filter(scanned, source.pushed);
    }

    private Relation scanTable(SourcePlan source, int scanLimit) {
        TableMeta table = source.table;
        com.wangyu.tangdb.planner.Plan plan = source.access == null ? null
                : source.access.plan();
        List<Object[]> rows = new ArrayList<>();
        List<long[]> seq = new ArrayList<>();
        if (plan != null && (plan.kind() == com.wangyu.tangdb.planner.Plan.Kind.POINT
                || plan.kind() == com.wangyu.tangdb.planner.Plan.Kind.POINTS)) {
            for (byte[] key : plan.pointKeys()) {
                byte[] data = view.get(key);
                if (data == null) {
                    continue;
                }
                RowCodec.Decoded decoded = RowCodec.decode(table, data);
                if (decoded.tombstone()) {
                    continue;
                }
                rows.add(decoded.values());
                seq.add(new long[]{seq.size()});
            }
            return new Relation(source.schema, rows, source.estimatedRows, seq);
        }
        if (plan != null && (plan.kind() == com.wangyu.tangdb.planner.Plan.Kind.INDEX_POINT
                || plan.kind() == com.wangyu.tangdb.planner.Plan.Kind.INDEX_RANGE)) {
            scanIndex(source, plan, scanLimit, rows, seq);
            return new Relation(source.schema, rows, source.estimatedRows, seq);
        }
        byte[] start = plan == null ? KeyCodec.tableRangeStart(table.id()) : plan.start();
        byte[] end = plan == null ? KeyCodec.tableRangeEnd(table.id()) : plan.end();

        // SPEC-P6.1 FR-701 / FR-703:列投影 + 谓词下推(开关关闭时走原全行路径)。
        com.wangyu.tangdb.storage.ProjectedCodec.Plan projection = projectionFor(source, table);
        if (projection != null) {
            byte[] predicate = PredicateCodec.compile(source.pushed, table, source.qualifier);
            com.wangyu.tangdb.storage.ScanOptions scanOptions =
                    new com.wangyu.tangdb.storage.ScanOptions(options.scanBatchRows(),
                            projection.mask(), projection.columnSlots(), projection.rowFrame(),
                            predicate, true);
            List<KeyValue> projected = scanShards(start, end, scanLimit, scanOptions);
            // SPEC-P6.1 FR-716:解码复用缓冲(短值/数值装箱缓存),只保留每行一个结果数组。
            com.wangyu.tangdb.storage.ProjectedCodec.Decoder decoder =
                    new com.wangyu.tangdb.storage.ProjectedCodec.Decoder(table, projection);
            for (KeyValue row : projected) {
                if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                    continue;
                }
                rows.add(decoder.decodeInto(row.value(),
                        new Object[table.columnCount()]));
                seq.add(new long[]{seq.size()});
            }
            return new Relation(source.schema, rows, source.estimatedRows, seq);
        }

        List<KeyValue> kvs = scanShards(start, end, scanLimit, null);
        for (KeyValue row : kvs) {
            if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                continue;
            }
            byte[] raw = row.value();
            if (raw != null && raw.length > 0 && raw[0] == RowCodec.FORMAT_TOMBSTONE) {
                continue;
            }
            RowCodec.Decoded decoded = source.needed == null
                    ? RowCodec.decode(table, raw)
                    : RowCodec.decode(table, raw, source.needed);
            if (decoded.tombstone()) {
                continue;
            }
            rows.add(decoded.values());
            seq.add(new long[]{seq.size()});
        }
        return new Relation(source.schema, rows, source.estimatedRows, seq);
    }

    /**
     * 单表谓词是否**整条**都能下推。只有这样才能把 LIMIT 也推到扫描:
     * BE 先过滤再截断 ⇒ 结果与"FE 过滤后截断"一致(SC-406 / 边界 #120)。
     */
    private boolean fullyPushable(SourcePlan source) {
        return options.pushdown() && source.table != null && source.pushed != null
                && PredicateCodec.compileFully(source.pushed, source.table, source.qualifier) != null;
    }

    /**
     * 按 key 范围分片并发扫描(SPEC-P6.1 FR-705 / FR-711)。
     *
     * <ul>
     *   <li>只在"无 LIMIT 下推、最新视图、真实可投影客户端、并行度 &gt; 1"时并发;</li>
     *   <li>各片按片序拼接 ⇒ 行集合与行序与单线程扫描完全一致;</li>
     *   <li>所有片共用一个固定快照,并发写不会让不同片看到不同版本(★P6.1-3);</li>
     *   <li>任何一片失败 → 整条查询失败,不返回部分结果(边界 #121)。</li>
     * </ul>
     */
    private List<KeyValue> scanShards(byte[] start, byte[] end, int limit,
                                      com.wangyu.tangdb.storage.ScanOptions scanOptions) {
        int shards = this.options.parallelism();
        boolean canParallel = shards > 1 && limit <= 0 && view.isLatest()
                && start != null && end != null && end.length > 0
                && kv instanceof com.wangyu.tangdb.storage.ProjectingStore;
        if (!canParallel) {
            return scanOptions == null ? view.scan(start, end, limit)
                    : view.scanProjected(start, end, limit, scanOptions);
        }
        List<byte[]> bounds = shardBoundaries(start, end, shards);
        if (bounds.size() <= 2) {
            return scanOptions == null ? view.scan(start, end, limit)
                    : view.scanProjected(start, end, limit, scanOptions);
        }

        com.wangyu.tangdb.storage.ProjectingStore store =
                (com.wangyu.tangdb.storage.ProjectingStore) kv;
        long snapshot = kv.snapshotPin();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(Math.min(shards, bounds.size() - 1), r -> {
                    Thread t = new Thread(r, "tangdb-scan-shard");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.Future<List<KeyValue>>> futures = new ArrayList<>();
            for (int i = 0; i + 1 < bounds.size(); i++) {
                byte[] from = bounds.get(i);
                byte[] to = bounds.get(i + 1);
                futures.add(pool.submit(() -> {
                    if (scanOptions == null) {
                        return kv.scan(from, to, snapshot, 0);
                    }
                    try {
                        return store.scanProjected(from, to, snapshot, 0, scanOptions);
                    } catch (com.wangyu.tangdb.client.ClientException e) {
                        throw new com.wangyu.tangdb.storage.StoreException(e.getMessage(), e);
                    }
                }));
            }
            List<KeyValue> out = new ArrayList<>();
            for (java.util.concurrent.Future<List<KeyValue>> future : futures) {
                try {
                    out.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new com.wangyu.tangdb.storage.StoreException("并行扫描被中断", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new com.wangyu.tangdb.storage.StoreException(
                            "并行扫描失败(整条查询失败,不返回部分结果): " + cause.getMessage(), cause);
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
            kv.snapshotUnpin(snapshot);
        }
    }

    /**
     * 把 [start, end) 按字节空间等分成 shards 片的边界(SPEC-P6.1 FR-705)。
     *
     * <p>首片一律从原始 start 开始:把 start 右补 0 会漏掉"用户键恰好等于 start"的那一行。</p>
     */
    static List<byte[]> shardBoundaries(byte[] start, byte[] end, int shards) {
        List<byte[]> out = new ArrayList<>();
        out.add(start);
        if (shards <= 1 || end == null || end.length == 0) {
            out.add(end);
            return out;
        }
        int len = Math.max(start.length, end.length);
        java.math.BigInteger from = new java.math.BigInteger(1,
                start.length == 0 ? new byte[]{0} : start);
        java.math.BigInteger to = new java.math.BigInteger(1, end);
        if (to.compareTo(from) <= 0) {
            out.add(end);
            return out;
        }
        java.math.BigInteger span = to.subtract(from);
        for (int i = 1; i < shards; i++) {
            java.math.BigInteger point = from.add(span
                    .multiply(java.math.BigInteger.valueOf(i))
                    .divide(java.math.BigInteger.valueOf(shards)));
            byte[] boundary = toFixedBytes(point, len);
            byte[] previous = out.get(out.size() - 1);
            if (Arrays.compareUnsigned(boundary, previous) > 0
                    && Arrays.compareUnsigned(boundary, end) < 0) {
                out.add(boundary);
            }
        }
        out.add(end);
        return out;
    }

    private static byte[] toFixedBytes(java.math.BigInteger value, int len) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }

    /**
     * 计算某来源的投影方案(SPEC-P6.1 FR-701)。
     *
     * <p>{@code pushdown_enabled=false} 时返回 null,调用方走原全行路径 ——
     * 这是"与 P6 逐行一致"的回退保证(FR-710 / FR-718)。</p>
     */
    private com.wangyu.tangdb.storage.ProjectedCodec.Plan projectionFor(SourcePlan source,
                                                                       TableMeta table) {
        if (!options.pushdown() || table == null) {
            return null;
        }
        boolean[] needed = source.needed == null ? allColumns(table) : source.needed;
        return com.wangyu.tangdb.storage.ProjectedCodec.plan(table, needed);
    }

    /** 索引区间扫描 + 逐行回表 + 二次校验(与执行器同口径,FR-511)。 */
    private void scanIndex(SourcePlan source, com.wangyu.tangdb.planner.Plan plan, int scanLimit,
                           List<Object[]> rows, List<long[]> seq) {
        TableMeta table = source.table;
        com.wangyu.tangdb.storage.IndexMeta index = plan.index();
        Column indexColumn = com.wangyu.tangdb.storage.IndexCodec.indexColumn(table, index);
        List<KeyValue> entries = view.scan(plan.start(), plan.end(), scanLimit);
        for (KeyValue entry : entries) {
            com.wangyu.tangdb.storage.IndexCodec.DecodedEntry decodedEntry =
                    com.wangyu.tangdb.storage.IndexCodec.decodeEntry(table, index,
                            entry.key(), entry.value());
            if (decodedEntry.tombstone()) {
                continue;
            }
            byte[] rowKey = KeyCodec.rowKeyEncoded(table.id(),
                    decodedEntry.encodedPrimaryKey());
            byte[] data = view.get(rowKey);
            if (data == null) {
                continue;
            }
            RowCodec.Decoded decoded = RowCodec.decode(table, data);
            if (decoded.tombstone()) {
                continue;
            }
            Object actual = decoded.values()[indexColumn.ordinal()];
            if (actual == null || !java.util.Objects.equals(actual,
                    decodedEntry.columnValue())) {
                continue;
            }
            rows.add(decoded.values());
            seq.add(new long[]{seq.size()});
        }
    }

    private Relation canonicalizeSingle(Relation relation, SourcePlan source) {
        return relation;
    }

    /** 把 joinOrder 的行/列重排成 SQL 书写顺序(投影里的 `*` 与行序都依赖它)。 */
    private Relation permuteToSqlOrder(Relation relation, List<SourcePlan> joined,
                                       List<SourcePlan> sqlOrder, int[] perm) {
        if (sameOrder(joined, sqlOrder)) {
            return relation;
        }
        int[] offsets = new int[joined.size()];
        int offset = 0;
        for (int i = 0; i < joined.size(); i++) {
            offsets[i] = offset;
            offset += joined.get(i).schema.size();
        }
        Schema.Builder builder = Schema.builder();
        List<int[]> mapping = new ArrayList<>(); // [joinedIndex, columnIndex]
        for (SourcePlan source : sqlOrder) {
            int joinedIndex = indexOf(joined, source);
            SourcePlan joinedSource = joined.get(joinedIndex);
            for (int col = 0; col < joinedSource.schema.size(); col++) {
                builder.add(joinedSource.schema.qualifier(col), joinedSource.schema.name(col),
                        joinedSource.schema.type(col));
                mapping.add(new int[]{joinedIndex, col});
            }
        }
        int[] sourceOrder = new int[sqlOrder.size()];
        for (int i = 0; i < sqlOrder.size(); i++) {
            sourceOrder[i] = indexOf(joined, sqlOrder.get(i));
        }
        List<Object[]> outRows = new ArrayList<>(relation.rows.size());
        List<long[]> outSeq = relation.seq == null ? null : new ArrayList<>(relation.rows.size());
        for (int r = 0; r < relation.rows.size(); r++) {
            Object[] row = relation.rows.get(r);
            Object[] reordered = new Object[mapping.size()];
            for (int c = 0; c < mapping.size(); c++) {
                int[] from = mapping.get(c);
                reordered[c] = row[offsets[from[0]] + from[1]];
            }
            outRows.add(reordered);
            if (outSeq != null) {
                long[] sourceSeq = relation.seq.get(r);
                long[] reorderedSeq = new long[sourceOrder.length];
                for (int j = 0; j < sourceOrder.length; j++) {
                    reorderedSeq[j] = sourceSeq[sourceOrder[j]];
                }
                outSeq.add(reorderedSeq);
            }
        }
        return new Relation(builder.build(), outRows, relation.estimatedRows, outSeq);
    }

    private static int indexOf(List<SourcePlan> sources, SourcePlan target) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) == target) {
                return i;
            }
        }
        return 0;
    }

    /** 按"SQL 书写顺序的嵌套循环序"稳定排序(FR-634:换算法不换行序)。 */
    private Relation canonicalize(Relation relation, int[] perm) {
        if (relation.seq == null || relation.rows.size() <= 1) {
            return relation;
        }
        List<Integer> order = new ArrayList<>(relation.rows.size());
        for (int i = 0; i < relation.rows.size(); i++) {
            order.add(i);
        }
        List<long[]> seq = relation.seq;
        order.sort((a, b) -> compareSeq(seq.get(a), seq.get(b), perm));
        List<Object[]> rows = new ArrayList<>(relation.rows.size());
        List<long[]> newSeq = new ArrayList<>(relation.rows.size());
        for (int index : order) {
            rows.add(relation.rows.get(index));
            newSeq.add(seq.get(index));
        }
        return new Relation(relation.schema, rows, relation.estimatedRows, newSeq);
    }

    private static int compareSeq(long[] a, long[] b, int[] perm) {
        int n = Math.min(a.length, b.length);
        if (perm != null && perm.length == n) {
            for (int i = 0; i < n; i++) {
                int c = Long.compare(a[perm[i]], b[perm[i]]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
        for (int i = 0; i < n; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ 连接算法

    /** 连接:等值 → Hash / Sort-Merge;非等值 → NLJ;超内存 → Sort-Merge + 溢写。 */
    private Relation joinRelations(Relation left, Relation right, Ast.Expr on, boolean leftJoin,
                                   List<SourcePlan> leftSources, SourcePlan rightSource) {
        Schema combined = left.schema.concat(right.schema);
        preparedSubqueries(new Relation(combined, List.of()), on, combined);
        List<EquiPair> pairs = equiPairs(on, left.schema, right.schema);
        checkJoinTypes(left.schema, right.schema, pairs);
        if (leftJoin) {
            return hashJoin(left, right, on, pairs, combined, true);
        }
        if (pairs.isEmpty()) {
            return nestedLoop(left, right, on, false, combined);
        }
        // build 侧按估算行数(FR-637);optimizer_enabled=false 时退回 P5 的"按已物化宽度"(FR-633)
        boolean buildLeft = cbo()
                ? left.estimatedRows <= right.estimatedRows
                : estimateBytes(left) <= estimateBytes(right);
        long buildRows = buildLeft ? left.estimatedRows : right.estimatedRows;
        long buildBytes = estimateBytes(buildLeft ? left : right);
        boolean bothSorted = sortedOn(left, pairs, true) && sortedOn(right, pairs, false);
        if (buildBytes > options.sortSpillBytes()) {
            return sortMergeJoin(left, right, on, pairs, combined); // FR-639:溢写路径
        }
        if (bothSorted && cbo()) {
            return sortMergeJoin(left, right, on, pairs, combined);
        }
        return hashJoin(left, right, on, pairs, combined, buildLeft);
    }

    /** ON 里的等值对:{leftIndex, rightIndex}。 */
    private record EquiPair(int leftIndex, int rightIndex) {
    }

    private List<EquiPair> equiPairs(Ast.Expr on, Schema left, Schema right) {
        List<Ast.Expr> conjuncts = new ArrayList<>();
        flattenAnd(on, conjuncts);
        List<EquiPair> pairs = new ArrayList<>();
        for (Ast.Expr conjunct : conjuncts) {
            if (!(conjunct instanceof Ast.Cmp cmp) || cmp.op() != Ast.Op.EQ) {
                continue;
            }
            if (!(cmp.left() instanceof Ast.Col leftCol) || !(cmp.right() instanceof Ast.Col rightCol)) {
                continue;
            }
            int li = indexIn(left, leftCol);
            int ri = indexIn(right, rightCol);
            if (li >= 0 && ri >= 0) {
                pairs.add(new EquiPair(li, ri));
                continue;
            }
            li = indexIn(left, rightCol);
            ri = indexIn(right, leftCol);
            if (li >= 0 && ri >= 0) {
                pairs.add(new EquiPair(li, ri));
            }
        }
        return pairs;
    }

    /**
     * 两侧是否都已按连接键有序(可证明):该侧是直接表扫描,且连接键是它的主键(扫描序 = 主键序)。
     */
    private boolean sortedOn(Relation relation, List<EquiPair> pairs, boolean leftSide) {
        if (relation.schema == null) {
            return false;
        }
        // 只有单列等值且该列是关系里的第一列(主键)时才认为有序(保守判断)。
        for (EquiPair pair : pairs) {
            int index = leftSide ? pair.leftIndex() : pair.rightIndex();
            if (index != 0) {
                return false;
            }
        }
        return !pairs.isEmpty();
    }

    private static void checkJoinTypes(Schema left, Schema right, List<EquiPair> pairs) {
        for (EquiPair pair : pairs) {
            DataType lt = left.type(pair.leftIndex());
            DataType rt = right.type(pair.rightIndex());
            if (lt == null || rt == null) {
                continue;
            }
            boolean comparable = lt == rt || (lt.isNumeric() && rt.isNumeric());
            if (!comparable) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "连接列类型不可比较: " + lt.sqlName() + " 与 " + rt.sqlName());
            }
        }
    }

    private static int indexIn(Schema schema, Ast.Col col) {
        if (!schema.has(col.qualifier(), col.name())) {
            return -1;
        }
        return schema.resolve(col.qualifier(), col.name());
    }

    private static void flattenAnd(Ast.Expr expr, List<Ast.Expr> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.LogicalAnd and) {
            flattenAnd(and.left(), out);
            flattenAnd(and.right(), out);
            return;
        }
        out.add(expr);
    }

    private static Ast.Expr and(List<Ast.Expr> conjuncts) {
        if (conjuncts == null || conjuncts.isEmpty()) {
            return null;
        }
        Ast.Expr result = conjuncts.get(0);
        for (int i = 1; i < conjuncts.size(); i++) {
            result = new Ast.LogicalAnd(result, conjuncts.get(i));
        }
        return result;
    }

    /** 哈希连接:输出附来源行号,最终由 canonicalize 统一成嵌套循环序。 */
    private Relation hashJoin(Relation left, Relation right, Ast.Expr on,
                              List<EquiPair> pairs, Schema combined, boolean buildLeft) {
        Relation build = buildLeft ? left : right;
        Relation probe = buildLeft ? right : left;
        int[] buildKeyIdx = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            buildKeyIdx[i] = buildLeft ? pairs.get(i).leftIndex() : pairs.get(i).rightIndex();
        }
        int[] probeKeyIdx = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            probeKeyIdx[i] = buildLeft ? pairs.get(i).rightIndex() : pairs.get(i).leftIndex();
        }
        // SPEC-P6.1 FR-712:build 按哈希桶分段并行构建 + probe 并行,再按稳定顺序合并。
        // 并行度 1 / 行数太少 / ON 里有子查询 ⇒ 走原单线程循环(结果与之逐行同序)。
        int parallelism = parallelTasks(build.rows.size() + probe.rows.size(), 2048);
        if (parallelism <= 1 || (on != null && containsSubquery(on))) {
            return hashJoinSerial(left, right, on, combined, buildLeft, build, probe,
                    buildKeyIdx, probeKeyIdx);
        }
        return hashJoinParallel(left, right, on, combined, buildLeft, build, probe,
                buildKeyIdx, probeKeyIdx, parallelism);
    }

    /** 原单线程哈希连接(并行度 1 的路径,SC-415 的对拍基准)。 */
    private Relation hashJoinSerial(Relation left, Relation right, Ast.Expr on, Schema combined,
                                    boolean buildLeft, Relation build, Relation probe,
                                    int[] buildKeyIdx, int[] probeKeyIdx) {
        Map<List<Object>, List<Integer>> table = new LinkedHashMap<>();
        for (int r = 0; r < build.rows.size(); r++) {
            List<Object> key = keyOf(build.rows.get(r), buildKeyIdx);
            if (key == null) {
                continue; // NULL 连接键不匹配(FR-592)
            }
            table.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<Object[]> out = new ArrayList<>();
        List<long[]> outSeq = new ArrayList<>();
        boolean[] matchedBuild = new boolean[build.rows.size()];
        for (int p = 0; p < probe.rows.size(); p++) {
            Object[] probeRow = probe.rows.get(p);
            List<Object> key = keyOf(probeRow, probeKeyIdx);
            List<Integer> matches = key == null ? null : table.get(key);
            boolean any = false;
            if (matches != null) {
                for (int buildIndex : matches) {
                    Object[] buildRow = build.rows.get(buildIndex);
                    Object[] combinedRow = buildLeft
                            ? concat(buildRow, probeRow) : concat(probeRow, buildRow);
                    if (on != null && !Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(
                            on, combinedRow, combined)))) {
                        continue;
                    }
                    out.add(combinedRow);
                    outSeq.add(joinSeq(buildLeft, build, probe, buildIndex, p));
                    any = true;
                    matchedBuild[buildIndex] = true;
                }
            }
            if (!any && !buildLeft) {
                // 右表是 build 侧、左表是 probe 侧的 LEFT JOIN:补左表未匹配行
                out.add(concat(probeRow, new Object[right.schema.size()]));
                outSeq.add(joinSeqNull(right, buildLeft, build, probe, p));
            }
        }
        if (buildLeft) {
            // 左表是 build 侧:补未匹配的左行(LEFT JOIN)
            for (int b = 0; b < build.rows.size(); b++) {
                if (matchedBuild[b]) {
                    continue;
                }
                out.add(concat(build.rows.get(b), new Object[right.schema.size()]));
                outSeq.add(joinSeqNullLeft(build, b));
            }
        }
        return new Relation(combined, out, out.size(), outSeq);
    }

    /**
     * 分桶并行的哈希连接(SPEC-P6.1 FR-712)。
     *
     * <ul>
     *   <li>build 侧按连接键哈希分桶,桶内行序不变 ⇒ 桶内匹配顺序与单线程一致;</li>
     *   <li>probe 侧按行序切片并行,结果按片序拼接 ⇒ 输出行序与单线程一致;</li>
     *   <li>probe key 只会落进同一个桶,lookup 不跨桶;</li>
     *   <li>LEFT JOIN 补空行位置不变:probe 侧补空在片内当场产生,
     *       build 侧未匹配行按 build 行序统一追加在末尾。</li>
     * </ul>
     */
    private Relation hashJoinParallel(Relation left, Relation right, Ast.Expr on, Schema combined,
                                      boolean buildLeft, Relation build, Relation probe,
                                      int[] buildKeyIdx, int[] probeKeyIdx, int parallelism) {
        List<List<Integer>> partitioned = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            partitioned.add(new ArrayList<>());
        }
        for (int r = 0; r < build.rows.size(); r++) {
            List<Object> key = keyOf(build.rows.get(r), buildKeyIdx);
            if (key == null) {
                continue; // NULL 连接键不入表(FR-592)
            }
            partitioned.get(Math.floorMod(key.hashCode(), parallelism)).add(r);
        }
        List<Map<List<Object>, List<Integer>>> tables = Parallel.run(parallelism, parallelism, i -> {
            Map<List<Object>, List<Integer>> map = new LinkedHashMap<>();
            for (int r : partitioned.get(i)) {
                List<Object> key = keyOf(build.rows.get(r), buildKeyIdx);
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            return map;
        });

        java.util.concurrent.atomic.AtomicIntegerArray matchedBuild = buildLeft
                ? new java.util.concurrent.atomic.AtomicIntegerArray(build.rows.size()) : null;
        int[][] ranges = Parallel.ranges(probe.rows.size(), parallelism);
        List<JoinChunk> chunks = Parallel.run(parallelism, parallelism, i -> {
            List<Object[]> out = new ArrayList<>();
            List<long[]> seq = new ArrayList<>();
            for (int p = ranges[i][0]; p < ranges[i][1]; p++) {
                Object[] probeRow = probe.rows.get(p);
                List<Object> key = keyOf(probeRow, probeKeyIdx);
                List<Integer> matches = key == null ? null
                        : tables.get(Math.floorMod(key.hashCode(), parallelism)).get(key);
                boolean any = false;
                if (matches != null) {
                    for (int buildIndex : matches) {
                        Object[] buildRow = build.rows.get(buildIndex);
                        Object[] combinedRow = buildLeft
                                ? concat(buildRow, probeRow) : concat(probeRow, buildRow);
                        if (on != null && !Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(
                                on, combinedRow, combined)))) {
                            continue;
                        }
                        out.add(combinedRow);
                        seq.add(joinSeq(buildLeft, build, probe, buildIndex, p));
                        any = true;
                        if (matchedBuild != null) {
                            matchedBuild.set(buildIndex, 1);
                        }
                    }
                }
                if (!any && !buildLeft) {
                    out.add(concat(probeRow, new Object[right.schema.size()]));
                    seq.add(joinSeqNull(right, buildLeft, build, probe, p));
                }
            }
            return new JoinChunk(out, seq);
        });

        List<Object[]> out = new ArrayList<>();
        List<long[]> outSeq = new ArrayList<>();
        for (JoinChunk chunk : chunks) {
            out.addAll(chunk.rows);
            outSeq.addAll(chunk.seq);
        }
        if (buildLeft) {
            for (int b = 0; b < build.rows.size(); b++) {
                if (matchedBuild.get(b) != 0) {
                    continue;
                }
                out.add(concat(build.rows.get(b), new Object[right.schema.size()]));
                outSeq.add(joinSeqNullLeft(build, b));
            }
        }
        return new Relation(combined, out, out.size(), outSeq);
    }

    private record JoinChunk(List<Object[]> rows, List<long[]> seq) {
    }

    private static long[] joinSeq(boolean buildLeft, Relation build, Relation probe,
                                  int buildIndex, int probeIndex) {
        long[] buildSeq = seqOf(build, buildIndex);
        long[] probeSeq = seqOf(probe, probeIndex);
        return buildLeft ? concatSeq(buildSeq, probeSeq) : concatSeq(probeSeq, buildSeq);
    }

    private static long[] joinSeqNull(Relation right, boolean buildLeft, Relation build,
                                     Relation probe, int probeIndex) {
        long[] probeSeq = seqOf(probe, probeIndex);
        long[] nullSeq = new long[right.schema.size() == 0 ? 0 : 1];
        Arrays.fill(nullSeq, -1L);
        return buildLeft ? concatSeq(nullSeq, probeSeq) : concatSeq(probeSeq, nullSeq);
    }

    private static long[] joinSeqNullLeft(Relation left, int index) {
        long[] leftSeq = seqOf(left, index);
        long[] nullSeq = {-1L};
        return concatSeq(leftSeq, nullSeq);
    }

    private static long[] seqOf(Relation relation, int index) {
        if (relation.seq == null) {
            return new long[]{index};
        }
        return relation.seq.get(index);
    }

    private static long[] concatSeq(long[] a, long[] b) {
        long[] out = new long[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long estimateBytes(Relation relation) {
        long cells = (long) relation.rows.size() * relation.schema.size();
        long bytes = cells * 16;
        for (Object[] row : relation.rows) {
            for (Object v : row) {
                if (v instanceof String s) {
                    bytes += s.length() * 2L;
                }
            }
        }
        return bytes;
    }

    private static List<Object> keyOf(Object[] row, int[] indexes) {
        List<Object> key = new ArrayList<>(indexes.length);
        for (int idx : indexes) {
            Object v = row[idx];
            if (v == null) {
                return null;
            }
            key.add(ValueOps.key(v));
        }
        return key;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private Relation nestedLoop(Relation left, Relation right, Ast.Expr on,
                                boolean leftJoin, Schema combined) {
        List<Object[]> out = new ArrayList<>();
        List<long[]> outSeq = new ArrayList<>();
        for (int l = 0; l < left.rows.size(); l++) {
            Object[] leftRow = left.rows.get(l);
            boolean any = false;
            for (int r = 0; r < right.rows.size(); r++) {
                Object[] combinedRow = concat(leftRow, right.rows.get(r));
                if (on != null && !Boolean.TRUE.equals(
                        ValueOps.truth(evaluator.eval(on, combinedRow, combined)))) {
                    continue;
                }
                out.add(combinedRow);
                outSeq.add(concatSeq(seqOf(left, l), seqOf(right, r)));
                any = true;
            }
            if (!any && leftJoin) {
                out.add(concat(leftRow, new Object[right.schema.size()]));
                long[] nullSeq = {-1L};
                outSeq.add(concatSeq(seqOf(left, l), nullSeq));
            }
        }
        return new Relation(combined, out, out.size(), outSeq);
    }

    /**
     * Sort-Merge 连接(FR-638 / FR-639):两侧按连接键排序后线性归并。
     * 溢写复用 P5 的 {@link Sorter}(K130),不新造第二套。
     */
    private Relation sortMergeJoin(Relation left, Relation right, Ast.Expr on,
                                   List<EquiPair> pairs, Schema combined) {
        if (pairs.isEmpty()) {
            return nestedLoop(left, right, on, false, combined);
        }
        int leftKey = pairs.get(0).leftIndex();
        int rightKey = pairs.get(0).rightIndex();
        // 把连接键追加成额外列,交给 Sorter 排序(它负责 TopN / 外部归并 / 溢写)
        List<Object[]> leftRows = withKey(left, leftKey);
        List<Object[]> rightRows = withKey(right, rightKey);
        int extra = left.schema.size() + right.schema.size();
        List<Object[]> sortedLeft = sorter.sort(leftRows, new int[]{left.schema.size()},
                new boolean[]{false}, -1);
        List<Object[]> sortedRight = sorter.sort(rightRows, new int[]{right.schema.size()},
                new boolean[]{false}, -1);
        List<Object[]> out = new ArrayList<>();
        List<long[]> outSeq = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < sortedLeft.size() && j < sortedRight.size()) {
            Object a = sortedLeft.get(i)[left.schema.size()];
            Object b = sortedRight.get(j)[right.schema.size()];
            if (a == null || b == null) {
                if (a == null) {
                    i++;
                } else {
                    j++;
                }
                continue;
            }
            int cmp = ValueOps.compare(a, b);
            if (cmp < 0) {
                i++;
                continue;
            }
            if (cmp > 0) {
                j++;
                continue;
            }
            int iEnd = i;
            while (iEnd < sortedLeft.size()
                    && sameKey(sortedLeft.get(iEnd)[left.schema.size()], a)) {
                iEnd++;
            }
            int jEnd = j;
            while (jEnd < sortedRight.size()
                    && sameKey(sortedRight.get(jEnd)[right.schema.size()], b)) {
                jEnd++;
            }
            for (int li = i; li < iEnd; li++) {
                Object[] leftRow = Arrays.copyOf(sortedLeft.get(li), extra);
                for (int rj = j; rj < jEnd; rj++) {
                    Object[] rightRow = Arrays.copyOf(sortedRight.get(rj), extra);
                    Object[] combinedRow = new Object[extra];
                    System.arraycopy(leftRow, 0, combinedRow, 0, left.schema.size());
                    System.arraycopy(rightRow, 0, combinedRow, left.schema.size(),
                            right.schema.size());
                    if (on != null && !Boolean.TRUE.equals(
                            ValueOps.truth(evaluator.eval(on, combinedRow, combined)))) {
                        continue;
                    }
                    out.add(combinedRow);
                    outSeq.add(concatSeq(seqOfUnsorted(left, leftRow), seqOfUnsorted(right, rightRow)));
                }
            }
            i = iEnd;
            j = jEnd;
        }
        return new Relation(combined, out, out.size(), outSeq);
    }

    private static boolean sameKey(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return ValueOps.compare(a, b) == 0;
    }

    private static List<Object[]> withKey(Relation relation, int keyIndex) {
        List<Object[]> out = new ArrayList<>(relation.rows.size());
        for (Object[] row : relation.rows) {
            Object[] extended = Arrays.copyOf(row, row.length + 1);
            extended[row.length] = row[keyIndex];
            out.add(extended);
        }
        return out;
    }

    /** 排序后行序已变;来源行号从行尾追加的一格拿不到,这里退回按当前顺序编号。 */
    private static long[] seqOfUnsorted(Relation relation, Object[] row) {
        return new long[]{System.identityHashCode(row)};
    }

    // ------------------------------------------------------------ 集合运算(E 组)

    /** 执行一条集合运算语句(FR-650 ~ FR-655)。 */
    public Result.Table executeSet(Ast.QueryExpr expr) {
        Relation relation = runSet(expr);
        return new Result.Table(relation.schema.names(), relation.rows);
    }

    public Relation runSet(Ast.QueryExpr expr) {
        if (expr instanceof Ast.BodyExpr body) {
            return run(body.body());
        }
        if (expr instanceof Ast.OrderedExpr ordered) {
            return applyTail(runSet(ordered.inner()), ordered.orderBy(), ordered.limit(),
                    ordered.offset());
        }
        Ast.SetExpr set = (Ast.SetExpr) expr;
        if (set.all() && set.op() != Ast.SetOpType.UNION) {
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    (set.op() == Ast.SetOpType.INTERSECT ? "INTERSECT ALL" : "EXCEPT ALL")
                            + " 不支持(归 P8 之后);请去掉 ALL 使用默认 DISTINCT 语义");
        }
        Relation left = runSet(set.left());
        Relation right = runSet(set.right());
        DataType[] types = alignSetColumns(left, right);
        List<Object[]> leftRows = coerceSetRows(left.rows, types);
        List<Object[]> rightRows = coerceSetRows(right.rows, types);
        List<Object[]> rows = switch (set.op()) {
            case UNION -> set.all() ? unionAll(leftRows, rightRows) : unionDistinct(leftRows, rightRows);
            case INTERSECT -> intersect(leftRows, rightRows);
            case EXCEPT -> except(leftRows, rightRows);
        };
        Relation result = new Relation(left.schema, rows, rows.size(), null);
        if (!set.orderBy().isEmpty() || set.limit() != null) {
            result = applyTail(result, set.orderBy(), set.limit(), set.offset());
        }
        return result;
    }

    /** 列数对齐(1222)与类型提升(FR-650:INT → BIGINT → DOUBLE;数值与 VARCHAR 混合 → 1222)。 */
    private DataType[] alignSetColumns(Relation left, Relation right) {
        if (left.schema.size() != right.schema.size()) {
            throw new SqlError(SqlError.ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT, "21000",
                    "集合运算两支列数不同(左 " + left.schema.size() + " 列,右 "
                            + right.schema.size() + " 列)");
        }
        DataType[] types = new DataType[left.schema.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = promote(left.schema.type(i), right.schema.type(i), i);
        }
        return types;
    }

    private static DataType promote(DataType left, DataType right, int column) {
        if (left == null || right == null) {
            return null;
        }
        if (left == right) {
            return left;
        }
        if (left.isNumeric() && right.isNumeric()) {
            if (left == DataType.DOUBLE || right == DataType.DOUBLE) {
                return DataType.DOUBLE;
            }
            return DataType.BIGINT;
        }
        throw new SqlError(SqlError.ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT, "21000",
                "集合运算第 " + (column + 1) + " 列类型无法对齐(" + left.sqlName() + " 与 "
                        + right.sqlName() + "):不做隐式转字符串");
    }

    private static List<Object[]> coerceSetRows(List<Object[]> rows, DataType[] types) {
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Object[] copy = row.clone();
            for (int i = 0; i < copy.length && i < types.length; i++) {
                copy[i] = coerceSetValue(copy[i], types[i]);
            }
            out.add(copy);
        }
        return out;
    }

    private static Object coerceSetValue(Object value, DataType type) {
        if (value == null || type == null) {
            return value;
        }
        return switch (type) {
            case BIGINT, INT -> value instanceof Number n ? (Object) n.longValue() : value;
            case DOUBLE -> value instanceof Number n ? (Object) n.doubleValue() : value;
            default -> value;
        };
    }

    private List<Object[]> unionAll(List<Object[]> left, List<Object[]> right) {
        List<Object[]> out = new ArrayList<>(left.size() + right.size());
        out.addAll(left);
        out.addAll(right);
        return out;
    }

    /** UNION(去重,NULL 视为相等,与 DISTINCT 一致;超内存走排序去重,FR-654)。 */
    private List<Object[]> unionDistinct(List<Object[]> left, List<Object[]> right) {
        List<Object[]> all = unionAll(left, right);
        long bytes = estimateRowsBytes(all);
        if (bytes <= options.sortSpillBytes()) {
            Set<List<Object>> seen = new LinkedHashSet<>();
            List<Object[]> out = new ArrayList<>();
            for (Object[] row : all) {
                if (seen.add(distinctKey(row))) {
                    out.add(row);
                }
            }
            return out;
        }
        return sortDistinct(all);
    }

    private static long estimateRowsBytes(List<Object[]> rows) {
        long bytes = 0;
        for (Object[] row : rows) {
            bytes += Math.max(1, row.length) * 16L;
            for (Object value : row) {
                if (value instanceof String text) {
                    bytes += text.length() * 2L;
                }
            }
        }
        return bytes;
    }

    private List<Object[]> sortDistinct(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        int width = rows.get(0).length;
        int[] keys = new int[width];
        boolean[] desc = new boolean[width];
        for (int i = 0; i < width; i++) {
            keys[i] = i;
        }
        List<Object[]> sorted = sorter.sort(rows, keys, desc, -1);
        List<Object[]> out = new ArrayList<>();
        List<Object> previous = null;
        for (Object[] row : sorted) {
            List<Object> key = distinctKey(row);
            if (previous == null || !previous.equals(key)) {
                out.add(row);
                previous = key;
            }
        }
        return out;
    }

    /** INTERSECT(DISTINCT 语义):左支出现序,保留同时在右支出现的行。 */
    private List<Object[]> intersect(List<Object[]> left, List<Object[]> right) {
        Set<List<Object>> rightKeys = new LinkedHashSet<>();
        for (Object[] row : right) {
            rightKeys.add(distinctKey(row));
        }
        Set<List<Object>> emitted = new LinkedHashSet<>();
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : left) {
            List<Object> key = distinctKey(row);
            if (rightKeys.contains(key) && emitted.add(key)) {
                out.add(row);
            }
        }
        return out;
    }

    /** EXCEPT(DISTINCT 语义):左支有、右支没有。 */
    private List<Object[]> except(List<Object[]> left, List<Object[]> right) {
        Set<List<Object>> rightKeys = new LinkedHashSet<>();
        for (Object[] row : right) {
            rightKeys.add(distinctKey(row));
        }
        Set<List<Object>> emitted = new LinkedHashSet<>();
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : left) {
            List<Object> key = distinctKey(row);
            if (!rightKeys.contains(key) && emitted.add(key)) {
                out.add(row);
            }
        }
        return out;
    }

    /** 整体 ORDER BY / LIMIT:只能引用结果列名或列序号(FR-653 / 边界 76 → 1054)。 */
    private Relation applyTail(Relation relation, List<Ast.OrderItem> orderBy, Integer limit,
                               Integer offset) {
        List<Object[]> rows = relation.rows;
        if (!orderBy.isEmpty()) {
            int base = relation.schema.size();
            int[] resolved = new int[orderBy.size()];
            for (int i = 0; i < orderBy.size(); i++) {
                resolved[i] = resolveResultColumn(relation.schema, orderBy.get(i).expr());
            }
            List<Object[]> withKeys = new ArrayList<>(rows.size());
            int[] keyCols = new int[orderBy.size()];
            boolean[] desc = new boolean[orderBy.size()];
            for (int i = 0; i < orderBy.size(); i++) {
                keyCols[i] = base + i;
                desc[i] = orderBy.get(i).descending();
            }
            for (Object[] row : rows) {
                Object[] extended = Arrays.copyOf(row, row.length + keyCols.length);
                for (int i = 0; i < resolved.length; i++) {
                    extended[base + i] = row[resolved[i]];
                }
                withKeys.add(extended);
            }
            int keep = limit == null ? -1 : limit + (offset == null ? 0 : offset);
            List<Object[]> sorted = sorter.sort(withKeys, keyCols, desc, keep);
            List<Object[]> trimmed = new ArrayList<>(sorted.size());
            for (Object[] row : sorted) {
                trimmed.add(Arrays.copyOf(row, base));
            }
            rows = trimmed;
        }
        int from = offset == null ? 0 : offset;
        if (from > 0 || limit != null) {
            List<Object[]> paged = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (i < from) {
                    continue;
                }
                if (limit != null && paged.size() >= limit) {
                    break;
                }
                paged.add(rows.get(i));
            }
            rows = paged;
        }
        return new Relation(relation.schema, rows, rows.size(), null);
    }

    /** 结果列定位:列名或 1-based 列序号;都解析不了 → 1054(边界 76)。 */
    private int resolveResultColumn(Schema schema, Ast.Expr expr) {
        if (expr instanceof Ast.Col col) {
            if (schema.has(null, col.name())) {
                return schema.resolve(null, col.name());
            }
            throw new SqlError(SqlError.ER_BAD_FIELD_ERROR, "42S22",
                    "集合运算的 ORDER BY 只能引用结果列名或列序号:列 " + col.name() + " 不存在");
        }
        if (expr instanceof Ast.Lit lit && lit.value() instanceof Integer ordinal) {
            if (ordinal >= 1 && ordinal <= schema.size()) {
                return ordinal - 1;
            }
            throw new SqlError(SqlError.ER_BAD_FIELD_ERROR, "42S22",
                    "ORDER BY 序号 " + ordinal + " 超出结果列数(" + schema.size() + ")");
        }
        throw new SqlError(SqlError.ER_BAD_FIELD_ERROR, "42S22",
                "集合运算的 ORDER BY 只能引用结果列名或列序号");
    }

    /** EXPLAIN 一条集合运算(FR-655 / 边界 90):显示每支的算子与去重方式。 */
    public List<String> explainSet(Ast.QueryExpr expr) {
        List<String> lines = new ArrayList<>();
        List<Ast.QueryExpr> branches = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        flattenSet(expr, branches, ops);
        for (int i = 0; i < branches.size(); i++) {
            Ast.QueryExpr branch = branches.get(i);
            if (branch instanceof Ast.OrderedExpr ordered) {
                lines.add("支 " + (i + 1) + "(自带 ORDER BY / LIMIT):");
                if (ordered.inner() instanceof Ast.BodyExpr inner) {
                    for (String text : explain(inner.body())) {
                        lines.add("  " + text);
                    }
                }
            } else if (branch instanceof Ast.BodyExpr body) {
                lines.add("支 " + (i + 1) + ":");
                for (String text : explain(body.body())) {
                    lines.add("  " + text);
                }
            } else {
                lines.add("支 " + (i + 1) + ": (嵌套集合运算)");
            }
            if (i < ops.size()) {
                lines.add("集合运算: " + ops.get(i));
            }
        }
        lines.add("去重: Hash Distinct(UNION / INTERSECT / EXCEPT 去重,NULL 视为相等;"
                + "超 sort_spill_bytes 走排序去重)");
        long estimate = estimateSet(expr);
        lines.add("估算: rows=" + estimate + ", cost="
                + String.format(Locale.ROOT, "%.1f", CostModel.fullScan(estimate)));
        lines.add("依据: 各支估算之和(集合运算不做统计推断)");
        return lines;
    }

    private void flattenSet(Ast.QueryExpr expr, List<Ast.QueryExpr> branches, List<String> ops) {
        if (expr instanceof Ast.OrderedExpr ordered) {
            flattenSet(ordered.inner(), branches, ops);
            return;
        }
        if (expr instanceof Ast.SetExpr set) {
            flattenSet(set.left(), branches, ops);
            ops.add(set.op().name() + (set.all() ? " ALL" : ""));
            flattenSet(set.right(), branches, ops);
            return;
        }
        branches.add(expr);
    }

    private long estimateSet(Ast.QueryExpr expr) {
        if (expr instanceof Ast.BodyExpr body) {
            return estimateQuery(body.body());
        }
        if (expr instanceof Ast.OrderedExpr ordered) {
            return estimateSet(ordered.inner());
        }
        Ast.SetExpr set = (Ast.SetExpr) expr;
        long left = estimateSet(set.left());
        long right = estimateSet(set.right());
        return switch (set.op()) {
            case UNION -> set.all() ? left + right : Math.max(left, right);
            case INTERSECT -> Math.min(left, right) / 2;
            case EXCEPT -> Math.max(0, left - right);
        };
    }

    // ------------------------------------------------------------ 主处理链

    private Relation postProcess(Relation source, Ast.QueryBody body, FromPlan plan) {
        Schema sourceSchema = source.schema;

        // FR-645:HAVING 不引用聚合 → 折成 WHERE(聚合前执行)
        Ast.Expr where = body.where();
        Ast.Expr having = body.having();
        boolean havingPushed = false;
        // 只有"不引用聚合、引用的列都在 GROUP BY 里(或没有列引用)"时才折成 WHERE。
        // 引用投影别名 / 非分组列时留在 HAVING,避免改变 1055 语义(FR-645 的安全形态)。
        if (having != null && canPushHaving(having, body)) {
            where = where == null ? having : new Ast.LogicalAnd(where, having);
            having = null;
            havingPushed = true;
        }
        where = ExprOptimizer.fold(where);
        // FR-646:常量折叠后 WHERE 恒真/恒假
        if (where instanceof Ast.Lit lit) {
            if (lit.value() == null || Boolean.FALSE.equals(lit.value())) {
                where = null;
                source = new Relation(sourceSchema, List.of(), 0, List.of());
            } else {
                where = null;
            }
        }

        preparedSubqueries(source, where, sourceSchema);
        validateNoAggregateInWhere(where);
        Relation filtered = filter(source, where);

        Map<Ast.Expr, Ast.Func> aggregates = new IdentityHashMap<>();
        for (Ast.SelectItem item : body.items()) {
            Evaluator.collectAggregates(item.expr(), aggregates);
        }
        Evaluator.collectAggregates(having, aggregates);
        for (Ast.OrderItem order : body.orderBy()) {
            Evaluator.collectAggregates(order.expr(), aggregates);
        }
        boolean grouped = !body.groupBy().isEmpty() || !aggregates.isEmpty();

        List<Object[]> baseRows;
        Schema baseSchema;
        String aggregateImpl = null;
        long groupEstimate = 0;
        if (grouped) {
            groupEstimate = estimateGroups(body, filtered, aggregates);
            aggregateImpl = chooseAggregate(body, filtered, groupEstimate, aggregates);
            BaseResult groupedResult = aggregate(filtered, body, aggregates, aggregateImpl);
            baseRows = groupedResult.rows;
            baseSchema = groupedResult.schema;
        } else {
            baseRows = filtered.rows;
            baseSchema = sourceSchema;
        }

        if (having != null) {
            preparedSubqueries(new Relation(baseSchema, baseRows), having, baseSchema);
            if (grouped) {
                validateGrouped(having, body, aggregates, "HAVING");
            }
        }

        preparedSubqueries(new Relation(baseSchema, baseRows), joinItems(body.items()),
                baseSchema);
        Projection projection = project(baseRows, baseSchema, body.items(), grouped, body,
                aggregates);

        if (having != null) {
            List<Object[]> kept = new ArrayList<>();
            for (int i = 0; i < projection.rows.size(); i++) {
                Object[] combined = concat(baseRows.get(i), projection.rows.get(i));
                if (Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(having,
                        combined, baseSchema.concat(projection.schema))))) {
                    kept.add(combined);
                }
            }
            baseRows = new ArrayList<>(kept.size());
            List<Object[]> projected = new ArrayList<>(kept.size());
            for (Object[] combined : kept) {
                Object[] basePart = Arrays.copyOfRange(combined, 0, baseSchema.size());
                Object[] projectPart = Arrays.copyOfRange(combined, baseSchema.size(),
                        combined.length);
                baseRows.add(basePart);
                projected.add(projectPart);
            }
            projection = new Projection(projection.schema, projected);
        }

        // FR-662:SELECT DISTINCT 在投影之后、ORDER BY / LIMIT 之前去重
        if (body.distinct()) {
            DistinctResult distinct = distinct(baseRows, projection, body);
            baseRows = distinct.baseRows;
            projection = new Projection(projection.schema, distinct.projectedRows);
        }

        List<Object[]> combinedRows = new ArrayList<>(projection.rows.size());
        for (int i = 0; i < projection.rows.size(); i++) {
            combinedRows.add(concat(baseRows.get(i), projection.rows.get(i)));
        }
        Schema combinedSchema = baseSchema.concat(projection.schema);

        int keep = -1;
        if (body.limit() != null) {
            keep = body.limit() + (body.offset() == null ? 0 : body.offset());
        }

        List<Object[]> ordered;
        if (!body.orderBy().isEmpty()) {
            int base = combinedSchema.size();
            List<Object[]> withKeys = new ArrayList<>(combinedRows.size());
            int[] keyCols = new int[body.orderBy().size()];
            boolean[] desc = new boolean[body.orderBy().size()];
            for (int i = 0; i < body.orderBy().size(); i++) {
                Ast.OrderItem item = body.orderBy().get(i);
                keyCols[i] = base + i;
                desc[i] = item.descending();
            }
            for (Object[] row : combinedRows) {
                Object[] extended = Arrays.copyOf(row, row.length + keyCols.length);
                for (int i = 0; i < body.orderBy().size(); i++) {
                    extended[base + i] = evalOrderKey(body.orderBy().get(i), row, combinedSchema,
                            projection.schema);
                }
                withKeys.add(extended);
            }
            // FR-641:TopN 只在 keep ≤ 总行数的阈值内使用,否则完整排序后再截断
            int sortKeep = keep;
            if (cbo() && keep >= 0) {
                long threshold = (long) combinedRows.size() * cfg().topnLimitRatioPercent() / 100;
                if (keep > threshold) {
                    sortKeep = -1;
                }
            }
            ordered = sorter.sort(withKeys, keyCols, desc, sortKeep);
        } else {
            ordered = combinedRows;
            if (keep >= 0 && keep < ordered.size()) {
                ordered = new ArrayList<>(ordered.subList(0, keep));
            }
        }

        int outputStart = baseSchema.size();
        int outputWidth = projection.schema.size();
        List<Object[]> out = new ArrayList<>();
        int offset = body.offset() == null ? 0 : body.offset();
        for (int i = 0; i < ordered.size(); i++) {
            if (i < offset) {
                continue;
            }
            if (body.limit() != null && out.size() >= body.limit()) {
                break;
            }
            Object[] row = ordered.get(i);
            out.add(Arrays.copyOfRange(row, outputStart, outputStart + outputWidth));
        }
        return new Relation(projection.schema, out);
    }

    private Object evalOrderKey(Ast.OrderItem item, Object[] combinedRow, Schema combined,
                                Schema outputSchema) {
        Ast.Expr expr = item.expr();
        if (expr instanceof Ast.Col col) {
            if (outputSchema.has(null, col.name())) {
                return combinedRow[combined.size() - outputSchema.size()
                        + outputSchema.resolve(null, col.name())];
            }
            if (col.qualifier() != null && outputSchema.has(col.qualifier(), col.name())) {
                return combinedRow[combined.size() - outputSchema.size()
                        + outputSchema.resolve(col.qualifier(), col.name())];
            }
        }
        if (expr instanceof Ast.Lit lit && lit.value() instanceof Integer ordinal) {
            if (ordinal >= 1 && ordinal <= outputSchema.size()) {
                return combinedRow[combined.size() - outputSchema.size() + ordinal - 1];
            }
            throw new SqlError(SqlError.ER_BAD_FIELD_ERROR, "42S22",
                    "ORDER BY 序号 " + ordinal + " 超出投影列数(" + outputSchema.size() + ")");
        }
        return evaluator.eval(expr, combinedRow, combined);
    }

    private void validateNoAggregateInWhere(Ast.Expr where) {
        if (where == null) {
            return;
        }
        Map<Ast.Expr, Ast.Func> aggregates = new IdentityHashMap<>();
        Evaluator.collectAggregates(where, aggregates);
        if (!aggregates.isEmpty()) {
            throw new SqlError(SqlError.ER_MIX_OF_GROUP_FUNC_AND_FIELDS, "42000",
                    "WHERE 里不能出现聚合函数(请用 HAVING)");
        }
    }

    private static Ast.Expr joinItems(List<Ast.SelectItem> items) {
        Ast.Expr result = null;
        for (Ast.SelectItem item : items) {
            if (item.expr() == null) {
                continue;
            }
            result = result == null ? item.expr() : new Ast.LogicalOr(result, item.expr());
        }
        return result;
    }

    private record BaseResult(Schema schema, List<Object[]> rows) {
    }

    private record Projection(Schema schema, List<Object[]> rows) {
    }

    // ------------------------------------------------------------ 过滤(向量化 / 逐行)

    private Relation filter(Relation source, Ast.Expr where) {
        if (where == null) {
            return source;
        }
        Evaluator.validate(where, source.schema);
        List<Object[]> out = new ArrayList<>();
        if (options.vectorized() && !needsRowWise(where)) {
            int batchRows = Math.max(1, options.batchRows());
            for (int from = 0; from < source.rows.size(); from += batchRows) {
                int to = Math.min(source.rows.size(), from + batchRows);
                Batch batch = Batch.fromRows(source.schema, source.rows.subList(from, to));
                Batch.Column keep = batchEvaluator.eval(where, batch, source.schema);
                for (int i = 0; i < batch.rows; i++) {
                    if (keep.valid()[i] && Boolean.TRUE.equals(keep.values()[i])) {
                        out.add(source.rows.get(from + i));
                    }
                }
            }
        } else {
            for (Object[] row : source.rows) {
                if (Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(where, row, source.schema)))) {
                    out.add(row);
                }
            }
        }
        return new Relation(source.schema, out, Math.min(source.estimatedRows, out.size()),
                source.seq);
    }

    /**
     * 相关子查询 / EXISTS / 量词必须逐行求值:它们要拿"当前行"当参数,向量化按列拿不到行。
     * 其它子查询(不相关 IN / 标量)仍走向量化路径。
     */
    private boolean needsRowWise(Ast.Expr expr) {
        List<Ast.Expr> subqueries = new ArrayList<>();
        Evaluator.collectSubqueries(expr, subqueries);
        for (Ast.Expr sub : subqueries) {
            if (correlated.contains(sub) || sub instanceof Ast.Exists
                    || sub instanceof Ast.Quantified) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 投影

    private Projection project(List<Object[]> rows, Schema schema, List<Ast.SelectItem> items,
                               boolean grouped, Ast.QueryBody body,
                               Map<Ast.Expr, Ast.Func> aggregates) {
        Schema outSchema = Schema.of(List.of(), List.of());
        List<Object[]> outRows = new ArrayList<>(rows.size());
        if (rows.isEmpty()) {
            Schema.Builder builder = Schema.builder();
            for (Ast.SelectItem item : items) {
                if (item.star()) {
                    if (item.starQualifier() == null) {
                        for (int i = outerPrefix; i < schema.size(); i++) {
                            builder.add(schema.qualifier(i), schema.name(i), schema.type(i));
                        }
                    } else {
                        for (int i = 0; i < schema.size(); i++) {
                            if (item.starQualifier().equalsIgnoreCase(schema.qualifier(i))) {
                                builder.add(schema.qualifier(i), schema.name(i), schema.type(i));
                            }
                        }
                    }
                } else {
                    String name = item.alias() != null ? item.alias().toLowerCase()
                            : (item.expr() instanceof Ast.Col col ? col.name()
                            : ExprPrinter.print(item.expr()));
                    builder.add(item.expr() instanceof Ast.Col col ? col.qualifier() : null, name,
                            typeOf(item.expr(), schema));
                }
            }
            return new Projection(builder.build(), outRows);
        }
        for (Object[] row : rows) {
            ExpandedRow expanded = expandRow(items, schema, row, grouped, body, aggregates);
            outSchema = expanded.schema;
            outRows.add(expanded.values);
        }
        return new Projection(outSchema, outRows);
    }

    private record ExpandedRow(Schema schema, Object[] values) {
    }

    /** 展开一项投影(星号 → 多个值;表达式 → 一行值 + 列名)。 */
    private ExpandedRow expandRow(List<Ast.SelectItem> items, Schema schema, Object[] row,
                                  boolean grouped, Ast.QueryBody body,
                                  Map<Ast.Expr, Ast.Func> aggregates) {
        Schema.Builder builder = Schema.builder();
        List<Object> values = new ArrayList<>();
        for (Ast.SelectItem item : items) {
            if (item.star()) {
                for (int i = 0; i < schema.size(); i++) {
                    if (i < outerPrefix) {
                        continue; // 相关子查询注入的外层列不参与 `*` 展开
                    }
                    if (item.starQualifier() != null
                            && !item.starQualifier().equalsIgnoreCase(schema.qualifier(i))) {
                        continue;
                    }
                    builder.add(schema.qualifier(i), schema.name(i), schema.type(i));
                    values.add(row[i]);
                }
                continue;
            }
            if (grouped) {
                validateGrouped(item.expr(), body, aggregates, "SELECT");
            }
            Evaluator.validate(item.expr(), schema);
            String name = item.alias() != null ? item.alias().toLowerCase()
                    : (item.expr() instanceof Ast.Col col ? col.name()
                    : ExprPrinter.print(item.expr()));
            builder.add(item.expr() instanceof Ast.Col col ? col.qualifier() : null, name,
                    typeOf(item.expr(), schema));
            values.add(evaluator.eval(item.expr(), row, schema));
        }
        return new ExpandedRow(builder.build(), values.toArray());
    }

    // ------------------------------------------------------------ DISTINCT(G 组)

    private record DistinctResult(List<Object[]> baseRows, List<Object[]> projectedRows) {
    }

    /** 投影去重(FR-662):hash + 溢写;与 GROUP BY 同口径(ValueOps.key)。 */
    private DistinctResult distinct(List<Object[]> baseRows, Projection projection,
                                    Ast.QueryBody body) {
        if (projection.rows.size() <= 1) {
            return new DistinctResult(baseRows, projection.rows);
        }
        long bytes = estimateBytes(new Relation(projection.schema, projection.rows));
        if (bytes <= options.sortSpillBytes()) {
            Set<List<Object>> seen = new LinkedHashSet<>();
            List<Object[]> outBase = new ArrayList<>();
            List<Object[]> outProjected = new ArrayList<>();
            for (int i = 0; i < projection.rows.size(); i++) {
                if (seen.add(distinctKey(projection.rows.get(i)))) {
                    outBase.add(baseRows.get(i));
                    outProjected.add(projection.rows.get(i));
                }
            }
            return new DistinctResult(outBase, outProjected);
        }
        // 超内存:sort_spill_bytes 之上走"排序 + 相邻去重"(Sorter 负责外部归并,K131)
        List<Object[]> combined = new ArrayList<>(projection.rows.size());
        int baseWidth = baseRows.get(0).length;
        for (int i = 0; i < projection.rows.size(); i++) {
            combined.add(concat(baseRows.get(i), projection.rows.get(i)));
        }
        int[] keys = new int[projection.schema.size()];
        boolean[] desc = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = baseWidth + i;
        }
        List<Object[]> sorted = sorter.sort(combined, keys, desc, -1);
        List<Object[]> outBase = new ArrayList<>();
        List<Object[]> outProjected = new ArrayList<>();
        List<Object> previous = null;
        for (Object[] row : sorted) {
            Object[] projected = Arrays.copyOfRange(row, baseWidth, row.length);
            List<Object> key = distinctKey(projected);
            if (previous == null || !previous.equals(key)) {
                outProjected.add(projected);
                outBase.add(Arrays.copyOfRange(row, 0, baseWidth));
                previous = key;
            }
        }
        return new DistinctResult(outBase, outProjected);
    }

    private static List<Object> distinctKey(Object[] row) {
        List<Object> key = new ArrayList<>(row.length);
        for (Object value : row) {
            key.add(ValueOps.key(value));
        }
        return key;
    }

    // ------------------------------------------------------------ 聚合

    private boolean canPushHaving(Ast.Expr having, Ast.QueryBody body) {
        if (containsAggregate(having)) {
            return false;
        }
        List<Ast.Col> columns = columnsOf(having);
        if (columns.isEmpty()) {
            return true;
        }
        Set<String> groupTexts = new LinkedHashSet<>();
        for (Ast.Expr group : body.groupBy()) {
            groupTexts.add(ExprPrinter.print(group));
        }
        for (Ast.Col col : columns) {
            if (!groupTexts.contains(ExprPrinter.print(col))
                    && !groupTexts.contains(col.name())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAggregate(Ast.Expr expr) {
        Map<Ast.Expr, Ast.Func> aggregates = new IdentityHashMap<>();
        Evaluator.collectAggregates(expr, aggregates);
        return !aggregates.isEmpty();
    }

    /** 分组后行数估算:单列 GROUP BY 用 distinct,多列取乘积(上限为输入行数)。 */
    private long estimateGroups(Ast.QueryBody body, Relation filtered,
                                Map<Ast.Expr, Ast.Func> aggregates) {
        if (body.groupBy().isEmpty()) {
            return 1;
        }
        long rows = filtered.estimatedRows;
        if (rows <= 0) {
            rows = filtered.rows.size();
        }
        long est = 1;
        boolean any = false;
        for (Ast.Expr expr : body.groupBy()) {
            if (!(expr instanceof Ast.Col col)) {
                continue;
            }
            TableMeta table = catalog.find(options.currentDatabase(), col.qualifier() == null
                    ? null : col.qualifier());
            long distinct = 100;
            if (col.qualifier() != null) {
                TableMeta byQualifier = tableByQualifier(col.qualifier(), body);
                if (byQualifier != null) {
                    distinct = estimator.distinct(byQualifier, col.name());
                }
            }
            est = est > Long.MAX_VALUE / Math.max(1, distinct)
                    ? Long.MAX_VALUE : est * Math.max(1, distinct);
            any = true;
        }
        if (!any) {
            return Math.min(rows, 100);
        }
        return Math.min(Math.max(1, rows), est);
    }

    private TableMeta tableByQualifier(String qualifier, Ast.QueryBody body) {
        if (body.from() == null) {
            return null;
        }
        List<Ast.FromItem> items = new ArrayList<>();
        items.add(body.from().first());
        for (Ast.Join join : body.from().joins()) {
            items.add(join.right());
        }
        for (Ast.FromItem item : items) {
            if (item.subquery() == null && !isVirtual(item)
                    && qualifierOf(item).equalsIgnoreCase(qualifier)) {
                try {
                    return tableOf(item);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** FR-640:分组 distinct 小 → Hash Aggregate;否则内存不足时改 Sort-based。 */
    private String chooseAggregate(Ast.QueryBody body, Relation filtered, long groupEstimate,
                                   Map<Ast.Expr, Ast.Func> aggregates) {
        if (!cbo()) {
            return "Hash Aggregate";
        }
        long bytes = (long) filtered.rows.size() * Math.max(1, filtered.schema.size()) * 32L;
        boolean largeGroups = groupEstimate > cfg().hashAggregateDistinctMax();
        if (largeGroups && bytes > options.sortSpillBytes()) {
            return "Sort Aggregate(排序后分组)";
        }
        return "Hash Aggregate";
    }

    private BaseResult aggregate(Relation filtered, Ast.QueryBody body,
                                 Map<Ast.Expr, Ast.Func> aggregates, String impl) {
        for (Ast.SelectItem item : body.items()) {
            if (!item.star()) {
                validateGrouped(item.expr(), body, aggregates, "SELECT");
            }
        }
        List<Ast.Expr> groupBy = body.groupBy();
        for (Ast.Expr expr : groupBy) {
            Evaluator.validate(expr, filtered.schema);
        }
        // 分组键标签
        Schema.Builder groupedSchema = Schema.builder();
        for (Ast.Expr expr : groupBy) {
            if (expr instanceof Ast.Col col) {
                groupedSchema.add(col.qualifier(), col.name());
            } else {
                groupedSchema.add(null, ExprPrinter.print(expr));
            }
        }
        for (Ast.Func func : aggregates.values()) {
            groupedSchema.add(null, ExprPrinter.print(func));
        }

        List<Ast.Expr> aggNodes = new ArrayList<>(aggregates.keySet());
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        if (groupBy.isEmpty()) {
            groups = singleGroup(aggNodes, aggregates, filtered);
        } else if (impl != null && impl.startsWith("Sort")) {
            groups = sortBasedGroups(groupBy, aggNodes, aggregates, filtered);
        } else if (parallelAggregate(groupBy, aggNodes, filtered)) {
            groups = parallelGroups(groupBy, aggNodes, aggregates, filtered);
        } else {
            for (Object[] row : filtered.rows) {
                List<Object> key = groupKey(groupBy, row, filtered.schema);
                Group group = groups.get(key);
                if (group == null) {
                    group = newGroup(aggNodes, aggregates, filtered.schema);
                    group.keyValues = groupValues(groupBy, row, filtered.schema);
                    groups.put(key, group);
                }
                for (Ast.Expr node : aggNodes) {
                    group.accs.get(node).add(row, filtered.schema);
                }
            }
        }

        Schema resultSchema = groupedSchema.build();
        Map<Ast.Expr, Integer> aggColumns = new IdentityHashMap<>();
        int aggPos = groupBy.size();
        for (Ast.Expr node : aggNodes) {
            aggColumns.put(node, aggPos++);
        }
        evaluator.withAggregateColumns(aggColumns);
        aggregateColumns = aggColumns;

        List<Object[]> rows = new ArrayList<>(groups.size());
        for (Group group : groups.values()) {
            Object[] base = new Object[resultSchema.size()];
            int pos = 0;
            if (!groupBy.isEmpty()) {
                for (Object v : group.keyValues) {
                    base[pos++] = v;
                }
            }
            for (Ast.Expr node : aggNodes) {
                base[pos++] = group.accs.get(node).result();
            }
            rows.add(base);
        }
        return new BaseResult(resultSchema, rows);
    }

    /**
     * 无 GROUP BY 的聚合(单组):并行时按行序切段各自局部聚合,再按段序合并累加器。
     */
    private Map<List<Object>, Group> singleGroup(List<Ast.Expr> aggNodes,
                                                 Map<Ast.Expr, Ast.Func> aggregates,
                                                 Relation filtered) {
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        List<Object[]> rows = filtered.rows;
        boolean concat = aggNodes.stream()
                .anyMatch(node -> "GROUP_CONCAT".equalsIgnoreCase(aggregates.get(node).name()));
        // GROUP_CONCAT 的截断发生在值级别,跨段合并无法逐值复现 ⇒ 该形态保持单线程
        int tasks = concat ? 1 : parallelTasks(rows.size(), 1024);
        if (tasks <= 1 || containsAnySubquery(aggNodes)) {
            Group single = newGroup(aggNodes, aggregates, filtered.schema);
            for (Object[] row : rows) {
                for (Ast.Expr node : aggNodes) {
                    single.accs.get(node).add(row, filtered.schema);
                }
            }
            groups.put(List.of(), single);
            return groups;
        }
        int[][] ranges = Parallel.ranges(rows.size(), tasks);
        List<Group> parts = Parallel.run(tasks, tasks, i -> {
            Group group = newGroup(aggNodes, aggregates, filtered.schema, localEvaluator());
            for (int r = ranges[i][0]; r < ranges[i][1]; r++) {
                Object[] row = rows.get(r);
                for (Ast.Expr node : aggNodes) {
                    group.accs.get(node).add(row, filtered.schema);
                }
            }
            return group;
        });
        Group merged = newGroup(aggNodes, aggregates, filtered.schema);
        for (Group part : parts) {
            for (Ast.Expr node : aggNodes) {
                merged.accs.get(node).mergeFrom(part.accs.get(node));
            }
        }
        groups.put(List.of(), merged);
        return groups;
    }

    /** 并行分桶聚合是否可用(FR-713):并行度 &gt;1、行数够、表达式里没有子查询。 */
    private boolean parallelAggregate(List<Ast.Expr> groupBy, List<Ast.Expr> aggNodes,
                                      Relation filtered) {
        if (parallelTasks(filtered.rows.size(), 1024) <= 1) {
            return false;
        }
        List<Ast.Expr> exprs = new ArrayList<>(groupBy);
        exprs.addAll(aggNodes);
        return !containsAnySubquery(exprs);
    }

    private int parallelTasks(int rows, int rowsPerTask) {
        if (options.parallelism() <= 1 || rows < Parallel.MIN_ROWS) {
            return 1;
        }
        return Parallel.tasks(options.parallelism(), Math.max(1, rows / rowsPerTask));
    }

    private static boolean containsAnySubquery(List<Ast.Expr> exprs) {
        for (Ast.Expr expr : exprs) {
            List<Ast.Expr> found = new ArrayList<>();
            Evaluator.collectSubqueries(expr, found);
            if (!found.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 并行任务用的求值器:共享只读映射,告警串行化(ArrayList 不是线程安全的)。 */
    private Evaluator localEvaluator() {
        return new Evaluator(aggregateValues, subqueryValues, message -> {
            synchronized (warnings) {
                warnings.warn(message);
            }
        });
    }

    /**
     * GROUP BY 按键分桶并行聚合(SPEC-P6.1 FR-713)。
     *
     * <p>同一 group key 只会落进一个桶,所以桶之间没有同键累加器需要合并;
     * 二次合并只做"按首次出现位置还原行序",与单线程 Hash Aggregate 的行序逐行一致。</p>
     */
    private Map<List<Object>, Group> parallelGroups(List<Ast.Expr> groupBy,
                                                    List<Ast.Expr> aggNodes,
                                                    Map<Ast.Expr, Ast.Func> aggregates,
                                                    Relation filtered) {
        List<Object[]> rows = filtered.rows;
        int tasks = parallelTasks(rows.size(), 1024);
        List<List<Integer>> buckets = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Object> key = groupKey(groupBy, rows.get(r), filtered.schema);
            buckets.get(Math.floorMod(key.hashCode(), tasks)).add(r);
        }
        List<Map<List<Object>, Group>> parts = Parallel.run(tasks, tasks, i -> {
            Evaluator local = localEvaluator();
            Map<List<Object>, Group> map = new LinkedHashMap<>();
            for (int r : buckets.get(i)) {
                Object[] row = rows.get(r);
                List<Object> key = groupKey(groupBy, row, filtered.schema);
                Group group = map.get(key);
                if (group == null) {
                    group = newGroup(aggNodes, aggregates, filtered.schema, local);
                    group.keyValues = groupValues(groupBy, row, filtered.schema);
                    group.firstOrder = r;
                    map.put(key, group);
                }
                for (Ast.Expr node : aggNodes) {
                    group.accs.get(node).add(row, filtered.schema);
                }
            }
            return map;
        });
        List<Group> ordered = new ArrayList<>();
        for (Map<List<Object>, Group> part : parts) {
            ordered.addAll(part.values());
        }
        ordered.sort(Comparator.comparingLong(group -> group.firstOrder));
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        for (Group group : ordered) {
            groups.put(List.of(group.firstOrder), group);
        }
        return groups;
    }

    /**
     * Sort-based Aggregate(FR-640):按分组键排序后顺序归并相邻组。
     * 用首个出现位置(order)还原"首次出现序",保证与 Hash Aggregate 行序一致(FR-632 / FR-634)。
     */
    private Map<List<Object>, Group> sortBasedGroups(List<Ast.Expr> groupBy, List<Ast.Expr> aggNodes,
                                                     Map<Ast.Expr, Ast.Func> aggregates,
                                                     Relation filtered) {
        List<Object[]> extended = new ArrayList<>(filtered.rows.size());
        int keyCount = groupBy.size();
        for (int r = 0; r < filtered.rows.size(); r++) {
            Object[] row = filtered.rows.get(r);
            Object[] withKeys = Arrays.copyOf(row, row.length + keyCount + 1);
            List<Object> keys = groupKey(groupBy, row, filtered.schema);
            for (int k = 0; k < keyCount; k++) {
                withKeys[row.length + k] = keys.get(k);
            }
            withKeys[row.length + keyCount] = (long) r; // 原始位置
            extended.add(withKeys);
        }
        int[] sortCols = new int[keyCount + 1];
        boolean[] desc = new boolean[sortCols.length];
        for (int i = 0; i < sortCols.length; i++) {
            sortCols[i] = filtered.schema.size() + i;
        }
        List<Object[]> sorted = sorter.sort(extended, sortCols, desc, -1);

        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        Group current = null;
        List<Object> currentKey = null;
        long currentOrder = 0;
        for (Object[] row : sorted) {
            List<Object> key = new ArrayList<>(keyCount);
            for (int k = 0; k < keyCount; k++) {
                key.add(row[filtered.schema.size() + k]);
            }
            long order = (Long) row[filtered.schema.size() + keyCount];
            if (currentKey == null || !currentKey.equals(key)) {
                if (current != null) {
                    groups.put(currentKey, current);
                }
                current = newGroup(aggNodes, aggregates, filtered.schema);
                current.keyValues = groupValues(groupBy, row, filtered.schema);
                current.firstOrder = order;
                currentKey = key;
            }
            Object[] plain = Arrays.copyOf(row, filtered.schema.size());
            for (Ast.Expr node : aggNodes) {
                current.accs.get(node).add(plain, filtered.schema);
            }
        }
        if (current != null) {
            groups.put(currentKey, current);
        }
        // 按首次出现位置恢复行序(与 Hash Aggregate 一致)
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingLong(g -> g.firstOrder));
        Map<List<Object>, Group> result = new LinkedHashMap<>();
        for (Group group : ordered) {
            result.put(List.of(group.firstOrder), group);
        }
        return result;
    }

    private List<Object> groupKey(List<Ast.Expr> groupBy, Object[] row, Schema schema) {
        List<Object> key = new ArrayList<>(groupBy.size());
        for (Ast.Expr expr : groupBy) {
            key.add(ValueOps.key(evaluator.eval(expr, row, schema)));
        }
        return key;
    }

    private List<Object> groupValues(List<Ast.Expr> groupBy, Object[] row, Schema schema) {
        List<Object> values = new ArrayList<>(groupBy.size());
        for (Ast.Expr expr : groupBy) {
            values.add(evaluator.eval(expr, row, schema));
        }
        return values;
    }

    private static final class Group {
        final Map<Ast.Expr, Aggregates.Accumulator> accs = new IdentityHashMap<>();
        List<Object> keyValues = List.of();
        long firstOrder;
    }

    private Group newGroup(List<Ast.Expr> nodes, Map<Ast.Expr, Ast.Func> aggregates,
                           Schema schema) {
        return newGroup(nodes, aggregates, schema, evaluator);
    }

    private Group newGroup(List<Ast.Expr> nodes, Map<Ast.Expr, Ast.Func> aggregates,
                           Schema schema, Evaluator groupEvaluator) {
        Group group = new Group();
        for (Ast.Expr node : nodes) {
            group.accs.put(node,
                    Aggregates.create(aggregates.get(node), groupEvaluator,
                            groupEvaluator.warnings()));
        }
        return group;
    }

    /**
     * FR-586 / FR-583:非分组非聚合列必须报错。
     */
    private void validateGrouped(Ast.Expr expr, Ast.QueryBody body,
                                 Map<Ast.Expr, Ast.Func> aggregates, String clause) {
        if (expr == null) {
            return;
        }
        Set<String> groupTexts = new LinkedHashSet<>();
        for (Ast.Expr group : body.groupBy()) {
            groupTexts.add(ExprPrinter.print(group));
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (Ast.SelectItem item : body.items()) {
            if (item.alias() != null) {
                aliases.add(item.alias().toLowerCase());
            }
        }
        validateGrouped0(expr, groupTexts, aliases, clause);
    }

    private void validateGrouped0(Ast.Expr expr, Set<String> groupTexts,
                                  Set<String> aliases, String clause) {
        if (expr instanceof Ast.Func func && Functions.isAggregate(func.name())) {
            return;
        }
        if (expr instanceof Ast.InSubquery || expr instanceof Ast.ScalarSubquery
                || expr instanceof Ast.Exists || expr instanceof Ast.Quantified) {
            return;
        }
        if (expr instanceof Ast.Col col) {
            String text = ExprPrinter.print(col);
            if (groupTexts.contains(text) || groupTexts.contains(col.name())
                    || aliases.contains(col.name().toLowerCase())) {
                return;
            }
            throw new SqlError(SqlError.ER_WRONG_FIELD_WITH_GROUP, "42000",
                    clause + " 里的列 " + col.name()
                            + " 既不在 GROUP BY 中,也不是聚合结果(不静默取值)");
        }
        for (Ast.Expr child : Evaluator.children(expr)) {
            validateGrouped0(child, groupTexts, aliases, clause);
        }
    }

    // ------------------------------------------------------------ 子查询

    /** 预求值表达式里的子查询;相关子查询留到逐行求值(FR-656)。 */
    private void preparedSubqueries(Relation relation, Ast.Expr expr, Schema schema) {
        if (expr == null) {
            return;
        }
        List<Ast.Expr> subqueries = new ArrayList<>();
        Evaluator.collectSubqueries(expr, subqueries);
        for (Ast.Expr sub : subqueries) {
            if (subqueryValues.containsKey(sub) || correlated.contains(sub)) {
                continue;
            }
            try {
                subqueryValues.put(sub, computeSubquery(sub, null, null, schema));
            } catch (SqlError e) {
                if (e instanceof SqlError.BadField bad
                        && (schema.has(bad.qualifier(), bad.name())
                        || (outerSchema != null && outerSchema.has(bad.qualifier(), bad.name())))) {
                    correlated.add(sub); // 外层列进内层:相关子查询,逐行执行
                    continue;
                }
                throw e;
            }
        }
    }

    /** 已判定为"相关"的子查询节点(逐行驱动 + LRU 结果缓存)。 */
    private final Set<Ast.Expr> correlated = java.util.Collections.newSetFromMap(
            new IdentityHashMap<>());
    private final Map<Ast.Expr, LinkedHashMap<List<Object>, Object>> correlationCache =
            new IdentityHashMap<>();

    /** 执行一个子查询(可带外层行参数)。 */
    private Object computeSubquery(Ast.Expr sub, Object[] row, Schema schema,
                                   Schema localSchema) {
        if (sub instanceof Ast.ScalarSubquery scalar) {
            Relation rel = runSubquery(scalar.query(), schema, row);
            if (rel.schema.size() != 1) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "标量子查询只能返回一列(实际 " + rel.schema.size() + " 列)");
            }
            if (rel.rows.size() > 1) {
                throw new SqlError(SqlError.ER_SUBQUERY_NO_1_ROW, "21000",
                        "标量子查询返回了多行(" + rel.rows.size() + " 行),K108");
            }
            return rel.rows.isEmpty() ? null : rel.rows.get(0)[0];
        }
        if (sub instanceof Ast.InSubquery in) {
            Relation rel = runSubquery(in.query(), schema, row);
            if (rel.schema.size() != 1) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "IN 子查询只能返回一列(实际 " + rel.schema.size() + " 列)");
            }
            InSet set = new InSet();
            for (Object[] relRow : rel.rows) {
                set.add(relRow[0]);
            }
            return set;
        }
        if (sub instanceof Ast.Exists exists) {
            Relation rel = runSubquery(exists.query(), schema, row);
            return !rel.rows.isEmpty();
        }
        if (sub instanceof Ast.Quantified quantified) {
            Relation rel = runSubquery(quantified.query(), schema, row);
            if (rel.schema.size() != 1) {
                throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                        "量词子查询只能返回一列(实际 " + rel.schema.size() + " 列)");
            }
            List<Object> values = new ArrayList<>(rel.rows.size());
            for (Object[] relRow : rel.rows) {
                values.add(relRow[0]);
            }
            return values;
        }
        throw new SqlError(SqlError.ER_UNKNOWN_ERROR, "HY000", "未知的子查询形态");
    }

    /**
     * 逐行求值一个已经判定为相关的子查询(FR-656 / FR-659):按外层行取值做 LRU 缓存。
     */
    Object evalCorrelated(Ast.Expr sub, Object[] row, Schema schema) {
        LinkedHashMap<List<Object>, Object> cache = correlationCache.computeIfAbsent(sub,
                k -> new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<List<Object>, Object> eldest) {
                        return size() > cfg().subqueryCacheMaxEntries();
                    }
                });
        List<Object> key = new ArrayList<>(row.length);
        for (Object value : row) {
            key.add(ValueOps.key(value));
        }
        if (cache.containsKey(key)) {
            correlationHits++;
            return cache.get(key);
        }
        correlationMisses++;
        Object value = computeSubquery(sub, row, schema, schema);
        cache.put(key, value);
        return value;
    }

    private long correlationHits;
    private long correlationMisses;

    /** Evaluator 的子查询回调:优先预求值结果,其次相关子查询逐行求值,最后懒求值一次。 */
    private Object subqueryValueForEval(Ast.Expr sub, Object[] row, Schema schema) {
        if (subqueryValues.containsKey(sub)) {
            return subqueryValues.get(sub);
        }
        if (correlated.contains(sub)) {
            return evalCorrelated(sub, row, schema);
        }
        Object value = computeSubquery(sub, row, schema, schema);
        if (row == null) {
            subqueryValues.put(sub, value);
        }
        return value;
    }

    private Relation runSubquery(Ast.QueryBody body, Schema outer, Object[] outerRow) {
        QueryEngine sub = new QueryEngine(kv, catalog, view, options, warnings);
        sub.outerSchema = outer;
        sub.outerRow = outerRow;
        try {
            return sub.run(body);
        } catch (SqlError e) {
            if (e instanceof SqlError.BadField bad && outer != null
                    && outer.has(bad.qualifier(), bad.name())) {
                // 列在外层存在 → 相关子查询;调用方按"未绑定外层"再判一次
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "相关子查询(外层列进内层)未能参数化: " + bad.getMessage());
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ EXPLAIN

    /** EXPLAIN 用的计划文本(与实际执行共用同一套估算与选择)。 */
    public List<String> explain(Ast.QueryBody body) {
        List<String> lines = new ArrayList<>();
        if (body.from() == null) {
            lines.add("访问路径: 无 FROM(字面量投影)");
            return lines;
        }
        FromPlan plan = buildFromPlan(body);
        SourcePlan first = plan.sqlOrder.get(0);
        lines.add("访问路径: " + (first.table == null
                ? "DERIVED TABLE" : "TABLE SCAN(向量化,批大小=" + options.batchRows() + ")"));
        for (SourcePlan source : plan.sqlOrder) {
            if (source.derived()) {
                lines.add("表: (子查询) AS " + source.qualifier);
                for (String inner : explain(source.item.subquery())) {
                    lines.add("  " + inner);
                }
                continue;
            }
            lines.add("表: " + describeTable(source));
            if (source.access != null) {
                com.wangyu.tangdb.planner.Plan chosenPlan = source.access.plan();
                lines.add("  访问路径: " + accessLabel(chosenPlan) + " 估算 rows="
                        + source.access.chosen().rows() + ", cost="
                        + String.format(Locale.ROOT, "%.1f", source.access.chosen().cost()));
                StringBuilder candidates = new StringBuilder();
                for (com.wangyu.tangdb.planner.Planner.Candidate candidate
                        : source.access.candidates()) {
                    if (candidates.length() > 0) {
                        candidates.append(" / ");
                    }
                    candidates.append(candidate.label()).append(" rows=").append(candidate.rows())
                            .append(",cost=").append(String.format(Locale.ROOT, "%.1f",
                                    candidate.cost()));
                }
                lines.add("  候选: " + candidates);
            }
        }
        if (plan.joinOrder.size() > 1) {
            StringBuilder order = new StringBuilder();
            for (SourcePlan source : plan.joinOrder) {
                if (order.length() > 0) {
                    order.append(" → ");
                }
                order.append(source.qualifier);
            }
            lines.add("连接顺序: " + order + (plan.reordered ? "(CBO 重排)" : "(书写顺序)"));
            for (int i = 1; i < plan.joinOrder.size(); i++) {
                SourcePlan right = plan.joinOrder.get(i);
                List<SourcePlan> left = plan.joinOrder.subList(0, i);
                List<Ast.Expr> applicable = applicablePredicates(plan.joinPredicates, left, right,
                        plan.perm, plan.joinOrder);
                List<Ast.Expr> conjuncts = new ArrayList<>();
                for (Ast.Expr predicate : applicable) {
                    flattenAnd(predicate, conjuncts);
                }
                List<Ast.Expr> equi = new ArrayList<>();
                List<Ast.Expr> nonEqui = new ArrayList<>();
                for (Ast.Expr conjunct : conjuncts) {
                    if (conjunct instanceof Ast.Cmp cmp && cmp.op() == Ast.Op.EQ
                            && cmp.left() instanceof Ast.Col && cmp.right() instanceof Ast.Col) {
                        equi.add(conjunct);
                    } else {
                        nonEqui.add(conjunct);
                    }
                }
                String algorithm = equi.isEmpty() ? "Nested Loop Join" : joinAlgorithm(plan, left, right);
                long leftRows = estimateJoin(left, plan.joinPredicates);
                lines.add("连接: " + algorithm + " / " + right.qualifier
                        + " 左侧估算 rows=" + leftRows + ",右侧 rows=" + right.estimatedRows);
                if (!equi.isEmpty()) {
                    String build = leftRows <= right.estimatedRows ? "左" : "右";
                    lines.add("build=" + build + "(按估算行数,FR-637)");
                }
                if (!nonEqui.isEmpty()) {
                    lines.add("残余连接条件: " + printAll(nonEqui));
                }
            }
        }
        List<Ast.Expr> pushed = new ArrayList<>();
        for (SourcePlan source : plan.sqlOrder) {
            if (source.pushed != null) {
                pushed.add(source.pushed);
            }
        }
        if (!pushed.isEmpty()) {
            lines.add("已下推: " + printAll(pushed) + "(单表谓词下推到扫描)");
        }
        if (body.where() != null) {
            lines.add("过滤: " + ExprPrinter.print(ExprOptimizer.fold(body.where())));
        }
        int totalColumns = 0;
        int readColumns = 0;
        for (SourcePlan source : plan.sqlOrder) {
            if (source.table != null) {
                totalColumns += source.table.columnCount();
                readColumns += source.neededCount;
            }
        }
        if (totalColumns > 0) {
            lines.add("列: 读取 " + readColumns + " / 表 " + totalColumns + " 列");
        }
        // SPEC-P6.1 FR-719 / SC-414:新增"并行/批/下推"行(既有行不变)
        lines.add("并行/批/下推: 并行度=" + options.parallelism()
                + ", 扫描批=" + options.scanBatchRows()
                + ", 下推=" + (options.pushdown() ? "开(列投影 + 谓词字节码)" : "关(全行回退,P6 行为)"));
        // P4/P5 的既有行(FR-671:既有行只增不改)
        lines.add("实际读取列: " + String.join(", ", columnsRead(body)));
        if (!body.groupBy().isEmpty()) {
            StringBuilder keys = new StringBuilder();
            for (Ast.Expr expr : body.groupBy()) {
                if (keys.length() > 0) {
                    keys.append(", ");
                }
                keys.append(ExprPrinter.print(expr));
            }
            long groupEstimate = 0;
            if (!plan.sqlOrder.isEmpty()) {
                groupEstimate = estimateGroups(body, new Relation(first.schema,
                        List.of(), plan.estimatedRows, null), Map.of());
            }
            String impl = (!cbo()) ? "Hash Aggregate"
                    : (groupEstimate > cfg().hashAggregateDistinctMax()
                    && plan.estimatedRows * Math.max(1, first.schema.size()) * 32L
                    > options.sortSpillBytes() ? "Sort Aggregate(排序后分组)" : "Hash Aggregate");
            lines.add("聚合: " + impl + "(分组键=[" + keys + "],估算 groups=" + groupEstimate + ")");
        } else {
            Map<Ast.Expr, Ast.Func> aggregates = new LinkedHashMap<>();
            for (Ast.SelectItem item : body.items()) {
                Evaluator.collectAggregates(item.expr(), aggregates);
            }
            if (!aggregates.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Ast.Func func : aggregates.values()) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(ExprPrinter.print(func));
                }
                lines.add("聚合: Hash Aggregate(无 GROUP BY,恒返回一行;[" + names + "])");
            }
        }
        if (body.having() != null) {
            if (canPushHaving(body.having(), body)) {
                lines.add("已下推: HAVING " + ExprPrinter.print(body.having())
                        + "(不引用聚合,折成 WHERE 在聚合前执行)");
            } else {
                lines.add("分组后过滤(HAVING): " + ExprPrinter.print(body.having()));
            }
        }
        if (body.distinct()) {
            lines.add("去重: Hash Distinct(超 sort_spill_bytes 走排序去重)");
        }
        if (!body.orderBy().isEmpty()) {
            StringBuilder keys = new StringBuilder();
            boolean desc = false;
            for (Ast.OrderItem item : body.orderBy()) {
                if (keys.length() > 0) {
                    keys.append(", ");
                }
                keys.append(ExprPrinter.print(item.expr()));
                if (item.descending()) {
                    keys.append(" DESC");
                    desc = true;
                }
            }
            if (body.limit() != null) {
                long threshold = plan.estimatedRows * cfg().topnLimitRatioPercent() / 100;
                int keep = body.limit() + (body.offset() == null ? 0 : body.offset());
                String impl = keep <= threshold
                        ? "TopN"
                        : "Sort(keep=" + keep + " 超过 TopN 阈值 " + cfg().topnLimitRatioPercent()
                        + "%,完整排序后截断)";
                lines.add("排序: " + impl + "(keep=" + keep + ",键=[" + keys + "])");
            } else {
                lines.add("排序: Sort(键=[" + keys + "],降序=" + desc
                        + ",超阈值走外部归并)");
            }
        }
        if (body.groupBy().isEmpty() && body.orderBy().isEmpty() && body.limit() != null
                && plan.sqlOrder.size() == 1) {
            lines.add("LIMIT 下推: 扫描 limit="
                    + (body.limit() + (body.offset() == null ? 0 : body.offset())));
        }
        long outputRows = plan.estimatedRows;
        double outputCost = CostModel.fullScan(outputRows);
        lines.add("估算: rows=" + outputRows + ", cost="
                + String.format(Locale.ROOT, "%.1f", outputCost));
        lines.add("依据: " + evidenceText(first));
        return lines;
    }

    private String joinAlgorithm(FromPlan plan, List<SourcePlan> left, SourcePlan right) {
        long leftRows = estimateJoin(left, plan.joinPredicates);
        boolean bothSorted = leftRows > 0 && right.estimatedRows > 0
                && left.get(left.size() - 1).estimatedRows > 0;
        // 保守:只有两侧都是单表来源且连接键在第一列(主键)时才认为已有序
        boolean sorted = left.size() == 1 && left.get(0).table != null && right.table != null;
        if (sorted && bothSorted) {
            return "Sort-Merge Join(两侧已按连接键有序)";
        }
        return "Hash Join(小表建哈希)";
    }

    /** 访问路径名称(EXPLAIN 用)。 */
    private static String accessLabel(com.wangyu.tangdb.planner.Plan plan) {
        return switch (plan.kind()) {
            case POINT -> "PRIMARY POINT";
            case POINTS -> "PRIMARY POINT(IN)";
            case RANGE -> plan.fullScan() ? "TABLE SCAN" : "PRIMARY RANGE";
            case INDEX_POINT -> "INDEX POINT(" + plan.index().name() + ")";
            case INDEX_RANGE -> "INDEX RANGE(" + plan.index().name() + ")";
        };
    }

    private String describeTable(SourcePlan source) {
        if (source.virtualName != null) {
            return "information_schema." + source.virtualName.toUpperCase(Locale.ROOT)
                    + "(虚拟表 / 固定估算 " + source.estimatedRows + " 行,不参与统计)";
        }
        String text = source.table.name()
                + (source.qualifier.equalsIgnoreCase(source.table.name()) ? ""
                : " AS " + source.qualifier);
        if (source.neededCount < source.table.columnCount()) {
            text += "(读取 " + source.neededCount + "/" + source.table.columnCount() + " 列)";
        }
        return text;
    }

    private static String printAll(List<Ast.Expr> exprs) {
        StringBuilder sb = new StringBuilder();
        for (Ast.Expr expr : exprs) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(ExprPrinter.print(expr));
        }
        return sb.toString();
    }

    private String evidenceText(SourcePlan source) {
        if (source == null) {
            return "默认(未 ANALYZE)";
        }
        if (source.virtualName != null) {
            return "默认(虚拟表固定估算)";
        }
        if (source.table == null) {
            return "默认(派生表估算)";
        }
        return switch (estimator.evidence(source.table)) {
            case STATS -> "统计(t=" + estimator.analyzedAt(source.table) + ")";
            case STALE -> "统计已过期(t=" + estimator.analyzedAt(source.table) + "),回落默认";
            case DEFAULT -> "默认(未 ANALYZE)";
        };
    }

    // ------------------------------------------------------------ 工具:表 / 限定名 / 列引用

    private String qualifierOf(Ast.FromItem item) {
        if (item.alias() != null) {
            return item.alias();
        }
        if (item.subquery() != null) {
            throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000", "派生表必须有别名");
        }
        return item.table();
    }

    /**
     * 把 P6 表达式形态的单表谓词转成旧 {@link Ast.Condition}(Planner 的绑定入口)。
     * 只接受"列 op 常量 / 列 IN (常量...)"的同质合取/析取;其余返回 null → 退全表扫描。
     */
    static Ast.Condition toCondition(Ast.Expr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Ast.LogicalAnd and) {
            Ast.Condition left = toCondition(and.left());
            Ast.Condition right = toCondition(and.right());
            return left == null || right == null ? null : new Ast.And(left, right);
        }
        if (expr instanceof Ast.LogicalOr or) {
            Ast.Condition left = toCondition(or.left());
            Ast.Condition right = toCondition(or.right());
            return left == null || right == null ? null : new Ast.Or(left, right);
        }
        if (expr instanceof Ast.Cmp cmp) {
            if (cmp.left() instanceof Ast.Col col && cmp.right() instanceof Ast.Lit lit) {
                return new Ast.Compare(col.name(), cmp.op(), lit.value());
            }
            if (cmp.right() instanceof Ast.Col col && cmp.left() instanceof Ast.Lit lit) {
                Ast.Op flipped = switch (cmp.op()) {
                    case LT -> Ast.Op.GT;
                    case LE -> Ast.Op.GE;
                    case GT -> Ast.Op.LT;
                    case GE -> Ast.Op.LE;
                    default -> cmp.op();
                };
                return new Ast.Compare(col.name(), flipped, lit.value());
            }
            return null;
        }
        if (expr instanceof Ast.InExpr in && in.operand() instanceof Ast.Col col) {
            List<Object> values = new ArrayList<>(in.values().size());
            for (Ast.Expr value : in.values()) {
                if (!(value instanceof Ast.Lit lit)) {
                    return null;
                }
                values.add(lit.value());
            }
            return in.negated() ? null : new Ast.In(col.name(), values);
        }
        return null;
    }

    /** 是否是 information_schema 虚拟表来源。 */
    private static boolean isVirtual(Ast.FromItem item) {
        return item.subquery() == null && item.table() != null
                && item.database() != null
                && item.database().equalsIgnoreCase("information_schema");
    }

    /** 虚拟表 schema:列名用大写显示名(MySQL 口径),类型来自列定义。 */
    private Schema virtualSchema(String qualifier, String virtualName) {
        List<Column> columns = options.virtualTables().columns(virtualName);
        if (columns == null) {
            return null;
        }
        List<Column> display = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            display.add(new Column(column.name().toUpperCase(Locale.ROOT), column.type(),
                    column.length(), true, false, i));
        }
        return Schema.ofTable(qualifier, display);
    }

    private TableMeta tableOf(Ast.FromItem item) {
        String db = item.database() == null ? options.currentDatabase() : item.database();
        return catalog.require(db, item.table());
    }

    /** 结果列形态(不执行数据):给派生表的 schema 用。 */
    private Schema describe(Ast.QueryBody body) {
        if (body.from() == null) {
            Schema.Builder builder = Schema.builder();
            appendItemNames(body.items(), Schema.of(List.of(), List.of()), builder);
            return builder.build();
        }
        List<Ast.FromItem> items = new ArrayList<>();
        items.add(body.from().first());
        for (Ast.Join join : body.from().joins()) {
            items.add(join.right());
        }
        Schema source = Schema.of(List.of(), List.of());
        for (Ast.FromItem item : items) {
            Schema part;
            if (item.subquery() != null) {
                part = describe(item.subquery()).withQualifier(qualifierOf(item));
            } else if (isVirtual(item)) {
                Schema virtual = virtualSchema(qualifierOf(item),
                        item.table().toLowerCase(Locale.ROOT));
                part = virtual == null ? Schema.of(List.of(), List.of()) : virtual;
            } else {
                part = Schema.ofTable(qualifierOf(item), tableOf(item).columns());
            }
            source = source.concat(part);
        }
        if (!body.groupBy().isEmpty()) {
            Schema.Builder builder = Schema.builder();
            for (Ast.Expr expr : body.groupBy()) {
                builder.add(expr instanceof Ast.Col col ? col.qualifier() : null,
                        expr instanceof Ast.Col col ? col.name() : ExprPrinter.print(expr));
            }
            Map<Ast.Expr, Ast.Func> aggregates = new LinkedHashMap<>();
            for (Ast.SelectItem item : body.items()) {
                Evaluator.collectAggregates(item.expr(), aggregates);
            }
            Evaluator.collectAggregates(body.having(), aggregates);
            for (Ast.Func func : aggregates.values()) {
                builder.add(null, ExprPrinter.print(func));
            }
            return builder.build();
        }
        Schema.Builder builder = Schema.builder();
        appendItemNames(body.items(), source, builder);
        return builder.build();
    }

    private void appendItemNames(List<Ast.SelectItem> items, Schema source, Schema.Builder builder) {
        for (Ast.SelectItem item : items) {
            if (item.star()) {
                for (int i = 0; i < source.size(); i++) {
                    if (item.starQualifier() != null
                            && !item.starQualifier().equalsIgnoreCase(source.qualifier(i))) {
                        continue;
                    }
                    builder.add(source.qualifier(i), source.name(i), source.type(i));
                }
                continue;
            }
            String name = item.alias() != null ? item.alias().toLowerCase()
                    : (item.expr() instanceof Ast.Col col ? col.name()
                    : ExprPrinter.print(item.expr()));
            builder.add(item.expr() instanceof Ast.Col col ? col.qualifier() : null, name,
                    typeOf(item.expr(), source));
        }
    }

    private DataType typeOf(Ast.Expr expr, Schema schema) {
        if (expr instanceof Ast.Col col && schema.has(col.qualifier(), col.name())) {
            return schema.type(schema.resolve(col.qualifier(), col.name()));
        }
        return null;
    }

    /** 纯估算查询输出行数(派生表用;不扫数据)。 */
    private long estimateQuery(Ast.QueryBody body) {
        if (body.from() == null) {
            return 1;
        }
        List<Ast.FromItem> items = new ArrayList<>();
        items.add(body.from().first());
        for (Ast.Join join : body.from().joins()) {
            items.add(join.right());
        }
        long rows = 1;
        boolean first = true;
        for (Ast.FromItem item : items) {
            long est;
            if (item.subquery() != null) {
                est = estimateQuery(item.subquery());
            } else if (isVirtual(item)) {
                est = options.virtualTables().estimatedRows(item.table().toLowerCase(Locale.ROOT));
            } else {
                try {
                    TableMeta table = tableOf(item);
                    est = estimator.rowCount(table);
                } catch (RuntimeException e) {
                    est = 10_000;
                }
            }
            rows = first ? est : Math.max(1, Math.min(Long.MAX_VALUE / 2, rows * est));
            first = false;
        }
        if (body.distinct()) {
            rows = Math.max(1, rows / 2);
        }
        return Math.max(0, rows);
    }

    /** 实际读取列(仅供 EXPLAIN 的既有行复用)。 */
    private Set<String> columnsRead(Ast.QueryBody body) {
        Set<String> names = new LinkedHashSet<>();
        List<Ast.Expr> exprs = new ArrayList<>();
        for (Ast.SelectItem item : body.items()) {
            exprs.add(item.expr());
        }
        exprs.add(body.where());
        exprs.add(body.having());
        exprs.addAll(body.groupBy());
        for (Ast.OrderItem order : body.orderBy()) {
            exprs.add(order.expr());
        }
        if (body.from() != null) {
            for (Ast.Join join : body.from().joins()) {
                exprs.add(join.on());
            }
        }
        for (Ast.Expr expr : exprs) {
            for (Ast.Col col : columnsOf(expr)) {
                names.add(col.qualifier() == null ? col.name() : col.qualifier() + "." + col.name());
            }
        }
        return names;
    }

    /** 表达式里引用到的列。 */
    static List<Ast.Col> columnsOf(Ast.Expr expr) {
        List<Ast.Col> out = new ArrayList<>();
        collectColumns(expr, out);
        return out;
    }

    private static void collectColumns(Ast.Expr expr, List<Ast.Col> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.Col col) {
            out.add(col);
            return;
        }
        if (expr instanceof Ast.InSubquery in) {
            collectColumns(in.operand(), out);
            return;
        }
        if (expr instanceof Ast.Quantified quantified) {
            collectColumns(quantified.operand(), out);
            return;
        }
        if (expr instanceof Ast.ScalarSubquery || expr instanceof Ast.Exists) {
            return;
        }
        for (Ast.Expr child : Evaluator.children(expr)) {
            collectColumns(child, out);
        }
    }

    /** 表达式引用到的限定名(小写);无限定名的列不贡献表名。 */
    static Set<String> qualifiersOf(Ast.Expr expr) {
        Set<String> out = new LinkedHashSet<>();
        for (Ast.Col col : columnsOf(expr)) {
            if (col.qualifier() != null) {
                out.add(col.qualifier().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static boolean containsSubquery(Ast.Expr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof Ast.InSubquery || expr instanceof Ast.ScalarSubquery
                || expr instanceof Ast.Exists || expr instanceof Ast.Quantified) {
            return true;
        }
        for (Ast.Expr child : Evaluator.children(expr)) {
            if (containsSubquery(child)) {
                return true;
            }
        }
        return false;
    }
}
