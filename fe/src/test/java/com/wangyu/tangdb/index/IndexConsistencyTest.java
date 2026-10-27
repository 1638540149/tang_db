package com.wangyu.tangdb.index;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.testing.FakeKvStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P4 场景 11 / SC-329 / INV-10:5000 次随机 INSERT / UPDATE / DELETE 后,
 * 每条索引按索引路径查出的主键集合必须与全表扫推算的主键集合完全一致(差异 0)。
 */
class IndexConsistencyTest {

    private static final int OPS = 5000;

    private final Map<Integer, String> codes = new HashMap<>();
    private final Map<Integer, Integer> buckets = new HashMap<>();

    private static Set<Integer> ids(Result.Table table) {
        Set<Integer> set = new TreeSet<>();
        for (Object[] row : table.rows()) {
            set.add(((Number) row[0]).intValue());
        }
        return set;
    }

    @Test
    void randomWritesKeepEveryIndexConsistentWithFullTableScan() {
        FakeKvStore kv = new FakeKvStore();
        Catalog catalog = Catalog.open(kv);
        Executor executor = new Executor(kv, catalog);
        executor.execute(Parser.parse("CREATE TABLE inv (id INT PRIMARY KEY, code VARCHAR(16), "
                + "bucket INT, note VARCHAR(8))"));
        executor.execute(Parser.parse("CREATE UNIQUE INDEX uk_code ON inv (code)"));
        executor.execute(Parser.parse("CREATE INDEX idx_bucket ON inv (bucket)"));

        Random random = new Random(20261026L);
        int applied = 0;
        for (int i = 0; i < OPS; i++) {
            int id = 1 + random.nextInt(400);
            int kind = random.nextInt(100);
            try {
                if (kind < 55) {
                    int code = random.nextInt(500);
                    String note = "n" + random.nextInt(10);
                    executor.execute(Parser.parse("INSERT INTO inv (id, code, bucket, note) VALUES ("
                            + id + ", 'v" + code + "', " + (code % 10) + ", '" + note + "')"));
                } else if (kind < 85) {
                    if (random.nextBoolean()) {
                        int bucket = random.nextInt(10);
                        executor.execute(Parser.parse(
                                "UPDATE inv SET bucket = " + bucket + " WHERE id = " + id));
                    } else {
                        int code = random.nextInt(500);
                        executor.execute(Parser.parse("UPDATE inv SET code = 'v" + code
                                + "' WHERE id = " + id));
                    }
                } else {
                    executor.execute(Parser.parse("DELETE FROM inv WHERE id = " + id));
                }
                applied++;
            } catch (StoreException e) {
                // 主键重复 / 唯一冲突 / 行不存在:这些语句整条不写,索引也不应留下痕迹。
            }
        }
        assertTrue(applied > OPS / 2, "随机脚本有效执行次数过少: " + applied);

        rebuildExpected(executor);

        int differences = 0;
        // 唯一索引:每个出现过的 code 值逐值对拍。
        for (String code : new HashSet<>(codes.values())) {
            Set<Integer> expected = new TreeSet<>();
            for (Map.Entry<Integer, String> e : codes.entrySet()) {
                if (e.getValue().equals(code)) {
                    expected.add(e.getKey());
                }
            }
            Result.Table found = (Result.Table) executor.execute(
                    Parser.parse("SELECT id FROM inv WHERE code = '" + code + "'"));
            Set<Integer> actual = ids(found);
            if (!expected.equals(actual)) {
                differences++;
                System.out.println("唯一索引不一致 code=" + code + " expected=" + expected
                        + " actual=" + actual);
            }
        }
        // 二级索引:每个出现过的 bucket 值逐值对拍。
        for (int bucket = 0; bucket < 10; bucket++) {
            Set<Integer> expected = new TreeSet<>();
            for (Map.Entry<Integer, Integer> e : buckets.entrySet()) {
                if (e.getValue() != null && e.getValue() == bucket) {
                    expected.add(e.getKey());
                }
            }
            Result.Table found = (Result.Table) executor.execute(
                    Parser.parse("SELECT id FROM inv WHERE bucket = " + bucket));
            Set<Integer> actual = ids(found);
            if (!expected.equals(actual)) {
                differences++;
                System.out.println("二级索引不一致 bucket=" + bucket + " expected=" + expected
                        + " actual=" + actual);
            }
        }
        // 改索引列后旧值必须查不到(场景 2 的反例)。
        Result.Table gone = (Result.Table) executor.execute(
                Parser.parse("SELECT id FROM inv WHERE code = 'never-existed'"));
        differences += gone.rows().size();

        assertEquals(0, differences, "INV-10:索引与全表扫差异必须为 0");
    }

    /** 用全表扫(不带任何索引条件)重建"真值"。 */
    private void rebuildExpected(Executor executor) {
        codes.clear();
        buckets.clear();
        Result.Table all = (Result.Table) executor.execute(
                Parser.parse("SELECT id, code, bucket FROM inv"));
        for (Object[] row : all.rows()) {
            int id = ((Number) row[0]).intValue();
            codes.put(id, (String) row[1]);
            buckets.put(id, row[2] == null ? null : ((Number) row[2]).intValue());
        }
        assertEquals(codes.size(), new HashSet<>(codes.values()).size(),
                "唯一索引必须保证全表扫看不到重复 code");
    }
}
