package com.wangyu.tangdb.parser;

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
 *            | SELECT ('*' | ident [AS ident] (',' ...)*) FROM ident [WHERE cond] [LIMIT int]
 * ident      := 普通标识符 | '`' 任意字符 '`'(反引号不解释关键字,不改变大小写语义)
 * cond       := cond AND cond | cond OR cond | '(' cond ')' | ident op literal | ident IN '(' literal,... ')'
 * </pre>
 */
public final class Parser {

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
            case "BEGIN" -> parseBegin();
            case "COMMIT" -> parseCommit();
            case "ROLLBACK" -> parseRollback();
            case "USE" -> parseUse();
            default -> throw error("不支持的语句: " + t.text()
                    + "(支持 CREATE/DROP/TRUNCATE/SHOW/DESC/INSERT/UPDATE/DELETE/SELECT/BEGIN/COMMIT/ROLLBACK/USE)");
        };
    }

    private Ast.Statement parseCreate() {
        expectKeyword("CREATE");
        if (matchKeyword("DATABASE")) {
            return new Ast.CreateDatabase(expectIdentifier("库名"));
        }
        expectKeyword("TABLE");
        Ref ref = parseTableRef("表名");
        expect(TokenType.LPAREN, "列定义列表的 '('");
        List<Ast.ColumnDef> columns = new ArrayList<>();
        do {
            columns.add(parseColumnDef());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "列定义列表的 ')'");
        return new Ast.CreateTable(ref.database(), ref.table(), columns);
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
        if (matchKeyword("PRIMARY")) {
            expectKeyword("KEY");
            primaryKey = true;
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
        if (matchKeyword("TABLES")) {
            return new Ast.ShowTables();
        }
        if (matchKeyword("DATABASES")) {
            return new Ast.ShowDatabases();
        }
        throw error("SHOW 只支持 SHOW TABLES / SHOW DATABASES");
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
        return new Ast.Update(ref.database(), ref.table(), assignments, where);
    }

    private Ast.Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        Ref ref = parseTableRef("表名");
        Ast.Condition where = null;
        if (matchKeyword("WHERE")) {
            where = parseCondition();
        }
        return new Ast.Delete(ref.database(), ref.table(), where);
    }

    private Ast.Statement parseSelect() {
        expectKeyword("SELECT");
        boolean star = false;
        List<Ast.Item> items = new ArrayList<>();
        if (match(TokenType.STAR)) {
            star = true;
        } else {
            do {
                String column = expectIdentifier("列名或 *");
                String alias = null;
                if (matchKeyword("AS")) {
                    alias = expectIdentifier("列别名");
                }
                items.add(new Ast.Item(column, alias));
            } while (match(TokenType.COMMA));
        }
        expectKeyword("FROM");
        Ref ref = parseTableRef("表名");
        Ast.Condition where = null;
        if (matchKeyword("WHERE")) {
            where = parseCondition();
        }
        Integer limit = null;
        if (matchKeyword("LIMIT")) {
            Token t = expect(TokenType.INTEGER, "LIMIT 的非负整数");
            long v;
            try {
                v = Long.parseLong(t.text());
            } catch (NumberFormatException e) {
                throw error(t, "LIMIT 取值非法: " + t.text());
            }
            if (v < 0 || v > Integer.MAX_VALUE) {
                throw error(t, "LIMIT 取值非法: " + v);
            }
            limit = (int) v;
        }
        return new Ast.Select(ref.database(), ref.table(), star, items, where, limit);
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
