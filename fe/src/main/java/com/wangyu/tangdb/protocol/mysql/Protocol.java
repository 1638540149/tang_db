package com.wangyu.tangdb.protocol.mysql;

/** MySQL 协议能力位、命令、状态位、字符集常量(SPEC-P3.5 FR-432 / FR-434 / K64)。 */
public final class Protocol {

    // 能力位(CLIENT_*)
    public static final long CLIENT_LONG_PASSWORD = 0x00000001L;
    public static final long CLIENT_CONNECT_WITH_DB = 0x00000008L;
    public static final long CLIENT_PROTOCOL_41 = 0x00000200L;
    public static final long CLIENT_TRANSACTIONS = 0x00002000L;
    public static final long CLIENT_SECURE_CONNECTION = 0x00008000L;
    public static final long CLIENT_PLUGIN_AUTH = 0x00080000L;
    public static final long CLIENT_MULTI_STATEMENTS = 0x00010000L;
    public static final long CLIENT_SSL = 0x00000800L;
    public static final long CLIENT_DEPRECATE_EOF = 0x01000000L;
    public static final long CLIENT_SESSION_TRACK = 0x00800000L;

    /** 服务端声明的能力位(FR-434):41 协议 + 插件认证 + 安全连接 + 带库连接 + 事务。 */
    public static final long SERVER_CAPABILITIES =
            CLIENT_LONG_PASSWORD | CLIENT_CONNECT_WITH_DB | CLIENT_PROTOCOL_41
                    | CLIENT_TRANSACTIONS | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH;

    // 状态位(SERVER_STATUS_*)
    public static final int SERVER_STATUS_IN_TRANS = 0x0001;
    public static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
    /** 非最后一条语句的结束包带此位(FR-557);单条语句绝不带。 */
    public static final int SERVER_MORE_RESULTS_EXISTS = 0x0008;

    // 字符集(K64:utf8mb4_general_ci = 45)
    public static final int CHARSET_UTF8MB4_GENERAL_CI = 45;

    // 命令
    public static final int COM_QUIT = 0x01;
    public static final int COM_INIT_DB = 0x02;
    public static final int COM_QUERY = 0x03;
    public static final int COM_FIELD_LIST = 0x04;
    public static final int COM_STATISTICS = 0x09;
    public static final int COM_PING = 0x0E;
    public static final int COM_CHANGE_USER = 0x11;
    public static final int COM_STMT_PREPARE = 0x16;
    public static final int COM_STMT_EXECUTE = 0x17;
    public static final int COM_STMT_CLOSE = 0x19;
    public static final int COM_SET_OPTION = 0x1B;

    private Protocol() {
    }
}
