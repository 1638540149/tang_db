package com.wangyu.tangdb.parser;

import com.wangyu.tangdb.query.Functions;
import com.wangyu.tangdb.storage.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写递归下降语法分析器(D2)。
 *
 * <p>文法(只覆盖 P2 + P2.1 范围):</p>
 * <pre>
 * statement := CREATE TABLE ident '(' coldef (',' coldef)* ')'
 *            | DROP TABLE ident
 *            | TRUNCATE TABLE ident
 *            | SHOW TABLES | SHOW DATABASES
 *            | DESC ident
 *            | INSERT INTO ident ['(' ident (',' ident)* ')'] VALUES 行 (',' 行)*   -- 省略列表 = 全部列
 *            | UPDATE ident SET ident '=' literal (',' ident '=' literal)* [WHERE cond]
 *            | DELETE FROM ident [WHERE cond]
 *            | SELECT ('*' | 'COUNT' '(' '*' ')' [[AS] ident] | ident [[AS] ident] (',' ...)*) FROM ident [WHERE cond] [LIMIT int]
 * ident      := 普通标识符 | '`' 任意字符 '`'(反引号不解释关键字,不改变大小写语义)
 * cond       := cond AND cond | cond OR cond | '(' cond ')' | ident op literal | ident IN '(' literal,... ')'
 * </pre>
 */
public final class Parser {

    /**
     * 投影项后面出现这些关键字时,它是子句起点,不是不带 {@code AS} 的别名(K102)。
     */
    private static final java.util.Set<String> ITEM_FOLLOW_KEYWORDS = java.util.Set.of(
            "FROM", "WHERE", "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET",
            "AND", "OR", "IN", "ASC", "DESC", "UNION", "JOIN", "ON");

    private final List<Token> tokens;
    private int index;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** 解析一条完整语句(允许末尾带分号)。 */
    public static Ast.Statement parse(String sql) {
        Parser p = new Parser(new Lexer(sql).tokenize());
        Ast.Statement statement = p.parseStatement();
        p.skipSemicolons();
        if (p.current().type() != TokenType.EOF) {
            throw p.error("语句结束后出现多余内容: " + p.current().text());
        }
        return statement;
    }

    private Ast.Statement parseStatement() {
        Token t = current();
        if (t.type() != TokenType.IDENT) {
            throw error("期望一条 SQL 语句,实际是 " + describe(t));
        }
        return switch (t.text().toUpperCase()) {
            case "CREATE" -> parseCreate();
            case "DROP" -> parseDrop();
            case "TRUNCATE" -> parseTruncate();
            case "SHOW" -> parseShow();
            case "DESC", "DESCRIBE" -> parseDescribe();
            case "INSERT" -> parseInsert();
            case "UPDATE" -> parseUpdate();
            case "DELETE" -> parseDelete();
            case "SELECT" -> parseSelect();
            case "EXPLAIN" -> parseExplain();
            case "KILL" -> parseKill();
            case "BEGIN" -> parseBegin();
            case "COMMIT" -> parseCommit();
            case "ROLLBACK" -> parseRollback();
            case "USE" -> parseUse();
            default -> throw error("不支持的语句: " + t.text()
                    + "(支持 CREATE/DROP/TRUNCATE/SHOW/DESC/INSERT/UPDATE/DELETE/SELECT/EXPLAIN/KILL"
                    + "/BEGIN/COMMIT/ROLLBACK/USE)");
        };
    }

    private Ast.Statement parseCreate() {
        expectKeyword("CREATE");
        if (matchKeyword("DATABASE")) {
            return new Ast.CreateDatabase(expectIdentifier("库名"));
        }
        if (matchKeyword("UNIQUE")) {
            expectKeyword("INDEX");
            return parseCreateIndex(true);
        }
        if (matchKeyword("INDEX")) {
            return parseCreateIndex(false);
        }
        expectKeyword("TABLE");
        Ref ref = parseTableRef("表名");
        expect(TokenType.LPAREN, "列定义列表的 '('");
        List<Ast.ColumnDef> columns = new ArrayList<>();
        List<Ast.InlineIndex> inlineIndexes = new ArrayList<>();
        String tablePrimaryKey = null;
        do {
            if (isPrimaryKeyConstraint()) {
                advance(); // PRIMARY
                expectKeyword("KEY");
                expect(TokenType.LPAREN, "主键约束的 '('");
                tablePrimaryKey = expectIdentifier("主键列名");
                if (match(TokenType.COMMA)) {
                    throw error("本期只支持单列主键");
                }
                expect(TokenType.RPAREN, "主键约束的 ')'");
            } else if (isInlineIndexStart()) {
                inlineIndexes.add(parseInlineIndex());
            } else {
                columns.add(parseColumnDef());
            }
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "列定义列表的 ')'");
        parseTableOptions();
        if (columns.isEmpty()) {
            throw error("建表至少要有一个列");
        }
        if (tablePrimaryKey != null) {
            columns = applyTablePrimaryKey(columns, tablePrimaryKey);
        }
        return new Ast.CreateTable(ref.database(), ref.table(), columns, inlineIndexes);
    }

    /** 表级 PRIMARY KEY (`col`) 约束:把对应列标记为主键(只支持单列)。 */
    private List<Ast.ColumnDef> applyTablePrimaryKey(List<Ast.ColumnDef> columns, String pkColumn) {
        List<Ast.ColumnDef> out = new ArrayList<>(columns.size());
        boolean found = false;
        for (Ast.ColumnDef column : columns) {
            boolean isPk = column.name().equalsIgnoreCase(pkColumn);
            if (isPk) {
                if (found) {
                    throw error("主键列 " + pkColumn + " 重复定义");
                }
                if (column.primaryKey()) {
                    throw error("列 " + column.name() + " 同时声明了列级与表级主键");
                }
                found = true;
            }
            if (column.primaryKey() && !isPk) {
                throw error("只能有一个主键(列 " + column.name() + " 与表级主键 " + pkColumn + ")");
            }
            out.add(new Ast.ColumnDef(column.name(), column.type(), column.length(), isPk));
        }
        if (!found) {
            throw error("表级主键指向不存在的列 " + pkColumn);
        }
        return out;
    }

    private boolean isPrimaryKeyConstraint() {
        Token t = current();
        if (t.type() != TokenType.IDENT || !t.text().equalsIgnoreCase("PRIMARY")) {
            return false;
        }
        return index + 1 < tokens.size()
                && tokens.get(index + 1).type() == TokenType.IDENT
                && tokens.get(index + 1).text().equalsIgnoreCase("KEY");
    }

    /** CREATE [UNIQUE] INDEX <idx> ON <tbl>(<col>)。 */
    private Ast.Statement parseCreateIndex(boolean unique) {
        String name = expectIdentifier("索引名");
        expectKeyword("ON");
        Ref ref = parseTableRef("表名");
        expect(TokenType.LPAREN, "索引列的 '('");
        String column = expectIdentifier("索引列名");
        if (match(TokenType.COMMA)) {
            throw error("本期只支持单列索引(复合索引归 P6)");
        }
        expect(TokenType.RPAREN, "索引列的 ')'");
        return new Ast.CreateIndex(ref.database(), ref.table(), name, column, unique);
    }

