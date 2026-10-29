package com.wangyu.tangdb.query;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** 分片边界:有序、无重叠、并集覆盖全范围(SPEC-P6.1 FR-705,边界 #117)。 */
class ShardBoundaryTest {

    private static byte[] key(int tableId, int id) {
        return new byte[]{(byte) tableId, (byte) (id >>> 24), (byte) (id >>> 16),
                (byte) (id >>> 8), (byte) id};
    }

    @Test
    void boundariesCoverRangeInOrder() {
        byte[] start = key(1, 0);
        byte[] end = key(1, 1000);
        for (int shards = 1; shards <= 16; shards++) {
            List<byte[]> bounds = QueryEngine.shardBoundaries(start, end, shards);
            assertTrue(bounds.size() >= 2, "至少要两个边界");
            assertArrayEquals(start, bounds.get(0));
            assertArrayEquals(end, bounds.get(bounds.size() - 1));
            for (int i = 1; i < bounds.size(); i++) {
                assertTrue(Arrays.compareUnsigned(bounds.get(i - 1), bounds.get(i)) < 0,
                        "边界必须严格递增: shards=" + shards + " i=" + i);
            }
            assertTrue(bounds.size() - 1 <= shards, "分片数不得超过并行度");
        }
    }

    @Test
    void everyKeyBelongsToExactlyOneShard() {
        byte[] start = key(1, 0);
        byte[] end = key(1, 100);
        List<byte[]> bounds = QueryEngine.shardBoundaries(start, end, 8);
        Set<Integer> seen = new HashSet<>();
        for (int id = 0; id < 100; id++) {
            byte[] k = key(1, id);
            int matches = 0;
            for (int i = 0; i + 1 < bounds.size(); i++) {
                if (Arrays.compareUnsigned(k, bounds.get(i)) >= 0
                        && Arrays.compareUnsigned(k, bounds.get(i + 1)) < 0) {
                    matches++;
                }
            }
            assertEquals(1, matches, "key " + id + " 应恰好落在一个分片");
            seen.add(id);
        }
        assertEquals(100, seen.size());
    }

    @Test
    void emptyRangeAndSingleShard() {
        byte[] start = key(1, 10);
        byte[] end = key(1, 10);
        List<byte[]> bounds = QueryEngine.shardBoundaries(start, end, 8);
        assertEquals(2, bounds.size(), "空范围不应切出多余分片");

        bounds = QueryEngine.shardBoundaries(start, key(1, 20), 1);
        assertEquals(2, bounds.size(), "并行度 1 不切分");

        bounds = QueryEngine.shardBoundaries(start, null, 8);
        assertEquals(2, bounds.size(), "无终点不切分");
    }

    @Test
    void boundariesAreStableForUnequalLengths() {
        byte[] start = new byte[]{1, 0};
        byte[] end = new byte[]{1, (byte) 0xFF, (byte) 0xFF};
        List<byte[]> bounds = QueryEngine.shardBoundaries(start, end, 5);
        TreeSet<ByteWrapper> unique = new TreeSet<>();
        for (byte[] b : bounds) {
            assertTrue(unique.add(new ByteWrapper(b)), "边界不得重复");
        }
        for (int i = 1; i < bounds.size(); i++) {
            assertTrue(Arrays.compareUnsigned(bounds.get(i - 1), bounds.get(i)) < 0);
        }
    }

    private record ByteWrapper(byte[] bytes) implements Comparable<ByteWrapper> {
        @Override
        public int compareTo(ByteWrapper other) {
            return Arrays.compareUnsigned(bytes, other.bytes);
        }
    }
}
