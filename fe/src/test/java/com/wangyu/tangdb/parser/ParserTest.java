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
        Ast.Compare cmp = (Ast.Compare) select.where();
        assertEquals("age", cmp.column());
        assertEquals(Ast.Op.GT, cmp.op());
        assertEquals(25, cmp.value());
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
}
