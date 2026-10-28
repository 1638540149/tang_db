package com.wangyu.tangdb.client;

import com.google.protobuf.ByteString;
import com.wangyu.tangdb.proto.AbortTxnRequest;
import com.wangyu.tangdb.proto.AbortTxnResponse;
import com.wangyu.tangdb.proto.BeginTxnRequest;
import com.wangyu.tangdb.proto.BeginTxnResponse;
import com.wangyu.tangdb.proto.CommitTxnRequest;
import com.wangyu.tangdb.proto.CommitTxnResponse;
import com.wangyu.tangdb.proto.GetRequest;
import com.wangyu.tangdb.proto.GetResponse;
import com.wangyu.tangdb.proto.Kv;
import com.wangyu.tangdb.proto.PingRequest;
import com.wangyu.tangdb.proto.PingResponse;
import com.wangyu.tangdb.proto.PutRequest;
import com.wangyu.tangdb.proto.PutResponse;
import com.wangyu.tangdb.proto.RowBatch;
import com.wangyu.tangdb.proto.ScanRequest;
import com.wangyu.tangdb.proto.StatusCode;
import com.wangyu.tangdb.proto.TangDbGrpc;
import com.wangyu.tangdb.storage.ConflictException;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TransactionStore;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * tang_db BE 的 gRPC 客户端封装。
 *
 * <p>只通过 {@code proto/tangdb.proto} 定义的接口与 BE 通信，不读写 BE 的任何数据文件。
 * 所有失败都转换成带中文提示的 {@link ClientException}，调用方无需处理 gRPC 异常类型。</p>
 */
public class TangDbClient implements AutoCloseable, KvStore, TransactionStore {

    /** 默认 BE 地址。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** 默认 gRPC 端口。 */
    public static final int DEFAULT_PORT = 9100;

    /** 单次 RPC 的截止时间，保证 BE 无响应时客户端不会无限挂起。 */
    private static final long RPC_DEADLINE_SECONDS = 5L;

    /** 扫描可能返回大量数据,给它比点操作更宽的截止时间。 */
    private static final long SCAN_DEADLINE_SECONDS = 60L;

    /**
     * 批量提交(CSV 导入 / ANALYZE 的统计批写)遇到后台 compaction 时,5 秒会误判超时
     * (SPEC-P6 的 10 万行导入实测在 compaction 期间单批可超过 5s)。放宽只影响
     * "失败多久被发现",不影响正确性。
     */
    private static final long COMMIT_DEADLINE_SECONDS = 60L;

    /** 关闭 channel 时等待在途 RPC 结束的时间。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final TangDbGrpc.TangDbBlockingStub stub;

    /**
     * 使用默认地址 {@code 127.0.0.1:9100} 创建客户端。
     */
    public TangDbClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * 创建客户端并打开到 {@code host:port} 的 channel。
     *
     * @param host BE 主机名或 IP
     * @param port BE gRPC 端口，取值 1..65535
     */
    public TangDbClient(String host, int port) {
        this.host = normalizeHost(host);
        this.port = validatePort(port);
        this.channel = ManagedChannelBuilder.forAddress(this.host, this.port)
                .usePlaintext()
                .build();
        this.stub = TangDbGrpc.newBlockingStub(channel);
    }

