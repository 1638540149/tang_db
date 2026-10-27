package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Ast;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 聚合累加器(SPEC-P5 D 组 / FR-581 ~ FR-586 / FR-612)。
 *
 * <ul>
 *   <li>除 COUNT 外,空输入一律 NULL;COUNT 返回 0(FR-581 / 边界 50);</li>
 *   <li>SUM/AVG/MIN/MAX 跳过 NULL;COUNT(DISTINCT) 的 NULL 不计入(FR-584 / 边界 43);</li>
 *   <li>VARIANCE/STDDEV 默认**总体**口径(K119);单值 = 0(边界 64);</li>
 *   <li>GROUP_CONCAT 默认分隔符 ',' ,上限 1MB,超长截断并告警(K118 / 边界 63)。</li>
 * </ul>
 */
public final class Aggregates {

    public static final int GROUP_CONCAT_LIMIT_BYTES = 1024 * 1024;

    private Aggregates() {
    }

    /**
     * 为一个聚合函数节点创建累加器。
     */
    public static Accumulator create(Ast.Func func, Evaluator evaluator,
                                     Evaluator.Warnings warnings) {
        String name = func.name().toUpperCase(Locale.ROOT);
        switch (name) {
            case "COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE", "GROUP_CONCAT":
                break;
            default:
                throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "不支持的聚合函数: " + func.name());
        }
        if (func.distinct() && !name.equals("COUNT")) {
            // SUM(DISTINCT) / AVG(DISTINCT) 归 P6(SPEC-P5 §2.2)。
            throw new SqlError(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    name + "(DISTINCT ...) 归 P6(本期只支持 COUNT(DISTINCT ...))");
        }
        return new Accumulator(func, name, evaluator, warnings);
    }

    /** 聚合累加器。 */
    public static final class Accumulator {

        private final Ast.Func func;
        private final String name;
        private final Evaluator evaluator;
        private final Evaluator.Warnings warnings;

        private long count;
        private boolean hasValue;
        private boolean anyDouble;
        private long longSum;
        private double doubleSum;
        private Object min;
        private Object max;
        private Set<Object> distinct;
        private StringBuilder concat;
        private long concatBytes;
        private boolean concatTruncated;

        // Welford(总体方差)
        private long statCount;
        private double statMean;
        private double statM2;

        Accumulator(Ast.Func func, String name, Evaluator evaluator,
                    Evaluator.Warnings warnings) {
            this.func = func;
            this.name = name;
            this.evaluator = evaluator;
            this.warnings = warnings;
            if (func.distinct()) {
                distinct = new LinkedHashSet<>();
            }
            if (name.equals("GROUP_CONCAT")) {
                concat = new StringBuilder();
            }
        }

        /** 喂入一行。 */
        public void add(Object[] row, Schema schema) {
            if (func.star()) {
                count++; // COUNT(*):每行都算,不看 NULL
                return;
            }
            Object value = evaluator.eval(func.args().get(0), row, schema);
            if (value == null) {
                return; // 所有聚合都跳过 NULL(COUNT(col) 也不计)
            }
            if (distinct != null) {
                if (!distinct.add(ValueOps.key(value))) {
                    return;
                }
            }
            switch (name) {
                case "COUNT" -> count++;
                case "SUM", "AVG" -> {
                    count++;
                    hasValue = true;
                    if (value instanceof Double || value instanceof Float) {
                        anyDouble = true;
                        doubleSum += ValueOps.toDouble(value);
                    } else {
                        longSum += ValueOps.toLong(value);
                        doubleSum += ValueOps.toDouble(value);
                    }
                }
                case "MIN" -> {
                    if (min == null || ValueOps.compare(value, min) < 0) {
                        min = value;
                    }
                    hasValue = true;
                }
                case "MAX" -> {
                    if (max == null || ValueOps.compare(value, max) > 0) {
                        max = value;
                    }
                    hasValue = true;
                }
                case "STDDEV", "VARIANCE" -> {
                    double x = ValueOps.toDouble(value);
                    statCount++;
                    double delta = x - statMean;
                    statMean += delta / statCount;
                    statM2 += delta * (x - statMean);
                    hasValue = true;
                }
                case "GROUP_CONCAT" -> appendConcat(value);
                default -> throw new IllegalStateException("unreachable");
            }
        }

        private void appendConcat(Object value) {
            String text = value instanceof byte[] b ? JsonCodec.toText(JsonCodec.decode(b))
                    : com.wangyu.tangdb.storage.Values.display(value);
            int separatorBytes = concat.length() == 0 ? 0 : 1;
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (concatBytes + separatorBytes + bytes > GROUP_CONCAT_LIMIT_BYTES) {
                if (!concatTruncated) {
                    concatTruncated = true;
                    warnings.warn("GROUP_CONCAT 结果超过 1MB 上限,已截断");
                }
                return;
            }
            if (separatorBytes > 0) {
                concat.append(',');
                concatBytes++;
            }
            concat.append(text);
            concatBytes += bytes;
        }

        /** 取聚合结果。 */
        public Object result() {
            return switch (name) {
                case "COUNT" -> count;
                case "SUM" -> !hasValue ? null : (anyDouble ? (Object) doubleSum : (Object) longSum);
                case "AVG" -> !hasValue ? null : doubleSum / count;
                case "MIN" -> min;
                case "MAX" -> max;
                case "VARIANCE" -> !hasValue ? null : populationVariance();
                case "STDDEV" -> !hasValue ? null : Math.sqrt(populationVariance());
                case "GROUP_CONCAT" -> concat.length() == 0 ? null : concat.toString();
                default -> throw new IllegalStateException("unreachable");
            };
        }

        private double populationVariance() {
            if (statCount == 0) {
                return 0.0;
            }
            return statM2 / statCount; // 总体方差(K119);单值 → 0(边界 64)
        }
    }
}
