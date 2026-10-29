package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.StoreException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 排序(SPEC-P5 C 组 / FR-577 ~ FR-580)。
 *
 * <ul>
 *   <li>多列 ASC/DESC;NULL 位置按 K105(升序在最前,降序在最后);</li>
 *   <li>{@code ORDER BY ... LIMIT n} 走 TopN 堆,内存只与 n(含 offset)相关(FR-579);</li>
 *   <li>纯排序超 {@code sort_spill_bytes} 走外部归并(分片排序 + 多路归并,FR-580);</li>
 *   <li>临时文件放在 FE 自己的临时目录(K114 的位置在 BE 侧会穿透边界,故 FE 排序用
 *       {@code tmp/fe-sort}),查询结束删除、启动时清理残留。</li>
 * </ul>
 */
public final class Sorter {

    /** 与配置项 sort_spill_bytes 对应。 */
    public static final long DEFAULT_SPILL_BYTES = 64L * 1024 * 1024;

    private final long spillBytes;
    private final Path workDir;
    /** 分片内并行排序的并行度(SPEC-P6.1 FR-714);1 = 原单线程路径。 */
    private final int parallelism;

    public Sorter(long spillBytes, Path workDir) {
        this(spillBytes, workDir, 1);
    }

    public Sorter(long spillBytes, Path workDir, int parallelism) {
        this.spillBytes = spillBytes > 0 ? spillBytes : DEFAULT_SPILL_BYTES;
        this.workDir = workDir;
        this.parallelism = Math.max(1, parallelism);
    }

