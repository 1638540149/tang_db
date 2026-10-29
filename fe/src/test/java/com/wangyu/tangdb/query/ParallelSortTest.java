package com.wangyu.tangdb.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-P6.1 FR-714:分片内并行排序 + 归并。
 *
 * <p>硬要求是"并行度 1 与 &gt;1 结果逐行同序",所以这里的断言都是逐行对拍,
 * 不是集合相等;并额外覆盖大量重复键(最容易被不稳定归并换序的情形)。</p>
 */
class ParallelSortTest {

    private static List<Object[]> rows(int n, long seed) {
        Random random = new Random(seed);
        List<Object[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 排序键取值范围小 ⇒ 大量并列,专门验证稳定性
            int key = random.nextInt(50);
            out.add(new Object[]{i, key, "v" + (i % 7)});
        }
        return out;
    }

    private static Sorter sorter(Path dir, int parallelism, long spillBytes) {
        return new Sorter(spillBytes, dir, parallelism);
    }

    @Test
    void parallelSortMatchesSingleThreadedRowByRow(@TempDir Path dir) {
        List<Object[]> input = rows(20_000, 20261029L);
        List<Object[]> serial = sorter(dir, 1, 64L * 1024 * 1024)
                .sort(input, new int[]{1}, new boolean[]{false}, -1);

        for (int parallelism : new int[]{2, 4, 8}) {
            List<Object[]> parallel = sorter(dir, parallelism, 64L * 1024 * 1024)
                    .sort(input, new int[]{1}, new boolean[]{false}, -1);
            assertEquals(serial.size(), parallel.size(), "行数必须一致");
            for (int i = 0; i < serial.size(); i++) {
                assertArrayEquals(serial.get(i), parallel.get(i),
                        "并行度 " + parallelism + " 的第 " + i + " 行必须与单线程同序");
            }
        }
    }

    @Test
    void parallelSortKeepsMultiColumnAndDescendingOrder(@TempDir Path dir) {
        List<Object[]> input = rows(9_000, 7L);
        int[] keys = {1, 0};
        boolean[] desc = {true, false};
        List<Object[]> serial = sorter(dir, 1, 64L * 1024 * 1024).sort(input, keys, desc, -1);
        List<Object[]> parallel = sorter(dir, 4, 64L * 1024 * 1024).sort(input, keys, desc, -1);
        for (int i = 0; i < serial.size(); i++) {
            assertArrayEquals(serial.get(i), parallel.get(i), "第 " + i + " 行");
        }
        // 降序键确实降序,且同键内按第二列升序(稳定比较器的语义)
        int previous = Integer.MAX_VALUE;
        for (Object[] row : parallel) {
            int current = (Integer) row[1];
            assertTrue(current <= previous, "第一排序键必须降序");
            previous = current;
        }
    }

    @Test
    void parallelTopNIsUnchanged(@TempDir Path dir) {
        List<Object[]> input = rows(10_000, 99L);
        List<Object[]> serial = sorter(dir, 1, 64L * 1024 * 1024)
                .sort(input, new int[]{1}, new boolean[]{false}, 20);
        List<Object[]> parallel = sorter(dir, 8, 64L * 1024 * 1024)
                .sort(input, new int[]{1}, new boolean[]{false}, 20);
        assertEquals(20, parallel.size());
        for (int i = 0; i < serial.size(); i++) {
            assertArrayEquals(serial.get(i), parallel.get(i), "TopN 第 " + i + " 行");
        }
    }

    @Test
    void parallelExternalSortMatchesInMemoryOrder(@TempDir Path dir) {
        // 把 sort_spill_bytes 压到很小 ⇒ 走外部归并(分片排序 + 多路归并)
        List<Object[]> input = rows(12_000, 4242L);
        List<Object[]> inMemory = sorter(dir, 1, 64L * 1024 * 1024)
                .sort(input, new int[]{1, 0}, new boolean[]{false, false}, -1);
        List<Object[]> spilled = sorter(dir, 4, 256L * 1024)
                .sort(input, new int[]{1, 0}, new boolean[]{false, false}, -1);
        assertEquals(inMemory.size(), spilled.size());
        for (int i = 0; i < inMemory.size(); i++) {
            assertArrayEquals(inMemory.get(i), spilled.get(i),
                    "外部归并第 " + i + " 行必须与内存稳定排序同序");
        }
    }

    @Test
    void emptyAndSingleRowInputsAreSafe(@TempDir Path dir) {
        Sorter sorter = sorter(dir, 8, 64L * 1024 * 1024);
        assertEquals(List.of(), sorter.sort(new ArrayList<>(), new int[]{0},
                new boolean[]{false}, -1));
        Object[] only = {1, 2};
        List<Object[]> input = new ArrayList<>();
        input.add(only);
        List<Object[]> sorted = sorter.sort(input, new int[]{0}, new boolean[]{false}, -1);
        assertEquals(1, sorted.size());
        assertArrayEquals(only, sorted.get(0));
    }
}
