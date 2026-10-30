package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P7 C 组:精确暴力向量检索(SC-419 / SC-424 / 边界 129 / 130 / 134 / 151)。
 *
 * <p>内存 store 没有图段能力,所以选路一定是"精确暴力"——正好用作 ANN 的对拍基准。</p>
 */
class VectorExactQueryTest {

    private FakeKvStore kv;
    private Catalog catalog;
    private Executor executor;

    @BeforeEach
    void setUp() {
        kv = new FakeKvStore();
        catalog = Catalog.open(kv);
        executor = new Executor(kv, catalog);
        executor.execute(Parser.parse("CREATE TABLE docs (id INT PRIMARY KEY, "
                + "title VARCHAR(32), embedding VECTOR(3))"));
        executor.execute(Parser.parse("INSERT INTO docs VALUES "
                + "(1, 'a', '[0,0,0]'), (2, 'b', '[1,0,0]'), (3, 'c', '[0,2,0]'), "
                + "(4, 'd', '[2,0,0]'), (5, 'e', '[0,0,3]')"));
    }

    private Result.Table select(String sql) {
        return (Result.Table) executor.execute(Parser.parse(sql));
    }

    private static List<Long> ids(Result.Table table, int column) {
        return table.rows().stream().map(row -> ((Number) row[column]).longValue()).toList();
    }

    @Test
    void l2OrderByLimitReturnsNearestByDistanceThenPrimaryKey() {
        Result.Table result = select("SELECT id, title FROM docs "
                + "ORDER BY l2_distance(embedding, '[1,0,0]') LIMIT 3");
        // 距离:1→1,2→0,4→1,3→2,5→3.x;距离相同(1)时按主键升序:2(0),1(1),4(1)
        assertEquals(List.of(2L, 1L, 4L), ids(result, 0));
        assertEquals(List.of("id", "title"), result.columns());
    }

    @Test
    void innerProductAndCosineAlsoWork() {
        Result.Table ip = select("SELECT id FROM docs "
                + "ORDER BY inner_product(embedding, '[1,0,0]') LIMIT 2");
        // inner_product 返回 -dot(越小越相似):id=4 的 -2 最优,其次 id=2 的 -1
        assertEquals(List.of(4L, 2L), ids(ip, 0));
        Result.Table cosine = select("SELECT id FROM docs "
                + "ORDER BY cosine_distance(embedding, '[1,0,0]') LIMIT 2");
        // cos 距离:2/4 都是 0,3/5 是 1,1 是 1(零向量);0 距离按主键:2,4
        assertEquals(List.of(2L, 4L), ids(cosine, 0));
    }

    @Test
    void limitZeroReturnsEmptyAndLimitOverMaxIsRejected() {
        assertEquals(0, select("SELECT id FROM docs ORDER BY l2_distance(embedding,'[0,0,0]') LIMIT 0")
                .rows().size());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> select("SELECT id FROM docs ORDER BY l2_distance(embedding,'[0,0,0]') LIMIT 1001"));
        assertTrue(e.getMessage().contains("1000"), e.getMessage());
    }

    @Test
    void structuredPredicateNarrowsResultAndZeroRowsStaysEmpty() {
        Result.Table filtered = select("SELECT id FROM docs WHERE id >= 3 "
                + "ORDER BY l2_distance(embedding, '[1,0,0]') LIMIT 5");
        assertEquals(List.of(4L, 3L, 5L), ids(filtered, 0));
        Result.Table none = select("SELECT id FROM docs WHERE id > 100 "
                + "ORDER BY l2_distance(embedding, '[1,0,0]') LIMIT 5");
        assertEquals(0, none.rows().size(), "过滤后 0 行必须返回 0 行,不能退化成全表 top-k");
    }

    @Test
    void explainShowsVectorSearchLineAndMode() {
        Result.Table explain = select("EXPLAIN SELECT id FROM docs "
                + "ORDER BY l2_distance(embedding, '[1,0,0]') LIMIT 3");
        String text = explain.rows().stream().map(row -> String.valueOf(row[0]))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("向量检索"), text);
        assertTrue(text.contains("路径=exact"), text);
        assertTrue(text.contains("精确"), text);
        assertTrue(text.contains("选路依据"), text);
    }

    @Test
    void exactModeIsReproducible() {
        String sql = "SELECT id FROM docs ORDER BY l2_distance(embedding, '[1,1,1]') LIMIT 5";
        assertEquals(ids(select(sql), 0), ids(select(sql), 0));
    }

    @Test
    void vectorColumnInPlainOrderByOrWhereIs1235() {
        RuntimeException order = assertThrows(RuntimeException.class,
                () -> select("SELECT id FROM docs ORDER BY embedding"));
        assertTrue(order.getMessage().contains("VECTOR"), order.getMessage());
        RuntimeException where = assertThrows(RuntimeException.class,
                () -> select("SELECT id FROM docs WHERE embedding = '[1,0,0]'"));
        assertTrue(where.getMessage().contains("1235") || where.getMessage().contains("VECTOR"),
                where.getMessage());
    }

    @Test
    void dimensionMismatchAndBadLiteralReportElementIndex() {
        RuntimeException dim = assertThrows(RuntimeException.class,
                () -> select("SELECT id FROM docs ORDER BY l2_distance(embedding, '[1,0]') LIMIT 2"));
        assertTrue(dim.getMessage().contains("期望 3") || dim.getMessage().contains("维度"),
                dim.getMessage());
        RuntimeException nan = assertThrows(RuntimeException.class,
                () -> select("SELECT id FROM docs ORDER BY l2_distance(embedding, '[1,NaN,0]') LIMIT 2"));
        assertTrue(nan.getMessage().contains("第 2 个元素"), nan.getMessage());
    }
}
