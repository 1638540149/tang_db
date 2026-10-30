package com.wangyu.tangdb.session;

import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.execute.SqlError;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话表(SPEC-P3.5 FR-411 / FR-412):创建、登记、会话上限、空闲超时回收、优雅退出等待。
 *
 * <p>并发模型(K60):每会话一个执行线程,共享 catalog;这里用 JDK 自带的并发原语
 * (ConcurrentHashMap / AtomicLong),不引任何线程池框架。</p>
 */
public final class SessionManager {

    /** 会话数已达上限(MySQL ER_CON_COUNT_ERROR)。 */
    public static final int ER_CON_COUNT_ERROR = 1040;

    private final int maxSessions;
    private final long idleTimeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentMap<Long, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param maxSessions       会话上限(>0)
     * @param idleTimeoutSeconds 空闲超时(秒),0 = 不超时
     */
    public SessionManager(int maxSessions, int idleTimeoutSeconds) {
        this.maxSessions = Math.max(1, maxSessions);
        this.idleTimeoutMillis = Math.max(0, idleTimeoutSeconds) * 1000L;
    }

    public int maxSessions() {
        return maxSessions;
    }

    public boolean idleTimeoutEnabled() {
        return idleTimeoutMillis > 0;
    }

    /**
     * 创建并登记一个会话。
     *
     * @throws SqlError 超过会话上限(1040)
     */
    public Session create(String source, Executor executor) {
        return register(allocateId(), source, executor);
    }

    /** 领取一个会话/连接 id(供握手包 connection id 使用,不登记)。 */
    public long allocateId() {
        return nextId.getAndIncrement();
    }

    /**
     * 用预分配的 id 登记一个会话。
     *
     * @throws SqlError 超过会话上限(1040)
     */
    public Session register(long id, String source, Executor executor) {
        return register(id, source, executor, Session.CHANNEL_MYSQL);
    }

    /** SPEC-P8 FR-862:控制台会话走自己的会话表(与 MySQL 连接池额度隔离)。 */
    public Session register(long id, String source, Executor executor, String channel) {
        if (sessions.size() >= maxSessions) {
            throw new SqlError(ER_CON_COUNT_ERROR, "08004",
                    "会话数已达上限 " + maxSessions + ",请稍后再试");
        }
        Session session = new Session(id, source, executor, channel);
        sessions.put(id, session);
        return session;
    }

    /** 注销会话(连接断开时调用)。 */
    public void remove(long id) {
        sessions.remove(id);
    }

    public Session get(long id) {
        return sessions.get(id);
    }

    /** 全部会话(按 id 升序)。 */
    public List<Session> all() {
        List<Session> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparingLong(Session::id));
        return list;
    }

    public int size() {
        return sessions.size();
    }

    /** 回收空闲超时的会话,返回回收数量。 */
    public int evictIdle() {
        if (idleTimeoutMillis <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int evicted = 0;
        for (Session session : sessions.values()) {
            if (now - session.lastActiveMillis() > idleTimeoutMillis) {
                session.executor().rollbackOnDisconnect();
                sessions.remove(session.id());
                evicted++;
            }
        }
        return evicted;
    }

    /** 关闭全部会话(丢弃未提交事务),用于 fed 优雅退出。 */
    public void closeAll() {
        for (Session session : sessions.values()) {
            session.executor().rollbackOnDisconnect();
        }
        sessions.clear();
    }
}
