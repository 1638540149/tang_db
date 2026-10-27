package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
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
import java.util.Map;
import java.util.Set;

/**
 * P5 查询引擎(SPEC-P5 B ~ E 组)。
 *
 * <p>执行顺序:FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT/OFFSET。</p>
 *
 * <p>扫描 / 过滤 / 投影走 batch + pull 接口;聚合、排序、连接在算子内部物化(单线程流水线,
 * K111)。{@code vectorized=false} 时过滤与投影走逐行路径,用于 FR-576 对拍。</p>
 */
public final class QueryEngine {

    /** 运行参数(来自配置)。 */
    public record Options(String currentDatabase, int batchRows, long sortSpillBytes,
                          Path sortTmpDir, boolean vectorized) {
        public static Options defaults(String database) {
            return new Options(database, 1024, Sorter.DEFAULT_SPILL_BYTES,
                    Path.of("tmp", "fe-sort"), true);
        }
    }

    private final KvStore kv;
    private final Catalog catalog;
    private final ReadView view;
    private final Options options;
    private final Evaluator.Warnings warnings;

    private final Map<Ast.Expr, Object> subqueryValues = new IdentityHashMap<>();
    private final Map<Ast.Expr, Object> aggregateValues = new IdentityHashMap<>();
    private Map<Ast.Expr, Integer> aggregateColumns = Map.of();
    private final Evaluator evaluator;
    private final BatchEvaluator batchEvaluator;
    private final Sorter sorter;

    /** 供相关子查询报错时判断"外层是否存在该列"。 */
    private Schema outerSchema;

    public QueryEngine(KvStore kv, Catalog catalog, ReadView view, Options options,
                       Evaluator.Warnings warnings) {
        this.kv = kv;
        this.catalog = catalog;
        this.view = view;
        this.options = options == null ? Options.defaults("default") : options;
        this.warnings = warnings == null ? Evaluator.Warnings.NONE : warnings;
        this.evaluator = new Evaluator(aggregateValues, subqueryValues, this.warnings);
        this.batchEvaluator = new BatchEvaluator(subqueryValues);
        this.sorter = new Sorter(this.options.sortSpillBytes(), this.options.sortTmpDir());
    }

    public QueryEngine withOuterSchema(Schema schema) {
        this.outerSchema = schema;
        return this;
    }

    // ------------------------------------------------------------ 入口

    /** 执行一条 SELECT,返回结果表。 */
    public Result.Table execute(Ast.QueryBody body) {
        Relation relation = run(body);
        return new Result.Table(relation.schema.names(), relation.rows);
    }

    /** 行结果(单测 / 子查询复用)。 */
    public Relation run(Ast.QueryBody body) {
        if (body.from() == null) {
            return runWithoutFrom(body);
        }
        Relation source = from(body.from());
        return postProcess(source, body);
    }

    /** 结果集:列名表 + 行。 */
    public static final class Relation {
        public final Schema schema;
        public final List<Object[]> rows;

        public Relation(Schema schema, List<Object[]> rows) {
            this.schema = schema;
            this.rows = rows;
        }
    }

    private Relation runWithoutFrom(Ast.QueryBody body) {
        // 无 FROM 的 SELECT 只在子查询里出现(例如 SELECT COUNT(*) 没有表);按一行空表处理。
        Schema empty = Schema.of(List.of(), List.of());
        Relation source = new Relation(empty, List.<Object[]>of(new Object[0]));
        return postProcess(source, body);
    }

    // ------------------------------------------------------------ FROM / JOIN

    private Relation from(Ast.FromClause from) {
        Relation left = fromItem(from.first());
        for (Ast.Join join : from.joins()) {
            Relation right = fromItem(join.right());
            left = join(left, right, join);
        }
        return left;
    }

    private Relation fromItem(Ast.FromItem item) {
        if (item.subquery() != null) {
            QueryEngine sub = new QueryEngine(kv, catalog, view, options, warnings);
            if (outerSchema != null) {
                sub.outerSchema = outerSchema;
            }
            Relation rel = sub.run(item.subquery());
            String alias = item.alias() == null ? "subquery" : item.alias();
            return new Relation(rel.schema.withQualifier(alias), rel.rows);
        }
        return scanTable(item);
    }

