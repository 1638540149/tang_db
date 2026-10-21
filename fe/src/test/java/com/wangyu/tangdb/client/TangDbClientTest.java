package com.wangyu.tangdb.client;

import com.wangyu.tangdb.proto.StatusCode;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TangDbClient} 的单元测试：状态码中文转换与 gRPC 异常转换。
 *
 * <p>不需要启动 BE。</p>
 */
class TangDbClientTest {

    @Test
    void defaultAddress() {
        assertEquals("127.0.0.1", TangDbClient.DEFAULT_HOST);
        assertEquals(9100, TangDbClient.DEFAULT_PORT);
    }

    @Test
    void describeStatus() {
        assertEquals("成功", TangDbClient.describeStatus(StatusCode.OK));
        assertEquals("键不存在", TangDbClient.describeStatus(StatusCode.NOT_FOUND));
        assertEquals("参数无效", TangDbClient.describeStatus(StatusCode.INVALID_ARGUMENT));
        assertEquals("BE 内部错误", TangDbClient.describeStatus(StatusCode.INTERNAL));
        assertEquals("BE 未返回状态", TangDbClient.describeStatus(StatusCode.STATUS_UNSPECIFIED));
        assertEquals("无法识别的状态码", TangDbClient.describeStatus(StatusCode.UNRECOGNIZED));
        assertEquals("未知状态", TangDbClient.describeStatus(null));
    }

    @Test
    void translateUnavailable() {
        StatusRuntimeException grpcError = Status.UNAVAILABLE
                .withDescription("Connection refused")
                .asRuntimeException();

        ClientException e = TangDbClient.translate(grpcError, "127.0.0.1", 9100);

        assertEquals("无法连接 BE 127.0.0.1:9100", e.getMessage());
        assertEquals("UNAVAILABLE", e.getErrorCode());
        assertSame(grpcError, e.getCause());
    }

    @Test
    void translateDeadlineExceeded() {
        StatusRuntimeException grpcError = Status.DEADLINE_EXCEEDED.asRuntimeException();

        ClientException e = TangDbClient.translate(grpcError, "10.0.0.8", 9200);

        assertEquals("连接 BE 10.0.0.8:9200 超时", e.getMessage());
        assertEquals("DEADLINE_EXCEEDED", e.getErrorCode());
    }

    @Test
    void translateOtherGrpcStatus() {
        StatusRuntimeException grpcError = Status.INTERNAL
                .withDescription("disk full")
                .asRuntimeException();

        ClientException e = TangDbClient.translate(grpcError, "127.0.0.1", 9100);

        assertTrue(e.getMessage().startsWith("与 BE 127.0.0.1:9100 通信失败"));
        assertTrue(e.getMessage().contains("disk full"));
        assertEquals("INTERNAL", e.getErrorCode());
    }

    @Test
    void clientException() {
        ClientException plain = new ClientException("键不存在");
        assertEquals("键不存在", plain.getMessage());
        assertNull(plain.getErrorCode());

        ClientException coded = new ClientException("参数无效", "INVALID_ARGUMENT");
        assertEquals("INVALID_ARGUMENT", coded.getErrorCode());
    }
}
