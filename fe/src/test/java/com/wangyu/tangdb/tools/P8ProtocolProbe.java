package com.wangyu.tangdb.tools;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;
import com.wangyu.tangdb.protocol.mysql.Protocol;

import java.nio.charset.StandardCharsets;

/**
 * SPEC-P8 A 组协议探测(SC-447 / SC-459):逐条跑图形客户端元数据语句并打印 CODE。
 *
 * <pre>
 * java ... P8ProtocolProbe --port 9966 --password p8test \
 *      --sql "SHOW TABLE STATUS" --sql "SHOW COLLATION" ...
 * </pre>
 *
 * <p>另有一条 {@code --probe-stmt} 模式:发 COM_STMT_PREPARE 断言回错误、再执行 SELECT 1
 * 证明连接仍可用(边界 166),以及 COM_FIELD_LIST 的列定义。</p>
 */
public final class P8ProtocolProbe {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 3406;
        String user = "root";
        String password = "tangdb";
        String database = "default";
        java.util.List<String> statements = new java.util.ArrayList<>();
        boolean statementProbe = false;
        String timedSql = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = i + 1 < args.length ? args[i + 1] : "";
            switch (arg) {
                case "--host" -> host = value;
                case "--port" -> port = Integer.parseInt(value);
                case "--user" -> user = value;
                case "--password" -> password = value;
                case "--db" -> database = value;
                case "--sql" -> statements.add(value);
                case "--probe-stmt" -> statementProbe = true;
                case "--timed-sql" -> timedSql = value;
                default -> {
                }
            }
        }

        int failures = 0;
        try (MysqlClientConnection conn =
                     MysqlClientConnection.connect(host, port, user, password, database)) {
            if (statementProbe) {
                byte[] reply = conn.rawCommand(Protocol.COM_STMT_PREPARE,
                        "SELECT 1".getBytes(StandardCharsets.UTF_8));
                int code = reply[0] == (byte) 0xFF
                        ? (reply[1] & 0xFF) | ((reply[2] & 0xFF) << 8) : 0;
                System.out.println("PROBE COM_STMT_PREPARE CODE=" + code);
                if (code != 1295) {
                    failures++;
                }
                boolean alive = conn.query("SELECT 1").isResultSet();
                System.out.println("PROBE after-error SELECT1=" + (alive ? "OK" : "FAIL"));
                if (!alive) {
                    failures++;
                }
                conn.writeCommand(Protocol.COM_FIELD_LIST,
                        "p8_load\0".getBytes(StandardCharsets.UTF_8));
                int columns = 0;
                while (true) {
                    byte[] packet = conn.readRawPacket();
                    if ((packet[0] & 0xFF) == 0xFE) {
                        break;
                    }
                    if ((packet[0] & 0xFF) == 0xFF) {
                        break;
                    }
                    columns++;
                }
                System.out.println("PROBE COM_FIELD_LIST columns=" + columns);
                if (columns < 1) {
                    failures++;
                }
            }
            if (timedSql != null && !timedSql.isEmpty()) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    long started = System.nanoTime();
                    conn.query(timedSql);
                    long millis = (System.nanoTime() - started) / 1_000_000L;
                    System.out.println("TIMED run=" + attempt + " elapsed_ms=" + millis
                            + " | " + timedSql);
                }
            }
            for (String sql : statements) {
                try {
                    conn.query(sql);
                    System.out.println("CODE=0 | " + sql);
                } catch (MysqlClientConnection.MysqlClientException e) {
                    failures++;
                    System.out.println("CODE=" + e.code() + " | " + sql + " | " + e.getMessage());
                }
            }
        }
        System.out.println("PROBE_FAILURES=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }
}
