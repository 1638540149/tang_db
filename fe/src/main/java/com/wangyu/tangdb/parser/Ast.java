package com.wangyu.tangdb.parser;

import com.wangyu.tangdb.storage.DataType;

import java.util.List;

/**
 * 抽象语法树(SPEC-P2 ~ SPEC-P4)。
 *
 * <p>P4 新增:CREATE/DROP INDEX、建表内联索引、SHOW 家族、EXPLAIN、KILL;
 * SELECT 增加 {@code ORDER BY}(单列)与 {@code COUNT(*)}(仅虚拟表执行,
 * 用户表上明确报不支持,K98)。</p>
 */
public final class Ast {

    private Ast() {
    }

    /** 一条语句。 */
    public sealed interface Statement permits CreateTable, DropTable, TruncateTable,
            ShowTables, ShowDatabases, ShowIndexes, ShowIndexDetails, ShowSimple, ShowTableStatus,
            ShowSegments, ShowCreateTable, ShowCreateDatabase, ShowColumns, Describe,
            Insert, Update, Delete, Select, SelectLiterals, Explain, Kill,
            Begin, Commit, Rollback, CreateDatabase, DropDatabase, Use,
            CreateIndex, DropIndex, SelectQuery, ShowImportTasks {
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

    /** 建表内联索引(FR-462 / FR-463):name 为 null 表示自动命名。 */
    public record InlineIndex(String name, String column, boolean unique) {
    }

    /** 表引用:database 为 null 表示未限定(用当前库)。 */
    public record CreateTable(String database, String table, List<ColumnDef> columns,
                              List<InlineIndex> inlineIndexes) implements Statement {
    }

    public record DropTable(String database, String table) implements Statement {
    }

    public record TruncateTable(String database, String table) implements Statement {
    }

    // ------------------------------------------------------------ P4:索引 DDL

    /** CREATE [UNIQUE] INDEX <idx> ON [db.]<tbl>(<col>)。 */
    public record CreateIndex(String database, String table, String name, String column,
                              boolean unique) implements Statement {
    }

    /** DROP INDEX <idx> ON [db.]<tbl>。 */
    public record DropIndex(String database, String table, String name) implements Statement {
    }

    // ------------------------------------------------------------ SHOW

    /** SHOW TABLES / FULL TABLES [FROM db] [LIKE pat] [WHERE cond]。 */
    public record ShowTables(boolean full, String database, String like, Condition where)
            implements Statement {
    }

    /** SHOW DATABASES:列出全部库(含 default)。 */
    public record ShowDatabases() implements Statement {
    }

    /** SHOW INDEXES [FROM <tbl>] [WHERE <cond>](tang_db 自有形态,FR-471 / FR-562)。 */
    public record ShowIndexes(String database, String table, Condition where) implements Statement {
    }

    /** SHOW INDEX / SHOW KEYS FROM <tbl> [WHERE <cond>](MySQL 13 列形态,FR-532 / FR-562)。 */
    public record ShowIndexDetails(String database, String table, Condition where)
            implements Statement {
    }

    /** SHOW 里"列定义正确的空集/固定行"的简单变体(FR-533 ~ FR-536)。 */
    public enum ShowKind {
        ENGINES,
        COLLATION,
        CHARACTER_SET,
        GRANTS,
        WARNINGS,
        ERRORS,
        /** SHOW COUNT(*) WARNINGS(FR-561):单值 @@session.warning_count。 */
        WARNING_COUNT,
        /** SHOW COUNT(*) ERRORS(FR-561):单值 @@session.error_count。 */
        ERROR_COUNT,
        TRIGGERS,
        EVENTS,
        ROUTINES,
        PROCEDURE_STATUS,
        FUNCTION_STATUS,
        VARIABLES,
        STATUS,
        PROCESSLIST,
        FULL_PROCESSLIST
    }

    public record ShowSimple(ShowKind kind, String like, Condition where) implements Statement {
        public ShowSimple(ShowKind kind, String like) {
            this(kind, like, null);
        }
    }

    /** SHOW TABLE STATUS [FROM db] [LIKE pat]。 */
    public record ShowTableStatus(String database, String like) implements Statement {
    }

    /** SHOW IMPORT TASKS:CSV 导入任务列表(FR-614;常驻任务归 P9)。 */
    public record ShowImportTasks() implements Statement {
    }

    /** SHOW SEGMENTS [FROM <tbl>]:自有诊断语句(FR-566),给出列式压缩真实统计。 */
    public record ShowSegments(String database, String table) implements Statement {
    }

    /** SHOW CREATE TABLE <t>:输出可 round-trip 的 DDL(K96)。 */
    public record ShowCreateTable(String database, String table) implements Statement {
    }

    /** SHOW CREATE DATABASE <db>。 */
    public record ShowCreateDatabase(String database) implements Statement {
    }

    /** SHOW [FULL] COLUMNS FROM <t> [FROM <db>] [LIKE pat]。 */
    public record ShowColumns(boolean full, String database, String table, String like)
            implements Statement {
    }

    /** EXPLAIN <SELECT|UPDATE|DELETE>:单列文本计划(FR-513)。 */
    public record Explain(Statement statement) implements Statement {
    }

    /** KILL [CONNECTION] <id>(FR-541)。 */
    public record Kill(long id) implements Statement {
    }

    public record Describe(String database, String table) implements Statement {
    }

    public record Insert(String database, String table, List<String> columns, List<List<Object>> rows)
            implements Statement {
    }

    public record Assignment(String column, Object value) {
    }

    /**
     * UPDATE。{@code limit} 非 null 表示语句带了 {@code LIMIT}:语法可解析,
     * 执行层明确报 1235(FR-554,不做半实现)。
     */
    public record Update(String database, String table, List<Assignment> assignments,
                         Condition where, Integer limit) implements Statement {
    }

    /** DELETE。{@code limit} 非 null 时执行层明确报 1235(FR-554)。 */
    public record Delete(String database, String table, Condition where, Integer limit)
            implements Statement {
    }

    /** 投影项:列名 + 可选别名(K29)。 */
    public record Item(String column, String alias) {
    }

    /**
     * ORDER BY 项。多列只对 {@code information_schema.*} 生效(K99);
     * 用户表上执行时明确报不支持(K98)。
     */
    public record Order(String column, boolean descending) {
    }

    /**
     * SELECT。{@code limit} 是行数,{@code offset} 是先跳过的行数(两者都可为 null)。
     * 三种写法 {@code LIMIT n} / {@code LIMIT off,n} / {@code LIMIT n OFFSET off}
     * 在 Parser 里统一归一到这两个字段(FR-549 ~ FR-552)。
     *
     * <p>{@code alias} 是 {@code COUNT(*)} 的可选别名(FR-555 / K102):与
     * {@link Item#alias()} 同义、同一套解析(带不带 {@code AS} 都收)。仅虚拟表执行时使用;
     * 用户表上的 {@code COUNT(*)} 无论带不带别名都报 1235(FR-554 / H-5)。</p>
     */
    public record Select(String database, String table, boolean star, List<Item> items,
                         Condition where, List<Order> orderBy, Integer limit, Integer offset,
                         boolean countStar, String alias) implements Statement {
    }

    /**
     * 一个无 {@code FROM} 的字面量投影项(FR-560)。
     *
     * @param value 字面量值(Integer / Long / Double / String / null)
     * @param name  结果列名 —— 字面量文本(整数 / 小数按原文,字符串取内容,NULL 为 {@code NULL})
     */
    public record LiteralItem(Object value, String name) {
    }

    /**
     * 无 {@code FROM} 的字面量投影 {@code SELECT <literal>[, <literal>...]}(FR-560):
     * 按字面量原样返回一行,列名为字面量文本。表达式 / 函数仍归 P5(报 1064,边界 39)。
     */
    public record SelectLiterals(List<LiteralItem> items) implements Statement {
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

    // ============================================================ SPEC-P5:表达式与查询

    /**
     * P5 表达式(SPEC-P5 FR-593 ~ FR-598 / FR-609 ~ FR-613)。
     *
     * <p>老的 {@link Condition} 只覆盖"列 op 常量"这一窄子集,继续服务 P2~P4 路径;
     * P5 的复杂查询(表达式 / 函数 / 聚合 / JOIN / 子查询)走这棵新的表达式树。</p>
     */
    public sealed interface Expr permits Lit, Col, Arith, Neg, Not, Cmp, LogicalAnd, LogicalOr,
            IsNull, Like, Between, InExpr, InSubquery, ScalarSubquery, Func, Case, Cast {
    }

    /** 字面量;text 保留原文用于表达式投影的列名(K107)。 */
    public record Lit(Object value, String text) implements Expr {
    }

    /** 列引用:qualifier 为表别名 / 表名,null 表示未限定。 */
    public record Col(String qualifier, String name) implements Expr {
    }

    public enum ArithOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%");

        private final String text;

        ArithOp(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Arith(ArithOp op, Expr left, Expr right) implements Expr {
    }

    public record Neg(Expr operand) implements Expr {
    }

    public record Not(Expr operand) implements Expr {
    }

    public record Cmp(Op op, Expr left, Expr right) implements Expr {
    }

    public record LogicalAnd(Expr left, Expr right) implements Expr {
    }

    public record LogicalOr(Expr left, Expr right) implements Expr {
    }

    public record IsNull(Expr operand, boolean negated) implements Expr {
    }

    /** LIKE 模式;escape 为转义字符(默认反斜杠,ESCAPE '' 表示不转义)。 */
    public record Like(Expr operand, Expr pattern, String escape) implements Expr {
    }

    public record Between(Expr operand, Expr lower, Expr upper, boolean negated) implements Expr {
    }

    public record InExpr(Expr operand, List<Expr> values, boolean negated) implements Expr {
    }

    public record InSubquery(Expr operand, QueryBody query, boolean negated) implements Expr {
    }

    public record ScalarSubquery(QueryBody query) implements Expr {
    }

    /** 函数 / 聚合:name 统一大写;star 仅 COUNT(*) 使用。 */
    public record Func(String name, List<Expr> args, boolean distinct, boolean star) implements Expr {
    }

    public record Case(Expr base, List<When> whens, Expr otherwise) implements Expr {
    }

    public record When(Expr condition, Expr result) {
    }

    public record Cast(Expr operand, DataType type) implements Expr {
    }

    // ------------------------------------------------------------ SELECT(完整形态)

    /** 投影项:star = true 时 expr 为 null;starQualifier 非空表示 {@code t.*}。 */
    public record SelectItem(Expr expr, String alias, boolean star, String starQualifier) {
    }

    public enum JoinType {
        INNER, LEFT, CROSS
    }

    /** FROM 里的一个表或子查询;alias 为本查询内使用的名字。 */
    public record FromItem(String database, String table, String alias, QueryBody subquery) {
    }

    public record Join(JoinType type, FromItem right, Expr on) {
    }

    public record FromClause(FromItem first, List<Join> joins) {
    }

    public record OrderItem(Expr expr, boolean descending) {
    }

    /** 一条完整查询(不含 EXPLAIN 外壳)。 */
    public record QueryBody(List<SelectItem> items, FromClause from, Expr where,
                            List<Expr> groupBy, Expr having, List<OrderItem> orderBy,
                            Integer limit, Integer offset) {
    }

    /** P5 的 SELECT 语句;执行入口与老 {@link Select} 分开,但共用同一套语义。 */
    public record SelectQuery(QueryBody body) implements Statement {
    }

}
