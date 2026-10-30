package com.wangyu.tangdb.query;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.ProjectedCodec;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.TableMeta;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SPEC-P7 FR-785 / FR-786 的量化取证工具(不进生产路径)。
 *
 * <p>两个口径都在 FE 侧、同一 JVM 内测:</p>
 * <ul>
 *   <li><b>FR-716 解码复用</b>:同一批行分别用 {@code ProjectedCodec.decode}
 *       (每行 new Object[] + 每次新建 Decoder)与 {@code Decoder} 复用缓冲解码,
 *       比较线程分配字节数与 GC 次数;</li>
 *   <li><b>FR-715 结果集形态</b>:同样行数下,"整表物化到 List&lt;Object[]&gt;"
 *       与"逐批消费、只保留一批"两种形态的保留堆大小(GC 后测量)。</li>
 * </ul>
 *
 * <p>用法:java ... com.wangyu.tangdb.query.P7Evidence [rows]</p>
 */
public final class P7Evidence {

    public static void main(String[] args) {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        TableMeta table = wideTable(20);
        List<byte[]> frames = buildFrames(table, rows);

        // ---- FR-716:解码复用前后 ----
        long gcBefore = gcCount();
        long allocA = allocatedBytes(() -> decodePerRow(table, frames));
        long gcA = gcCount() - gcBefore;

        gcBefore = gcCount();
        long allocB = allocatedBytes(() -> decodeReuse(table, frames));
        long gcB = gcCount() - gcBefore;

        System.out.printf(Locale.ROOT,
                "DECODE_ROWS=%d ALLOC_NEW=%dB ALLOC_REUSE=%dB ALLOC_PER_ROW=%dB "
                        + "ALLOC_REUSE_PER_ROW=%dB ALLOC_RATIO=%.3f GC_NEW=%d GC_REUSE=%d%n",
                rows, allocA, allocB, allocA / rows, allocB / rows,
                allocA == 0 ? 0 : (double) allocB / allocA, gcA, gcB);

        // ---- FR-715:物化 vs 流式(保留堆) ----
        int small = Math.max(1000, rows / 10);
        long heapSmall = retainedHeap(() -> materialize(table, frames, small));
        long heapBig = retainedHeap(() -> materialize(table, frames, rows));
        long heapStream = retainedHeap(() -> stream(frames));
        System.out.printf(Locale.ROOT,
                "ROWS_SMALL=%d ROWS_BIG=%d HEAP_SMALL=%dB HEAP_BIG=%dB HEAP_STREAM=%dB "
                        + "HEAP_GROWTH=%.2fx%n",
                small, rows, heapSmall, heapBig, heapStream,
                heapSmall == 0 ? 0 : (double) heapBig / heapSmall);
    }

    private static TableMeta wideTable(int columns) {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column("id", DataType.BIGINT, 0, false, true, 0));
        for (int i = 1; i < columns; i++) {
            cols.add(new Column("c" + i, DataType.VARCHAR, 128, true, false, i));
        }
        return new TableMeta(77, "wide", TableMeta.STATE_ACTIVE, "", cols, false);
    }

    /** 造 rows 条的投影帧(与 BE 回传的帧格式一致)。 */
    private static List<byte[]> buildFrames(TableMeta table, int rows) {
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, null);
        List<byte[]> frames = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            Object[] values = new Object[table.columnCount()];
            values[0] = (long) i;
            for (int c = 1; c < table.columnCount(); c++) {
                values[c] = "value-" + i + "-" + c;
            }
            byte[] row = RowCodec.encode(table, values);
            frames.add(project(table, plan, row));
        }
        return frames;
    }

    /** 与 BE 的 ProjectRow 等价(只做本工具需要的"整行投影")。 */
    private static byte[] project(TableMeta table, ProjectedCodec.Plan plan, byte[] storedRow) {
        int nullmap = (table.columnCount() + 7) / 8;
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.util.List<Integer> selected = new ArrayList<>();
        for (int ordinal : plan.selectedOrdinals()) {
            selected.add(ordinal);
        }
        byte[] nulls = new byte[(selected.size() + 7) / 8];
        int pos = 7 + nullmap;
        for (int i = 0; i < table.columnCount(); i++) {
            boolean isNull = (storedRow[7 + i / 8] & (1 << (i % 8))) != 0;
            int start = pos;
            int len = 0;
            if (!isNull) {
                Column column = table.columns().get(i);
                if (column.type() == DataType.VARCHAR || column.type() == DataType.JSON) {
                    len = ProjectedCodec.readLe4(storedRow, pos);
                    start = pos + 4;
                    pos += 4 + len;
                } else {
                    len = ProjectedCodec.slotWidth(column);
                    pos += len;
                }
            }
            if (!selected.contains(i)) {
                continue;
            }
            int slot = selected.indexOf(i);
            if (isNull) {
                nulls[slot / 8] |= (byte) (1 << (slot % 8));
                continue;
            }
            body.write(len & 0xff);
            body.write((len >>> 8) & 0xff);
            body.write((len >>> 16) & 0xff);
            body.write((len >>> 24) & 0xff);
            body.write(storedRow, start, len);
        }
        byte[] payload = body.toByteArray();
        byte[] frame = new byte[nulls.length + payload.length];
        System.arraycopy(nulls, 0, frame, 0, nulls.length);
        System.arraycopy(payload, 0, frame, nulls.length, payload.length);
        return frame;
    }

    private static long sink;

    private static void decodePerRow(TableMeta table, List<byte[]> frames) {
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, null);
        for (byte[] frame : frames) {
            sink += ProjectedCodec.decode(table, plan, frame)[0].hashCode();
        }
    }

    private static void decodeReuse(TableMeta table, List<byte[]> frames) {
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, null);
        ProjectedCodec.Decoder decoder = new ProjectedCodec.Decoder(table, plan);
        Object[] reuse = new Object[table.columnCount()];
        for (byte[] frame : frames) {
            decoder.decodeInto(frame, reuse);
            sink += reuse[0].hashCode();
        }
    }

    /** 物化形态:把每行解码成 Object[] 后全部保留(结果集先物化再写 socket 的老口径)。 */
    private static List<Object[]> materialize(TableMeta table, List<byte[]> frames, int count) {
        ProjectedCodec.Plan plan = ProjectedCodec.plan(table, null);
        List<Object[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(ProjectedCodec.decode(table, plan, frames.get(i)));
        }
        return out;
    }

    private static Object stream(List<byte[]> frames) {
        // 流式形态:只保留"当前一批 / 当前一行",消费完即丢
        byte[] current = null;
        for (byte[] frame : frames) {
            current = frame;
        }
        return current;
    }

    private static long allocatedBytes(Runnable action) {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        action.run();
        long after = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        return Math.max(0, after - before);
    }

    /** 保留一个对象引用跨过两次 GC,测量这块结果的保留堆。 */
    private static long retainedHeap(java.util.function.Supplier<Object> action) {
        System.gc();
        sleep();
        long before = usedHeap();
        Object keep = action.get();
        System.gc();
        sleep();
        long after = usedHeap();
        sink += keep == null ? 0 : keep.hashCode();
        return Math.max(0, after - before);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void sleep() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, bean.getCollectionCount());
        }
        return total;
    }
}
