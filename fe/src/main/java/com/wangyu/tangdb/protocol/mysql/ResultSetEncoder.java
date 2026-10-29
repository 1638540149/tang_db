package com.wangyu.tangdb.protocol.mysql;

import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.storage.Values;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本结果集、OK / ERR / EOF 包编码(SPEC-P3.5 FR-435 / FR-439)。
 *
 * <p>结果集形态:列数 → N 个列定义包 → EOF → 行(每值长度编码字符串,NULL = {@code 0xFB})
 * → EOF。类型映射按 §5.2,由值运行时类型推断(INT→LONG、BIGINT→LONGLONG、DOUBLE→DOUBLE、
 * BOOLEAN→TINY、DATE→DATE、字符串→VAR_STRING)。</p>
 */
public final class ResultSetEncoder {

    public static final int TYPE_LONG = 3;
    public static final int TYPE_LONGLONG = 8;
    public static final int TYPE_DOUBLE = 5;
    public static final int TYPE_TINY = 1;
    public static final int TYPE_DATE = 10;
    public static final int TYPE_VAR_STRING = 253;
    public static final int TYPE_NULL = 6;

    private ResultSetEncoder() {
    }

    /** 把一条语句的执行结果编码成一组协议包(OK 包 或 文本结果集)。 */
    public static List<byte[]> forResult(Result result, int statusFlags, boolean includeInfo) {
        return forResult(result, statusFlags, includeInfo, false);
    }

    /**
     * 把一条语句的执行结果编码成一组协议包;{@code moreResults} 为 true 时,
     * **终止包**(OK 包或结果集的最后一个 EOF)带 {@code SERVER_MORE_RESULTS_EXISTS}(FR-557)。
     *
     * <p>单条语句一律传 false,报文序列与 P4 之前逐字节一致。</p>
     */
    public static List<byte[]> forResult(Result result, int statusFlags, boolean includeInfo,
                                         boolean moreResults) {
        int terminalStatus = moreResults
                ? statusFlags | Protocol.SERVER_MORE_RESULTS_EXISTS
                : statusFlags;
        if (result instanceof Result.Message message) {
            long affected = message.affectedRows() >= 0 ? message.affectedRows() : 0;
            String info = includeInfo ? message.text() : null;
            return List.of(okPacket(affected, 0, terminalStatus, 0, info));
        }
        if (result instanceof Result.StreamTable streaming) {
            return drain(streaming, statusFlags, terminalStatus);
        }
        Result.Table table = (Result.Table) result;
        List<ColumnInfo> columns = new ArrayList<>(table.columns().size());
        int[] types = inferTypes(table);
        for (int i = 0; i < table.columns().size(); i++) {
            columns.add(new ColumnInfo(table.columns().get(i), types[i]));
        }

        List<byte[]> packets = new ArrayList<>();
        packets.add(lenencInt(table.columns().size()));
        for (ColumnInfo column : columns) {
            packets.add(columnDefPacket(column));
        }
        packets.add(eofPacket(0, statusFlags));
        for (Object[] row : table.rows()) {
            packets.add(rowPacket(row));
        }
        packets.add(eofPacket(0, terminalStatus));
        return packets;
    }

    // ------------------------------------------------------------ 流式写出(FR-715)

    /**
     * 直接把结果写进协议连接(SPEC-P6.1 FR-715)。
     *
     * <p>普通提示 / 物化结果集与 {@link #forResult} 逐字节一致;流式结果集则
     * 按批取数、按批写包,不再先攒出整张结果集的包列表。</p>
     */
    public static void writeTo(PacketIO io, Result result, int statusFlags, boolean includeInfo,
                               boolean moreResults) throws IOException {
        int terminalStatus = moreResults
                ? statusFlags | Protocol.SERVER_MORE_RESULTS_EXISTS
                : statusFlags;
        if (result instanceof Result.StreamTable streaming) {
            writeStream(io, streaming, statusFlags, terminalStatus);
            return;
        }
        for (byte[] packet : forResult(result, statusFlags, includeInfo, moreResults)) {
            io.writePacket(packet);
        }
    }

