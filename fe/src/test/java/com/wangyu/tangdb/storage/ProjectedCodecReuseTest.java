package com.wangyu.tangdb.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-P6.1 FR-716:投影帧解码复用。
 *
 * <p>覆盖三件事:</p>
 * <ol>
 *   <li>复用缓冲与逐行 new 的结果逐格一致(含 NULL、各基本类型、VARCHAR、JSON);</li>
 *   <li>同一个 Plan 连续解 N 行不再产生"每行一个 Object[] + 每列一个装箱对象"的分配;</li>
 *   <li>截断 / 尾部多余字节 / 目标缓冲太窄仍然报同样的错误(不静默放过坏帧)。</li>
 * </ol>
 */
class ProjectedCodecReuseTest {

    private static TableMeta table() {
        return new TableMeta(77L, "t", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 100, true, false, 1),
                new Column("age", DataType.INT, 0, true, false, 2),
                new Column("score", DataType.DOUBLE, 0, true, false, 3),
                new Column("birthday", DataType.DATE, 0, true, false, 4),
                new Column("active", DataType.BOOLEAN, 0, true, false, 5),
                new Column("doc", DataType.JSON, 0, true, false, 6)
        ), false);
    }

    private static boolean[] allNeeded(TableMeta table) {
        boolean[] needed = new boolean[table.columnCount()];
        java.util.Arrays.fill(needed, true);
        return needed;
    }

    /** 与 BE 侧 {@code ProjectRow} 同算法的投影:把整行值编码成帧。 */
    private static byte[] frame(ProjectedCodec.Plan plan, Object[] values) {
        int[] ordinals = plan.selectedOrdinals();
        byte[] nullmap = new byte[plan.nullmapBytes()];
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < ordinals.length; i++) {
            Object value = values[ordinals[i]];
            if (value == null) {
                nullmap[i / 8] |= (byte) (1 << (i % 8));
                continue;
            }
            payloads.add(payload(value));
        }
        int size = nullmap.length;
        for (byte[] payload : payloads) {
            size += 4 + payload.length;
        }
        byte[] out = new byte[size];
        System.arraycopy(nullmap, 0, out, 0, nullmap.length);
        int pos = nullmap.length;
        for (byte[] payload : payloads) {
            out[pos] = (byte) payload.length;
            out[pos + 1] = (byte) (payload.length >>> 8);
            out[pos + 2] = (byte) (payload.length >>> 16);
            out[pos + 3] = (byte) (payload.length >>> 24);
            System.arraycopy(payload, 0, out, pos + 4, payload.length);
            pos += 4 + payload.length;
        }
        return out;
    }

    private static byte[] payload(Object value) {
        if (value instanceof Long v) {
            byte[] out = new byte[8];
            for (int i = 0; i < 8; i++) {
                out[i] = (byte) (v >>> (8 * i));
            }
            return out;
        }
        if (value instanceof Integer v) {
            return new byte[]{(byte) (int) v, (byte) ((int) v >>> 8), (byte) ((int) v >>> 16),
                    (byte) ((int) v >>> 24)};
        }
        if (value instanceof Double v) {
            long bits = Double.doubleToLongBits(v);
            byte[] out = new byte[8];
            for (int i = 0; i < 8; i++) {
                out[i] = (byte) (bits >>> (8 * i));
            }
            return out;
        }
        if (value instanceof Boolean v) {
            return new byte[]{(byte) (v ? 1 : 0)};
        }
        if (value instanceof LocalDate v) {
            int day = (int) v.toEpochDay();
            return new byte[]{(byte) day, (byte) (day >>> 8), (byte) (day >>> 16),
                    (byte) (day >>> 24)};
        }
        if (value instanceof byte[] raw) {
            return raw.clone();
        }
        return ((String) value).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reuseBufferProducesIdenticalValues() {
        TableMeta table = table();
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, allNeeded(table));
        assertNotNull(plan);
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);

        Object[][] rows = {
                {1L, "北京", 26, 1.5, LocalDate.of(2024, 2, 29), true, new byte[]{1, 2, 3}},
                {2L, null, null, null, null, false, null},
                {3L, "上海", -1, 0.0, LocalDate.of(1970, 1, 1), false, new byte[0]},
                {4L, "a-very-long-string-over-32-bytes-to-skip-the-cache", 0, -2.5,
                        LocalDate.of(2000, 1, 1), true, new byte[]{9}}
        };
        for (Object[] expected : rows) {
            byte[] frame = frame(plan, expected);
            Object[] reused = decoder.decode(frame);
            assertSame(reused, decoder.reusableRow(), "解码必须落在复用缓冲上");
            Object[] fresh = ProjectedCodec.decode(table, plan, frame);
            assertArrayEquals(fresh, reused, "复用缓冲与逐行 new 的结果必须逐格一致");
            assertArrayEquals(expected, reused, "解码结果必须与原值一致");
        }
    }

    @Test
    void consecutiveDecodesDoNotAllocate() {
        // JSON 列每次都要交付一份独立的 byte[](调用方可能持有/修改),不参与"零分配"口径。
        TableMeta table = new TableMeta(78L, "t2", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("id", DataType.BIGINT, 0, false, true, 0),
                new Column("name", DataType.VARCHAR, 100, true, false, 1),
                new Column("age", DataType.INT, 0, true, false, 2),
                new Column("score", DataType.DOUBLE, 0, true, false, 3),
                new Column("birthday", DataType.DATE, 0, true, false, 4),
                new Column("active", DataType.BOOLEAN, 0, true, false, 5)
        ), false);
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, allNeeded(table));
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);
        byte[] frame = frame(plan, new Object[]{42L, "北京", 26, 1.5,
                LocalDate.of(2024, 2, 29), true});

        com.sun.management.ThreadMXBean bean = allocatedBytesBean();
        Assumptions.assumeTrue(bean != null, "当前 JVM 不支持线程分配统计");

        long[] sink = new long[1];
        for (int i = 0; i < 50_000; i++) { // 预热 + 填充值缓存
            sink[0] += decoder.decode(frame)[0] == null ? 0 : 1;
        }
        long tid = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(tid);
        int iterations = 500_000;
        for (int i = 0; i < iterations; i++) {
            // decodeInto 复用内部缓冲:解码本身不再 new Object[列数],也不装箱
            decoder.decode(frame);
            sink[0]++;
        }
        long allocated = bean.getThreadAllocatedBytes(tid) - before;
        assertTrue(sink[0] > iterations, "防止 JIT 把循环整个优化掉");
        assertTrue(allocated < 1_000_000,
                "同一 Plan 连续解 " + iterations + " 行不应分配(实测 " + allocated + " 字节)");
    }

    @Test
    void decodeIntoUsesCallerRowArray() {
        TableMeta table = table();
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, allNeeded(table));
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);
        byte[] frame = frame(plan, new Object[]{7L, "x", 3, 0.25, LocalDate.of(2020, 5, 6),
                true, new byte[]{4}});

        Object[] target = new Object[table.columnCount()];
        Object[] returned = decoder.decodeInto(frame, target);
        assertSame(target, returned, "decodeInto 必须写进调用方提供的数组");
        assertEquals(7L, target[0]);
        assertEquals("x", target[1]);
        assertEquals(3, target[2]);
        assertEquals(0.25, target[3]);
        assertEquals(LocalDate.of(2020, 5, 6), target[4]);
        assertEquals(Boolean.TRUE, target[5]);

        assertThrows(StoreException.class,
                () -> decoder.decodeInto(frame, new Object[2]), "目标行缓冲太窄必须报错");
    }

    @Test
    void badFramesAreStillRejected() {
        TableMeta table = table();
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, allNeeded(table));
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);
        byte[] good = frame(plan, new Object[]{1L, "a", 1, 1.0, LocalDate.of(2020, 1, 1), true,
                new byte[]{1}});

        assertThrows(StoreException.class, () -> decoder.decode(null));
        assertThrows(StoreException.class, () -> decoder.decode(new byte[0]));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 1);
        assertThrows(StoreException.class, () -> decoder.decode(truncated));
        byte[] padded = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(StoreException.class, () -> decoder.decode(padded));
    }

    @Test
    void projectionMaskStillSkipsColumns() {
        TableMeta table = table();
        boolean[] needed = allNeeded(table);
        needed[1] = false; // 不读 name
        needed[4] = false; // 不读 birthday
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, needed);
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);
        byte[] frame = frame(plan, new Object[]{5L, "ignored", 9, 2.5, LocalDate.of(2021, 1, 1),
                false, new byte[]{7}});
        Object[] decoded = decoder.decode(frame);
        assertEquals(5L, decoded[0]);
        assertNull(decoded[1], "未选中的列必须是 null");
        assertEquals(9, decoded[2]);
        assertNull(decoded[4], "未选中的列必须是 null");
        assertEquals(Boolean.FALSE, decoded[5]);
    }

    private static com.sun.management.ThreadMXBean allocatedBytesBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean sun ? sun : null;
    }
}