    /**
     * 写入一个键值。
     *
     * @return 固定返回 {@code "OK"}（BE 仅在 WAL 已 fsync 后才返回 OK）
     * @throws ClientException BE 返回非 OK 状态，或连接失败
     */
    public String put(String key, String value) throws ClientException {
        if (key == null) {
            throw new ClientException("key 不能为 null", "INVALID_ARGUMENT");
        }
        if (value == null) {
            throw new ClientException("value 不能为 null", "INVALID_ARGUMENT");
        }

        PutResponse response;
        try {
            response = stubWithDeadline().put(PutRequest.newBuilder()
                    .setKey(ByteString.copyFromUtf8(key))
                    .setValue(ByteString.copyFromUtf8(value))
                    .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }

        StatusCode status = response.getStatus();
        if (status == StatusCode.OK) {
            return "OK";
        }
        throw fromResponseStatus(status, response.getMessage());
    }

    /**
     * 读取一个键值。
     *
     * @return 存在时返回对应 value（空字符串是合法值）
     * @throws ClientException key 不存在时抛出 {@code errorCode=NOT_FOUND}、message=“键不存在”；
     *                         其他状态或连接失败同样抛出本异常
     */
    public Optional<String> get(String key) throws ClientException {
        if (key == null) {
            throw new ClientException("key 不能为 null", "INVALID_ARGUMENT");
        }

        GetResponse response;
        try {
            response = stubWithDeadline().get(GetRequest.newBuilder()
                    .setKey(ByteString.copyFromUtf8(key))
                    .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }

        StatusCode status = response.getStatus();
        if (status == StatusCode.OK) {
            return Optional.of(response.getValue().toStringUtf8());
        }
        if (status == StatusCode.NOT_FOUND) {
            throw new ClientException("键不存在", "NOT_FOUND");
        }
        throw fromResponseStatus(status, response.getMessage());
    }

    // ------------------------------------------------------------ 原始 KV 接口(KvStore)

    /**
     * 读取原始 key 的最新已提交版本;不存在返回 null。
     */
    public byte[] getRaw(byte[] key) throws ClientException {
        return getRaw(key, 0);
    }

    /**
     * 在指定快照下读取原始 key(snapshot = 0 表示最新已提交);不存在返回 null。
     */
    public byte[] getRaw(byte[] key, long snapshot) throws ClientException {
        if (key == null || key.length == 0) {
            throw new ClientException("key 不能为空", "INVALID_ARGUMENT");
        }
        GetResponse response;
        try {
            response = stubWithDeadline().get(GetRequest.newBuilder()
                    .setKey(ByteString.copyFrom(key))
                    .setSnapshot(Math.max(snapshot, 0))
                    .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }
        if (response.getStatus() == StatusCode.OK) {
            return response.getValue().toByteArray();
        }
        if (response.getStatus() == StatusCode.NOT_FOUND) {
            return null;
        }
        throw fromResponseStatus(response.getStatus(), response.getMessage());
    }

    /** 写入原始 key/value。返回即代表 BE 已完成 WAL fsync。 */
    public void putRaw(byte[] key, byte[] value) throws ClientException {
        if (key == null || key.length == 0) {
            throw new ClientException("key 不能为空", "INVALID_ARGUMENT");
        }
        PutResponse response;
        try {
            response = stubWithDeadline().put(PutRequest.newBuilder()
                    .setKey(ByteString.copyFrom(key))
                    .setValue(ByteString.copyFrom(value == null ? new byte[0] : value))
                    .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }
        if (response.getStatus() != StatusCode.OK) {
            throw fromResponseStatus(response.getStatus(), response.getMessage());
        }
    }

    /**
     * 按 key 范围 [start, end) 流式扫描并收集结果。end 为空表示到末尾。
     *
     * <p>BE 的中途错误以 status != OK 的 RowBatch 结束,这里必须把它转成失败,
     * 不能当成正常截断(规约 FR-106)。</p>
     */
    public List<KeyValue> scanRaw(byte[] start, byte[] end, int limit) throws ClientException {
        return scanRaw(start, end, 0, limit);
    }

    /**
     * 在指定快照下按用户键范围 [start, end) 扫描(snapshot = 0 表示最新)。
     *
     * <p>返回的 key 已剥掉 BE 维护的版本段,调用方只看到用户键。</p>
     */
    public List<KeyValue> scanRaw(byte[] start, byte[] end, long snapshot, int limit)
            throws ClientException {
        List<KeyValue> rows = new ArrayList<>();
        Iterator<RowBatch> iterator;
        try {
            iterator = stubWithDeadline(SCAN_DEADLINE_SECONDS).scan(ScanRequest.newBuilder()
                    .setStartKey(start == null ? ByteString.EMPTY : ByteString.copyFrom(start))
                    .setEndKey(end == null ? ByteString.EMPTY : ByteString.copyFrom(end))
                    .setLimit(Math.max(limit, 0))
                    .setSnapshot(Math.max(snapshot, 0))
                    .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }

        try {
            while (iterator.hasNext()) {
                RowBatch batch = iterator.next();
                if (batch.getStatus() != StatusCode.OK) {
                    String detail = batch.getMessage();
                    throw new ClientException("BE 扫描失败"
                            + (detail == null || detail.isBlank() ? "" : ": " + detail), "SCAN_FAILED");
                }
                for (com.wangyu.tangdb.proto.Kv kv : batch.getRowsList()) {
                    // P3 起 BE 的 Scan 直接返回**用户键**(版本段已在 BE 侧按快照消解),
                    // 所以这里不能再剥 8 字节 —— 否则主键会被截短(P4 发现的回归)。
                    byte[] userKey = kv.getKey().toByteArray();
                    if (userKey.length < 8) {
                        throw new ClientException("BE 扫描返回的 key 长度非法: " + userKey.length,
                                "SCAN_FAILED");
                    }
                    rows.add(new KeyValue(userKey, kv.getValue().toByteArray()));
                }
            }
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }
        return rows;
    }

    @Override
    public byte[] get(byte[] key) {
        try {
            return getRaw(key);
        } catch (ClientException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        try {
            putRaw(key, value);
        } catch (ClientException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    @Override
    public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
        try {
            return scanRaw(start, end, limit);
        } catch (ClientException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    @Override
    public byte[] get(byte[] key, long snapshot) {
        try {
            return getRaw(key, snapshot);
        } catch (ClientException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    @Override
    public List<KeyValue> scan(byte[] start, byte[] end, long snapshot, int limit) {
        try {
            return scanRaw(start, end, snapshot, limit);
        } catch (ClientException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    /** DDL 的多条元数据记录走一条 CommitTxn 批记录,原子生效(FR-355)。 */
    @Override
    public void writeBatch(List<KeyValue> writes) {
        commit(0, 0, writes);
    }

    // ------------------------------------------------------------ 事务(TransactionStore)

    @Override
    public TxnHandle beginTxn() {
        BeginTxnResponse response;
        try {
            response = stubWithDeadline().beginTxn(BeginTxnRequest.newBuilder()
                    .setReadOnly(false)
                    .build());
        } catch (StatusRuntimeException e) {
            throw new StoreException(translate(e, host, port).getMessage(), e);
        }
        if (response.getStatus() != StatusCode.OK) {
            throw new StoreException("BEGIN 失败: " + detailOf(response.getStatus(), response.getMessage()));
        }
        return new TxnHandle(response.getTxnId(), response.getSnapshot());
    }

    @Override
    public long commit(long txnId, long snapshot, List<KeyValue> writes) {
        if (writes == null || writes.isEmpty()) {
            if (txnId != 0) {
                abort(txnId);
            }
            return 0;
        }
        CommitTxnRequest.Builder builder = CommitTxnRequest.newBuilder()
                .setTxnId(txnId)
                .setSnapshot(Math.max(snapshot, 0));
        for (KeyValue write : writes) {
            builder.addWrites(Kv.newBuilder()
                    .setKey(ByteString.copyFrom(write.key()))
                    .setValue(ByteString.copyFrom(write.value() == null ? new byte[0] : write.value())));
        }

        CommitTxnResponse response;
        try {
            response = stubWithDeadline(COMMIT_DEADLINE_SECONDS).commitTxn(builder.build());
        } catch (StatusRuntimeException e) {
            throw new StoreException(translate(e, host, port).getMessage(), e);
        }

        if (response.getStatus() == StatusCode.CONFLICT) {
            throw new ConflictException("写写冲突", response.getConflictKey().toByteArray());
        }
        if (response.getStatus() != StatusCode.OK) {
            throw new StoreException("COMMIT 失败: "
                    + detailOf(response.getStatus(), response.getMessage()));
        }
        return response.getCommitTs();
    }

    @Override
    public void abort(long txnId) {
        if (txnId == 0) {
            return;
        }
        try {
            AbortTxnResponse response = stubWithDeadline().abortTxn(AbortTxnRequest.newBuilder()
                    .setTxnId(txnId)
                    .build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException("ROLLBACK 失败: "
                        + detailOf(response.getStatus(), response.getMessage()));
            }
        } catch (StatusRuntimeException e) {
            throw new StoreException(translate(e, host, port).getMessage(), e);
        }
    }

    private static String detailOf(StatusCode status, String detail) {
        String message = describeStatus(status);
        if (detail != null && !detail.isBlank()) {
            message += ":" + detail;
        }
        return message;
    }

    /**
     * 探活，返回 BE 版本号。
     *
     * @throws ClientException 连接失败或 BE 返回错误
     */
    public String ping() throws ClientException {
        try {
            PingResponse response = stubWithDeadline().ping(PingRequest.getDefaultInstance());
            return response.getVersion();
        } catch (StatusRuntimeException e) {
            throw translate(e, host, port);
        }
    }

    /**
     * @return 当前连接目标，形如 {@code 127.0.0.1:9100}
     */
    public String address() {
        return host + ":" + port;
    }

    @Override
    public void close() {
        if (channel.isShutdown()) {
            return;
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每次 RPC 都重新计算截止时间，避免固定 deadline 在客户端长时间运行后失效。
     */
    private TangDbGrpc.TangDbBlockingStub stubWithDeadline() {
        return stubWithDeadline(RPC_DEADLINE_SECONDS);
    }

    private TangDbGrpc.TangDbBlockingStub stubWithDeadline(long seconds) {
        return stub.withDeadlineAfter(seconds, TimeUnit.SECONDS);
    }

    /**
     * 把 BE 返回的状态码转换成中文提示。
     */
    static String describeStatus(StatusCode status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case OK:
                return "成功";
            case NOT_FOUND:
                return "键不存在";
            case INVALID_ARGUMENT:
                return "参数无效";
            case INTERNAL:
                return "BE 内部错误";
            case CONFLICT:
                return "写写冲突";
            case STATUS_UNSPECIFIED:
                return "BE 未返回状态";
            case UNRECOGNIZED:
                return "无法识别的状态码";
            default:
                return "未知状态";
        }
    }

    /**
     * 把 gRPC 层的 {@link StatusRuntimeException} 转成面向用户的中文异常。
     * 不把原始堆栈暴露给 CLI，只保留状态码与必要的描述。
     */
    static ClientException translate(StatusRuntimeException e, String host, int port) {
        Status status = e.getStatus();
        String address = host + ":" + port;
        String detail = status.getDescription();

        switch (status.getCode()) {
            case UNAVAILABLE:
                return new ClientException("无法连接 BE " + address, "UNAVAILABLE", e);
            case DEADLINE_EXCEEDED:
                return new ClientException("连接 BE " + address + " 超时", "DEADLINE_EXCEEDED", e);
            case CANCELLED:
                return new ClientException("请求已被取消", "CANCELLED", e);
            case PERMISSION_DENIED:
                return new ClientException("没有访问 BE " + address + " 的权限", "PERMISSION_DENIED", e);
            default:
                String message = "与 BE " + address + " 通信失败（" + status.getCode() + "）";
                if (detail != null && !detail.isBlank()) {
                    message += "：" + detail;
                }
                return new ClientException(message, status.getCode().name(), e);
        }
    }

    private ClientException fromResponseStatus(StatusCode status, String detail) {
        String message = describeStatus(status);
        if (detail != null && !detail.isBlank()) {
            message += "：" + detail;
        }
        return new ClientException(message, status == null ? "UNKNOWN" : status.name());
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return DEFAULT_HOST;
        }
        return host.trim();
    }

    private static int validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口无效：" + port + "（应为 1-65535）");
        }
        return port;
    }
}