    /**
     * 建表内联约束:`UNIQUE[(KEY)] [name](<col>)` / `INDEX|KEY [name](<col>)`。
     * 名字可省略(FR-463 自动命名);带名字的形式用于 SHOW CREATE TABLE round-trip(K96)。
     */
    private boolean isInlineIndexStart() {
        Token t = current();
        if (t.type() != TokenType.IDENT) {
            return false;
        }
        String upper = t.text().toUpperCase();
        return upper.equals("UNIQUE") || upper.equals("INDEX") || upper.equals("KEY");
    }

    private Ast.InlineIndex parseInlineIndex() {
        boolean unique = matchKeyword("UNIQUE");
        if (unique) {
            matchKeyword("KEY");
            matchKeyword("INDEX");
        } else if (!matchKeyword("INDEX")) {
            expectKeyword("KEY");
        }
        String name = null;
        if (current().type() != TokenType.LPAREN) {
            name = expectIdentifier("索引名");
        }
        expect(TokenType.LPAREN, "内联索引的 '('");
        String column = expectIdentifier("索引列名");
        if (match(TokenType.COMMA)) {
            throw error("本期只支持单列索引(复合索引归 P6)");
        }
        expect(TokenType.RPAREN, "内联索引的 ')'");
        return new Ast.InlineIndex(name, column, unique);
    }

    /**
     * 表选项(`) DEFAULT CHARSET=utf8mb4 COLLATE=...` 等):SHOW CREATE TABLE 的输出
     * 必须能被原样吃回去(K96),这里把它安全跳过。
     */
    private void parseTableOptions() {
        while (current().type() != TokenType.EOF && current().type() != TokenType.SEMICOLON) {
            Token t = current();
            if (t.type() != TokenType.IDENT) {
                return;
            }
            String upper = t.text().toUpperCase();
            if (upper.equals("DEFAULT")) {
                advance();
                continue;
            }
            if (!upper.equals("CHARSET") && !upper.equals("COLLATE") && !upper.equals("ENGINE")
                    && !upper.equals("AUTO_INCREMENT") && !upper.equals("COMMENT")
                    && !upper.equals("ROW_FORMAT")) {
                return;
            }
            advance();
            if (current().type() == TokenType.EQ) {
                advance();
            }
            if (current().type() == TokenType.IDENT || current().type() == TokenType.STRING
                    || current().type() == TokenType.INTEGER
                    || current().type() == TokenType.QUOTED_IDENT) {
                advance();
            }
        }
    }

    /** 解析一个可限定的表引用:ident 或 ident . ident(db.tbl)。 */
    private Ref parseTableRef(String what) {
        String first = expectIdentifier(what);
        if (match(TokenType.DOT)) {
            return new Ref(first, expectIdentifier("表名"));
        }
        return new Ref(null, first);
    }

    /** 表引用:database 为 null 表示未限定。 */
    private record Ref(String database, String table) {
    }

    private Ast.ColumnDef parseColumnDef() {
        String name = expectIdentifier("列名");
        DataType type = parseDataType();
        int length = 0;
        if (type == DataType.VARCHAR) {
            if (!match(TokenType.LPAREN)) {
                throw error("VARCHAR 必须给出长度,例如 VARCHAR(32)");
            }
            Token lenToken = expect(TokenType.INTEGER, "VARCHAR 的长度");
            long len;
            try {
                len = Long.parseLong(lenToken.text());
            } catch (NumberFormatException e) {
                throw error(lenToken, "VARCHAR 长度非法: " + lenToken.text());
            }
            if (len < 1 || len > 65535) {
                throw error(lenToken, "VARCHAR 长度必须在 1-65535 之间: " + len);
            }
            expect(TokenType.RPAREN, "VARCHAR 长度的 ')'");
            length = (int) len;
        }
        boolean primaryKey = false;
        // 列级修饰:PRIMARY KEY / NOT NULL / NULL / DEFAULT ... / AUTO_INCREMENT / COMMENT ...
        // (NULL 性由主键标记推导;这些修饰只为让 SHOW CREATE TABLE 的输出能原样吃回去,K96)
        while (true) {
            if (matchKeyword("PRIMARY")) {
                expectKeyword("KEY");
                primaryKey = true;
                continue;
            }
            if (matchKeyword("NOT")) {
                expectKeyword("NULL");
                continue;
            }
            if (matchKeyword("NULL")) {
                continue;
            }
            if (matchKeyword("AUTO_INCREMENT")) {
                continue;
            }
            if (matchKeyword("DEFAULT")) {
                parseLiteral();
                continue;
            }
            if (matchKeyword("COMMENT")) {
                expect(TokenType.STRING, "COMMENT 的字符串");
                continue;
            }
            if (matchKeyword("COLLATE")) {
                expectIdentifier("排序规则");
                continue;
            }
            break;
        }
        return new Ast.ColumnDef(name, type, length, primaryKey);
    }

    private DataType parseDataType() {
        Token t = expect(TokenType.IDENT, "列类型");
        DataType type = DataType.parse(t.text());
        if (type == null) {
            throw error(t, "未知的列类型: " + t.text()
                    + "(支持 INT / BIGINT / DOUBLE / VARCHAR(n) / DATE / BOOLEAN)");
        }
        return type;
    }

    // ------------------------------------------------------------ 事务控制(P3)

    private Ast.Statement parseBegin() {
        advance(); // BEGIN
        // 兼容常见的 BEGIN TRANSACTION / BEGIN WORK 写法(都不改变语义)。
        if (!matchKeyword("TRANSACTION")) {
            matchKeyword("WORK");
        }
        return new Ast.Begin();
    }

    private Ast.Statement parseCommit() {
        advance(); // COMMIT
        matchKeyword("WORK");
        return new Ast.Commit();
    }

    private Ast.Statement parseRollback() {
        advance(); // ROLLBACK
        matchKeyword("WORK");
        return new Ast.Rollback();
    }

    private Ast.Statement parseDrop() {
        expectKeyword("DROP");
        if (matchKeyword("DATABASE")) {
            return new Ast.DropDatabase(expectIdentifier("库名"));
        }
        if (matchKeyword("INDEX")) {
            String name = expectIdentifier("索引名");
            expectKeyword("ON");
            Ref ref = parseTableRef("表名");
            return new Ast.DropIndex(ref.database(), ref.table(), name);
        }
        expectKeyword("TABLE");
        Ref ref = parseTableRef("表名");
        return new Ast.DropTable(ref.database(), ref.table());
    }

    private Ast.Statement parseTruncate() {
        expectKeyword("TRUNCATE");
        expectKeyword("TABLE");
        Ref ref = parseTableRef("表名");
        return new Ast.TruncateTable(ref.database(), ref.table());
    }

    private Ast.Statement parseUse() {
        expectKeyword("USE");
        return new Ast.Use(expectIdentifier("库名"));
    }

