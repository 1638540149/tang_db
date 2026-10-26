package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.util.ArrayList;
import java.util.List;

/**
 * 已绑定到列序号的 WHERE 条件。求值遵循 SQL 三值逻辑:
 * TRUE / FALSE / UNKNOWN(null),只有 TRUE 的行被保留(FR-147)。
 */
public sealed interface Predicate {

    /** 三值求值:Boolean.TRUE / Boolean.FALSE / null(UNKNOWN)。 */
    Boolean test(Object[] row);

    /** 恒真:没有 WHERE 时使用。 */
    record AlwaysTrue() implements Predicate {
        @Override
        public Boolean test(Object[] row) {
            return Boolean.TRUE;
        }
    }

    record And(Predicate left, Predicate right) implements Predicate {
        @Override
        public Boolean test(Object[] row) {
            Boolean l = left.test(row);
            if (Boolean.FALSE.equals(l)) {
                return Boolean.FALSE;
            }
            Boolean r = right.test(row);
            if (Boolean.FALSE.equals(r)) {
                return Boolean.FALSE;
            }
            if (l == null || r == null) {
                return null;
            }
            return Boolean.TRUE;
        }
    }

    record Or(Predicate left, Predicate right) implements Predicate {
        @Override
        public Boolean test(Object[] row) {
            Boolean l = left.test(row);
            if (Boolean.TRUE.equals(l)) {
                return Boolean.TRUE;
            }
            Boolean r = right.test(row);
            if (Boolean.TRUE.equals(r)) {
                return Boolean.TRUE;
            }
            if (l == null || r == null) {
                return null;
            }
            return Boolean.FALSE;
        }
    }

    record Comparison(int columnIndex, Column column, Ast.Op op, Object literal) implements Predicate {
        @Override
        public Boolean test(Object[] row) {
            Object value = row[columnIndex];
            if (value == null || literal == null) {
                return null;
            }
            int cmp = Values.compare(value, column, literal);
            return switch (op) {
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
            };
        }
    }

    record In(int columnIndex, Column column, List<Object> literals) implements Predicate {
        @Override
        public Boolean test(Object[] row) {
            Object value = row[columnIndex];
            if (value == null) {
                return null;
            }
            boolean sawNull = false;
            for (Object literal : literals) {
                if (literal == null) {
                    sawNull = true;
                    continue;
                }
                if (Values.compare(value, column, literal) == 0) {
                    return Boolean.TRUE;
                }
            }
            return sawNull ? null : Boolean.FALSE;
        }
    }

    /** 把 AST 条件绑定到表定义上。列不存在、类型不匹配在此报错。 */
    static Predicate bind(Ast.Condition condition, TableMeta table) {
        if (condition == null) {
            return new AlwaysTrue();
        }
        if (condition instanceof Ast.And and) {
            Predicate left = bind(and.left(), table);
            Predicate right = bind(and.right(), table);
            return new And(left, right);
        }
        if (condition instanceof Ast.Or or) {
            return new Or(bind(or.left(), table), bind(or.right(), table));
        }
        if (condition instanceof Ast.Compare cmp) {
            Column column = requireColumn(table, cmp.column());
            Values.checkComparable(column, cmp.value());
            return new Comparison(column.ordinal(), column, cmp.op(), cmp.value());
        }
        if (condition instanceof Ast.In in) {
            Column column = requireColumn(table, in.column());
            List<Object> literals = new ArrayList<>(in.values().size());
            for (Object literal : in.values()) {
                Values.checkComparable(column, literal);
                literals.add(literal);
            }
            return new In(column.ordinal(), column, List.copyOf(literals));
        }
        throw new CatalogException("不支持的 WHERE 条件");
    }

    static Column requireColumn(TableMeta table, String name) {
        Column column = table.column(name);
        if (column == null) {
            throw new CatalogException("列 " + name + " 不存在(表 " + table.name() + ")",
                    com.wangyu.tangdb.execute.SqlError.ER_BAD_FIELD_ERROR);
        }
        return column;
    }
}
