package com.wangyu.tangdb.session;

import com.wangyu.tangdb.execute.Executor;

/**
 * 一个客户端连接对应的会话(SPEC-P3.5 FR-403)。
 *
 * <p>会话独立持有当前库、当前事务、写集与语句级状态;这些状态都封装在
 * 每个会话自己的 {@link Executor} 里(会话内语句串行)。</p>
 */
public final class Session {

    private final long id;
    private final String source;
    private final Executor executor;
    private final long startTimeMillis;
    private volatile long lastActiveMillis;

    public Session(long id, String source, Executor executor) {
        this.id = id;
        this.source = source == null ? "" : source;
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
}