    private Relation scanTable(Ast.FromItem item) {
        String db = item.database() == null ? options.currentDatabase() : item.database();
        TableMeta table;
        try {
            table = catalog.require(db, item.table());
        } catch (CatalogException e) {
            throw e;
        }
        String qualifier = item.alias() == null ? table.name() : item.alias();
        Schema schema = Schema.ofTable(qualifier, table.columns());

        List<KeyValue> kvs = view.scan(KeyCodec.tableRangeStart(table.id()),
                KeyCodec.tableRangeEnd(table.id()), 0);
        List<Object[]> rows = new ArrayList<>();
        for (KeyValue row : kvs) {
            if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                continue;
            }
            byte[] raw = row.value();
            if (raw != null && raw.length > 0 && raw[0] == RowCodec.FORMAT_TOMBSTONE) {
                continue;
            }
            RowCodec.Decoded decoded = RowCodec.decode(table, raw);
            if (decoded.tombstone()) {
                continue;
            }
            rows.add(decoded.values());
        }
        return new Relation(schema, rows);
    }

    /** 连接:等值条件走 Hash Join(小表建哈希),否则 NLJ(E-2 / K112)。 */
    private Relation join(Relation left, Relation right, Ast.Join join) {
        Schema combined = left.schema.concat(right.schema);
        preparedSubqueries(right, join.on(), combined);
        Relation out;
        if (join.type() == Ast.JoinType.CROSS) {
            out = new Relation(combined, nestedLoop(left, right, null, false, combined));
        } else {
            List<EquiPair> equi = equiPairs(join.on(), left.schema, right.schema);
            checkJoinTypes(left.schema, right.schema, equi);
            if (!equi.isEmpty()) {
                out = new Relation(combined, hashJoin(left, right, join, equi, combined));
            } else {
                out = new Relation(combined, nestedLoop(left, right, join.on(),
                        join.type() == Ast.JoinType.LEFT, combined));
            }
        }
        return out;
    }

    /** ON 里的等值对:{leftIndex, rightIndex, 原始 left 表达式,原始 right 表达式}。 */
    private record EquiPair(int leftIndex, int rightIndex, Ast.Expr leftExpr, Ast.Expr rightExpr) {
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
                pairs.add(new EquiPair(li, ri, leftCol, rightCol));
                continue;
            }
            li = indexIn(left, rightCol);
            ri = indexIn(right, leftCol);
            if (li >= 0 && ri >= 0) {
                pairs.add(new EquiPair(li, ri, rightCol, leftCol));
            }
        }
        return pairs;
    }

    /** E-5 / FR-592:连接列类型不可比较时报类型错误,不得静默当作"无匹配"。 */
    private static void checkJoinTypes(Schema left, Schema right, List<EquiPair> pairs) {
        for (EquiPair pair : pairs) {
            com.wangyu.tangdb.storage.DataType lt = left.type(pair.leftIndex());
            com.wangyu.tangdb.storage.DataType rt = right.type(pair.rightIndex());
            if (lt == null || rt == null) {
                continue;
            }
            boolean comparable = lt == rt
                    || (lt.isNumeric() && rt.isNumeric());
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

    /** 小表侧(行数 × 行宽估算,K112)建哈希表。 */
    private List<Object[]> hashJoin(Relation left, Relation right, Ast.Join join,
                                    List<EquiPair> pairs, Schema combined) {
        long leftWidth = estimateWidth(left);
        long rightWidth = estimateWidth(right);
        boolean buildLeft = leftWidth <= rightWidth;
        Relation build = buildLeft ? left : right;
        Relation probe = buildLeft ? right : left;

        Map<List<Object>, List<Object[]>> table = new LinkedHashMap<>();
        int[] buildKeyIdx = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            buildKeyIdx[i] = buildLeft ? pairs.get(i).leftIndex() : pairs.get(i).rightIndex();
        }
        for (Object[] row : build.rows) {
            List<Object> key = keyOf(row, buildKeyIdx);
            if (key == null) {
                continue; // NULL 连接键不匹配(FR-592)
            }
            table.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int[] probeKeyIdx = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            probeKeyIdx[i] = buildLeft ? pairs.get(i).rightIndex() : pairs.get(i).leftIndex();
        }
        List<Object[]> out = new ArrayList<>();
        boolean[] matchedBuild = null;
        Set<Integer> matchedBuildRows = new LinkedHashSet<>();
        for (Object[] probeRow : probe.rows) {
            List<Object> key = keyOf(probeRow, probeKeyIdx);
            List<Object[]> matches = key == null ? null : table.get(key);
            boolean any = false;
            if (matches != null) {
                for (Object[] buildRow : matches) {
                    Object[] combinedRow = buildLeft
                            ? concat(buildRow, probeRow) : concat(probeRow, buildRow);
                    if (join.on() != null
                            && !Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(
                            join.on(), combinedRow, combined)))) {
                        continue;
                    }
                    out.add(combinedRow);
                    any = true;
                    if (buildLeft) {
                        matchedBuildRows.add(System.identityHashCode(buildRow));
                    }
                }
            }
            if (!any && join.type() == Ast.JoinType.LEFT && !buildLeft) {
                out.add(concat(probeRow, new Object[right.schema.size()]));
            }
        }
        if (join.type() == Ast.JoinType.LEFT && buildLeft) {
            // 左表是 build 侧:补左表未匹配的行(右列 NULL)。
            for (Object[] buildRow : build.rows) {
                if (!matchedBuildRows.contains(System.identityHashCode(buildRow))) {
                    out.add(concat(buildRow, new Object[right.schema.size()]));
                }
            }
        }
        return out;
    }

    private static long estimateWidth(Relation rel) {
        long cells = (long) rel.rows.size() * rel.schema.size();
        long bytes = cells * 16;
        for (Object[] row : rel.rows) {
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

    private List<Object[]> nestedLoop(Relation left, Relation right, Ast.Expr on,
                                      boolean leftJoin, Schema combined) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] leftRow : left.rows) {
            boolean any = false;
            for (Object[] rightRow : right.rows) {
                Object[] combinedRow = concat(leftRow, rightRow);
                if (on != null && !Boolean.TRUE.equals(
                        ValueOps.truth(evaluator.eval(on, combinedRow, combined)))) {
                    continue;
                }
                out.add(combinedRow);
                any = true;
            }
            if (!any && leftJoin) {
                out.add(concat(leftRow, new Object[right.schema.size()]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 主处理链

    private Relation postProcess(Relation source, Ast.QueryBody body) {
        Schema sourceSchema = source.schema;
        preparedSubqueries(source, body.where(), sourceSchema);

        validateNoAggregateInWhere(body.where());
        Relation filtered = filter(source, body.where());

        Map<Ast.Expr, Ast.Func> aggregates = new IdentityHashMap<>();
        for (Ast.SelectItem item : body.items()) {
            Evaluator.collectAggregates(item.expr(), aggregates);
        }
        Evaluator.collectAggregates(body.having(), aggregates);
        for (Ast.OrderItem order : body.orderBy()) {
            Evaluator.collectAggregates(order.expr(), aggregates);
        }
        boolean grouped = !body.groupBy().isEmpty() || !aggregates.isEmpty();

        List<Object[]> baseRows;
        Schema baseSchema;
        if (grouped) {
            BaseResult groupedResult = aggregate(filtered, body, aggregates);
            baseRows = groupedResult.rows;
            baseSchema = groupedResult.schema;
        } else {
            baseRows = filtered.rows;
            baseSchema = sourceSchema;
        }

        if (body.having() != null) {
            preparedSubqueries(new Relation(baseSchema, baseRows), body.having(), baseSchema);
            if (grouped) {
                validateGrouped(body.having(), body, aggregates, "HAVING");
            }
        }

        // 投影(星号展开 / 表达式 / 别名)
        preparedSubqueries(new Relation(baseSchema, baseRows), joinItems(body.items()),
                baseSchema);
        Projection projection = project(baseRows, baseSchema, body.items(), grouped, body, aggregates);

        if (body.having() != null) {
            List<Object[]> kept = new ArrayList<>();
            for (int i = 0; i < projection.rows.size(); i++) {
                Object[] combined = concat(baseRows.get(i), projection.rows.get(i));
                if (Boolean.TRUE.equals(ValueOps.truth(evaluator.eval(body.having(),
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

        // ORDER BY + LIMIT
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
            ordered = sorter.sort(withKeys, keyCols, desc, keep);
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
            // 别名优先(MySQL 口径)
            if (outputSchema.has(null, col.name())) {
                return combinedRow[combined.size() - outputSchema.size()
                        + outputSchema.resolve(null, col.name())];
            }
            // 也支持 output.qualifier 形式
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

    /** 判断 ON 是否"看起来是等值连接"(用于 EXPLAIN;实际执行按 equiPairs 结果)。 */
    private static boolean looksEqui(Ast.Expr on) {
        List<Ast.Expr> conjuncts = new ArrayList<>();
        flattenAnd(on, conjuncts);
        for (Ast.Expr conjunct : conjuncts) {
            if (conjunct instanceof Ast.Cmp cmp && cmp.op() == Ast.Op.EQ
                    && cmp.left() instanceof Ast.Col && cmp.right() instanceof Ast.Col
                    && !((Ast.Col) cmp.left()).name().equalsIgnoreCase(
                    ((Ast.Col) cmp.right()).name())) {
                return true;
            }
        }
        return false;
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
        if (options.vectorized()) {
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
        return new Relation(source.schema, out);
    }

    // ------------------------------------------------------------ 投影

    private Projection project(List<Object[]> rows, Schema schema, List<Ast.SelectItem> items,
                               boolean grouped, Ast.QueryBody body,
                               Map<Ast.Expr, Ast.Func> aggregates) {
        Schema outSchema = Schema.of(List.of(), List.of());
        List<Object[]> outRows = new ArrayList<>(rows.size());
        if (rows.isEmpty()) {
            // 空结果也要给出列名。
            Schema.Builder builder = Schema.builder();
            for (Ast.SelectItem item : items) {
                if (item.star()) {
                    if (item.starQualifier() == null) {
                        for (int i = 0; i < schema.size(); i++) {
                            builder.add(schema.qualifier(i), schema.name(i));
                        }
                    } else {
                        for (int i = 0; i < schema.size(); i++) {
                            if (item.starQualifier().equalsIgnoreCase(schema.qualifier(i))) {
                                builder.add(schema.qualifier(i), schema.name(i));
                            }
                        }
                    }
                } else {
                    String name = item.alias() != null ? item.alias().toLowerCase()
                            : (item.expr() instanceof Ast.Col col ? col.name()
                            : ExprPrinter.print(item.expr()));
                    builder.add(item.expr() instanceof Ast.Col col ? col.qualifier() : null, name);
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
                    if (item.starQualifier() != null
                            && !item.starQualifier().equalsIgnoreCase(schema.qualifier(i))) {
                        continue;
                    }
                    builder.add(schema.qualifier(i), schema.name(i));
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
            builder.add(item.expr() instanceof Ast.Col col ? col.qualifier() : null, name);
            values.add(evaluator.eval(item.expr(), row, schema));
        }
        return new ExpandedRow(builder.build(), values.toArray());
    }

    // ------------------------------------------------------------ 聚合

    private BaseResult aggregate(Relation filtered, Ast.QueryBody body,
                                 Map<Ast.Expr, Ast.Func> aggregates) {
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
            // 无 GROUP BY:恒一组(空表也要一行,FR-585)
            Group single = newGroup(aggNodes, aggregates, filtered.schema);
            groups.put(List.of(), single);
            for (Object[] row : filtered.rows) {
                for (Ast.Expr node : aggNodes) {
                    single.accs.get(node).add(row, filtered.schema);
                }
            }
        } else {
            for (Object[] row : filtered.rows) {
                List<Object> key = new ArrayList<>(groupBy.size());
                for (Ast.Expr expr : groupBy) {
                    key.add(ValueOps.key(evaluator.eval(expr, row, filtered.schema)));
                }
                Group group = groups.get(key);
                if (group == null) {
                    group = newGroup(aggNodes, aggregates, filtered.schema);
                    group.keyValues = new ArrayList<>(groupBy.size());
                    for (Ast.Expr expr : groupBy) {
                        group.keyValues.add(evaluator.eval(expr, row, filtered.schema));
                    }
                    groups.put(key, group);
                }
                for (Ast.Expr node : aggNodes) {
                    group.accs.get(node).add(row, filtered.schema);
                }
            }
        }

        Schema resultSchema = groupedSchema.build();
        // 聚合节点 → 结果列序号:投影 / HAVING / ORDER BY 都从当前行取聚合值。
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

    private static final class Group {
        final Map<Ast.Expr, Aggregates.Accumulator> accs = new IdentityHashMap<>();
        List<Object> keyValues = List.of();
    }

    private Group newGroup(List<Ast.Expr> nodes, Map<Ast.Expr, Ast.Func> aggregates,
                           Schema schema) {
        Group group = new Group();
        for (Ast.Expr node : nodes) {
            group.accs.put(node, Aggregates.create(aggregates.get(node), evaluator, warnings));
        }
        return group;
    }

    /**
     * FR-586 / FR-583:非分组非聚合列必须报错。
     *
     * <p>聚合节点内部不检查;列引用必须与某个 GROUP BY 表达式同文,或命中投影别名。</p>
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
        if (expr instanceof Ast.InSubquery || expr instanceof Ast.ScalarSubquery) {
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

    /** 预求值表达式里的子查询(不支持相关子查询,归 P6)。 */
    private void preparedSubqueries(Relation relation, Ast.Expr expr, Schema schema) {
        if (expr == null) {
            return;
        }
        List<Ast.Expr> subqueries = new ArrayList<>();
        Evaluator.collectSubqueries(expr, subqueries);
        for (Ast.Expr sub : subqueries) {
            if (subqueryValues.containsKey(sub)) {
                continue;
            }
            if (sub instanceof Ast.ScalarSubquery scalar) {
                Relation rel = runSubquery(scalar.query(), schema);
                if (rel.schema.size() != 1) {
                    throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                            "标量子查询只能返回一列(实际 " + rel.schema.size() + " 列)");
                }
                if (rel.rows.size() > 1) {
                    throw new SqlError(SqlError.ER_SUBQUERY_NO_1_ROW, "21000",
                            "标量子查询返回了多行(" + rel.rows.size() + " 行),K108");
                }
                subqueryValues.put(sub, rel.rows.isEmpty() ? null : rel.rows.get(0)[0]);
            } else if (sub instanceof Ast.InSubquery in) {
                Relation rel = runSubquery(in.query(), schema);
                if (rel.schema.size() != 1) {
                    throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                            "IN 子查询只能返回一列(实际 " + rel.schema.size() + " 列)");
                }
                InSet set = new InSet();
                for (Object[] row : rel.rows) {
                    set.add(row[0]);
                }
                subqueryValues.put(sub, set);
            }
        }
    }

    private Relation runSubquery(Ast.QueryBody body, Schema outer) {
        QueryEngine sub = new QueryEngine(kv, catalog, view, options, warnings);
        sub.outerSchema = outer;
        try {
            return sub.run(body);
        } catch (SqlError e) {
            if (e.code() == SqlError.ER_BAD_FIELD_ERROR && outer != null) {
                // 列在外层存在 → 相关子查询(P6),不是"列不存在"。
                String message = e.getMessage() == null ? "" : e.getMessage();
                for (int i = 0; i < outer.size(); i++) {
                    if (message.contains(outer.name(i))) {
                        throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                                "相关子查询(外层列进内层)归 P6,本期不支持");
                    }
                }
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ EXPLAIN

    /** EXPLAIN 用的计划文本(与实际执行共用同一套结构判断)。 */
    public List<String> explain(Ast.QueryBody body) {
        List<String> lines = new ArrayList<>();
        lines.add("访问路径: TABLE SCAN(向量化,批大小=" + options.batchRows() + ")");
        if (body.from() != null) {
            lines.add("表: " + describeFrom(body.from().first()));
            for (Ast.Join join : body.from().joins()) {
                String type = join.type() == Ast.JoinType.LEFT ? "LEFT JOIN" : "JOIN";
                boolean hash = join.on() != null && looksEqui(join.on());
                lines.add("连接: " + type + " → " + (hash ? "Hash Join(小表建哈希)"
                        : "Nested Loop Join") + " / " + describeFrom(join.right()));
            }
        }
        if (body.where() != null) {
            lines.add("过滤: " + ExprPrinter.print(body.where()));
        }
        if (!body.groupBy().isEmpty()) {
            StringBuilder keys = new StringBuilder();
            for (Ast.Expr expr : body.groupBy()) {
                if (keys.length() > 0) {
                    keys.append(", ");
                }
                keys.append(ExprPrinter.print(expr));
            }
            lines.add("聚合: Hash Aggregate(分组键=[" + keys + "])");
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
            lines.add("分组后过滤(HAVING): " + ExprPrinter.print(body.having()));
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
                lines.add("排序: TopN(keep=" + (body.limit()
                        + (body.offset() == null ? 0 : body.offset())) + ",键=[" + keys + "])");
            } else {
                lines.add("排序: Sort(键=[" + keys + "],降序=" + desc
                        + ",超阈值走外部归并)");
            }
        }
        lines.add("实际读取列: " + String.join(", ", columnsRead(body)));
        return lines;
    }

    private String describeFrom(Ast.FromItem item) {
        if (item.subquery() != null) {
            return "(子查询) AS " + item.alias();
        }
        return item.table() + (item.alias() == null ? "" : " AS " + item.alias());
    }

    private Set<String> columnsRead(Ast.QueryBody body) {
        Set<String> names = new LinkedHashSet<>();
        for (Ast.SelectItem item : body.items()) {
            collectColumns(item.expr(), names);
        }
        collectColumns(body.where(), names);
        for (Ast.Expr expr : body.groupBy()) {
            collectColumns(expr, names);
        }
        collectColumns(body.having(), names);
        for (Ast.OrderItem item : body.orderBy()) {
            collectColumns(item.expr(), names);
        }
        if (body.from() != null) {
            for (Ast.Join join : body.from().joins()) {
                collectColumns(join.on(), names);
            }
        }
        return names;
    }

    private static void collectColumns(Ast.Expr expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Ast.Col col) {
            out.add(col.qualifier() == null ? col.name() : col.qualifier() + "." + col.name());
            return;
        }
        for (Ast.Expr child : Evaluator.children(expr)) {
            collectColumns(child, out);
        }
    }
}
