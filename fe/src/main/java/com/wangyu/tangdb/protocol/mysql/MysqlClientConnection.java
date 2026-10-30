package com.wangyu.tangdb.protocol.mysql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简 MySQL 协议客户端(纯 JDK),供 tang_db 瘦 CLI 连 fed 使用(SPEC-P3.5 K67 / FR-408)。
 *
 * <p>只实现握手 + 认证 + {@code COM_QUERY} / {@code COM_INIT_DB} / {@code COM_PING} /
 * {@code COM_QUIT} + 文本结果集解析。不引任何第三方驱动。</p>
 */
public final class MysqlClientConnection implements AutoCloseable {

    /** 客户端连接错误。 */
    public static final class MysqlClientException extends RuntimeException {
        private final int code;

        MysqlClientException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** 一条语句的响应。 */
    public static final class Response {
        private final boolean resultSet;
        private final List<String> columns;
        private final List<Object[]> rows;
        private final long affectedRows;
        private final String info;
        private final boolean inTransaction;
        private final boolean moreResults;

        private Response(boolean resultSet, List<String> columns, List<Object[]> rows,
                         long affectedRows, String info, boolean inTransaction,
                         boolean moreResults) {
            this.resultSet = resultSet;
            this.columns = columns;
            this.rows = rows;
            this.affectedRows = affectedRows;
            this.info = info;
            this.inTransaction = inTransaction;
            this.moreResults = moreResults;
        }

        public boolean isResultSet() {
            return resultSet;
        }

        public List<String> columns() {
            return columns;
        }

        public List<Object[]> rows() {
            return rows;
        }

        public long affectedRows() {
            return affectedRows;
        }

        /** OK 包的 info 字符串(可能为 null)。 */
        public String info() {
            return info;
        }

        public boolean inTransaction() {
            return inTransaction;
        }

        /** 服务端是否还有后续结果集(SERVER_MORE_RESULTS_EXISTS,FR-557)。 */
        public boolean moreResults() {
            return moreResults;
        }
    }

    private final String host;
    private final int port;
    private final Socket socket;
    private final PacketIO io;
    private boolean inTransaction;
    /** 最近一次结束包是否带 SERVER_MORE_RESULTS_EXISTS(仅 queryAll 使用)。 */
    private boolean moreResults;

    private MysqlClientConnection(String host, int port, Socket socket, PacketIO io) {
        this.host = host;
        this.port = port;
        this.socket = socket;
        this.io = io;
    }

    /** 连接并完成握手认证。失败抛 {@link MysqlClientException} 或 {@link IOException}。 */
    public static MysqlClientConnection connect(String host, int port, String user,
                                                String password, String database)
            throws IOException {
        return connect(host, port, user, password, database, false);
    }

    /**
     * 连接并握手。{@code multiStatements} = true 时在握手响应里声明
     * {@code CLIENT_MULTI_STATEMENTS}(FR-557 的多结果集路径),配合 {@link #queryAll} 使用。
     */
    public static MysqlClientConnection connect(String host, int port, String user,
                                                String password, String database,
                                                boolean multiStatements) throws IOException {
        Socket socket = new Socket(host, port);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        PacketIO io = new PacketIO(in, out);

        byte[] handshake = io.readPacket();
        HandshakeInfo hs = parseHandshake(handshake);

        long caps = Protocol.CLIENT_LONG_PASSWORD | Protocol.CLIENT_PROTOCOL_41
                | Protocol.CLIENT_SECURE_CONNECTION | Protocol.CLIENT_PLUGIN_AUTH
                | Protocol.CLIENT_TRANSACTIONS | Protocol.CLIENT_SESSION_TRACK;
        if (multiStatements) {
            caps |= Protocol.CLIENT_MULTI_STATEMENTS;
        }
        if (database != null && !database.isBlank()) {
            caps |= Protocol.CLIENT_CONNECT_WITH_DB;
        }

        byte[] token = NativePassword.scramble(
                (password == null ? "" : password).getBytes(StandardCharsets.UTF_8), hs.seed);

        ByteArrayOutputStream resp = new ByteArrayOutputStream(64);
        writeLe4(resp, caps);
        writeLe4(resp, 0xFFFFFF);
        resp.write(Protocol.CHARSET_UTF8MB4_GENERAL_CI);
        writeZeros(resp, 23);
        writeNul(resp, user == null ? "" : user);
        resp.write(token.length);
        resp.writeBytes(token);
        if ((caps & Protocol.CLIENT_CONNECT_WITH_DB) != 0) {
            writeNul(resp, database);
        }
        writeNul(resp, "mysql_native_password");

        io.setWriteSeq(1);
        io.writePacket(resp.toByteArray());

        byte[] authResult = io.readPacket();
        if (authResult.length > 0 && authResult[0] == (byte) 0xFF) {
            throw parseError(authResult);
        }
        // 认证成功后可能是 OK(0x00)或 ERR(已处理)或(带库失败时的 ERR)。
        MysqlClientConnection conn = new MysqlClientConnection(host, port, socket, io);
        conn.updateStatusFromAuth(authResult);
        return conn;
    }