    /** 启动时清理残留的排序临时文件(K114 精神:崩溃残留不得被当成有效数据)。 */
    public static void cleanTempDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("sort-"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清理失败不影响启动
                        }
                    });
        } catch (IOException ignored) {
            // 目录不存在 / 无权限:不影响主流程
        }
    }

    /**
     * 排序并截断。
     *
     * @param rows        待排序行(每行含排序键列)
     * @param keyCols     排序键所在列序号(按优先级)
     * @param descending  每个键是否降序
     * @param keep        需要保留的最大行数(limit + offset);<0 表示全部
     */
    public List<Object[]> sort(List<Object[]> rows, int[] keyCols, boolean[] descending, int keep) {
        if (rows.isEmpty()) {
            return rows;
        }
        Comparator<Object[]> cmp = comparator(keyCols, descending);
        if (keep >= 0 && keep < rows.size()) {
            return topN(rows, cmp, keep); // FR-579
        }
        long estimated = estimateBytes(rows);
        if (estimated <= spillBytes) {
            if (parallelism > 1 && rows.size() >= Parallel.MIN_ROWS) {
                return parallelSort(rows, cmp); // FR-714
            }
            List<Object[]> sorted = new ArrayList<>(rows);
            sorted.sort(cmp);
            return sorted;
        }
        return externalSort(rows, cmp); // FR-580
    }

    /**
     * 分片内并行排序 + 确定性归并(SPEC-P6.1 FR-714)。
     *
     * <p>每片用同一个稳定比较器排好,再按"片序优先"做 k 路归并 —— 与单线程
     * {@code List.sort}(TimSort,稳定)的结果逐行同序,排序键相等的行不会被换序。</p>
     */
    private List<Object[]> parallelSort(List<Object[]> rows, Comparator<Object[]> cmp) {
        int tasks = Parallel.tasks(parallelism, rows.size() / 1024);
        if (tasks <= 1) {
            List<Object[]> sorted = new ArrayList<>(rows);
            sorted.sort(cmp);
            return sorted;
        }
        int[][] ranges = Parallel.ranges(rows.size(), tasks);
        List<List<Object[]>> parts = Parallel.run(tasks, tasks, i -> {
            List<Object[]> part = new ArrayList<>(rows.subList(ranges[i][0], ranges[i][1]));
            part.sort(cmp);
            return part;
        });
        return mergeParts(parts, cmp);
    }

    /** k 路归并:键相等时按片序取(片内已稳定排序 ⇒ 全局稳定)。 */
    private static List<Object[]> mergeParts(List<List<Object[]>> parts, Comparator<Object[]> cmp) {
        java.util.PriorityQueue<int[]> heap = new java.util.PriorityQueue<>(
                Math.max(1, parts.size()),
                (a, b) -> {
                    int c = cmp.compare(parts.get(a[0]).get(a[1]), parts.get(b[0]).get(b[1]));
                    return c != 0 ? c : Integer.compare(a[0], b[0]);
                });
        int total = 0;
        for (int p = 0; p < parts.size(); p++) {
            total += parts.get(p).size();
            if (!parts.get(p).isEmpty()) {
                heap.add(new int[]{p, 0});
            }
        }
        List<Object[]> out = new ArrayList<>(total);
        while (!heap.isEmpty()) {
            int[] top = heap.poll();
            out.add(parts.get(top[0]).get(top[1]));
            if (top[1] + 1 < parts.get(top[0]).size()) {
                top[1]++;
                heap.add(top);
            }
        }
        return out;
    }

    private static Comparator<Object[]> comparator(int[] keyCols, boolean[] descending) {
        return (a, b) -> {
            for (int i = 0; i < keyCols.length; i++) {
                int c = ValueOps.compareForSort(a[keyCols[i]], b[keyCols[i]], descending[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    /** TopN:保留 keep 个"最小"行,内存 O(keep)。 */
    private static List<Object[]> topN(List<Object[]> rows, Comparator<Object[]> cmp, int keep) {
        if (keep <= 0) {
            return new ArrayList<>();
        }
        PriorityQueue<Object[]> heap = new PriorityQueue<>(keep + 1, cmp.reversed());
        for (Object[] row : rows) {
            if (heap.size() < keep) {
                heap.add(row);
            } else if (cmp.compare(row, heap.peek()) < 0) {
                heap.poll();
                heap.add(row);
            }
        }
        List<Object[]> out = new ArrayList<>(heap);
        out.sort(cmp);
        return out;
    }

    private long estimateBytes(List<Object[]> rows) {
        long total = 0;
        for (Object[] row : rows) {
            for (Object v : row) {
                total += 16;
                if (v instanceof String s) {
                    total += s.length() * 2L;
                } else if (v instanceof byte[] b) {
                    total += b.length;
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------ 外部归并

    private List<Object[]> externalSort(List<Object[]> rows, Comparator<Object[]> cmp) {
        Path dir = prepareWorkDir();
        List<Path> runs = new ArrayList<>();
        long rowBytes = Math.max(64, estimateBytes(rows) / Math.max(1, rows.size()));
        int chunkRows = (int) Math.max(256, Math.min(rows.size(), spillBytes / rowBytes));
        int chunks = (rows.size() + chunkRows - 1) / chunkRows;
        try {
            // SPEC-P6.1 FR-714:分片内并行排序(落盘与归并仍按片序,保证稳定)
            List<List<Object[]>> sorted = Parallel.run(parallelism, chunks, i -> {
                int from = i * chunkRows;
                int to = Math.min(rows.size(), from + chunkRows);
                List<Object[]> chunk = new ArrayList<>(rows.subList(from, to));
                chunk.sort(cmp);
                return chunk;
            });
            for (int i = 0; i < sorted.size(); i++) {
                Path run = dir.resolve("sort-run-" + System.nanoTime() + "-" + i + ".bin");
                writeRun(run, sorted.get(i));
                runs.add(run);
            }
            return mergeRuns(runs, cmp);
        } catch (IOException e) {
            throw new StoreException("排序临时文件读写失败(磁盘不足或不可写): " + e.getMessage(), e);
        } finally {
            for (Path run : runs) {
                try {
                    Files.deleteIfExists(run);
                } catch (IOException ignored) {
                    // 清理失败:下次启动 cleanTempDir 再删
                }
            }
        }
    }

    private Path prepareWorkDir() {
        try {
            Files.createDirectories(workDir);
            return workDir;
        } catch (IOException e) {
            throw new StoreException("无法创建排序临时目录 " + workDir + ": " + e.getMessage(), e);
        }
    }

    private static void writeRun(Path path, List<Object[]> rows) throws IOException {
        try (OutputStream raw = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            for (Object[] row : rows) {
                out.writeInt(row.length);
                for (Object v : row) {
                    writeValue(out, v);
                }
            }
        }
    }

    private static List<Object[]> mergeRuns(List<Path> runs, Comparator<Object[]> cmp)
            throws IOException {
        List<RunReader> readers = new ArrayList<>(runs.size());
        try {
            for (int i = 0; i < runs.size(); i++) {
                readers.add(new RunReader(runs.get(i), i));
            }
            // 键相等时按 run 序取:run 是按原始行序落盘的,这样归并结果与稳定排序一致
            PriorityQueue<RunReader> heap = new PriorityQueue<>(
                    Math.max(1, readers.size()),
                    (a, b) -> {
                        int c = cmp.compare(a.current(), b.current());
                        return c != 0 ? c : Integer.compare(a.index, b.index);
                    });
            for (RunReader reader : readers) {
                if (reader.advance()) {
                    heap.add(reader);
                }
            }
            List<Object[]> out = new ArrayList<>();
            while (!heap.isEmpty()) {
                RunReader top = heap.poll();
                out.add(top.current());
                if (top.advance()) {
                    heap.add(top);
                }
            }
            return out;
        } finally {
            for (RunReader reader : readers) {
                reader.close();
            }
        }
    }

    private static final class RunReader implements AutoCloseable {
        private final InputStream raw;
        private final DataInputStream in;
        private final int index;
        private Object[] current;

        RunReader(Path path, int index) throws IOException {
            this.raw = Files.newInputStream(path);
            this.in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
            this.index = index;
        }

        Object[] current() {
            return current;
        }

        boolean advance() throws IOException {
            try {
                int n = in.readInt();
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    row[i] = readValue(in);
                }
                current = row;
                return true;
            } catch (EOFException e) {
                current = null;
                return false;
            }
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(0);
        } else if (v instanceof Integer i) {
            out.writeByte(1);
            out.writeInt(i);
        } else if (v instanceof Long l) {
            out.writeByte(2);
            out.writeLong(l);
        } else if (v instanceof Double d) {
            out.writeByte(3);
            out.writeDouble(d);
        } else if (v instanceof String s) {
            out.writeByte(4);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(b.length);
            out.write(b);
        } else if (v instanceof Boolean b) {
            out.writeByte(5);
            out.writeBoolean(b);
        } else if (v instanceof LocalDate d) {
            out.writeByte(6);
            out.writeLong(d.toEpochDay());
        } else if (v instanceof byte[] b) {
            out.writeByte(7);
            out.writeInt(b.length);
            out.write(b);
        } else {
            throw new SqlError(SqlError.ER_WRONG_ARGUMENTS, "HY000",
                    "排序临时文件不支持的值类型: " + v.getClass().getSimpleName());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        int tag = in.readByte();
        return switch (tag) {
            case 0 -> null;
            case 1 -> in.readInt();
            case 2 -> in.readLong();
            case 3 -> in.readDouble();
            case 4 -> {
                int len = in.readInt();
                if (len < 0 || len > 64 * 1024 * 1024) {
                    throw new IOException("非法的字符串长度 " + len);
                }
                byte[] b = new byte[len];
                in.readFully(b);
                yield new String(b, StandardCharsets.UTF_8);
            }
            case 5 -> in.readBoolean();
            case 6 -> LocalDate.ofEpochDay(in.readLong());
            case 7 -> {
                int len = in.readInt();
                if (len < 0 || len > 64 * 1024 * 1024) {
                    throw new IOException("非法的字节块长度 " + len);
                }
                byte[] b = new byte[len];
                in.readFully(b);
                yield b;
            }
            default -> throw new IOException("排序临时文件类型标记非法: " + tag);
        };
    }
}