    private Ast.Statement parseShow() {
        expectKeyword("SHOW");
        boolean full = matchKeyword("FULL");

        // SHOW COUNT(*) WARNINGS / ERRORS(FR-561):单值 @@session.warning_count / error_count。
        if (isCountStar()) {
            advance(); // COUNT
            expect(TokenType.LPAREN, "COUNT 的 '('");
            expect(TokenType.STAR, "COUNT(*) 的 '*'");
            expect(TokenType.RPAREN, "COUNT(*) 的 ')'");
            if (matchKeyword("WARNINGS")) {
                return new Ast.ShowSimple(Ast.ShowKind.WARNING_COUNT, null);
            }
            if (matchKeyword("ERRORS")) {
                return new Ast.ShowSimple(Ast.ShowKind.ERROR_COUNT, null);
            }
            throw error("SHOW COUNT(*) 只支持 WARNINGS / ERRORS");
        }

        // [GLOBAL|SESSION] VARIABLES / STATUS
        if (matchKeyword("GLOBAL") || matchKeyword("SESSION") || matchKeyword("LOCAL")) {
            if (matchKeyword("VARIABLES")) {
                return parseVariablesLikeWhere(Ast.ShowKind.VARIABLES);
            }
            if (matchKeyword("STATUS")) {
                return parseVariablesLikeWhere(Ast.ShowKind.STATUS);
            }
            throw error("只支持 SHOW [GLOBAL|SESSION] VARIABLES / STATUS");
        }

        if (matchKeyword("TABLES")) {
            String db = parseOptionalFrom();
            String like = parseOptionalLike();
            Ast.Condition where = matchKeyword("WHERE") ? parseCondition() : null;
            return new Ast.ShowTables(full, db, like, where);
        }
        if (matchKeyword("DATABASES") || matchKeyword("SCHEMAS")) {
            String like = parseOptionalLike();
            return new Ast.ShowDatabases();
        }
        if (matchKeyword("IMPORT")) {
            expectKeyword("TASKS");
            return new Ast.ShowImportTasks();
        }
        if (matchKeyword("SEGMENTS")) {
            String db = null;
            String table = null;
            if (matchKeyword("FROM") || matchKeyword("IN")) {
                Ref ref = parseTableRef("表名");
                db = ref.database();
                table = ref.table();
            }
            return new Ast.ShowSegments(db, table);
        }
        if (matchKeyword("INDEXES")) {
            String db = null;
            String table = null;
            if (matchKeyword("FROM") || matchKeyword("IN")) {
                Ref ref = parseTableRef("表名");
                db = ref.database();
                table = ref.table();
            }
            Ast.Condition where = matchKeyword("WHERE") ? parseCondition() : null;
            return new Ast.ShowIndexes(db, table, where);
        }
        if (matchKeyword("INDEX") || matchKeyword("KEYS")) {
            if (!matchKeyword("FROM") && !matchKeyword("IN")) {
                throw error("SHOW INDEX / SHOW KEYS 需要 FROM <表>");
            }
            Ref ref = parseTableRef("表名");
            Ast.Condition where = matchKeyword("WHERE") ? parseCondition() : null;
            return new Ast.ShowIndexDetails(ref.database(), ref.table(), where);
        }
        if (matchKeyword("TABLE")) {
            expectKeyword("STATUS");
            String db = parseOptionalFrom();
            return new Ast.ShowTableStatus(db, parseOptionalLike());
        }
        if (matchKeyword("CREATE")) {
            if (matchKeyword("TABLE")) {
                Ref ref = parseTableRef("表名");
                return new Ast.ShowCreateTable(ref.database(), ref.table());
            }
            if (matchKeyword("DATABASE") || matchKeyword("SCHEMA")) {
                return new Ast.ShowCreateDatabase(expectIdentifier("库名"));
            }
            throw error("SHOW CREATE 只支持 TABLE / DATABASE");
        }
        if (matchKeyword("COLUMNS") || matchKeyword("FIELDS")) {
            String db = null;
            String table;
            if (matchKeyword("FROM") || matchKeyword("IN")) {
                Ref ref = parseTableRef("表名");
                db = ref.database();
                table = ref.table();
            } else {
                throw error("SHOW COLUMNS 需要 FROM <表>");
            }
            // MySQL 允许 SHOW COLUMNS FROM tbl FROM db;这里把第二个 FROM 当作库名。
            if (matchKeyword("FROM") || matchKeyword("IN")) {
                db = expectIdentifier("库名");
            }
            return new Ast.ShowColumns(full, db, table, parseOptionalLike());
        }
        if (matchKeyword("ENGINES")) {
            return new Ast.ShowSimple(Ast.ShowKind.ENGINES, null);
        }
        if (matchKeyword("COLLATION")) {
            return new Ast.ShowSimple(Ast.ShowKind.COLLATION, parseOptionalLike());
        }
        if (matchKeyword("CHARACTER")) {
            expectKeyword("SET");
            return new Ast.ShowSimple(Ast.ShowKind.CHARACTER_SET, parseOptionalLike());
        }
        if (matchKeyword("CHARSET")) {
            return new Ast.ShowSimple(Ast.ShowKind.CHARACTER_SET, parseOptionalLike());
        }
        if (matchKeyword("GRANTS")) {
            // SHOW GRANTS [FOR user] —— 本期无权限体系,FOR 部分直接跳过。
            skipRestOfStatement();
            return new Ast.ShowSimple(Ast.ShowKind.GRANTS, null);
        }
        if (matchKeyword("WARNINGS")) {
            skipOptionalLimit();
            return new Ast.ShowSimple(Ast.ShowKind.WARNINGS, null);
        }
        if (matchKeyword("ERRORS")) {
            skipOptionalLimit();
            return new Ast.ShowSimple(Ast.ShowKind.ERRORS, null);
        }
        if (matchKeyword("TRIGGERS")) {
            parseOptionalFrom();
            parseOptionalLike();
            return new Ast.ShowSimple(Ast.ShowKind.TRIGGERS, null);
        }
        if (matchKeyword("EVENTS")) {
            parseOptionalFrom();
            parseOptionalLike();
            return new Ast.ShowSimple(Ast.ShowKind.EVENTS, null);
        }
        if (matchKeyword("ROUTINES")) {
            parseOptionalFrom();
            parseOptionalLike();
            return new Ast.ShowSimple(Ast.ShowKind.ROUTINES, null);
        }
        // SHOW PROCEDURE|FUNCTION|FUNC STATUS [LIKE '<pat>'] [WHERE <cond>](FR-559 / H-6):
        // 本期没有存储过程/函数,恒为空集,但列定义与 SHOW ROUTINES 一致。
        if (peekKeyword("PROCEDURE") || peekKeyword("FUNCTION") || peekKeyword("FUNC")) {
            Ast.ShowKind kind = matchKeyword("PROCEDURE")
                    ? Ast.ShowKind.PROCEDURE_STATUS : Ast.ShowKind.FUNCTION_STATUS;
            if (kind == Ast.ShowKind.FUNCTION_STATUS) {
                advance(); // FUNCTION / FUNC
            }
            expectKeyword("STATUS");
            String routineLike = parseOptionalLike();
            Ast.Condition routineWhere = matchKeyword("WHERE") ? parseCondition() : null;
            return new Ast.ShowSimple(kind, routineLike, routineWhere);
        }
        if (matchKeyword("VARIABLES")) {
            return parseVariablesLikeWhere(Ast.ShowKind.VARIABLES);
        }
        if (matchKeyword("STATUS")) {
            return parseVariablesLikeWhere(Ast.ShowKind.STATUS);
        }
        if (matchKeyword("PROCESSLIST")) {
            return new Ast.ShowSimple(
                    full ? Ast.ShowKind.FULL_PROCESSLIST : Ast.ShowKind.PROCESSLIST, null);
        }
        throw error("不支持的 SHOW 语句");
    }

    /** SHOW VARIABLES / STATUS 的可选 LIKE + WHERE(FR-562;WHERE 走 SHOW 输出的通用过滤路径)。 */
    private Ast.ShowSimple parseVariablesLikeWhere(Ast.ShowKind kind) {
        String like = parseOptionalLike();
        Ast.Condition where = matchKeyword("WHERE") ? parseCondition() : null;
        return new Ast.ShowSimple(kind, like, where);
    }