    /** 执行一条 SQL,返回响应。 */
    public Response query(String sql) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        out.write(Protocol.COM_QUERY);
        out.writeBytes(sql.getBytes(StandardCharsets.UTF_8));
        io.setWriteSeq(0);
        io.writePacket(out.toByteArray());
        return readResponse();
    }

    /**
     * 执行一条可能含多条语句的 SQL,读到服务端不再带
     * {@code SERVER_MORE_RESULTS_EXISTS} 为止,返回全部响应(FR-557 / FR-558)。
     * 中途某条失败时抛 {@link MysqlClientException}(此前已收到的响应由调用方决定怎么用)。
     */
    public List<Response> queryAll(String sql) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        out.write(Protocol.COM_QUERY);
        out.writeBytes(sql.getBytes(StandardCharsets.UTF_8));
        io.setWriteSeq(0);
        io.writePacket(out.toByteArray());
        List<Response> responses = new ArrayList<>();
        while (true) {
            Response response = readResponse();
            responses.add(response);
            if (!response.moreResults()) {
                return responses;
            }
        }
    }

    /** COM_INIT_DB:切库。 */
    public void changeDatabase(String database) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        out.write(Protocol.COM_INIT_DB);
        out.writeBytes(database.getBytes(StandardCharsets.UTF_8));
        io.setWriteSeq(0);
        io.writePacket(out.toByteArray());
        Response response = readResponse();
        if (response.isResultSet()) {
            throw new MysqlClientException(1105, "COM_INIT_DB 返回了结果集");
        }
        if (response.inTransaction) {
            inTransaction = true;
        }
    }

    /**
     * SPEC-P8:发送任意命令的原始载荷(协议探测用:COM_STMT_* / COM_FIELD_LIST 等),
     * 返回服务端首包原文,由调用方自行解释。
     */
    public byte[] rawCommand(int command, byte[] args) throws IOException {
        writeCommand(command, args);
        return io.readPacket();
    }

    /** 只发包不读回(配合 {@link #readRawPacket()} 读多包响应,如 COM_FIELD_LIST)。 */
    public void writeCommand(int command, byte[] args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        out.write(command);
        if (args != null) {
            out.writeBytes(args);
        }
        io.setWriteSeq(0);
        io.writePacket(out.toByteArray());
    }

    /** 读一个原始协议包(COM_FIELD_LIST 的多包响应等)。 */
    public byte[] readRawPacket() throws IOException {
        return io.readPacket();
    }

    /** COM_PING:探活。 */
    public void ping() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2);
        out.write(Protocol.COM_PING);
        io.setWriteSeq(0);
        io.writePacket(out.toByteArray());
        readResponse();
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    @Override
    public void close() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(2);
            out.write(Protocol.COM_QUIT);
            io.setWriteSeq(0);
            io.writePacket(out.toByteArray());
        } catch (IOException ignored) {
            // 已断开。
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已断开。
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    // ------------------------------------------------------------ 响应解析

    private Response readResponse() throws IOException {
        byte[] first = io.readPacket();
        if (first.length == 0) {
            return new Response(false, List.of(), List.of(), 0, null, inTransaction, false);
        }
        int header = first[0] & 0xFF;
        if (header == 0x00) {
            return parseOk(first);
        }
        if (header == 0xFF) {
            throw parseError(first);
        }
        // 否则是列数(lenenc int)→ 文本结果集
        int[] count = new int[1];
        int pos = readLenencInt(first, 0, count);
        int columnCount = count[0];

        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            byte[] def = io.readPacket();
            columns.add(parseColumnName(def));
        }
        // 读 EOF(CLIENT_DEPRECATE_EOF 未置,所以有 EOF)
        byte[] eof1 = io.readPacket();
        updateStatusFromEof(eof1);

        List<Object[]> rows = new ArrayList<>();
        while (true) {
            byte[] rowPacket = io.readPacket();
            if (rowPacket.length > 0 && (rowPacket[0] & 0xFF) == 0xFE && rowPacket.length < 9) {
                updateStatusFromEof(rowPacket);
                break; // 结束 EOF
            }
            rows.add(parseRow(rowPacket, columnCount));
        }
        return new Response(true, columns, rows, 0, null, inTransaction, moreResults);
    }

    private Response parseOk(byte[] packet) {
        int[] affected = new int[1];
        int pos = 1; // 跳过 0x00
        pos = readLenencInt(packet, pos, affected);
        int[] lastId = new int[1];
        pos = readLenencInt(packet, pos, lastId);
        int status = readLe2(packet, pos);
        pos += 2;
        pos += 2; // warnings
        inTransaction = (status & Protocol.SERVER_STATUS_IN_TRANS) != 0;
        moreResults = (status & Protocol.SERVER_MORE_RESULTS_EXISTS) != 0;
        String info = null;
        if (pos < packet.length) {
            // CLIENT_SESSION_TRACK:info 字符串
            int[] infoLen = new int[1];
            pos = readLenencInt(packet, pos, infoLen);
            if (pos + infoLen[0] <= packet.length) {
                info = new String(packet, pos, infoLen[0], StandardCharsets.UTF_8);
            }
        }
        return new Response(false, List.of(), List.of(), affected[0], info, inTransaction,
                moreResults);
    }

    private Object[] parseRow(byte[] packet, int columnCount) {
        Object[] row = new Object[columnCount];
        int pos = 0;
        for (int i = 0; i < columnCount; i++) {
            int first = packet[pos] & 0xFF;
            if (first == 0xFB) {
                row[i] = null;
                pos += 1;
                continue;
            }
            int[] len = new int[1];
            pos = readLenencInt(packet, pos, len);
            row[i] = new String(packet, pos, len[0], StandardCharsets.UTF_8);
            pos += len[0];
        }
        return row;
    }

    private static String parseColumnName(byte[] def) {
        // 列定义包:lenenc(catalog) lenenc(schema) lenenc(table) lenenc(org_table) lenenc(name) ...
        int pos = 0;
        for (int i = 0; i < 4; i++) {
            int[] len = new int[1];
            pos = readLenencInt(def, pos, len);
            pos += len[0];
        }
        int[] nameLen = new int[1];
        pos = readLenencInt(def, pos, nameLen);
        return new String(def, pos, nameLen[0], StandardCharsets.UTF_8);
    }

    private void updateStatusFromEof(byte[] eof) {
        if (eof.length >= 5) {
            int status = readLe2(eof, 3);
            inTransaction = (status & Protocol.SERVER_STATUS_IN_TRANS) != 0;
            moreResults = (status & Protocol.SERVER_MORE_RESULTS_EXISTS) != 0;
        }
    }

    private void updateStatusFromAuth(byte[] authResult) {
        if (authResult.length > 0 && authResult[0] == 0x00) {
            parseOk(authResult);
        }
    }

    // ------------------------------------------------------------ 握手解析

    private record HandshakeInfo(byte[] seed) {
    }

    private static HandshakeInfo parseHandshake(byte[] p) {
        int pos = 0;
        pos += 1; // protocol version
        pos = skipNul(p, pos); // server version
        pos += 4; // connection id
        byte[] seed = new byte[20];
        System.arraycopy(p, pos, seed, 0, 8);
        pos += 8;
        pos += 1; // filler
        pos += 2; // caps lower
        pos += 1; // charset
        pos += 2; // status
        pos += 2; // caps upper
        pos += 1; // auth-plugin-data-len
        pos += 10; // reserved
        System.arraycopy(p, pos, seed, 8, 12);
        return new HandshakeInfo(seed);
    }

    private static MysqlClientException parseError(byte[] packet) {
        int code = packet.length >= 3 ? readLe2(packet, 1) : 1105;
        String message = "";
        if (packet.length > 1) {
            // 跳过 0xFF + 2 字节 code + '#' + 5 字节 sqlstate
            int pos = 1 + 2;
            if (pos < packet.length && packet[pos] == '#') {
                pos += 6;
            }
            if (pos < packet.length) {
                message = new String(packet, pos, packet.length - pos, StandardCharsets.UTF_8);
            }
        }
        return new MysqlClientException(code, message);
    }

    // ------------------------------------------------------------ 字节工具

    private static int readLenencInt(byte[] p, int pos, int[] out) {
        int first = p[pos] & 0xFF;
        if (first < 0xFB) {
            out[0] = first;
            return pos + 1;
        }
        if (first == 0xFC) {
            out[0] = (p[pos + 1] & 0xFF) | ((p[pos + 2] & 0xFF) << 8);
            return pos + 3;
        }
        if (first == 0xFD) {
            out[0] = (p[pos + 1] & 0xFF) | ((p[pos + 2] & 0xFF) << 8) | ((p[pos + 3] & 0xFF) << 16);
            return pos + 4;
        }
        out[0] = (int) readLe4(p, pos + 1);
        return pos + 9;
    }

    private static int readLe2(byte[] p, int pos) {
        return (p[pos] & 0xFF) | ((p[pos + 1] & 0xFF) << 8);
    }

    private static long readLe4(byte[] p, int pos) {
        return (p[pos] & 0xFFL) | ((p[pos + 1] & 0xFFL) << 8)
                | ((p[pos + 2] & 0xFFL) << 16) | ((p[pos + 3] & 0xFFL) << 24);
    }

    private static int skipNul(byte[] p, int pos) {
        while (pos < p.length && p[pos] != 0) {
            pos++;
        }
        return pos + 1;
    }

    private static void writeNul(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeZeros(ByteArrayOutputStream out, int n) {
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    private static void writeLe4(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }
}
