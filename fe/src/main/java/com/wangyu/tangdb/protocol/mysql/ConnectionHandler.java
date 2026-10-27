package com.wangyu.tangdb.protocol.mysql;

import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.session.Session;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.catalog.Catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单个 MySQL 连接的会话处理器(握手 → 认证 → 命令循环)。
 *
 * <p>只做分帧 + 编解码 + 命令分发(FR-447 / §6 第 6 条);语句一律交给 {@link SqlEngine},
 * 这里不出现任何 SQL 语义判断。</p>
 */
public final class ConnectionHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ConnectionHandler.class.getName());

    private static final String AUTH_PLUGIN_NAME = "mysql_native_password";

    private final Socket socket;
    private final long connectionId;
    private final SqlEngine engine;
    private final SessionManager sessions;
    private final KvStore kv;
    private final Catalog catalog;
    private final String user;
    private final byte[] storedHash;

    private PacketIO io;
    private Session session;
    private boolean authenticated;
    /** 客户端是否置了 CLIENT_SESSION_TRACK(是则 OK 包附带 info 字符串)。 */
    private boolean clientSessionTrack;
    /** 客户端握手是否声明了 CLIENT_MULTI_STATEMENTS(FR-557:只按声明回多结果集)。 */
    private boolean clientMultiStatements;

    public ConnectionHandler(Socket socket, long connectionId, SqlEngine engine,
                             SessionManager sessions, KvStore kv, Catalog catalog,
                             String user, byte[] storedHash) {
        this.socket = socket;
        this.connectionId = connectionId;
        this.engine = engine;
        this.sessions = sessions;
        this.kv = kv;
        this.catalog = catalog;
        this.user = user == null ? "" : user;
        this.storedHash = storedHash;
    }

    @Override
    public void run() {
        try (Socket ignored = socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            this.io = new PacketIO(in, out);

            byte[] seed = NativePassword.randomSeed();
            io.setWriteSeq(0);
            io.writePacket(handshakePacket(seed));

            byte[] authPayload;
            try {
                authPayload = io.readPacket();
            } catch (IOException e) {
                return; // 边界 7:握手中途断开,不刷屏
            }
            if (!authenticate(seed, authPayload)) {
                return;
            }
            authenticated = true;
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.okPacket(0, 0,
                    Protocol.SERVER_STATUS_AUTOCOMMIT, 0));

            commandLoop();
        } catch (IOException e) {
            // 连接被关闭;不记录为错误(正常断开)。
        } finally {
            if (session != null) {
                try {
                    session.executor().rollbackOnDisconnect();
                } catch (RuntimeException ignored) {
                    // 断开时回滚失败无害。
                }
                sessions.remove(session.id());
            }
        }
    }

    // ------------------------------------------------------------ 握手与认证

    private byte[] handshakePacket(byte[] seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        out.write(0x0A); // Protocol::HandshakeV10
        writeNul(out, SqlEngine.SERVER_VERSION);
        writeLe4(out, connectionId);
        out.write(seed, 0, 8); // auth-plugin-data-part-1
        out.write(0x00);            // filler
        writeLe2(out, (int) (Protocol.SERVER_CAPABILITIES & 0xFFFF)); // lower
        out.write(Protocol.CHARSET_UTF8MB4_GENERAL_CI);
        writeLe2(out, Protocol.SERVER_STATUS_AUTOCOMMIT);
        writeLe2(out, (int) ((Protocol.SERVER_CAPABILITIES >>> 16) & 0xFFFF)); // upper
        out.write(21); // auth-plugin-data-len
        writeZeros(out, 10); // reserved
        out.write(seed, 8, 12); // auth-plugin-data-part-2
        out.write(0x00); // terminator
        writeNul(out, AUTH_PLUGIN_NAME);
        return out.toByteArray();
    }

    /** @return true 认证通过并可能已注册会话;false 已发 ERR 并应断开 */
    private boolean authenticate(byte[] seed, byte[] payload) throws IOException {
        AuthInfo auth = parseHandshakeResponse(payload);
        if (auth == null) {
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_ACCESS_DENIED_ERROR, "28000",
                    "Access denied: 认证数据非法"));
            return false;
        }
        if ((auth.capabilities & Protocol.CLIENT_SSL) != 0) {
            // 边界 9:置了 CLIENT_SSL 按不支持的参数处理,拒绝(本期不做 TLS)。
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                    "不支持 SSL 连接(请用 useSSL=false)"));
            return false;
        }
        if (!user.equalsIgnoreCase(auth.username)) {
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_ACCESS_DENIED_ERROR, "28000",
                    "Access denied for user '" + auth.username + "'@'" + sourceHost() + "'"));
            return false;
        }
        if (!NativePassword.verify(storedHash, seed, auth.token)) {
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_ACCESS_DENIED_ERROR, "28000",
                    "Access denied for user '" + auth.username + "'@'" + sourceHost()
                            + "' (using password: YES)"));
            return false;
        }
        clientSessionTrack = (auth.capabilities & Protocol.CLIENT_SESSION_TRACK) != 0;
        clientMultiStatements = (auth.capabilities & Protocol.CLIENT_MULTI_STATEMENTS) != 0;

        Executor executor = new Executor(kv, catalog);
        try {
            session = sessions.register(connectionId, source(), executor);
        } catch (SqlError e) {
            io.setWriteSeq(2);
            io.writePacket(ResultSetEncoder.errPacket(e.code(), e.sqlState(), e.getMessage()));
            return false;
        }

        if (auth.database != null && !auth.database.isBlank()) {
            try {
                engine.useDatabase(session, auth.database);
            } catch (SqlError e) {
                io.setWriteSeq(2);
                io.writePacket(ResultSetEncoder.errPacket(e.code(), e.sqlState(), e.getMessage()));
                return false;
            } catch (RuntimeException e) {
                SqlError se = SqlError.from(e);
                io.setWriteSeq(2);
                io.writePacket(ResultSetEncoder.errPacket(se.code(), se.sqlState(), se.getMessage()));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ 命令循环

    private void commandLoop() throws IOException {
        while (true) {
            byte[] packet;
            try {
                packet = io.readPacket();
            } catch (IOException e) {
                return; // 客户端断开
            }
            if (packet.length == 0) {
                continue;
            }
            int command = packet[0] & 0xFF;
            byte[] args = Arrays.copyOfRange(packet, 1, packet.length);
            io.setWriteSeq(1);
            switch (command) {
                case Protocol.COM_QUIT -> {
                    return;
                }
                case Protocol.COM_PING -> io.writePacket(ResultSetEncoder.okPacket(0, 0, statusFlags(), 0));
                case Protocol.COM_QUERY -> handleQuery(args);
                case Protocol.COM_INIT_DB -> handleInitDb(args);
                case Protocol.COM_STMT_PREPARE, Protocol.COM_STMT_EXECUTE, Protocol.COM_STMT_CLOSE ->
                        io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_UNSUPPORTED_PS, "HY000",
                                "不支持服务端预编译,请使用 useServerPrepStmts=false"));
                default -> io.writePacket(ResultSetEncoder.errPacket(SqlError.ER_NOT_SUPPORTED_YET, "42000",
                        "不支持的协议命令: " + command));
            }
        }
    }

    private void handleInitDb(byte[] args) throws IOException {
        String database = new String(args, StandardCharsets.UTF_8);
        try {
            engine.useDatabase(session, database);
            io.writePacket(ResultSetEncoder.okPacket(0, 0, statusFlags(), 0));
        } catch (SqlError e) {
            io.writePacket(ResultSetEncoder.errPacket(e.code(), e.sqlState(), e.getMessage()));
        } catch (RuntimeException e) {
            SqlError se = SqlError.from(e);
            io.writePacket(ResultSetEncoder.errPacket(se.code(), se.sqlState(), se.getMessage()));
        }
    }

    private void handleQuery(byte[] args) throws IOException {
        String sql = new String(args, StandardCharsets.UTF_8);
        try {
            List<Result> results = engine.executeBatch(session, sql);
            int status = statusFlags();
            if (!clientMultiStatements) {
                // FR-557:客户端未声明 CLIENT_MULTI_STATEMENTS → 只回最后一条的结果
                // (前面的照常执行、结果丢弃),不追加多余结果集,避免协议错位。
                Result last = results.get(results.size() - 1);
                for (byte[] packet : ResultSetEncoder.forResult(last, status, clientSessionTrack)) {
                    io.writePacket(packet);
                }
                return;
            }
            // 声明了 → 逐条回;非最后一条的结束包带 SERVER_MORE_RESULTS_EXISTS(FR-557)。
            for (int i = 0; i < results.size(); i++) {
                boolean more = i < results.size() - 1;
                for (byte[] packet : ResultSetEncoder.forResult(
                        results.get(i), status, clientSessionTrack, more)) {
                    io.writePacket(packet);
                }
            }
        } catch (SqlError e) {
            // FR-558:某条失败只回该条的错误包、不带 MORE 位,后续语句未执行,连接保持。
            io.writePacket(ResultSetEncoder.errPacket(e.code(), e.sqlState(), e.getMessage()));
            logError(e.code(), sql);
        } catch (RuntimeException e) {
            SqlError se = SqlError.from(e);
            io.writePacket(ResultSetEncoder.errPacket(se.code(), se.sqlState(), se.getMessage()));
            logError(se.code(), sql);
        }
    }

    /** FR-448:协议层错误落日志(connection id + 错误码 + SQL 摘要),绝不打印口令。 */
    private void logError(int code, String sql) {
        String summary = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        if (summary.length() > 200) {
            summary = summary.substring(0, 200) + "...";
        }
        LOG.log(Level.INFO, "SQL 错误 connection_id={0} code={1} sql={2}",
                new Object[]{connectionId, code, summary});
    }

    private int statusFlags() {
        int flags = Protocol.SERVER_STATUS_AUTOCOMMIT;
        if (session != null && session.inTransaction()) {
            flags |= Protocol.SERVER_STATUS_IN_TRANS;
        }
        return flags;
    }

    private String source() {
        String address = socket.getRemoteSocketAddress() == null
                ? "" : socket.getRemoteSocketAddress().toString();
        return address.startsWith("/") ? address.substring(1) : address;
    }

    private String sourceHost() {
        String s = source();
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }

    // ------------------------------------------------------------ 握手响应解析

    private record AuthInfo(long capabilities, String username, byte[] token, String database) {
    }

    private static AuthInfo parseHandshakeResponse(byte[] p) {
        if (p == null || p.length < 32) {
            return null;
        }
        int pos = 0;
        long caps = readLe4(p, pos);
        pos += 4;
        pos += 4; // max packet size
        pos += 1; // charset
        pos += 23; // reserved

        String username = readNulString(p, pos);
        pos = nextNul(p, pos);
        if (username == null) {
            return null;
        }

        byte[] token;
        if ((caps & 0x00200000L) != 0) { // CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
            int[] len = new int[1];
            pos = readLenencInt(p, pos, len);
            token = Arrays.copyOfRange(p, pos, pos + len[0]);
            pos += len[0];
        } else if ((caps & Protocol.CLIENT_SECURE_CONNECTION) != 0) {
            int len = p[pos] & 0xFF;
            pos += 1;
            token = Arrays.copyOfRange(p, pos, pos + len);
            pos += len;
        } else {
            token = readNulString(p, pos).getBytes(StandardCharsets.UTF_8);
            pos = nextNul(p, pos);
        }

        String database = null;
        if ((caps & Protocol.CLIENT_CONNECT_WITH_DB) != 0) {
            database = readNulString(p, pos);
            pos = nextNul(p, pos);
        }
        // CLIENT_PLUGIN_AUTH 时还有插件名(忽略,我们只认 mysql_native_password)
        return new AuthInfo(caps, username, token, database);
    }

    // ------------------------------------------------------------ 字节工具

    private static void writeNul(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeZeros(ByteArrayOutputStream out, int n) {
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
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

    private static long readLe4(byte[] p, int pos) {
        return (p[pos] & 0xFFL)
                | ((p[pos + 1] & 0xFFL) << 8)
                | ((p[pos + 2] & 0xFFL) << 16)
                | ((p[pos + 3] & 0xFFL) << 24);
    }

    private static String readNulString(byte[] p, int pos) {
        int end = pos;
        while (end < p.length && p[end] != 0) {
            end++;
        }
        return new String(p, pos, end - pos, StandardCharsets.UTF_8);
    }

    private static int nextNul(byte[] p, int pos) {
        while (pos < p.length && p[pos] != 0) {
            pos++;
        }
        return pos + 1; // 跳过 NUL
    }

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
}
