package com.wangyu.tangdb.server;

import com.wangyu.tangdb.proto.HeartbeatRequest;
import com.wangyu.tangdb.proto.HeartbeatResponse;
import com.wangyu.tangdb.proto.RegisterBeRequest;
import com.wangyu.tangdb.proto.RegisterBeResponse;
import com.wangyu.tangdb.proto.StatusCode;
import com.wangyu.tangdb.proto.TangDbFrontendGrpc;
import io.grpc.stub.StreamObserver;

/**
 * FE 侧的注册/心跳服务(注意方向:BE 是客户端,FE 是服务端)。
 */
public final class FrontendService extends TangDbFrontendGrpc.TangDbFrontendImplBase {

    private final BackendRegistry registry;

    public FrontendService(BackendRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerBe(RegisterBeRequest request, StreamObserver<RegisterBeResponse> observer) {
        try {
            BackendRegistry.Backend backend = registry.register(request);
            observer.onNext(RegisterBeResponse.newBuilder()
                    .setStatus(StatusCode.OK)
                    .setMessage("注册成功:" + backend.endpoint())
                    .build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            observer.onNext(RegisterBeResponse.newBuilder()
                    .setStatus(StatusCode.INTERNAL)
                    .setMessage("注册失败: " + e.getMessage())
                    .build());
            observer.onCompleted();
        }
    }

    @Override
    public void reportHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> observer) {
        try {
            registry.heartbeat(request);
            observer.onNext(HeartbeatResponse.newBuilder()
                    .setStatus(StatusCode.OK)
                    .setMessage("OK")
                    .build());
            observer.onCompleted();
        } catch (RuntimeException e) {
            observer.onNext(HeartbeatResponse.newBuilder()
                    .setStatus(StatusCode.INTERNAL)
                    .setMessage("心跳处理失败: " + e.getMessage())
                    .build());
            observer.onCompleted();
        }
    }
}