    /**
     * 流式结果集的报文序列与物化结果集完全相同:列数 → 列定义 → EOF → 行 → EOF。
     *
     * <p>列类型先按"前若干批"推断(与物化路径的全量推断同口径:取每列第一个非 NULL 值),
     * 因此常见的全 NULL 列、数值列都不会因为流式而改类型。</p>
     */
    private static void writeStream(PacketIO io, Result.StreamTable streaming, int statusFlags,
                                    int terminalStatus) throws IOException {
        List<String> columns = streaming.columns();
        List<Object[]> pending = new ArrayList<>();
        int[] types = new int[columns.size()];
        java.util.Arrays.fill(types, TYPE_VAR_STRING);
        boolean typed = columns.isEmpty();
        int primed = 0;
        boolean done = false;
        // 先缓冲前若干批用于类型推断(上限 8192 行,内存有界)
        while (!typed && primed < 8192) {
            List<Object[]> batch = streaming.stream().nextBatch();
            if (batch == null) {
                done = true;
                break;
            }
            pending.addAll(batch);
            primed += batch.size();
            typed = inferTypes(types, pending);
        }
        try {
            io.writePacket(lenencInt(columns.size()));
            for (int i = 0; i < columns.size(); i++) {
                io.writePacket(columnDefPacket(new ColumnInfo(columns.get(i), types[i])));
            }
            io.writePacket(eofPacket(0, statusFlags));
            for (Object[] row : pending) {
                io.writePacket(rowPacket(row));
            }
            while (!done) {
                List<Object[]> batch = streaming.stream().nextBatch();
                if (batch == null) {
                    break;
                }
                for (Object[] row : batch) {
                    io.writePacket(rowPacket(row));
                }
            }
            io.writePacket(eofPacket(0, terminalStatus));
        } finally {
            streaming.stream().close();
        }
    }

    /** 把流式结果抽干成普通包列表(单测 / 一次性 API 用;服务端走 writeTo 不经过这里)。 */
    private static List<byte[]> drain(Result.StreamTable streaming, int statusFlags,
                                      int terminalStatus) {
        List<String> columns = streaming.columns();
        List<Object[]> rows = new ArrayList<>();
        try {
            while (true) {
                List<Object[]> batch = streaming.stream().nextBatch();
                if (batch == null) {
                    break;
                }
                rows.addAll(batch);
            }
        } finally {
            streaming.stream().close();
        }
        Result.Table table = new Result.Table(columns, rows);
        List<byte[]> packets = new ArrayList<>();
        packets.add(lenencInt(columns.size()));
        int[] types = inferTypes(table);
        for (int i = 0; i < columns.size(); i++) {
            packets.add(columnDefPacket(new ColumnInfo(columns.get(i), types[i])));
        }
        packets.add(eofPacket(0, statusFlags));
        for (Object[] row : rows) {
            packets.add(rowPacket(row));
        }
        packets.add(eofPacket(0, terminalStatus));
        return packets;
    }

    /** 用已缓冲的行填充列类型;所有列都定好类型时返回 true。 */
    private static boolean inferTypes(int[] types, List<Object[]> rows) {
        boolean all = true;
        for (int c = 0; c < types.length; c++) {
            if (types[c] != TYPE_VAR_STRING) {
                continue;
            }
            boolean found = false;
            for (Object[] row : rows) {
                Object value = c < row.length ? row[c] : null;
                if (value != null) {
                    types[c] = typeOf(value);
                    found = true;
                    break;
                }
            }
            if (!found) {
                all = false;
            }
        }
        return all;
    }

    // ------------------------------------------------------------ 包编码


    public static byte[] okPacket(long affectedRows, long lastInsertId, int statusFlags, int warnings) {
        return okPacket(affectedRows, lastInsertId, statusFlags, warnings, null);
    }

