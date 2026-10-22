package com.wangyu.tangdb.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * FE 的心跳 gRPC 服务(hook 到 9091,可配)。
 *
 * <p>端口被占用时必须启动失败并明确报错(FR-158),不能静默降级。</p>
 */
public final class FeGrpcServer implements AutoCloseable {

    private final int port;
    private final Server server;

    private FeGrpcServer(int port, Server server) {
        this.port = port;
        this.server = server;
    }

    public static FeGrpcServer start(int port, BackendRegistry registry) {
        Server server = ServerBuilder.forPort(port)
                .addService(new FrontendService(registry))
                .build();
        try {
            server.start();
        } catch (IOException e) {
            throw new FeStartupException("心跳服务端口 " + port + " 无法监听(可能已被占用): " + e.getMessage(), e);
        }
        return new FeGrpcServer(port, server);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.shutdown();
        try {
            if (!server.awaitTermination(3, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
