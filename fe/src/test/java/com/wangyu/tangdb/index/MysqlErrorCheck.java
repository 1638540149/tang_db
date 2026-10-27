package com.wangyu.tangdb.index;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;

/**
 * 脚本用:执行一条 SQL 并打印 MySQL 错误码(用于断言 1062 / 1235 / 1146 等)。
 *
 * <p>用法:{@code java ... MysqlErrorCheck <port> <user> <password> <db> <sql>}。
 * 成功则打印 {@code CODE=0};失败打印 {@code CODE=<mysql code>} 与消息。</p>
 */
public final class MysqlErrorCheck {

    private MysqlErrorCheck() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String user = args[1];
        String password = args[2];
        String db = args[3];
        String sql = args[4];
        try (MysqlClientConnection conn = MysqlClientConnection.connect(
                "127.0.0.1", port, user, password, db)) {
            try {
                MysqlClientConnection.Response response = conn.query(sql);
                System.out.println("CODE=0");
                if (response.isResultSet()) {
                    System.out.println("ROWS=" + response.rows().size());
                    System.out.println("COLS=" + String.join(",", response.columns()));
                } else {
                    System.out.println("AFFECTED=" + response.affectedRows());
                }
            } catch (MysqlClientConnection.MysqlClientException e) {
                System.out.println("CODE=" + e.code());
                System.out.println("MESSAGE=" + e.getMessage());
            }
        }
    }
}