    /** 可选的 FROM / IN <db>(只取库名,不吃表名)。 */
    private String parseOptionalFrom() {
        if (matchKeyword("FROM") || matchKeyword("IN")) {
            return expectIdentifier("库名");
        }
        return null;
    }

    /** 跳过语句剩余 token(用于 SHOW GRANTS FOR ... 这类本期不解释的尾缀)。 */
    private void skipRestOfStatement() {
        while (current().type() != TokenType.EOF && current().type() != TokenType.SEMICOLON) {
            advance();
        }
    }

    private void skipOptionalLimit() {
        if (matchKeyword("LIMIT")) {
            while (current().type() != TokenType.EOF && current().type() != TokenType.SEMICOLON) {
                advance();
            }
        }
    }

    private String parseOptionalLike() {
        if (matchKeyword("LIKE")) {
            Token t = expect(TokenType.STRING, "LIKE 的模式字符串");
            return t.text();
        }
        return null;
    }

    // ------------------------------------------------------------ P4:EXPLAIN / KILL

    private Ast.Statement parseExplain() {
        expectKeyword("EXPLAIN");
        // 语法层接受任意语句;执行层对 INSERT / DDL / 事务语句明确报"不支持解释"(FR-518)。
        Ast.Statement inner = parseStatement();
        return new Ast.Explain(inner);
    }

    private Ast.Statement parseKill() {
        expectKeyword("KILL");
        matchKeyword("CONNECTION");
        matchKeyword("QUERY");
        Token t = expect(TokenType.INTEGER, "会话 id");
        long id;
        try {
            id = Long.parseLong(t.text());
        } catch (NumberFormatException e) {
            throw error(t, "会话 id 非法: " + t.text());
        }
        return new Ast.Kill(id);
    }

    private Ast.Statement parseDescribe() {
        advance(); // DESC / DESCRIBE
        Ref ref = parseTableRef("表名");
        return new Ast.Describe(ref.database(), ref.table());
    }

    private Ast.Statement parseInsert() {
        expectKeyword("INSERT");
        expectKeyword("INTO");
        Ref ref = parseTableRef("表名");
        // 列名列表可省略(FR-190):空列表即"按表定义顺序取全部列"。
        List<String> columns = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            if (current().type() == TokenType.RPAREN) {
                throw error("列名列表不能为空"); // FR-192 / K38
            }
            do {
                columns.add(expectIdentifier("列名"));
            } while (match(TokenType.COMMA));
            expect(TokenType.RPAREN, "列名列表的 ')'");
        }
        expectKeyword("VALUES");

        List<List<Object>> rows = new ArrayList<>();
        do {
            expect(TokenType.LPAREN, "VALUES 的 '('");
            List<Object> row = new ArrayList<>();
            // 允许空组:解析层放行,由执行器统一报"取值个数(0)与列数(N)不一致"(§9)。
            if (current().type() != TokenType.RPAREN) {
                do {
                    row.add(parseLiteral());
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "VALUES 的 ')'");
            rows.add(row);
        } while (match(TokenType.COMMA));
        return new Ast.Insert(ref.database(), ref.table(), columns, rows);
    }

    private Ast.Statement parseUpdate() {
        expectKeyword("UPDATE");
        Ref ref = parseTableRef("表名");
        expectKeyword("SET");
        List<Ast.Assignment> assignments = new ArrayList<>();
        do {
            String column = expectIdentifier("列名");
            expect(TokenType.EQ, "赋值号的 '='");
            assignments.add(new Ast.Assignment(column, parseLiteral()));
        } while (match(TokenType.COMMA));
        Ast.Condition where = null;
        if (matchKeyword("WHERE")) {
            where = parseCondition();
        }
        Integer limit = null;
        if (peekKeyword("LIMIT")) {
            limit = parseLimitClause().count();
        }
        return new Ast.Update(ref.database(), ref.table(), assignments, where, limit);
    }

