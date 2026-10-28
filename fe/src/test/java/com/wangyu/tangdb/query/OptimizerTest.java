package com.wangyu.tangdb.query;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.planner.OptimizerConfig;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-P6 B ~ G 组:CBO 选择、连接顺序、谓词下推/列裁剪、集合运算、相关子查询与量词、
 * DISTINCT、派生表、EXPLAIN 可解释性与计划稳定性。
 */
class OptimizerTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
    }

    private Result exec(String sql) {
        return executor.execute(Parser.parse(sql));
    }

    private Result.Table table(String sql) {
        Result result = executor.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, "期望结果集: " + sql);
        return (Result.Table) result;
    }

    private List<List<Object>> rows(String sql) {
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : table(sql).rows()) {
            out.add(new ArrayList<>(Arrays.asList(row)));
        }
        return out;
    }

    private static List<Integer> ints(Result.Table table) {
        List<Integer> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add(((Number) row[0]).intValue());
        }
        return out;
    }

    private String explain(String sql) {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : table("EXPLAIN " + sql).rows()) {
            sb.append(row[0]).append('\n');
        }
        return sb.toString();
    }

    private void seedUsers() {
        exec("CREATE TABLE u (id INT PRIMARY KEY, name VARCHAR(16), city VARCHAR(8), score INT)");
        exec("INSERT INTO u VALUES (1, 'alice', 'bj', 10), (2, 'bob', 'sh', 20), "
                + "(3, 'cathy', 'bj', NULL), (4, 'dave', NULL, 40), (5, 'eve', 'gz', 50)");
        exec("CREATE TABLE ord (oid INT PRIMARY KEY, uid INT, amount INT)");
        exec("INSERT INTO ord VALUES (100, 1, 5), (101, 1, 7), (102, 2, 9), "
                + "(103, 99, 11), (104, NULL, 13)");
    }

    // ============================================================ E 组:集合运算

    @Test
    void unionAndUnionAll() {
        exec("CREATE TABLE a (id INT PRIMARY KEY, v INT)");
        exec("CREATE TABLE b (id INT PRIMARY KEY, v INT)");
        exec("INSERT INTO a VALUES (1, 10), (2, 20), (3, NULL)");
        exec("INSERT INTO b VALUES (1, 20), (2, 30), (3, NULL)");

        List<List<Object>> all = rows("SELECT v FROM a UNION ALL SELECT v FROM b");
        assertEquals(6, all.size(), "UNION ALL 不去重");
        List<List<Object>> distinct = rows("SELECT v FROM a UNION SELECT v FROM b");
        // 10,20,30,NULL → NULL 视为相等只留一个(FR-654)
        assertEquals(4, distinct.size(), "UNION 去重且 NULL 相等: " + distinct);
        assertTrue(distinct.stream().anyMatch(r -> r.get(0) == null));
    }

    @Test
    void intersectExceptAndMinusAlias() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("CREATE TABLE b (id INT PRIMARY KEY)");
        exec("INSERT INTO a VALUES (1), (2), (3)");
        exec("INSERT INTO b VALUES (2), (3), (4)");
        assertEquals(List.of(2, 3), ints(table("SELECT id FROM a INTERSECT SELECT id FROM b")));
        assertEquals(List.of(1), ints(table("SELECT id FROM a EXCEPT SELECT id FROM b")));
        assertEquals(List.of(1), ints(table("SELECT id FROM a MINUS SELECT id FROM b")),
                "MINUS 是 EXCEPT 的别名");
        SqlError e = assertThrows(SqlError.class,
                () -> table("SELECT id FROM a INTERSECT ALL SELECT id FROM b"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, e.code());
        SqlError exceptAll = assertThrows(SqlError.class,
                () -> table("SELECT id FROM a EXCEPT ALL SELECT id FROM b"));
        assertEquals(SqlError.ER_NOT_SUPPORTED_YET, exceptAll.code());
    }

    @Test
    void setColumnCountAndTypeMismatchAre1222() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("CREATE TABLE b (id INT PRIMARY KEY, name VARCHAR(8))");
        exec("CREATE TABLE c (id VARCHAR(8) PRIMARY KEY)");
        SqlError count = assertThrows(SqlError.class,
                () -> table("SELECT id FROM a UNION SELECT id, name FROM b"));
        assertEquals(SqlError.ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT, count.code());
        assertTrue(count.getMessage().contains("列数"));
        SqlError type = assertThrows(SqlError.class,
                () -> table("SELECT id FROM a UNION SELECT id FROM c"));
        assertEquals(SqlError.ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT, type.code());
    }

    @Test
    void setPrecedenceIntersectBindsTighter() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("CREATE TABLE b (id INT PRIMARY KEY)");
        exec("CREATE TABLE c (id INT PRIMARY KEY)");
        exec("INSERT INTO a VALUES (1), (2)");
        exec("INSERT INTO b VALUES (2), (3)");
        exec("INSERT INTO c VALUES (3), (4)");
        // INTERSECT 先算:(a ∩ b) ∪ c = {2} ∪ {2,4} = {2,4}
        // c = {3,4},b ∩ c = {3} → a ∪ {3} = {1,2,3};若按左结合会得到 (a∪b)∩c = {3}
        assertEquals(List.of(1, 2, 3),
                ints(table("SELECT id FROM a UNION SELECT id FROM b INTERSECT SELECT id FROM c")));
        assertEquals(List.of(3), ints(table(
                "(SELECT id FROM a UNION SELECT id FROM b) INTERSECT SELECT id FROM c")));
    }

    @Test
    void setOrderByResolvesResultColumnsAndRejectsSourceColumns() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("INSERT INTO a VALUES (1), (2), (3)");
        assertEquals(List.of(3, 2, 1), ints(table(
                "SELECT id FROM a UNION SELECT id FROM a WHERE id > 1 ORDER BY id DESC")));
        assertEquals(List.of(1, 2, 3), ints(table(
                "SELECT id FROM a UNION SELECT id FROM a ORDER BY 1")));
        SqlError e = assertThrows(SqlError.class,
                () -> table("SELECT id FROM a UNION SELECT id FROM a ORDER BY nope"));
        assertEquals(SqlError.ER_BAD_FIELD_ERROR, e.code());
        RuntimeException unparenthesized = assertThrows(RuntimeException.class,
                () -> table("SELECT id FROM a ORDER BY id UNION SELECT id FROM a"));
        assertEquals(SqlError.ER_SYNTAX_ERROR, SqlError.from(unparenthesized).code(),
                "分支自带的 ORDER BY 必须加括号(边界 75)");
    }

    @Test
    void parenthesizedBranchCanCarryLimit() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("INSERT INTO a VALUES (1), (2), (3)");
        assertEquals(List.of(1, 2),
                ints(table("(SELECT id FROM a ORDER BY id LIMIT 2) UNION ALL "
                        + "SELECT id FROM a WHERE id = 9")),
                "括号内分支的 ORDER BY / LIMIT 先算完");
    }

    // ============================================================ G 组:DISTINCT

    @Test
    void distinctMatchesGroupBy() {
        seedUsers();
        assertEquals(rows("SELECT city FROM u GROUP BY city"),
                rows("SELECT DISTINCT city FROM u"));
        assertEquals(rows("SELECT city, score FROM u GROUP BY city, score"),
                rows("SELECT DISTINCT city, score FROM u"));
        assertEquals(5, table("SELECT DISTINCT id FROM u").rows().size());
        assertTrue(table("SELECT DISTINCT city FROM u WHERE 1 = 0").rows().isEmpty());
        // DISTINCT + ORDER BY + LIMIT:先排序去重再取(NULL 排最前,边界口径见 K105)
        List<Object[]> distinctOrdered = table(
                "SELECT DISTINCT city FROM u ORDER BY city LIMIT 2").rows();
        assertEquals(2, distinctOrdered.size());
        assertNull(distinctOrdered.get(0)[0]);
        assertEquals("bj", distinctOrdered.get(1)[0]);
    }

    // ============================================================ G 组:派生表

    @Test
    void derivedTablesNestingJoinAndAliases() {
        seedUsers();
        List<List<Object>> single = rows(
                "SELECT t.city, t.c FROM (SELECT city, COUNT(*) AS c FROM u GROUP BY city) t "
                        + "ORDER BY t.city");
        assertEquals(4, single.size(), "bj / sh / gz / NULL 四组");
        List<Object> bj = single.stream().filter(r -> "bj".equals(r.get(0))).findFirst().orElseThrow();
        assertEquals(2L, ((Number) bj.get(1)).longValue());

        List<List<Object>> nested = rows(
                "SELECT x.city FROM (SELECT y.city FROM (SELECT city FROM u) y) x "
                        + "WHERE x.city = 'bj'");
        assertEquals(2, nested.size(), "两层嵌套");

        List<List<Object>> joined = rows(
                "SELECT d.name, o.amount FROM u d JOIN "
                        + "(SELECT uid, SUM(amount) AS amount FROM ord GROUP BY uid) o "
                        + "ON d.id = o.uid ORDER BY d.id");
        assertEquals(2, joined.size());
        assertEquals(12L, ((Number) joined.get(0).get(1)).longValue());

        assertEquals(1, table("SELECT * FROM (SELECT id FROM u LIMIT 1) AS s").rows().size(),
                "列别名 / 表别名");
    }

    @Test
    void derivedTableRequiresAliasAndRejectsLateral() {
        seedUsers();
        RuntimeException noAlias = assertThrows(RuntimeException.class,
                () -> table("SELECT * FROM (SELECT id FROM u)"));
        assertEquals(1248, SqlError.from(noAlias).code(), "派生表缺别名 → 1248(边界 78)");

        RuntimeException lateral = assertThrows(RuntimeException.class,
                () -> table("SELECT * FROM u x WHERE EXISTS (SELECT 1 FROM "
                        + "(SELECT id FROM ord WHERE ord.uid = x.id) y)"));
        assertEquals(1235, SqlError.from(lateral).code(), "横向相关 → 1235(边界 80)");

        RuntimeException deep = assertThrows(RuntimeException.class, () -> table(
                "SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM "
                        + "(SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM "
                        + "(SELECT id FROM u) a) b) c) d) e) f) g) h) i"));
        assertEquals(1235, SqlError.from(deep).code(), "派生表嵌套 > 8 → 1235(边界 79)");
    }

    // ============================================================ F 组:EXISTS / ANY / ALL

    @Test
    void existsAndNotExistsIncludingEmptyInner() {
        seedUsers();
        assertEquals(List.of(1, 2), ints(table(
                "SELECT id FROM u WHERE EXISTS (SELECT 1 FROM ord WHERE ord.uid = u.id) "
                        + "ORDER BY id")));
        List<Integer> notExists = ints(table(
                "SELECT id FROM u WHERE NOT EXISTS (SELECT 1 FROM ord WHERE ord.uid = u.id) "
                        + "ORDER BY id"));
        assertEquals(List.of(3, 4, 5), notExists);
        // 内层空集时 NOT EXISTS 为真(边界 81)
        assertEquals(5, table("SELECT id FROM u WHERE NOT EXISTS "
                + "(SELECT 1 FROM ord WHERE ord.amount > 1000)").rows().size());
    }

    @Test
    void anyAndAllWithNullAndEmptySets() {
        exec("CREATE TABLE n (id INT PRIMARY KEY, v INT)");
        exec("INSERT INTO n VALUES (1, 1), (2, 2), (3, NULL)");
        // = ANY:有匹配即真;NULL 不影响真
        assertEquals(List.of(1, 2), ints(table(
                "SELECT id FROM n WHERE v = ANY (SELECT v FROM n WHERE v IS NOT NULL) "
                        + "ORDER BY id")));
        // > ALL:空集恒真 → 全部行
        assertEquals(3, table("SELECT id FROM n WHERE v > ALL "
                + "(SELECT v FROM n WHERE v > 100)").rows().size());
        // = ANY 空集恒假
        assertTrue(table("SELECT id FROM n WHERE v = ANY "
                + "(SELECT v FROM n WHERE v > 100)").rows().isEmpty());
        // = ANY 内层含 NULL 且无匹配 → NULL(WHERE 不通过,不报错;边界 83)
        assertTrue(table("SELECT id FROM n WHERE v = ANY (SELECT v FROM n) AND id = 999")
                .rows().isEmpty());
        // <> ALL 等价 NOT IN:NULL 行不参与(UNKNOWN 不通过)
        assertTrue(table("SELECT id FROM n WHERE v <> ALL "
                + "(SELECT v FROM n WHERE v IS NOT NULL)").rows().isEmpty());
        exec("INSERT INTO n VALUES (4, 99)");
        assertEquals(List.of(4), ints(table(
                "SELECT id FROM n WHERE v = 99 AND v <> ALL "
                        + "(SELECT v FROM n WHERE v IS NOT NULL AND v <> 99) ORDER BY id")),
                "<> ALL 对不在集合里的非 NULL 值为真");
    }

    @Test
    void notInSubqueryWithNullReturnsEmptySet() {
        seedUsers();
        // 边界 82:NOT IN 子查询含 NULL → 三值语义 → 空集
        assertEquals(0, table("SELECT id FROM u WHERE id NOT IN "
                + "(SELECT uid FROM ord)").rows().size());
    }

    @Test
    void correlatedScalarAndInSubqueries() {
        seedUsers();
        assertEquals(List.of(1, 2), ints(table(
                "SELECT id FROM u WHERE id IN (SELECT uid FROM ord WHERE ord.uid = u.id) "
                        + "ORDER BY id")));
        List<List<Object>> perRow = rows(
                "SELECT u.id, (SELECT COUNT(*) FROM ord WHERE ord.uid = u.id) AS c "
                        + "FROM u ORDER BY u.id");
        assertEquals(2L, ((Number) perRow.get(0).get(1)).longValue(), "uid=1 在 ord 里有 2 行");
        assertEquals(0L, ((Number) perRow.get(2).get(1)).longValue());
        // 结果缓存:同一相关性取值重复命中,结果不变(边界 84)
        for (int i = 0; i < 50; i++) {
            assertEquals(2L, ((Number) rows("SELECT (SELECT COUNT(*) FROM ord o "
                    + "WHERE o.uid = u.id) FROM u WHERE u.id = 1").get(0).get(0)).longValue());
        }
    }

    // ============================================================ B / C 组:CBO 与 EXPLAIN

    @Test
    void explainHasEstimateCandidatesAndEvidence() {
        seedUsers();
        String plan = explain("SELECT * FROM u WHERE city = 'bj'");
        assertTrue(plan.contains("候选: "), plan);
        assertTrue(plan.contains("估算: rows="), plan);
        assertTrue(plan.contains("依据: 默认(未 ANALYZE)"), plan);
        exec("ANALYZE TABLE u");
        String analyzed = explain("SELECT * FROM u WHERE city = 'bj'");
        assertTrue(analyzed.contains("依据: 统计(t="), analyzed);
    }

    @Test
    void planChangesAfterAnalyzeButResultsStayIdentical() {
        exec("CREATE TABLE s (id INT PRIMARY KEY, city VARCHAR(16))");
        for (int i = 0; i < 1000; i++) {
            exec("INSERT INTO s VALUES (" + i + ", '" + (i < 600 ? "hot" : "cold")
                    + "')");
        }
        exec("CREATE INDEX idx_s_city ON s (city)");
        String before = explain("SELECT id FROM s WHERE city = 'hot'");
        List<List<Object>> rowsBefore = rows("SELECT id FROM s WHERE city = 'hot'");
        exec("ANALYZE TABLE s");
        String after = explain("SELECT id FROM s WHERE city = 'hot'");
        assertTrue(!before.equals(after), "ANALYZE 前后计划文本应有差异(SC-374)");
        assertTrue(after.contains("TABLE SCAN"), "60% 选择率应走全表扫描:\n" + after);
        assertEquals(rowsBefore, rows("SELECT id FROM s WHERE city = 'hot'"),
                "计划变化不影响结果");
    }

    @Test
    void optimizerOffFallsBackAndKeepsIdenticalResults() {
        seedUsers();
        exec("CREATE INDEX idx_u_city ON u (city)");
        exec("ANALYZE TABLE u");
        exec("ANALYZE TABLE ord");
        List<String> queries = List.of(
                "SELECT id, name FROM u WHERE city = 'bj'",
                "SELECT city, COUNT(*) FROM u GROUP BY city",
                "SELECT u.name, o.amount FROM u JOIN ord o ON u.id = o.uid",
                "SELECT id FROM u WHERE id IN (SELECT uid FROM ord)",
                "SELECT city FROM u UNION SELECT city FROM u WHERE score > 10",
                "SELECT DISTINCT city FROM u",
                "SELECT id FROM u WHERE EXISTS (SELECT 1 FROM ord WHERE ord.uid = u.id)",
                "SELECT * FROM (SELECT id, city FROM u WHERE score > 5) d ORDER BY id",
                "SELECT id FROM u ORDER BY city DESC, id LIMIT 3",
                "SELECT COUNT(*) FROM u WHERE score IS NULL",
                "SELECT id FROM u WHERE id > ALL (SELECT uid FROM ord WHERE uid IS NOT NULL)",
                "SELECT o.oid FROM ord o WHERE o.amount > 6 AND o.uid = 1");
        for (String sql : queries) {
            Executor on = new Executor(kv, catalog)
                    .withOptimizer(OptimizerConfig.defaults());
            Executor off = new Executor(kv, catalog)
                    .withOptimizer(OptimizerConfig.disabled());
            off.withStats(on.stats());
            List<List<Object>> withCbo = resultRows(on, sql);
            List<List<Object>> withoutCbo = resultRows(off, sql);
            assertEquals(withoutCbo, withCbo, "optimizer_enabled 开关不改结果: " + sql);
        }
    }

    private List<List<Object>> resultRows(Executor target, String sql) {
        Result result = target.execute(Parser.parse(sql));
        assertInstanceOf(Result.Table.class, result, sql);
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : ((Result.Table) result).rows()) {
            out.add(new ArrayList<>(Arrays.asList(row)));
        }
        return out;
    }

    @Test
    void explainIsStableAcrossRepeatedCalls() {
        seedUsers();
        exec("ANALYZE TABLE u");
        for (String sql : List.of(
                "SELECT * FROM u WHERE city = 'bj' ORDER BY score DESC LIMIT 2",
                "SELECT city, COUNT(*) FROM u GROUP BY city",
                "SELECT u.name, o.amount FROM u JOIN ord o ON u.id = o.uid",
                "SELECT id FROM u UNION SELECT uid FROM ord")) {
            String first = explain(sql);
            String second = explain(sql);
            String third = explain(sql);
            assertEquals(first, second, "计划文本必须稳定(SC-386): " + sql);
            assertEquals(first, third, "计划文本必须稳定(SC-386): " + sql);
            assertTrue(!first.contains("hash="), "EXPLAIN 不含对象 hash: " + first);
        }
    }

    @Test
    void threeTableJoinOrderFollowsEstimates() {
        exec("CREATE TABLE small (id INT PRIMARY KEY, val INT)");
        exec("CREATE TABLE big (id INT PRIMARY KEY, small_id INT, val INT)");
        exec("CREATE TABLE mid (id INT PRIMARY KEY, big_id INT)");
        for (int i = 0; i < 20; i++) {
            exec("INSERT INTO small VALUES (" + i + ", " + i + ")");
        }
        for (int i = 0; i < 2000; i++) {
            exec("INSERT INTO big VALUES (" + i + ", " + (i % 20) + ", " + i + ")");
        }
        for (int i = 0; i < 100; i++) {
            exec("INSERT INTO mid VALUES (" + i + ", " + (i * 20) + ")");
        }
        exec("ANALYZE TABLE small");
        exec("ANALYZE TABLE big");
        exec("ANALYZE TABLE mid");
        String plan = explain("SELECT s.id, b.id, m.id FROM big b "
                + "JOIN small s ON b.small_id = s.id JOIN mid m ON m.big_id = b.id");
        assertTrue(plan.contains("连接顺序: "), plan);
        assertTrue(plan.contains("build="), plan);
        List<List<Object>> rows = rows("SELECT s.id, b.id, m.id FROM big b "
                + "JOIN small s ON b.small_id = s.id JOIN mid m ON m.big_id = b.id");
        assertEquals(100, rows.size(), "结果正确性与连接顺序无关");
        assertTrue(rows.get(0).get(0) != null);
    }

    // ============================================================ D 组:下推 / 折叠 / 列裁剪 / LIMIT

    @Test
    void predicatePushdownAndColumnPruningVisibleInExplain() {
        exec("CREATE TABLE p (id INT PRIMARY KEY, a INT, b INT, c INT)");
        exec("INSERT INTO p VALUES (1, 10, 20, 30), (2, 11, 21, 31)");
        String plan = explain("SELECT b FROM p WHERE id = 2");
        assertTrue(plan.contains("已下推: "), plan);
        assertTrue(plan.contains("列: 读取 "), plan);
        assertTrue(plan.contains("实际读取列: "), plan);
        assertEquals(1, table("SELECT b FROM p WHERE id = 2").rows().size());
    }

    @Test
    void constantFoldingAndLimitPushdown() {
        exec("CREATE TABLE p (id INT PRIMARY KEY, amount INT)");
        exec("INSERT INTO p VALUES (1, 10), (2, 20), (3, 30)");
        assertEquals(3, table("SELECT id FROM p WHERE 1 = 1").rows().size());
        assertTrue(table("SELECT id FROM p WHERE 1 = 2").rows().isEmpty());
        assertEquals(List.of(2, 3), ints(table("SELECT id FROM p WHERE NOT NOT id > 1")));
        assertEquals(3, table("SELECT amount / 0 FROM p").rows().size(), "除零仍返回行");
        assertEquals(List.of(1, 2),
                ints(table("SELECT id FROM p WHERE id > 0 LIMIT 2")),
                "有过滤时 LIMIT 不能提前截断扫描");
        String plan = explain("SELECT id FROM p LIMIT 2");
        assertTrue(plan.contains("LIMIT 下推: 扫描 limit=2"), plan);
    }

    @Test
    void havingWithoutAggregateIsPushedDown() {
        exec("CREATE TABLE g (id INT PRIMARY KEY, city VARCHAR(8))");
        exec("INSERT INTO g VALUES (1, 'bj'), (2, 'bj'), (3, 'sh')");
        assertEquals(List.of("bj", "bj"),
                firstColumn(table("SELECT city FROM g GROUP BY city, id HAVING city = 'bj' "
                        + "ORDER BY id")),
                "HAVING 折成 WHERE 后结果不变");
        String plan = explain("SELECT city FROM g GROUP BY city HAVING city = 'bj'");
        assertTrue(plan.contains("已下推: HAVING"), plan);
    }

    private static List<String> firstColumn(Result.Table table) {
        List<String> out = new ArrayList<>();
        for (Object[] row : table.rows()) {
            out.add((String) row[0]);
        }
        return out;
    }

    @Test
    void clientIntrospectionOnInformationSchemaWorks() {
        seedUsers();
        // 图形客户端自省原句形态:ROUTINES LEFT JOIN (SELECT ... GROUP_CONCAT ... GROUP BY ...)
        List<List<Object>> routines = rows("SELECT R.ROUTINE_SCHEMA, R.ROUTINE_NAME, P.ordinals "
                + "FROM information_schema.ROUTINES R LEFT JOIN (SELECT SPECIFIC_SCHEMA, "
                + "SPECIFIC_NAME, GROUP_CONCAT(PARAMETER_NAME) AS ordinals "
                + "FROM information_schema.PARAMETERS GROUP BY SPECIFIC_SCHEMA, SPECIFIC_NAME) P "
                + "ON R.ROUTINE_SCHEMA = P.SPECIFIC_SCHEMA AND R.ROUTINE_NAME = P.SPECIFIC_NAME");
        assertTrue(routines.isEmpty(), "ROUTINES 恒空,LEFT JOIN 后仍为空集");

        // COUNT(*) ... UNION ...(客户端另一条探针)
        List<List<Object>> unionCounts = rows(
                "SELECT COUNT(*) AS c FROM information_schema.TABLES "
                        + "UNION SELECT COUNT(*) AS c FROM information_schema.COLUMNS");
        assertEquals(2, unionCounts.size());
        assertTrue(((Number) unionCounts.get(0).get(0)).longValue() >= 1);

        // 虚拟表不参与统计:EXPLAIN 给固定估算(JOIN 形态走统一计划器)
        String plan = explain("SELECT T.TABLE_NAME, C.COLUMN_NAME "
                + "FROM information_schema.TABLES T JOIN information_schema.COLUMNS C "
                + "ON T.TABLE_NAME = C.TABLE_NAME");
        assertTrue(plan.contains("虚拟表"), plan);
        assertTrue(plan.contains("固定估算"), plan);
    }

    @Test
    void setOperationExplainShowsBranchesAndDedup() {
        exec("CREATE TABLE a (id INT PRIMARY KEY)");
        exec("INSERT INTO a VALUES (1)");
        String plan = explain("SELECT id FROM a UNION SELECT id FROM a");
        assertTrue(plan.contains("支 1:"), plan);
        assertTrue(plan.contains("支 2:"), plan);
        assertTrue(plan.contains("集合运算: UNION"), plan);
        assertTrue(plan.contains("去重: Hash Distinct"), plan);
    }
}