    /**
     * OK 包。{@code info} 非空且客户端置了 {@code CLIENT_SESSION_TRACK} 时,
     * 在包尾附带 info 字符串 + 空的 session state changes(供 tang_db CLI 显示完整提示)。
     */
    public static byte[] okPacket(long affectedRows, long lastInsertId, int statusFlags,
                                  int warnings, String info) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        out.write(0x00);
        writeLenencInt(out, affectedRows);
        writeLenencInt(out, lastInsertId);
        writeLe2(out, statusFlags);
        writeLe2(out, warnings);
        if (info != null) {
            writeLenencString(out, info);
            writeLenencInt(out, 0); // 空 session state changes
        }
        return out.toByteArray();
    }

    public static byte[] errPacket(int code, String sqlState, String message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write(0xFF);
        writeLe2(out, code);
        String state = sqlState == null || sqlState.length() != 5 ? "HY000" : sqlState;
        out.write('#');
        writeAscii(out, state);
        writeAscii(out, message == null ? "" : message);
        return out.toByteArray();
    }

    public static byte[] eofPacket(int warnings, int statusFlags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        out.write(0xFE);
        writeLe2(out, warnings);
        writeLe2(out, statusFlags);
        return out.toByteArray();
    }

    private static byte[] columnDefPacket(ColumnInfo column) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeLenencString(out, "def");
        writeLenencString(out, "");
        writeLenencString(out, "");
        writeLenencString(out, "");
        writeLenencString(out, column.name);
        writeLenencString(out, "");
        out.write(0x0C); // 固定字段长度
        writeLe2(out, Protocol.CHARSET_UTF8MB4_GENERAL_CI);
        writeLe4(out, columnLength(column.type));
        out.write(column.type);
        writeLe2(out, 0); // flags
        out.write(0);     // decimals
        writeLe2(out, 0); // filler
        return out.toByteArray();
    }

    private static byte[] rowPacket(Object[] row) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        for (Object value : row) {
            if (value == null) {
                out.write(0xFB);
            } else {
                writeLenencString(out, Values.display(value));
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------ 类型推断

    private static int[] inferTypes(Result.Table table) {
        int[] types = new int[table.columns().size()];
        for (int c = 0; c < types.length; c++) {
            types[c] = TYPE_VAR_STRING; // 默认(全 NULL 列)
            for (Object[] row : table.rows()) {
                Object value = c < row.length ? row[c] : null;
                if (value != null) {
                    types[c] = typeOf(value);
                    break;
                }
            }
        }
        return types;
    }

    private static int typeOf(Object value) {
        if (value instanceof Integer) {
            return TYPE_LONG;
        }
        if (value instanceof Long) {
            return TYPE_LONGLONG;
        }
        if (value instanceof Double || value instanceof Float) {
            return TYPE_DOUBLE;
        }
        if (value instanceof Boolean) {
            return TYPE_TINY;
        }
        if (value instanceof LocalDate) {
            return TYPE_DATE;
        }
        return TYPE_VAR_STRING;
    }

    private static long columnLength(int type) {
        return switch (type) {
            case TYPE_LONG, TYPE_TINY, TYPE_DATE -> 16;
            case TYPE_LONGLONG -> 20;
            case TYPE_DOUBLE -> 22;
            default -> 1024;
        };
    }

    // ------------------------------------------------------------ 工具

    private record ColumnInfo(String name, int type) {
    }

    private static void writeLenencInt(ByteArrayOutputStream out, long value) {
        if (value < 251) {
            out.write((int) value);
        } else if (value < 0x10000) {
            out.write(0xFC);
            writeLe2(out, (int) value);
        } else if (value < 0x1000000) {
            out.write(0xFD);
            out.write((int) (value & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
        } else {
            out.write(0xFE);
            writeLe8(out, value);
        }
    }

    private static void writeLenencString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        writeLenencInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    public static byte[] lenencInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4);
        writeLenencInt(out, value);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeLe2(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe4(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static void writeLe8(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xFF));
        }
    }
}
