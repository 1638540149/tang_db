package com.wangyu.tangdb.parser;

import com.wangyu.tangdb.storage.DataType;

import java.util.List;

/**
 * P2 的抽象语法树。
 *
 * <p>只覆盖规约 §2.1 的能力:CREATE/DROP/TRUNCATE/SHOW/DESC/INSERT/UPDATE/DELETE/SELECT。
 * 没有 JOIN、聚合、子查询、ORDER BY、表达式 —— 那些是后续期次的事。</p>
 */
public final class Ast {

    private Ast() {
    }

    /** 一条语句。 */
    public sealed interface Statement permits CreateTable, DropTable, TruncateTable,
            ShowTables, ShowDatabases, Describe, Insert, Update, Delete, Select {
    }

    /** 列定义。NULL 性由主键标记推导:主键列不可空,其余列可空。 */
    public record ColumnDef(String name, DataType type, int length, boolean primaryKey) {
        public boolean nullable() {
            return !primaryKey;
        }
    }

    public record CreateTable(String table, List<ColumnDef> columns) implements Statement {
    }

    public record DropTable(String table) implements Statement {
    }

    public record TruncateTable(String table) implements Statement {
    }

    public record ShowTables() implements Statement {
    }

    /** SHOW DATABASES:只有默认库 default(K22 / FR-200),由 FE 直接回答。 */
    public record ShowDatabases() implements Statement {
    }

    public record Describe(String table) implements Statement {
    }

    public record Insert(String table, List<String> columns, List<List<Object>> rows) implements Statement {
    }

    public record Assignment(String column, Object value) {
    }

    public record Update(String table, List<Assignment> assignments, Condition where) implements Statement {
    }

    public record Delete(String table, Condition where) implements Statement {
    }

    /** 投影项:列名 + 可选别名(K29)。 */
    public record Item(String column, String alias) {
    }

    public record Select(String table, boolean star, List<Item> items, Condition where, Integer limit)
            implements Statement {
    }

    // ------------------------------------------------------------ 条件

    /** WHERE 条件。 */
    public sealed interface Condition permits Compare, In, And, Or {
    }

    public enum Op {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

        private final String text;

        Op(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        public Op negate() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
            };
        }
    }

    /** 列 与 常量 的比较。常量可能是 Integer / Long / Double / String / Boolean / null。 */
    public record Compare(String column, Op op, Object value) implements Condition {
    }

    /** 列 IN (常量列表)。 */
    public record In(String column, List<Object> values) implements Condition {
    }

    public record And(Condition left, Condition right) implements Condition {
    }

    public record Or(Condition left, Condition right) implements Condition {
    }
}
