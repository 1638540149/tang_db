package com.wangyu.tangdb.session;

import com.wangyu.tangdb.execute.Executor;

/**
 * 一个客户端连接对应的会话(SPEC-P3.5 FR-403)。
 *
 * <p>会话独立持有当前库、当前事务、写集与语句级状态;这些状态都封装在
 * 每个会话自己的 {@link Executor} 里(会话内语句串行)。</p>
 */
public final class Session {

    /** 会话通道(SPEC-P8 边界 186:控制台与 MySQL 指标独立计数,不串号)。 */
    public static final String CHANNEL_MYSQL = "mysql";
    public static final String CHANNEL_CONSOLE = "console";

    private final long id;
    private final String source;
    private final String channel;
    private final Executor executor;
    private final long startTimeMillis;
    private volatile long lastActiveMillis;
    /** KILL 标记:被 KILL 的会话下一条语句明确失败(SPEC-P4 FR-541)。 */
    private volatile boolean killed;

    public Session(long id, String source, Executor executor) {
        this(id, source, executor, CHANNEL_MYSQL);
    }

    public Session(long id, String source, Executor executor, String channel) {
        this.id = id;
        this.source = source == null ? "" : source;
        this.channel = channel == null ? CHANNEL_MYSQL : channel;
        this.executor = executor;
        this.startTimeMillis = System.currentTimeMillis();
        this.lastActiveMillis = startTimeMillis;
    }

    public long id() {
        return id;
    }

    /** 连接源(形如 host:port)。 */
    public String source() {
        return source;
    }

    public Executor executor() {
        return executor;
    }

    /** 会话通道:mysql(协议端口)/ console(Web 控制台)。 */
    public String channel() {
        return channel;
    }

    public boolean console() {
        return CHANNEL_CONSOLE.equals(channel);
    }

    public String currentDatabase() {
        return executor.currentDatabase();
    }

    public boolean inTransaction() {
        return executor.inTransaction();
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public long lastActiveMillis() {
        return lastActiveMillis;
    }

    /** 刷新最近活跃时间(收到语句时调用)。 */
    public void touch() {
        lastActiveMillis = System.currentTimeMillis();
    }

    /** KILL:标记会话已终止;执行入口在收到下一条语句时拒绝(FR-541 / K97)。 */
    public void kill() {
        killed = true;
    }

    public boolean killed() {
        return killed;
    }
}
