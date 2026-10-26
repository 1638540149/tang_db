package com.wangyu.tangdb.parser;

import com.wangyu.tangdb.storage.DataType;

import java.util.List;

/**
 * P2 的抽象语法树(SPEC-P3.5 起表引用带可选的库名,并新增库级语句)。
 *
 * <p>只覆盖规约 §2.1 的能力:CREATE/DROP/TRUNCATE/SHOW/DESC/INSERT/UPDATE/DELETE/SELECT,
 * 以及 P3 的 BEGIN/COMMIT/ROLLBACK、P3.5 的 CREATE/DROP DATABASE / USE。
 * 没有 JOIN、聚合、子查询、ORDER BY、表达式 —— 那些是后续期次的事。</p>
 */
public final class Ast {

    private Ast() {
    }

    /** 一条语句。 */
    public sealed interface Statement permits CreateTable, DropTable, TruncateTable,
            ShowTables, ShowDatabases, Describe, Insert, Update, Delete, Select,
            Begin, Commit, Rollback, CreateDatabase, DropDatabase, Use {
    }

    // ------------------------------------------------------------ P3.5:库

    /** CREATE DATABASE:建库。 */
    public record CreateDatabase(String name) implements Statement {
    }

    /** DROP DATABASE:删库(非空拒绝,default 不可删)。 */
    public record DropDatabase(String name) implements Statement {
    }

    /** USE:切换会话当前库。 */
    public record Use(String name) implements Statement {
    }

    // ------------------------------------------------------------ P3:事务控制

    /** BEGIN:向 BE 取快照,开启显式事务。 */
    public record Begin() implements Statement {
    }

    /** COMMIT:提交写集(冲突时整批不写)。 */
    public record Commit() implements Statement {
    }

    /** ROLLBACK:丢弃写集,释放 BE 句柄。 */
    public record Rollback() implements Statement {
    }

    /** 列定义。NULL 性由主键标记推导:主键列不可空,其余列可空。 */
    public record ColumnDef(String name, DataType type, int length, boolean primaryKey) {
        public boolean nullable() {
            return !primaryKey;
        }
    }

    /** 表引用:database 为 null 表示未限定(用当前库)。 */
    public record CreateTable(String database, String table, List<ColumnDef> columns)
            implements Statement {
    }

    public record DropTable(String database, String table) implements Statement {
    }

    public record TruncateTable(String database, String table) implements Statement {
    }

    public record ShowTables() implements Statement {
    }

    /** SHOW DATABASES:列出全部库(含 default)。 */
    public record ShowDatabases() implements Statement {
    }

    public record Describe(String database, String table) implements Statement {
    }

    public record Insert(String database, String table, List<String> columns, List<List<Object>> rows)
            implements Statement {
    }

    public record Assignment(String column, Object value) {
    }

    public record Update(String database, String table, List<Assignment> assignments, Condition where)
            implements Statement {
    }

    public record Delete(String database, String table, Condition where) implements Statement {
    }

    /** 投影项:列名 + 可选别名(K29)。 */
    public record Item(String column, String alias) {
    }

    public record Select(String database, String table, boolean star, List<Item> items,
                         Condition where, Integer limit) implements Statement {
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
