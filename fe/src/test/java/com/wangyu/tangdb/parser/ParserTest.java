package com.wangyu.tangdb.parser;

import com.wangyu.tangdb.storage.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 词法/语法分析测试:覆盖 P2 支持的全部语句与错误定位。 */
class ParserTest {

    @Test
    void createTable() {
        Ast.CreateTable stmt = (Ast.CreateTable) Parser.parse(
                "CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), age INT, "
                        + "score DOUBLE, birthday DATE, active BOOLEAN)");
        assertEquals("users", stmt.table());
        assertEquals(6, stmt.columns().size());
        assertEquals(DataType.BIGINT, stmt.columns().get(0).type());
        assertTrue(stmt.columns().get(0).primaryKey());
        assertEquals(32, stmt.columns().get(1).length());
        assertEquals(DataType.VARCHAR, stmt.columns().get(1).type());
        assertTrue(stmt.columns().get(1).nullable());
        assertEquals(DataType.INT, stmt.columns().get(2).type());
    }

    @Test
    void createRejectsVarcharWithoutLength() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("CREATE TABLE t (a VARCHAR PRIMARY KEY)"));
        assertTrue(e.getMessage().contains("VARCHAR 必须给出长度"), e.getMessage());
    }

    @Test
    void createRejectsUnknownType() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("CREATE TABLE t (a TEXT PRIMARY KEY)"));
        assertTrue(e.getMessage().contains("未知的列类型: TEXT"), e.getMessage());
    }

    @Test
    void ddlStatements() {
        assertEquals("t", ((Ast.DropTable) Parser.parse("DROP TABLE t")).table());
        assertEquals("t", ((Ast.TruncateTable) Parser.parse("TRUNCATE TABLE t")).table());
        assertInstanceOf(Ast.ShowTables.class, Parser.parse("SHOW TABLES"));
        assertEquals("t", ((Ast.Describe) Parser.parse("DESC t")).table());
        assertEquals("t", ((Ast.Describe) Parser.parse("DESCRIBE t")).table());
    }

    @Test
    void insertMultipleRows() {
        Ast.Insert insert = (Ast.Insert) Parser.parse(
                "INSERT INTO users (id, name, age) VALUES (1, 'wangyu', 26), (2, '李四', NULL)");
        assertEquals("users", insert.table());
        assertEquals(List.of("id", "name", "age"), insert.columns());
        assertEquals(2, insert.rows().size());
        assertEquals(1, insert.rows().get(0).get(0));
        assertEquals("wangyu", insert.rows().get(0).get(1));
        assertEquals(26, insert.rows().get(0).get(2));
        assertEquals("李四", insert.rows().get(1).get(1));
        assertNull(insert.rows().get(1).get(2));
    }

    @Test
    void numberLiterals() {
        Ast.Insert insert = (Ast.Insert) Parser.parse(
                "INSERT INTO t (a, b, c, d) VALUES (-2147483648, -9223372036854775808, 1.5, -2.5)");
        List<Object> row = insert.rows().get(0);
        assertEquals(Integer.MIN_VALUE, row.get(0));
        assertEquals(Long.MIN_VALUE, row.get(1));
        assertEquals(1.5, row.get(2));
        assertEquals(-2.5, row.get(3));
    }

    @Test
    void updateWithWhere() {
        Ast.Update update = (Ast.Update) Parser.parse(
                "UPDATE users SET city = '上海', age = 27 WHERE id = 1");
        assertEquals("users", update.table());
        assertEquals(2, update.assignments().size());
        assertInstanceOf(Ast.Compare.class, update.where());
    }

    @Test
    void deleteWithoutWhere() {
        Ast.Delete delete = (Ast.Delete) Parser.parse("DELETE FROM users");
        assertNull(delete.where());
    }

    @Test
    void selectStar() {
        Ast.Select select = (Ast.Select) Parser.parse("SELECT * FROM users");
        assertTrue(select.star());
        assertEquals("users", select.table());
        assertNull(select.where());
        assertNull(select.limit());
    }

    @Test
    void selectProjectionAliasAndLimit() {
        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT id AS 编号, name FROM users WHERE age > 25 LIMIT 10");
        assertFalse(select.star());
        assertEquals(2, select.items().size());
        assertEquals("编号", select.items().get(0).alias());
        assertEquals(10, select.limit());
        assertNull(select.offset());
        Ast.Compare cmp = (Ast.Compare) select.where();
        assertEquals("age", cmp.column());
        assertEquals(Ast.Op.GT, cmp.op());
        assertEquals(25, cmp.value());
    }

    @Test
    void limitFormsNormalizeToCountAndOffset() {
        Ast.Select plain = (Ast.Select) Parser.parse("SELECT * FROM t LIMIT 10");
        assertEquals(10, plain.limit());
        assertNull(plain.offset());

        // LIMIT <offset>, <row_count>
        Ast.Select comma = (Ast.Select) Parser.parse("SELECT * FROM t LIMIT 20, 10");
        assertEquals(10, comma.limit());
        assertEquals(20, comma.offset());

        // LIMIT <row_count> OFFSET <offset>
        Ast.Select offset = (Ast.Select) Parser.parse("SELECT * FROM t LIMIT 10 OFFSET 20");
        assertEquals(10, offset.limit());
        assertEquals(20, offset.offset());

        Ast.Select zero = (Ast.Select) Parser.parse("SELECT * FROM t LIMIT 0, 0");
        assertEquals(0, zero.limit());
        assertEquals(0, zero.offset());
    }

    @Test
    void limitRejectsNegativeDecimalAndMissingArguments() {
        for (String sql : List.of(
                "SELECT * FROM t LIMIT -1, 10",
                "SELECT * FROM t LIMIT 10, -1",
                "SELECT * FROM t LIMIT 1.5",
                "SELECT * FROM t LIMIT",
                "SELECT * FROM t LIMIT 10,",
                "SELECT * FROM t LIMIT 10 OFFSET",
                "SELECT * FROM t LIMIT 10 OFFSET -1")) {
            ParseException e = assertThrows(ParseException.class, () -> Parser.parse(sql), sql);
            assertTrue(e.getMessage().contains("第 1 行第"), sql + " → " + e.getMessage());
            assertTrue(e.getMessage().contains("LIMIT") || e.getMessage().contains("OFFSET"),
                    sql + " → " + e.getMessage());
        }
    }

    @Test
    void updateAndDeleteParseLimitForExecutorRejection() {
        assertEquals(3, ((Ast.Update) Parser.parse("UPDATE t SET a = 1 LIMIT 3")).limit());
        assertEquals(5, ((Ast.Update) Parser.parse("UPDATE t SET a = 1 LIMIT 0, 5")).limit());
        assertEquals(4, ((Ast.Delete) Parser.parse("DELETE FROM t LIMIT 4")).limit());
        assertNull(((Ast.Update) Parser.parse("UPDATE t SET a = 1")).limit());
        assertNull(((Ast.Delete) Parser.parse("DELETE FROM t")).limit());
    }

    // ------------------------------------------------------------ P4 H 组:COUNT(*) 别名

    @Test
    void countStarAcceptsAliasWithAndWithoutAs() {
        Ast.Select withAs = (Ast.Select) Parser.parse(
                "SELECT COUNT(*) AS support_ndb FROM information_schema.ENGINES "
                        + "WHERE Engine = 'ndbcluster'");
        assertTrue(withAs.countStar());
        assertEquals("support_ndb", withAs.alias());
        assertNull(withAs.limit());

        Ast.Select withoutAs = (Ast.Select) Parser.parse(
                "SELECT COUNT(*) support_ndb FROM information_schema.ENGINES");
        assertTrue(withoutAs.countStar());
        assertEquals("support_ndb", withoutAs.alias());

        Ast.Select backticked = (Ast.Select) Parser.parse(
                "SELECT COUNT(*) AS `Total` FROM information_schema.TABLES");
        assertEquals("total", backticked.alias(), "反引号别名与普通列同样统一小写");

        Ast.Select plain = (Ast.Select) Parser.parse(
                "SELECT COUNT(*) FROM information_schema.ENGINES");
        assertTrue(plain.countStar());
        assertNull(plain.alias(), "没有别名时 alias 为 null(结果列名仍由执行层给 COUNT(*))");
    }

    @Test
    void countStarDoesNotSwallowFollowingClause() {
        // 不带 AS 的别名只吃标识符;FROM 是子句起点,不能被当成别名。
        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'default'");
        assertNull(select.alias());
        assertNotNull(select.where());
    }

    @Test
    void normalProjectionAliasAlsoAcceptsNoAs() {
        Ast.Select select = (Ast.Select) Parser.parse("SELECT id 编号, name FROM users");
        assertEquals("编号", select.items().get(0).alias());
        assertNull(select.items().get(1).alias());
        assertEquals("users", select.table());
    }

    @Test
    void whereAndOrParenthesesIn() {
        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT * FROM t WHERE (a = 1 OR b = 2) AND c IN (3, 4, 5)");
        Ast.And and = (Ast.And) select.where();
        assertInstanceOf(Ast.Or.class, and.left());
        assertInstanceOf(Ast.In.class, and.right());
        assertEquals(3, ((Ast.In) and.right()).values().size());
    }

    @Test
    void comparisonOperators() {
        for (String op : List.of("=", "!=", "<>", "<", "<=", ">", ">=")) {
            Ast.Select select = (Ast.Select) Parser.parse("SELECT * FROM t WHERE a " + op + " 1");
            assertInstanceOf(Ast.Compare.class, select.where(), "op " + op);
        }
    }

    @Test
    void lineComments() {
        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT * -- 取所有列\nFROM users -- 表名\n");
        assertEquals("users", select.table());
    }

    @Test
    void trailingSemicolonAccepted() {
        assertInstanceOf(Ast.Select.class, Parser.parse("SELECT * FROM t;"));
        assertInstanceOf(Ast.Select.class, Parser.parse("SELECT * FROM t ;  "));
    }

    @Test
    void syntaxErrorHasChinesePosition() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SELEC * FROM users"));
        assertTrue(e.getMessage().startsWith("语法错误: 第 1 行第 1 列"), e.getMessage());
        assertTrue(e.getMessage().contains("不支持"), e.getMessage());

        ParseException missingFrom = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * users"));
        assertEquals(1, missingFrom.line());
        assertTrue(missingFrom.getMessage().contains("期望关键字 FROM"), missingFrom.getMessage());
    }

    @Test
    void unterminatedStringReportsPosition() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * FROM t WHERE name = 'abc"));
        assertTrue(e.getMessage().contains("字符串缺少结束的单引号"), e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行"), e.getMessage());
    }

    @Test
    void unknownCharReportsPosition() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * FROM t WHERE a = 1 # 2"));
        assertTrue(e.getMessage().contains("无法识别的字符 '#'"), e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行"), e.getMessage());
    }

    // ------------------------------------------------------------ P2.1 反引号标识符

    @Test
    void quotedIdentifiersEverywhere() {
        Ast.CreateTable create = (Ast.CreateTable) Parser.parse(
                "CREATE TABLE `t_quoted` (`id` INT PRIMARY KEY, `select` VARCHAR(64), `from` INT)");
        assertEquals("t_quoted", create.table());
        assertEquals(List.of("id", "select", "from"), columnNames(create));

        Ast.Insert insert = (Ast.Insert) Parser.parse(
                "INSERT INTO `t_quoted` (`id`, `select`, `from`) VALUES (1, 'abc', 2)");
        assertEquals("t_quoted", insert.table());
        assertEquals(List.of("id", "select", "from"), insert.columns());
        assertEquals(List.of(1, "abc", 2), insert.rows().get(0));

        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT `id`, `from` AS `F` FROM `t_quoted` WHERE `id` = 1");
        assertEquals("t_quoted", select.table());
        assertEquals("id", select.items().get(0).column());
        assertEquals("from", select.items().get(1).column());
        assertEquals("f", select.items().get(1).alias());
        assertEquals("id", ((Ast.Compare) select.where()).column());

        Ast.Update update = (Ast.Update) Parser.parse(
                "UPDATE `t_quoted` SET `from` = 3 WHERE `id` = 1");
        assertEquals("t_quoted", update.table());
        assertEquals("from", update.assignments().get(0).column());

        Ast.Delete delete = (Ast.Delete) Parser.parse("DELETE FROM `t_quoted` WHERE `id` = 1");
        assertEquals("t_quoted", delete.table());
        assertEquals("t_quoted", ((Ast.Describe) Parser.parse("DESC `t_quoted`")).table());
        assertEquals("t_quoted", ((Ast.DropTable) Parser.parse("DROP TABLE `t_quoted`")).table());
        assertEquals("t_quoted",
                ((Ast.TruncateTable) Parser.parse("TRUNCATE TABLE `t_quoted`")).table());
        assertEquals("t_quoted", ((Ast.Select) Parser.parse("SELECT * FROM `t_quoted`")).table());
    }

    @Test
    void quotedIdentAllowsArbitraryCharacters() {
        Ast.CreateTable create = (Ast.CreateTable) Parser.parse(
                "CREATE TABLE `my table` (`a-b c` INT PRIMARY KEY, `中文列` INT, `1st` INT)");
        assertEquals("my table", create.table());
        assertEquals(List.of("a-b c", "中文列", "1st"), columnNames(create));

        // §9:反引号里的 -- 是名字的一部分,不是注释。
        Ast.Select select = (Ast.Select) Parser.parse("SELECT `a--b` FROM t");
        assertEquals("a--b", select.items().get(0).column());
    }

    @Test
    void quotedIdentEscapesBacktickByDoubling() {
        Ast.Select select = (Ast.Select) Parser.parse("SELECT `a``b` FROM t");
        assertEquals("a`b", select.items().get(0).column());
    }

    @Test
    void quotedIdentKeepsCaseRulesUnchanged() {
        // 反引号不引入大小写语义(FR-182 / K31):内部统一小写。
        Ast.Select select = (Ast.Select) Parser.parse(
                "SELECT `ID` FROM `T_Quoted` WHERE `ID` = 1");
        assertEquals("t_quoted", select.table());
        assertEquals("id", select.items().get(0).column());
        assertEquals("id", ((Ast.Compare) select.where()).column());
        // 不带反引号时的规则一字不变(解析层保留原样,下游按不区分大小写匹配)。
        assertEquals("T_Quoted", ((Ast.Select) Parser.parse("SELECT * FROM T_Quoted")).table());
    }

    @Test
    void unterminatedQuotedIdentReportsPosition() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * FROM `t_quoted"));
        assertTrue(e.getMessage().contains("标识符缺少结束的反引号"), e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行第 15 列"), e.getMessage());
        assertFalse(e.getMessage().contains("无法识别的字符"), e.getMessage());
    }

    @Test
    void emptyQuotedIdentRejected() {
        ParseException table = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * FROM ``"));
        assertTrue(table.getMessage().contains("标识符不能为空"), table.getMessage());
        assertFalse(table.getMessage().contains("无法识别的字符"), table.getMessage());

        ParseException column = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT `` FROM t"));
        assertTrue(column.getMessage().contains("标识符不能为空"), column.getMessage());
    }

    @Test
    void quotedIdentCannotWrapKeywordOrType() {
        // FR-186:反引号不能用来包裹语句关键字。
        ParseException statement = assertThrows(ParseException.class,
                () -> Parser.parse("`select` * from t_quoted"));
        assertTrue(statement.getMessage().contains("期望一条 SQL 语句"), statement.getMessage());

        ParseException keyword = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT * `from` t_quoted"));
        assertTrue(keyword.getMessage().contains("期望关键字 FROM"), keyword.getMessage());

        // 也不能包裹列类型。
        ParseException type = assertThrows(ParseException.class,
                () -> Parser.parse("CREATE TABLE t (a `INT` PRIMARY KEY)"));
        assertTrue(type.getMessage().contains("期望 列类型"), type.getMessage());
    }

    // ------------------------------------------------------------ P2.1 INSERT 省略列名列表

    @Test
    void insertWithoutColumnListParsesEmptyColumns() {
        Ast.Insert single = (Ast.Insert) Parser.parse(
                "INSERT INTO users VALUES (5, 'zhaoliu', '杭州', 19)");
        assertTrue(single.columns().isEmpty());
        assertEquals(1, single.rows().size());

        Ast.Insert multi = (Ast.Insert) Parser.parse(
                "INSERT INTO users VALUES (6, 'sunqi', '成都', 21), (7, 'zhouba', '西安', 24)");
        assertTrue(multi.columns().isEmpty());
        assertEquals(2, multi.rows().size());
        assertEquals(List.of(6, "sunqi", "成都", 21), multi.rows().get(0));
        assertEquals(List.of(7, "zhouba", "西安", 24), multi.rows().get(1));
    }

    @Test
    void insertEmptyColumnListRejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("INSERT INTO users () VALUES (1)"));
        assertTrue(e.getMessage().contains("列名列表不能为空"), e.getMessage());
    }

    @Test
    void insertEmptyValuesGroupParsesToZeroLiterals() {
        // 空取值组在解析层放行,由执行器统一报"取值个数(0)与列数(N)不一致"(§9)。
        Ast.Insert insert = (Ast.Insert) Parser.parse("INSERT INTO users VALUES ()");
        assertTrue(insert.columns().isEmpty());
        assertEquals(1, insert.rows().size());
        assertTrue(insert.rows().get(0).isEmpty());
    }

    // ------------------------------------------------------------ P2.1 SHOW DATABASES

    @Test
    void transactionStatements() {
        assertInstanceOf(Ast.Begin.class, Parser.parse("BEGIN"));
        assertInstanceOf(Ast.Begin.class, Parser.parse("begin;"));
        assertInstanceOf(Ast.Begin.class, Parser.parse("BEGIN TRANSACTION"));
        assertInstanceOf(Ast.Commit.class, Parser.parse("COMMIT"));
        assertInstanceOf(Ast.Commit.class, Parser.parse("commit work;"));
        assertInstanceOf(Ast.Rollback.class, Parser.parse("ROLLBACK"));
        assertInstanceOf(Ast.Rollback.class, Parser.parse("rollback;"));
    }

    @Test
    void showDatabases() {
        assertInstanceOf(Ast.ShowDatabases.class, Parser.parse("SHOW DATABASES"));
        assertInstanceOf(Ast.ShowDatabases.class, Parser.parse("show databases;"));
        assertInstanceOf(Ast.ShowDatabases.class, Parser.parse("ShOw DaTaBaSeS"));
        assertInstanceOf(Ast.ShowTables.class, Parser.parse("show tables"));
    }

    @Test
    void showSupportsP4TargetsAndRejectsUnknownOnes() {
        // P4 起 SCHEMAS / COLUMNS 属于 SHOW 家族(FR-527 ~ FR-536)。
        assertInstanceOf(Ast.ShowDatabases.class, Parser.parse("SHOW SCHEMAS"));
        assertThrows(ParseException.class, () -> Parser.parse("SHOW COLUMNS"));
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SHOW SLAVE STATUS"));
        assertTrue(e.getMessage().contains("不支持的 SHOW"), e.getMessage());
    }

    // ------------------------------------------------------------ P4 H-7(FR-560 ~ FR-562)

    @Test
    void selectLiteralProjectionWithoutFrom() {
        // FR-560:字面量原样返回,列名为字面量文本。
        Ast.SelectLiterals one = assertInstanceOf(Ast.SelectLiterals.class, Parser.parse("SELECT 1"));
        assertEquals(1, one.items().size());
        assertEquals(1, one.items().get(0).value());
        assertEquals("1", one.items().get(0).name());

        Ast.SelectLiterals two = assertInstanceOf(Ast.SelectLiterals.class,
                Parser.parse("SELECT 1, 'x'"));
        assertEquals(2, two.items().size());
        assertEquals("x", two.items().get(1).value());
        assertEquals("x", two.items().get(1).name());

        Ast.SelectLiterals mixed = assertInstanceOf(Ast.SelectLiterals.class,
                Parser.parse("SELECT -2, 1.5, NULL"));
        assertEquals(-2, mixed.items().get(0).value());
        assertEquals("-2", mixed.items().get(0).name());
        assertEquals(1.5, mixed.items().get(1).value());
        assertEquals("1.5", mixed.items().get(1).name());
        assertNull(mixed.items().get(2).value());
        assertEquals("NULL", mixed.items().get(2).name());
    }

    @Test
    void selectExpressionWithoutFromStillRejected() {
        // 边界 39:表达式 / 函数归 P5,仍报 1064,不得返回错值。
        assertThrows(ParseException.class, () -> Parser.parse("SELECT 1+1"));
        assertThrows(ParseException.class, () -> Parser.parse("SELECT now()"));
        assertThrows(ParseException.class, () -> Parser.parse("SELECT 1 FROM users"));
    }

    @Test
    void showCountStarWarningsAndErrors() {
        // FR-561
        Ast.ShowSimple warnings = assertInstanceOf(Ast.ShowSimple.class,
                Parser.parse("SHOW COUNT(*) WARNINGS"));
        assertEquals(Ast.ShowKind.WARNING_COUNT, warnings.kind());
        Ast.ShowSimple errors = assertInstanceOf(Ast.ShowSimple.class,
                Parser.parse("SHOW COUNT(*) ERRORS"));
        assertEquals(Ast.ShowKind.ERROR_COUNT, errors.kind());
        assertThrows(ParseException.class, () -> Parser.parse("SHOW COUNT(*) STATUS"));
    }

    @Test
    void showVariablesStatusAndIndexAcceptWhere() {
        // FR-562:列名与输出列名一致,走 SHOW 输出的通用过滤路径。
        Ast.ShowSimple variables = assertInstanceOf(Ast.ShowSimple.class,
                Parser.parse("SHOW VARIABLES WHERE Variable_name = 'version'"));
        assertEquals(Ast.ShowKind.VARIABLES, variables.kind());
        assertNotNull(variables.where());

        Ast.ShowSimple status = assertInstanceOf(Ast.ShowSimple.class,
                Parser.parse("SHOW STATUS WHERE Variable_name = 'Uptime'"));
        assertEquals(Ast.ShowKind.STATUS, status.kind());
        assertNotNull(status.where());

        Ast.ShowSimple sessionVariables = assertInstanceOf(Ast.ShowSimple.class,
                Parser.parse("SHOW SESSION VARIABLES LIKE 'version%' WHERE Variable_name = 'version'"));
        assertEquals(Ast.ShowKind.VARIABLES, sessionVariables.kind());
        assertEquals("version%", sessionVariables.like());
        assertNotNull(sessionVariables.where());

        Ast.ShowIndexDetails index = assertInstanceOf(Ast.ShowIndexDetails.class,
                Parser.parse("SHOW INDEX FROM t WHERE Key_name = 'PRIMARY'"));
        assertNotNull(index.where());
        Ast.ShowIndexDetails keys = assertInstanceOf(Ast.ShowIndexDetails.class,
                Parser.parse("SHOW KEYS FROM t WHERE Non_unique = 0"));
        assertNotNull(keys.where());
        Ast.ShowIndexes indexes = assertInstanceOf(Ast.ShowIndexes.class,
                Parser.parse("SHOW INDEXES FROM t WHERE index_name = 'idx'"));
        assertNotNull(indexes.where());
    }

    @Test
    void doubleQuotedIdentIsNotSupported() {
        // §9:双引号标识符不做,按现有语法错误报(留给 P5 与字符串字面量一起定)。
        ParseException e = assertThrows(ParseException.class,
                () -> Parser.parse("SELECT \"id\" FROM users"));
        assertTrue(e.getMessage().contains("无法识别的字符 '\"'"), e.getMessage());
    }

    private static List<String> columnNames(Ast.CreateTable create) {
        return create.columns().stream().map(Ast.ColumnDef::name).toList();
    }
}