    private Ast.Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        Ref ref = parseTableRef("表名");
        Ast.Condition where = null;
        if (matchKeyword("WHERE")) {
            where = parseCondition();
        }
        Integer limit = null;
        if (peekKeyword("LIMIT")) {
            limit = parseLimitClause().count();
        }
        return new Ast.Delete(ref.database(), ref.table(), where, limit);
    }

    private Ast.Statement parseSelect() {
        expectKeyword("SELECT");
        if (isLiteralProjectionStart()) {
            // 先尝试"无 FROM 的纯字面量投影"(FR-560);不是纯字面量就回退到表达式解析,
            // 这样 `SELECT 1 + 2 FROM t` 能走 P5 表达式,而 `SELECT 1 FROM t` 仍按边界 39 拒绝。
            int save = index;
            try {
                Ast.Statement literal = parseLiteralProjection();
                if (current().type() == TokenType.EOF || current().type() == TokenType.SEMICOLON) {
                    return literal;
                }
            } catch (ParseException ignored) {
                // 回退
            }
            index = save;
        }
        Ast.QueryBody body = parseQueryBody();
        if (isLiteralOnly(body.items())) {
            throw new ParseException("语法错误: 带 FROM 的常量投影不支持(边界 39);"
                    + "请显式给出列或表达式", 0, 0);
        }
        boolean infoSchema = body.from() != null && body.from().first().database() != null
                && body.from().first().database().equalsIgnoreCase("information_schema");
        if (infoSchema && !canLegacy(body)) {
            throw new ParseException(
                    "语法错误: information_schema 上不支持该查询形态(只支持单表投影 / WHERE"
                            + " / LIMIT / 多列 ORDER BY / COUNT(*))", 0, 0);
        }
        if (canLegacy(body)) {
            return toLegacy(body);
        }
        return new Ast.SelectQuery(body);
    }

    private static boolean isLiteralOnly(List<Ast.SelectItem> items) {
        for (Ast.SelectItem item : items) {
            if (!(item.expr() instanceof Ast.Lit)) {
                return false;
            }
        }
        return true;
    }

    /** SELECT ... 之后的完整查询体(SPEC-P5 E 组 / F 组)。 */
    private Ast.QueryBody parseQueryBody() {
        if (peekKeyword("DISTINCT") || peekKeyword("ALL")) {
            throw error("本期不支持 SELECT DISTINCT(归 P6;COUNT(DISTINCT x) 可用)");
        }
        List<Ast.SelectItem> items = new ArrayList<>();
        do {
            items.add(parseSelectItem());
        } while (match(TokenType.COMMA));

        expectKeyword("FROM");
        Ast.FromItem first = parseFromItem();
        List<Ast.Join> joins = new ArrayList<>();
        while (true) {
            if (match(TokenType.COMMA)) {
                joins.add(new Ast.Join(Ast.JoinType.CROSS, parseFromItem(), null));
                continue;
            }
            Ast.JoinType type = null;
            if (matchKeyword("INNER")) {
                expectKeyword("JOIN");
                type = Ast.JoinType.INNER;
            } else if (matchKeyword("JOIN")) {
                type = Ast.JoinType.INNER;
            } else if (matchKeyword("LEFT")) {
                matchKeyword("OUTER");
                expectKeyword("JOIN");
                type = Ast.JoinType.LEFT;
            } else if (matchKeyword("CROSS")) {
                expectKeyword("JOIN");
                type = Ast.JoinType.CROSS;
            } else if (peekKeyword("RIGHT") || peekKeyword("FULL")) {
                throw error("本期只支持 INNER / LEFT / CROSS JOIN(RIGHT/FULL 未实现)");
            } else if (peekKeyword("UNION") || peekKeyword("INTERSECT") || peekKeyword("EXCEPT")) {
                throw error("集合运算(UNION / INTERSECT / EXCEPT)归 P6");
            }
            if (type == null) {
                break;
            }
            Ast.FromItem right = parseFromItem();
            Ast.Expr on = null;
            if (type != Ast.JoinType.CROSS) {
                expectKeyword("ON");
                on = parseExpr();
            } else if (matchKeyword("ON")) {
                on = parseExpr();
            }
            joins.add(new Ast.Join(type, right, on));
        }
        Ast.FromClause from = new Ast.FromClause(first, joins);

        Ast.Expr where = null;
        if (matchKeyword("WHERE")) {
            where = parseExpr();
        }
        List<Ast.Expr> groupBy = new ArrayList<>();
        if (matchKeyword("GROUP")) {
            expectKeyword("BY");
            do {
                groupBy.add(parseExpr());
            } while (match(TokenType.COMMA));
        }
        Ast.Expr having = null;
        if (matchKeyword("HAVING")) {
            having = parseExpr();
        }
        List<Ast.OrderItem> orderBy = new ArrayList<>();
        if (matchKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                Ast.Expr expr = parseExpr();
                boolean descending = false;
                if (matchKeyword("DESC")) {
                    descending = true;
                } else {
                    matchKeyword("ASC");
                }
                orderBy.add(new Ast.OrderItem(expr, descending));
            } while (match(TokenType.COMMA));
        }
        Integer limit = null;
        Integer offset = null;
        if (peekKeyword("LIMIT")) {
            Limit parsed = parseLimitClause();
            limit = parsed.count();
            offset = parsed.offset();
        }
        if (peekKeyword("UNION") || peekKeyword("INTERSECT") || peekKeyword("EXCEPT")) {
            throw error("集合运算(UNION / INTERSECT / EXCEPT)归 P6");
        }
        return new Ast.QueryBody(items, from, where, groupBy, having, orderBy, limit, offset);
    }

    private Ast.SelectItem parseSelectItem() {
        // t.*
        if (current().type() == TokenType.IDENT || current().type() == TokenType.QUOTED_IDENT) {
            if (index + 2 < tokens.size()
                    && tokens.get(index + 1).type() == TokenType.DOT
                    && tokens.get(index + 2).type() == TokenType.STAR) {
                String qualifier = expectIdentifier("表别名");
                advance(); // .
                advance(); // *
                return new Ast.SelectItem(null, null, true, qualifier);
            }
        }
        if (match(TokenType.STAR)) {
            return new Ast.SelectItem(null, null, true, null);
        }
        Ast.Expr expr = parseExpr();
        String alias = parseOptionalAlias("列别名");
        return new Ast.SelectItem(expr, alias, false, null);
    }

    private Ast.FromItem parseFromItem() {
        if (match(TokenType.LPAREN)) {
            Ast.QueryBody subquery = parseQueryBody();
            expect(TokenType.RPAREN, "子查询的 ')'");
            String alias = parseOptionalTableAlias();
            if (alias == null) {
                throw error("FROM 里的子查询必须有别名");
            }
            return new Ast.FromItem(null, null, alias, subquery);
        }
        Ref ref = parseTableRef("表名");
        String alias = parseOptionalTableAlias();
        return new Ast.FromItem(ref.database(), ref.table(), alias, null);
    }

    /** 表别名:[AS] ident;遇到子句关键字不算别名。 */
    private String parseOptionalTableAlias() {
        java.util.Set<String> stop = java.util.Set.of(
                "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "JOIN", "INNER",
                "LEFT", "RIGHT", "FULL", "CROSS", "ON", "USING", "UNION", "INTERSECT", "EXCEPT",
                "SET", "VALUES", "AS");
        if (matchKeyword("AS")) {
            return expectIdentifier("表别名");
        }
        Token t = current();
        if (t.type() == TokenType.QUOTED_IDENT) {
            return expectIdentifier("表别名");
        }
        if (t.type() == TokenType.IDENT
                && !stop.contains(t.text().toUpperCase(java.util.Locale.ROOT))
                && !ITEM_FOLLOW_KEYWORDS.contains(t.text().toUpperCase(java.util.Locale.ROOT))) {
            return expectIdentifier("表别名");
        }
        return null;
    }

    // ------------------------------------------------------------ P5 表达式

    private Ast.Expr parseExpr() {
        return parseOrExpr();
    }

    private Ast.Expr parseOrExpr() {
        Ast.Expr left = parseAndExpr();
        while (matchKeyword("OR")) {
            left = new Ast.LogicalOr(left, parseAndExpr());
        }
        return left;
    }

    private Ast.Expr parseAndExpr() {
        Ast.Expr left = parseNotExpr();
        while (matchKeyword("AND")) {
            left = new Ast.LogicalAnd(left, parseNotExpr());
        }
        return left;
    }

    private Ast.Expr parseNotExpr() {
        if (matchKeyword("NOT")) {
            return new Ast.Not(parseNotExpr());
        }
        return parsePredicate();
    }

    private Ast.Expr parsePredicate() {
        Ast.Expr left = parseAdditive();
        // IS [NOT] NULL
        if (matchKeyword("IS")) {
            boolean negated = matchKeyword("NOT");
            expectKeyword("NULL");
            return new Ast.IsNull(left, negated);
        }
        boolean negated = false;
        if (peekKeyword("NOT")) {
            Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
            if (next != null && next.type() == TokenType.IDENT) {
                String kw = next.text().toUpperCase(java.util.Locale.ROOT);
                if (kw.equals("IN") || kw.equals("LIKE") || kw.equals("BETWEEN")) {
                    advance(); // NOT
                    negated = true;
                }
            }
        }
        if (matchKeyword("LIKE")) {
            Ast.Expr pattern = parseAdditive();
            String escape = "\\";
            if (matchKeyword("ESCAPE")) {
                Object esc = parseLiteral();
                escape = esc == null ? "" : esc.toString();
            }
            return new Ast.Like(left, pattern, escape);
        }
        if (matchKeyword("BETWEEN")) {
            Ast.Expr lower = parseAdditive();
            expectKeyword("AND");
            Ast.Expr upper = parseAdditive();
            return new Ast.Between(left, lower, upper, negated);
        }
        if (matchKeyword("IN")) {
            expect(TokenType.LPAREN, "IN 列表的 '('");
            if (peekKeyword("SELECT")) {
                advance(); // SELECT
                Ast.QueryBody subquery = parseQueryBody();
                expect(TokenType.RPAREN, "IN 子查询的 ')'");
                return new Ast.InSubquery(left, subquery, negated);
            }
            List<Ast.Expr> values = new ArrayList<>();
            if (current().type() != TokenType.RPAREN) {
                do {
                    values.add(parseAdditive());
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "IN 列表的 ')'");
            return new Ast.InExpr(left, values, negated);
        }
        Ast.Op op = peekOp();
        if (op != null) {
            advance();
            Ast.Expr right = parseAdditive();
            return new Ast.Cmp(op, left, right);
        }
        if (negated) {
            throw error("NOT 之后需要 IN / LIKE / BETWEEN");
        }
        return left;
    }

    private Ast.Op peekOp() {
        Token t = current();
        return switch (t.type()) {
            case EQ -> Ast.Op.EQ;
            case NEQ -> Ast.Op.NE;
            case LT -> Ast.Op.LT;
            case LTE -> Ast.Op.LE;
            case GT -> Ast.Op.GT;
            case GTE -> Ast.Op.GE;
            default -> null;
        };
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr left = parseMultiplicative();
        while (true) {
            if (match(TokenType.PLUS)) {
                left = new Ast.Arith(Ast.ArithOp.ADD, left, parseMultiplicative());
            } else if (match(TokenType.MINUS)) {
                left = new Ast.Arith(Ast.ArithOp.SUB, left, parseMultiplicative());
            } else {
                return left;
            }
        }
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr left = parseUnary();
        while (true) {
            if (match(TokenType.STAR)) {
                left = new Ast.Arith(Ast.ArithOp.MUL, left, parseUnary());
            } else if (match(TokenType.SLASH)) {
                left = new Ast.Arith(Ast.ArithOp.DIV, left, parseUnary());
            } else if (match(TokenType.PERCENT)) {
                left = new Ast.Arith(Ast.ArithOp.MOD, left, parseUnary());
            } else {
                return left;
            }
        }
    }

    private Ast.Expr parseUnary() {
        if (match(TokenType.MINUS)) {
            return new Ast.Neg(parseUnary());
        }
        if (match(TokenType.PLUS)) {
            return parseUnary();
        }
        return parsePrimaryExpr();
    }

    private Ast.Expr parsePrimaryExpr() {
        Token t = current();
        if (t.type() == TokenType.LPAREN) {
            advance();
            if (peekKeyword("SELECT")) {
                advance(); // SELECT
                Ast.QueryBody subquery = parseQueryBody();
                expect(TokenType.RPAREN, "标量子查询的 ')'");
                return new Ast.ScalarSubquery(subquery);
            }
            Ast.Expr inner = parseExpr();
            expect(TokenType.RPAREN, "表达式的 ')'");
            return inner;
        }
        if (t.type() == TokenType.IDENT) {
            String upper = t.text().toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("CASE")) {
                return parseCaseExpr();
            }
            if (upper.equals("CAST")) {
                advance();
                expect(TokenType.LPAREN, "CAST 的 '('");
                Ast.Expr operand = parseExpr();
                expectKeyword("AS");
                DataType type = parseDataType();
                expect(TokenType.RPAREN, "CAST 的 ')'");
                return new Ast.Cast(operand, type);
            }
            if (upper.equals("EXISTS")) {
                throw error("EXISTS 归 P6(本期不做相关子查询与 EXISTS)");
            }
            if (upper.equals("NULL")) {
                advance();
                return new Ast.Lit(null, "NULL");
            }
            if (upper.equals("TRUE") || upper.equals("FALSE")) {
                advance();
                return new Ast.Lit(upper.equals("TRUE"), upper);
            }
            // 列引用 or 函数调用
            if (index + 1 < tokens.size() && tokens.get(index + 1).type() == TokenType.LPAREN) {
                String name = t.text();
                advance(); // 函数名
                advance(); // (
                return parseFunctionCall(name);
            }
            String first = expectIdentifier("列名");
            if (match(TokenType.DOT)) {
                String second = expectIdentifier("列名");
                return new Ast.Col(first, second);
            }
            return new Ast.Col(null, first);
        }
        if (t.type() == TokenType.QUOTED_IDENT) {
            String first = expectIdentifier("列名");
            if (match(TokenType.DOT)) {
                String second = expectIdentifier("列名");
                return new Ast.Col(first, second);
            }
            return new Ast.Col(null, first);
        }
        // 字面量(数字 / 字符串 / 负数由 unary 处理)
        Object value = parseLiteral();
        return new Ast.Lit(value, null);
    }

    private Ast.Expr parseFunctionCall(String rawName) {
        String name = rawName.toUpperCase(java.util.Locale.ROOT);
        if (current().type() == TokenType.STAR) {
            advance();
            expect(TokenType.RPAREN, "函数参数表的 ')'");
            return new Ast.Func(name, List.of(), false, true);
        }
        boolean distinct = matchKeyword("DISTINCT");
        List<Ast.Expr> args = new ArrayList<>();
        if (current().type() != TokenType.RPAREN) {
            do {
                args.add(parseExpr());
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RPAREN, "函数参数表的 ')'");
        if (!Functions.isAggregate(name) && !Functions.exists(name)
                && !Functions.isDeferred(name)) {
            throw new ParseException("语法错误: 不支持的函数 " + rawName, 0, 0);
        }
        return new Ast.Func(name, args, distinct, false);
    }

    private Ast.Expr parseCaseExpr() {
        expectKeyword("CASE");
        Ast.Expr base = null;
        if (!peekKeyword("WHEN")) {
            base = parseExpr();
        }
        List<Ast.When> whens = new ArrayList<>();
        while (matchKeyword("WHEN")) {
            Ast.Expr condition = parseExpr();
            expectKeyword("THEN");
            Ast.Expr result = parseExpr();
            whens.add(new Ast.When(condition, result));
        }
        if (whens.isEmpty()) {
            throw error("CASE 至少要有一个 WHEN");
        }
        Ast.Expr otherwise = null;
        if (matchKeyword("ELSE")) {
            otherwise = parseExpr();
        }
        expectKeyword("END");
        return new Ast.Case(base, whens, otherwise);
    }

    // ------------------------------------------------------------ P5 → 老 Ast.Select 兼容转换

    /**
     * 老 {@link Ast.Select} 能表达的窄子集(单表、列投影、简单 WHERE、information_schema
     * 的列排序)。能转就转,旧执行路径(P2~P4 的索引选择 / EXPLAIN)完全不变。
     */
    private boolean canLegacy(Ast.QueryBody body) {
        if (body.from() == null || body.from().first() == null
                || body.from().first().subquery() != null
                || !body.from().joins().isEmpty()) {
            return false;
        }
        if (!body.groupBy().isEmpty() || body.having() != null) {
            return false;
        }
        boolean infoSchema = body.from().first().database() != null
                && body.from().first().database().equalsIgnoreCase("information_schema");
        boolean countStarOnly = infoSchema && body.items().size() == 1
                && body.items().get(0).expr() instanceof Ast.Func func
                && func.name().equals("COUNT") && func.star();
        for (Ast.SelectItem item : body.items()) {
            if (item.star()) {
                if (item.starQualifier() != null) {
                    return false;
                }
                continue;
            }
            if (countStarOnly) {
                continue;
            }
            if (!(item.expr() instanceof Ast.Col col) || col.qualifier() != null) {
                return false;
            }
        }
        if (body.where() != null && !conditionConvertible(body.where())) {
            return false;
        }
        for (Ast.OrderItem order : body.orderBy()) {
            if (!(order.expr() instanceof Ast.Col)) {
                return false;
            }
            if (!infoSchema) {
                return false; // 用户表 ORDER BY 从 P5 起走新引擎(真支持)
            }
        }
        return true;
    }

    private boolean conditionConvertible(Ast.Expr expr) {
        if (expr instanceof Ast.LogicalAnd and) {
            return conditionConvertible(and.left()) && conditionConvertible(and.right());
        }
        if (expr instanceof Ast.LogicalOr or) {
            return conditionConvertible(or.left()) && conditionConvertible(or.right());
        }
        if (expr instanceof Ast.Cmp cmp) {
            return (cmp.left() instanceof Ast.Col && cmp.right() instanceof Ast.Lit)
                    || (cmp.left() instanceof Ast.Lit && cmp.right() instanceof Ast.Col);
        }
        if (expr instanceof Ast.InExpr in) {
            if (in.negated() || !(in.operand() instanceof Ast.Col)) {
                return false;
            }
            for (Ast.Expr value : in.values()) {
                if (!(value instanceof Ast.Lit)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private Ast.Statement toLegacy(Ast.QueryBody body) {
        Ast.FromItem first = body.from().first();
        boolean star = false;
        boolean countStar = false;
        String countAlias = null;
        List<Ast.Item> items = new ArrayList<>();
        if (body.items().size() == 1 && body.items().get(0).expr() instanceof Ast.Func func
                && func.name().equals("COUNT") && func.star()) {
            countStar = true;
            countAlias = body.items().get(0).alias();
        } else {
            for (Ast.SelectItem item : body.items()) {
                if (item.star()) {
                    star = true;
                    continue;
                }
                Ast.Col col = (Ast.Col) item.expr();
                items.add(new Ast.Item(col.name(), item.alias()));
            }
        }
        List<Ast.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : body.orderBy()) {
            Ast.Col col = (Ast.Col) order.expr();
            orderBy.add(new Ast.Order(col.name(), order.descending()));
        }
        return new Ast.Select(first.database(), first.table(), star, items,
                toLegacyCondition(body.where()), orderBy, body.limit(), body.offset(),
                countStar, countAlias);
    }

    private Ast.Condition toLegacyCondition(Ast.Expr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Ast.LogicalAnd and) {
            return new Ast.And(toLegacyCondition(and.left()), toLegacyCondition(and.right()));
        }
        if (expr instanceof Ast.LogicalOr or) {
            return new Ast.Or(toLegacyCondition(or.left()), toLegacyCondition(or.right()));
        }
        if (expr instanceof Ast.InExpr in) {
            Ast.Col col = (Ast.Col) in.operand();
            List<Object> values = new ArrayList<>(in.values().size());
            for (Ast.Expr value : in.values()) {
                values.add(((Ast.Lit) value).value());
            }
            return new Ast.In(col.name(), values);
        }
        Ast.Cmp cmp = (Ast.Cmp) expr;
        if (cmp.left() instanceof Ast.Col col && cmp.right() instanceof Ast.Lit lit) {
            return new Ast.Compare(col.name(), cmp.op(), lit.value());
        }
        Ast.Lit lit = (Ast.Lit) cmp.left();
        Ast.Col col = (Ast.Col) cmp.right();
        Ast.Op flipped = switch (cmp.op()) {
            case EQ -> Ast.Op.EQ;
            case NE -> Ast.Op.NE;
            case LT -> Ast.Op.GT;
            case LE -> Ast.Op.GE;
            case GT -> Ast.Op.LT;
            case GE -> Ast.Op.LE;
        };
        return new Ast.Compare(col.name(), flipped, lit.value());
    }

    /**
     * 投影项的可选别名(K29 / K102):{@code [AS] <ident>},{@code AS} 可省。
     *
     * <p>不带 {@code AS} 时只有三种情况算别名:反引号标识符、普通标识符、且该标识符
     * 不是投影项后面的子句起点({@code FROM} / {@code WHERE} / {@code ORDER} 等)。
     * 普通列与 {@code COUNT(*)} 共用这一条路径,不各写一套。</p>
     *
     * @return 别名;没有别名返回 {@code null}
     */
    private String parseOptionalAlias(String what) {
        if (matchKeyword("AS")) {
            return expectIdentifier(what);
        }
        Token t = current();
        if (t.type() == TokenType.QUOTED_IDENT) {
            return expectIdentifier(what);
        }
        if (t.type() == TokenType.IDENT
                && !ITEM_FOLLOW_KEYWORDS.contains(t.text().toUpperCase(java.util.Locale.ROOT))) {
            return expectIdentifier(what);
        }
        return null;
    }

    /**
     * LIMIT 子句(FR-549 ~ FR-552):三种写法等价地归一到 count / offset。
     *
     * <pre>
     *   LIMIT n                 → count = n, offset = null
     *   LIMIT off, n            → count = n, offset = off
     *   LIMIT n OFFSET off      → count = n, offset = off
     * </pre>
     *
     * 数值必须是非负整数(INTEGER token);负数(前导 {@code -})、小数、缺参数
     * 都在对应 token 的位置报 1064(边界 31)。
     */
    private Limit parseLimitClause() {
        expectKeyword("LIMIT");
        int first = parseLimitNumber("LIMIT 的非负整数");
        if (match(TokenType.COMMA)) {
            int count = parseLimitNumber("LIMIT 的行数");
            return new Limit(count, first);
        }
        if (matchKeyword("OFFSET")) {
            int offset = parseLimitNumber("OFFSET 的非负整数");
            return new Limit(first, offset);
        }
        return new Limit(first, null);
    }

    private int parseLimitNumber(String what) {
        Token t = expect(TokenType.INTEGER, what);
        long v;
        try {
            v = Long.parseLong(t.text());
        } catch (NumberFormatException e) {
            throw error(t, what + " 取值非法: " + t.text());
        }
        if (v < 0 || v > Integer.MAX_VALUE) {
            throw error(t, what + " 取值非法: " + v);
        }
        return (int) v;
    }

    /** LIMIT 子句解析结果:count = 取多少行,offset = 先跳过多少行(null 表示 0)。 */
    private record Limit(Integer count, Integer offset) {
    }

    private boolean isCountStar() {
        if (current().type() != TokenType.IDENT
                || !current().text().equalsIgnoreCase("COUNT")) {
            return false;
        }
        if (index + 3 >= tokens.size()) {
            return false;
        }
        return tokens.get(index + 1).type() == TokenType.LPAREN
                && tokens.get(index + 2).type() == TokenType.STAR
                && tokens.get(index + 3).type() == TokenType.RPAREN;
    }

    // ------------------------------------------------------------ 无 FROM 的字面量投影(FR-560)

    /** {@code SELECT} 之后紧跟字面量 → 无 {@code FROM} 的字面量投影(FR-560)。 */
    private boolean isLiteralProjectionStart() {
        Token t = current();
        return switch (t.type()) {
            case INTEGER, DECIMAL, STRING, MINUS -> true;
            case IDENT -> t.text().equalsIgnoreCase("NULL");
            default -> false;
        };
    }

    /**
     * {@code SELECT <literal>[, <literal>...]}(FR-560):返回一行,列名为字面量文本。
     * 字面量只收整数 / 小数 / 单引号字符串 / {@code NULL};表达式与函数仍报 1064(边界 39)。
     */
    private Ast.Statement parseLiteralProjection() {
        List<Ast.LiteralItem> items = new ArrayList<>();
        do {
            items.add(parseLiteralItem());
        } while (match(TokenType.COMMA));
        return new Ast.SelectLiterals(items);
    }

    private Ast.LiteralItem parseLiteralItem() {
        boolean negative = match(TokenType.MINUS);
        Token t = current();
        switch (t.type()) {
            case INTEGER -> {
                advance();
                long v;
                try {
                    v = Long.parseLong(t.text());
                } catch (NumberFormatException e) {
                    throw error(t, "整数超出范围: " + t.text());
                }
                if (negative) {
                    v = -v;
                }
                Object value = (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                        ? (Object) (int) v : (Object) v;
                return new Ast.LiteralItem(value, (negative ? "-" : "") + t.text());
            }
            case DECIMAL -> {
                advance();
                double d;
                try {
                    d = Double.parseDouble(t.text());
                } catch (NumberFormatException e) {
                    throw error(t, "小数格式非法: " + t.text());
                }
                return new Ast.LiteralItem(negative ? -d : d, (negative ? "-" : "") + t.text());
            }
            case STRING -> {
                if (negative) {
                    throw error(t, "字符串前不能加负号");
                }
                advance();
                return new Ast.LiteralItem(t.text(), t.text());
            }
            case IDENT -> {
                if (!negative && t.text().equalsIgnoreCase("NULL")) {
                    advance();
                    return new Ast.LiteralItem(null, "NULL");
                }
                throw error("无 FROM 的 SELECT 只支持字面量(整数 / 小数 / 单引号字符串 / NULL)");
            }
            default -> throw error(
                    "无 FROM 的 SELECT 只支持字面量(整数 / 小数 / 单引号字符串 / NULL)");
        }
    }

    // ------------------------------------------------------------ 条件

    private Ast.Condition parseCondition() {
        return parseOr();
    }

    private Ast.Condition parseOr() {
        Ast.Condition left = parseAnd();
        while (matchKeyword("OR")) {
            left = new Ast.Or(left, parseAnd());
        }
        return left;
    }

    private Ast.Condition parseAnd() {
        Ast.Condition left = parsePrimary();
        while (matchKeyword("AND")) {
            left = new Ast.And(left, parsePrimary());
        }
        return left;
    }

    private Ast.Condition parsePrimary() {
        if (match(TokenType.LPAREN)) {
            Ast.Condition cond = parseOr();
            expect(TokenType.RPAREN, "条件的 ')'");
            return cond;
        }
        String column = expectIdentifier("列名");

        if (matchKeyword("IN")) {
            expect(TokenType.LPAREN, "IN 列表的 '('");
            List<Object> values = new ArrayList<>();
            do {
                values.add(parseLiteral());
            } while (match(TokenType.COMMA));
            expect(TokenType.RPAREN, "IN 列表的 ')'");
            return new Ast.In(column, values);
        }

        Ast.Op op = parseOp();
        Object value = parseLiteral();
        return new Ast.Compare(column, op, value);
    }

    private Ast.Op parseOp() {
        Token t = current();
        return switch (t.type()) {
            case EQ -> {
                advance();
                yield Ast.Op.EQ;
            }
            case NEQ -> {
                advance();
                yield Ast.Op.NE;
            }
            case LT -> {
                advance();
                yield Ast.Op.LT;
            }
            case LTE -> {
                advance();
                yield Ast.Op.LE;
            }
            case GT -> {
                advance();
                yield Ast.Op.GT;
            }
            case GTE -> {
                advance();
                yield Ast.Op.GE;
            }
            default -> throw error("这里需要一个比较运算符(= != <> < <= > >=),实际是 " + describe(t));
        };
    }

    /** 解析一个常量:数字(可带负号)、字符串、TRUE/FALSE、NULL。 */
    private Object parseLiteral() {
        boolean negative = match(TokenType.MINUS);
        Token t = current();
        Object value;
        switch (t.type()) {
            case INTEGER -> {
                advance();
                java.math.BigInteger big;
                try {
                    big = new java.math.BigInteger(t.text());
                } catch (NumberFormatException e) {
                    throw error(t, "整数超出范围: " + t.text());
                }
                if (negative) {
                    big = big.negate();
                }
                if (big.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0
                        || big.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                    throw error(t, "整数超出范围: " + t.text());
                }
                long v = big.longValue();
                value = (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (Object) (int) v : (Object) v;
            }
            case DECIMAL -> {
                advance();
                double d;
                try {
                    d = Double.parseDouble(t.text());
                } catch (NumberFormatException e) {
                    throw error(t, "小数格式非法: " + t.text());
                }
                value = negative ? -d : d;
            }
            case STRING -> {
                if (negative) {
                    throw error(t, "字符串前不能加负号");
                }
                advance();
                value = t.text();
            }
            case IDENT -> {
                if (negative) {
                    throw error(t, "常量前不能加负号: " + t.text());
                }
                String upper = t.text().toUpperCase();
                if (upper.equals("NULL")) {
                    advance();
                    value = null;
                } else if (upper.equals("TRUE") || upper.equals("FALSE")) {
                    advance();
                    value = upper.equals("TRUE");
                } else {
                    throw error(t, "这里需要一个常量,实际是 " + t.text());
                }
            }
            default -> throw error("这里需要一个常量,实际是 " + describe(t));
        }
        return value;
    }

    // ------------------------------------------------------------ 工具

    private Token current() {
        return tokens.get(index);
    }

    private void advance() {
        if (index < tokens.size() - 1) {
            index++;
        }
    }

    private boolean match(TokenType type) {
        if (current().type() == type) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String what) {
        Token t = current();
        if (t.type() != type) {
            throw error("期望 " + what + ",实际是 " + describe(t));
        }
        advance();
        return t;
    }

    private boolean matchKeyword(String keyword) {
        Token t = current();
        if (t.type() == TokenType.IDENT && t.text().equalsIgnoreCase(keyword)) {
            advance();
            return true;
        }
        return false;
    }

    /** 只看不消费:当前 token 是否是给定关键字。 */
    private boolean peekKeyword(String keyword) {
        Token t = current();
        return t.type() == TokenType.IDENT && t.text().equalsIgnoreCase(keyword);
    }

    private void expectKeyword(String keyword) {
        if (!matchKeyword(keyword)) {
            throw error("期望关键字 " + keyword + ",实际是 " + describe(current()));
        }
    }

    private String expectIdentifier(String what) {
        Token t = expectIdentifierToken(what);
        // 反引号不引入大小写语义(FR-182 / K31):内部统一小写。
        // 普通标识符保持原样交给下游(下游本来就按不区分大小写匹配)。
        return t.type() == TokenType.QUOTED_IDENT
                ? t.text().toLowerCase(java.util.Locale.ROOT)
                : t.text();
    }

    /** 接受普通标识符与反引号标识符(FR-180);关键字仍只匹配普通标识符。 */
    private Token expectIdentifierToken(String what) {
        Token t = current();
        if (t.type() != TokenType.IDENT && t.type() != TokenType.QUOTED_IDENT) {
            throw error("期望 " + what + ",实际是 " + describe(t));
        }
        advance();
        return t;
    }

    private void skipSemicolons() {
        while (current().type() == TokenType.SEMICOLON) {
            advance();
        }
    }

    private ParseException error(String message) {
        Token t = current();
        return error(t, message);
    }

    private ParseException error(Token t, String message) {
        return new ParseException(
                "语法错误: 第 " + t.line() + " 行第 " + t.column() + " 列 " + message,
                t.line(), t.column());
    }

    private static String describe(Token t) {
        if (t.type() == TokenType.EOF) {
            return "输入结束";
        }
        if (t.type() == TokenType.STRING) {
            return "字符串 '" + t.text() + "'";
        }
        if (t.type() == TokenType.QUOTED_IDENT) {
            return "`" + t.text() + "`";
        }
        return "'" + t.text() + "'";
    }
}
