package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.ParseException;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 执行器测试:DDL/DML/SELECT 全流程 + 边界情形(规约 §8)。 */
class ExecutorTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        executor.execute(Parser.parse(
                "CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(32), city VARCHAR(32), age INT)"));
    }

    private Result.Table select(String sql) {
        return (Result.Table) executor.execute(Parser.parse(sql));
    }

    private Result.Message dml(String sql) {
        return (Result.Message) executor.execute(Parser.parse(sql));
    }

    @Test
    void insertThenPointQuery() {
        assertEquals("OK, 1 row affected",
                dml("INSERT INTO users (id, name, city, age) VALUES (1, 'wangyu', '北京', 26)").text());

        Result.Table result = select("SELECT * FROM users WHERE id = 1");
        assertEquals(List.of("id", "name", "city", "age"), result.columns());
        assertEquals(1, result.rows().size());
        assertArrayEquals(new Object[]{1L, "wangyu", "北京", 26}, result.rows().get(0));
    }

    @Test
    void primaryKeyConflictDoesNotOverwrite() {
        dml("INSERT INTO users (id, name) VALUES (1, 'wangyu')");
        StoreException e = assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("INSERT INTO users (id, name) VALUES (1, '其他人')")));
        assertTrue(e.getMessage().contains("主键冲突"), e.getMessage());

        assertEquals("wangyu", select("SELECT name FROM users WHERE id = 1").rows().get(0)[0]);
    }

    @Test
    void multiRowInsertConflictReportsGroupAndKeepsWrittenRows() {
        dml("INSERT INTO users (id, name) VALUES (10, 'a')");
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users (id, name) VALUES (11, 'b'), (10, 'dup'), (12, 'c')")));
        assertTrue(e.getMessage().contains("第 2 组"), e.getMessage());
        assertTrue(e.getMessage().contains("此前已写入 1 行"), e.getMessage());

        assertEquals(1, select("SELECT * FROM users WHERE id = 11").rows().size());
        assertEquals(0, select("SELECT * FROM users WHERE id = 12").rows().size());
    }

    @Test
    void filterOnNonPrimaryKey() {
        dml("INSERT INTO users (id, name, city, age) VALUES (1, 'a', '北京', 26)");
        dml("INSERT INTO users (id, name, city, age) VALUES (2, 'b', '北京', 24)");
        dml("INSERT INTO users (id, name, city, age) VALUES (3, 'c', '上海', 30)");

        Result.Table result = select("SELECT name, city FROM users WHERE city = '北京' AND age > 25");
        assertEquals(List.of("name", "city"), result.columns());
        assertEquals(1, result.rows().size());
        assertArrayEquals(new Object[]{"a", "北京"}, result.rows().get(0));
    }

    @Test
    void updateAndDeleteWithAffectedRows() {
        dml("INSERT INTO users (id, name, city) VALUES (1, 'a', '北京')");

        assertEquals("OK, 1 row affected", dml("UPDATE users SET city = '上海' WHERE id = 1").text());
        assertEquals("上海", select("SELECT city FROM users WHERE id = 1").rows().get(0)[0]);

        assertEquals("OK, 1 row affected", dml("DELETE FROM users WHERE id = 1").text());
        assertEquals(0, select("SELECT * FROM users WHERE id = 1").rows().size());

        assertEquals("OK, 0 rows affected", dml("UPDATE users SET city = 'x' WHERE id = 99").text());
        assertEquals("OK, 0 rows affected", dml("DELETE FROM users WHERE id = 99").text());
    }

    @Test
    void updateRequiresNonPrimaryKeyColumns() {
        dml("INSERT INTO users (id, name) VALUES (1, 'a')");
        StoreException e = assertThrows(StoreException.class,
                () -> executor.execute(Parser.parse("UPDATE users SET id = 2 WHERE id = 1")));
        assertTrue(e.getMessage().contains("不允许修改主键列"), e.getMessage());
    }

    @Test
    void deleteWithoutWhereAffectsAllRows() {
        dml("INSERT INTO users (id) VALUES (1), (2), (3)");
        assertEquals("OK, 3 rows affected", dml("DELETE FROM users").text());
        assertEquals(0, select("SELECT * FROM users").rows().size());
    }

    @Test
    void limitAndNullSemantics() {
        dml("INSERT INTO users (id, name, age) VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
        dml("INSERT INTO users (id, name) VALUES (4, 'd')");

        assertEquals(2, select("SELECT * FROM users LIMIT 2").rows().size());
        assertEquals(0, select("SELECT * FROM users LIMIT 0").rows().size());
        assertEquals(4, select("SELECT * FROM users").rows().size());

        // age 缺省为 NULL;NULL 比较结果为 UNKNOWN,不满足条件
        assertEquals(0, select("SELECT * FROM users WHERE age > 5 AND id = 4").rows().size());
        Result.Table nulls = select("SELECT age FROM users WHERE id = 4");
        assertNull(nulls.rows().get(0)[0]);
    }

    @Test
    void inAndOrConditions() {
        dml("INSERT INTO users (id, name, age) VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30), (4, 'd', 40)");
        assertEquals(2, select("SELECT * FROM users WHERE id IN (1, 3)").rows().size());
        assertEquals(3, select("SELECT * FROM users WHERE age = 10 OR age >= 30").rows().size());
        assertEquals(1, select("SELECT * FROM users WHERE (age = 10 OR age = 20) AND name = 'b'")
                .rows().size());
    }

    @Test
    void aliasInProjection() {
        dml("INSERT INTO users (id, name) VALUES (1, 'a')");
        Result.Table result = select("SELECT id AS 编号, name AS 姓名 FROM users WHERE id = 1");
        assertEquals(List.of("编号", "姓名"), result.columns());
        assertEquals(1L, result.rows().get(0)[0]);
    }

    @Test
    void validationErrors() {
        assertTrue(assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users (id, name) VALUES ('abc', 'a')")))
                .getMessage().contains("期望 BIGINT"));

        assertTrue(assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users (id, age) VALUES (1, 99999999999)")))
                .getMessage().contains("超出 INT 范围"));

        assertTrue(assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users (id, name) VALUES (1, '这个字符串明显超过三十二个字节这个字符串明显超过三十二个字节')")))
                .getMessage().contains("超长"));

        assertTrue(assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users (name) VALUES ('a')")))
                .getMessage().contains("必须给出主键列"));

        assertTrue(assertThrows(CatalogException.class, () -> executor.execute(Parser.parse(
                "SELECT * FROM no_such_table")))
                .getMessage().contains("不存在"));
    }

    @Test
    void dateTableRoundTrip() {
        executor.execute(Parser.parse(
                "CREATE TABLE events (id INT PRIMARY KEY, day DATE, ok BOOLEAN, score DOUBLE)"));
        dml("INSERT INTO events (id, day, ok, score) VALUES (1, '2024-01-02', TRUE, 1.5)");
        Result.Table result = select("SELECT day, ok, score FROM events WHERE id = 1");
        assertArrayEquals(new Object[]{LocalDate.of(2024, 1, 2), true, 1.5}, result.rows().get(0));

        assertTrue(assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO events (id, day) VALUES (2, '2024/01/02')")))
                .getMessage().contains("YYYY-MM-DD"));

        assertEquals(1, select("SELECT * FROM events WHERE day > '2024-01-01'").rows().size());
    }

    @Test
    void showTablesDescribeDropTruncate() {
        Result.Table tables = select("SHOW TABLES");
        assertEquals(List.of("table_name", "table_id", "created_at"), tables.columns());
        assertEquals("users", tables.rows().get(0)[0]);

        Result.Table desc = select("DESC users");
        assertEquals(List.of("column", "type", "primary_key", "nullable"), desc.columns());
        assertEquals("id", desc.rows().get(0)[0]);
        assertEquals("BIGINT", desc.rows().get(0)[1]);
        assertEquals("YES", desc.rows().get(0)[2]);
        assertEquals("NO", desc.rows().get(0)[3]);
        assertEquals("VARCHAR(32)", desc.rows().get(1)[1]);

        dml("INSERT INTO users (id, name) VALUES (1, 'a')");
        Result.Message truncate = dml("TRUNCATE TABLE users");
        assertTrue(truncate.text().contains("已清空"), truncate.text());
        assertEquals(0, select("SELECT * FROM users").rows().size());

        dml("DROP TABLE users");
        assertTrue(assertThrows(CatalogException.class, () -> select("SELECT * FROM users"))
                .getMessage().contains("不存在"));
        assertEquals(0, select("SHOW TABLES").rows().size());
    }

    // ------------------------------------------------------------ P2.1 反引号标识符

    @Test
    void quotedIdentifiersEndToEnd() {
        executor.execute(Parser.parse(
                "CREATE TABLE `t_quoted` (`id` INT PRIMARY KEY, `select` VARCHAR(64), `from` INT)"));

        assertEquals("OK, 1 row affected",
                dml("INSERT INTO `t_quoted` (`id`, `select`, `from`) VALUES (1, 'abc', 2)").text());
        assertArrayEquals(new Object[]{1, 2},
                select("SELECT `id`, `from` FROM `t_quoted` WHERE `id` = 1").rows().get(0));

        assertEquals("OK, 1 row affected",
                dml("UPDATE `t_quoted` SET `from` = 3 WHERE `id` = 1").text());
        assertEquals(3, select("SELECT `from` FROM `t_quoted` WHERE `id` = 1").rows().get(0)[0]);

        assertEquals("OK, 1 row affected", dml("DELETE FROM `t_quoted` WHERE `id` = 1").text());
        assertEquals(0, select("SELECT * FROM `t_quoted`").rows().size());

        // 反引号不引入大小写语义(FR-182):`ID` 与 ID 是同一个名字。
        dml("INSERT INTO `T_QUOTED` (`ID`, `SELECT`, `FROM`) VALUES (2, 'abc', 4)");
        assertEquals(1, select("SELECT ID FROM T_QUOTED WHERE ID = 2").rows().size());
        assertEquals("abc", select("SELECT `select` FROM `t_quoted` WHERE `id` = 2")
                .rows().get(0)[0]);
    }

    // ------------------------------------------------------------ P2.1 INSERT 省略列名列表

    @Test
    void insertWithoutColumnListExpandsAllColumnsInTableOrder() {
        assertEquals("OK, 1 row affected",
                dml("INSERT INTO users VALUES (5, 'zhaoliu', '杭州', 19)").text());
        assertArrayEquals(new Object[]{5L, "zhaoliu", "杭州", 19},
                select("SELECT * FROM users WHERE id = 5").rows().get(0));

        assertEquals("OK, 2 rows affected",
                dml("INSERT INTO users VALUES (6, 'sunqi', '成都', 21), (7, 'zhouba', '西安', 24)").text());
        assertArrayEquals(new Object[]{6L, "sunqi", "成都", 21},
                select("SELECT * FROM users WHERE id = 6").rows().get(0));

        // FR-193:两种写法可在同一会话内混用,显式列名形式语义不变。
        assertEquals("OK, 1 row affected",
                dml("INSERT INTO users (id, name) VALUES (8, 'qianjiu')").text());
        assertArrayEquals(new Object[]{8L, "qianjiu", null, null},
                select("SELECT * FROM users WHERE id = 8").rows().get(0));
        assertEquals(4, select("SELECT * FROM users").rows().size());
    }

    @Test
    void insertWithoutColumnListRejectsWrongArity() {
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users VALUES (8, 'qianjiu')")));
        assertTrue(e.getMessage().contains("第 1 组取值个数(2)与列数(4)不一致"), e.getMessage());
        // 不写半行数据
        assertEquals(0, select("SELECT * FROM users").rows().size());
    }

    @Test
    void insertWithoutColumnListReportsGroupIndexForMultiRow() {
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users VALUES (1, 'a', '北京', 20), (2, 'b')")));
        assertTrue(e.getMessage().contains("第 2 组取值个数(2)与列数(4)不一致"), e.getMessage());
        // 出错的那一组不写半行;此前已成功的组保留(与主键冲突时的口径一致)
        assertEquals(1, select("SELECT * FROM users").rows().size());
        assertEquals(1, select("SELECT * FROM users WHERE id = 1").rows().size());
    }

    @Test
    void insertEmptyColumnListRejected() {
        ParseException e = assertThrows(ParseException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users () VALUES (1)")));
        assertTrue(e.getMessage().contains("列名列表不能为空"), e.getMessage());
    }

    @Test
    void insertZeroValuesReportsArityMismatch() {
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users VALUES ()")));
        assertTrue(e.getMessage().contains("第 1 组取值个数(0)与列数(4)不一致"), e.getMessage());
    }

    @Test
    void quotedIdentifiersFailLikeUnquotedOnMissingObjects() {
        assertTrue(assertThrows(CatalogException.class, () -> executor.execute(Parser.parse(
                "SELECT * FROM `no_such`"))).getMessage().contains("表 no_such 不存在"));
        assertTrue(assertThrows(CatalogException.class, () -> executor.execute(Parser.parse(
                "SELECT `nope` FROM users"))).getMessage().contains("列 nope 不存在(表 users)"));
    }

    @Test
    void insertWithoutColumnListReportsPrimaryKeyConflictPerGroup() {
        dml("INSERT INTO users VALUES (1, 'a', '北京', 20)");
        StoreException e = assertThrows(StoreException.class, () -> executor.execute(Parser.parse(
                "INSERT INTO users VALUES (2, 'b', '上海', 21), (1, 'dup', '上海', 22)")));
        assertTrue(e.getMessage().contains("第 2 组主键冲突"), e.getMessage());
        assertTrue(e.getMessage().contains("此前已写入 1 行"), e.getMessage());
        assertEquals(1, select("SELECT * FROM users WHERE id = 1").rows().size());
        assertEquals(1, select("SELECT * FROM users WHERE id = 2").rows().size());
    }

    // ------------------------------------------------------------ P2.1 SHOW DATABASES

    @Test
    void showDatabases() {
        Result.Table table = select("SHOW DATABASES");
        assertEquals(List.of("Database"), table.columns());
        assertEquals(1, table.rows().size());
        assertEquals("default", table.rows().get(0)[0]);
    }

    @Test
    void showDatabasesDoesNotTouchBackend() {
        // FR-201:由 FE 内存直接回答,BE 不可用也应照常出结果。
        KvStore offline = new KvStore() {
            @Override
            public byte[] get(byte[] key) {
                throw new AssertionError("SHOW DATABASES 不应访问 BE");
            }

            @Override
            public void put(byte[] key, byte[] value) {
                throw new AssertionError("SHOW DATABASES 不应访问 BE");
            }

            @Override
            public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                throw new AssertionError("SHOW DATABASES 不应访问 BE");
            }
        };
        Executor offlineExecutor = new Executor(offline, catalog);

        Result.Table table = (Result.Table) offlineExecutor.execute(Parser.parse("show databases"));
        assertEquals(List.of("Database"), table.columns());
        assertEquals("default", table.rows().get(0)[0]);
    }
}
