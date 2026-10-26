package com.wangyu.tangdb.protocol.mysql;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.execute.SqlEngine;
import com.wangyu.tangdb.session.SessionManager;
import com.wangyu.tangdb.storage.KvStore;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL 协议监听(SPEC-P3.5 FR-431 / FR-453,纯 JDK ServerSocket)。
 *
 * <p>每连接一个线程(会话内语句串行,会话间并发,K60)。启动失败(端口被占)抛异常,
 * 不静默降级;{@link #close()} 停止接受新连接,{@link #closeAllClients()} 断开在途连接。</p>
 */
public final class MysqlServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MysqlServer.class.getName());

    private final int port;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final SqlEngine engine;
    private final SessionManager sessions;
    private final KvStore kv;
    private final Catalog catalog;
    private final String user;
    private final byte[] storedHash;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

    public MysqlServer(int port, SqlEngine engine, SessionManager sessions,
                       KvStore kv, Catalog catalog, String user, byte[] storedHash)
            throws IOException {
        this.port = port;
        this.engine = engine;
        this.sessions = sessions;
        this.kv = kv;
        this.catalog = catalog;
        this.user = user;
        this.storedHash = storedHash;
        this.serverSocket = new ServerSocket(port);
        this.acceptThread = new Thread(this::acceptLoop, "mysql-accept");
    }

    public void start() {
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                long id = sessions.allocateId();
                clients.add(socket);
                ConnectionHandler handler = new ConnectionHandler(
                        socket, id, engine, sessions, kv, catalog, user, storedHash);
                Thread thread = new Thread(() -> {
                    try {
                        handler.run();
                    } finally {
                        clients.remove(socket);
                    }
                }, "mysql-conn-" + id);
                thread.setDaemon(true);
                thread.start();
            } catch (SocketException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                LOG.log(Level.WARNING, "接受连接失败", e);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "接受连接失败", e);
            }
        }
    }

    /** 当前在途连接数。 */
    public int activeConnections() {
        return clients.size();
    }

    /** 停止接受新连接(优雅退出第 ① 步)。 */
    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "关闭监听端口失败", e);
        }
    }

    /** 断开所有在途连接(优雅退出超时后的兜底)。 */
    public void closeAllClients() {
        for (Socket socket : clients) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭。
            }
        }
    }
}
