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
import com.wangyu.tangdb.proto.DropVectorIndexRequest;
import com.wangyu.tangdb.proto.DropVectorIndexResponse;
import com.wangyu.tangdb.proto.RegisterVectorIndexRequest;
import com.wangyu.tangdb.proto.RegisterVectorIndexResponse;
import com.wangyu.tangdb.proto.SearchVectorIndexRequest;
import com.wangyu.tangdb.proto.SearchVectorIndexResponse;
import com.wangyu.tangdb.proto.VectorCandidate;
import com.wangyu.tangdb.proto.VectorIndexStatusRequest;
import com.wangyu.tangdb.proto.VectorIndexStatusResponse;
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
public class TangDbClient implements AutoCloseable, KvStore, TransactionStore,
        com.wangyu.tangdb.backup.BackupChannel,
        com.wangyu.tangdb.storage.ProjectingStore,
        com.wangyu.tangdb.storage.CursoredStore,
        com.wangyu.tangdb.storage.VectorStore {

    /** 默认 BE 地址。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** 默认 gRPC 端口。 */
    public static final int DEFAULT_PORT = 9100;

    /** 单次 RPC 的截止时间(默认值,SPEC-P6.1 FR-722 的三档之一)。 */
    private static final long DEFAULT_RPC_DEADLINE_SECONDS = 5L;

    /** 范围扫描 / 分片扫描期限(默认值)。 */
    private static final long DEFAULT_SCAN_DEADLINE_SECONDS = 60L;

    /**
     * 批量提交 / 导入 / 回填 / ANALYZE 期限(默认值)。
     *
     * <p>5 秒会在 compaction 期间误判超时(SPEC-P6 的 10 万行导入实测单批可超过 5s);
     * 放宽只影响"失败多久被发现",不影响正确性。</p>
     */
    private static final long DEFAULT_BULK_DEADLINE_SECONDS = 60L;

    /** 三档期限(秒,可在启动时由配置覆盖,SPEC-P6.1 FR-722)。 */
    private long rpcDeadlineSeconds = DEFAULT_RPC_DEADLINE_SECONDS;
    private long scanDeadlineSeconds = DEFAULT_SCAN_DEADLINE_SECONDS;
    private long bulkDeadlineSeconds = DEFAULT_BULK_DEADLINE_SECONDS;

    /** 扫描收到的 key+value 载荷字节数(SPEC-P6.1 SC-405 的实测口径)。 */
    private final java.util.concurrent.atomic.AtomicLong scanPayloadBytes =
            new java.util.concurrent.atomic.AtomicLong();

    /** 分片扫描固定的快照 → BE 事务句柄(SPEC-P6.1 FR-711)。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> pinnedTxns =
            new java.util.concurrent.ConcurrentHashMap<>();

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
            throw asStoreException(e);
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
            throw asStoreException(e);
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
            throw asStoreException(e);
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
            throw asStoreException(e);
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
            iterator = stubWithDeadline(scanDeadlineSeconds).scan(ScanRequest.newBuilder()
                    .setStartKey(start == null ? ByteString.EMPTY : ByteString.copyFrom(start))
                    .setEndKey(end == null ? ByteString.EMPTY : ByteString.copyFrom(end))
                    .setLimit(Math.max(limit, 0))
                    .setSnapshot(Math.max(snapshot, 0))
                    .build());
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
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
                    scanPayloadBytes.addAndGet(userKey.length + kv.getValue().size());
                    rows.add(new KeyValue(userKey, kv.getValue().toByteArray()));
                }
            }
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
        return rows;
    }

    /**
     * 固定一个快照给并发分片扫描用(SPEC-P6.1 FR-711)。
     *
     * <p>借 BeginTxn 取当前已提交水位并登记活跃快照(compaction 的 GC 水位不会越过它),
     * 用完由 {@link #snapshotUnpin(long)} 释放。失败时返回 0(退回逐片读最新)。</p>
     */
    @Override
    public long snapshotPin() {
        try {
            BeginTxnResponse response = stubWithDeadline().beginTxn(
                    BeginTxnRequest.newBuilder().setReadOnly(true).build());
            if (response.getStatus() != StatusCode.OK) {
                return 0;
            }
            pinnedTxns.put(response.getSnapshot(), response.getTxnId());
            return response.getSnapshot();
        } catch (StatusRuntimeException e) {
            return 0;
        }
    }

    @Override
    public void snapshotUnpin(long snapshot) {
        if (snapshot <= 0) {
            return;
        }
        Long txnId = pinnedTxns.remove(snapshot);
        if (txnId != null) {
            try {
                abort(txnId);
            } catch (RuntimeException ignored) {
                // 释放失败不影响查询结果;BE 侧句柄最终随会话回收。
            }
        }
    }

    /** 自上次 {@link #resetScanPayloadBytes()} 以来,扫描收到的载荷字节数。 */
    public long scanPayloadBytes() {
        return scanPayloadBytes.get();
    }

    public void resetScanPayloadBytes() {
        scanPayloadBytes.set(0);
    }

    /** 应用三档期限(SPEC-P6.1 FR-722);非法值忽略,沿用内置默认。 */
    public TangDbClient withDeadlines(int rpcSeconds, int bulkSeconds, int scanSeconds) {
        if (rpcSeconds > 0) {
            this.rpcDeadlineSeconds = rpcSeconds;
        }
        if (bulkSeconds > 0) {
            this.bulkDeadlineSeconds = bulkSeconds;
        }
        if (scanSeconds > 0) {
            this.scanDeadlineSeconds = scanSeconds;
        }
        return this;
    }

    /** 当前三档期限(秒),日志与 EXPLAIN 用。 */
    public long[] deadlines() {
        return new long[]{rpcDeadlineSeconds, bulkDeadlineSeconds, scanDeadlineSeconds};
    }

    /**
     * 带列投影 / 谓词下推 / 列式块的扫描(SPEC-P6.1 FR-701 ~ FR-707)。
     *
     * <p>返回的 value 是投影帧,不是整行;key 仍是用户键。BE 侧返回 columnar_block
     * 时按块解码,否则按逐 KV 帧。</p>
     */
    @Override
    public List<KeyValue> scanProjected(byte[] start, byte[] end, long snapshot, int limit,
                                        com.wangyu.tangdb.storage.ScanOptions options)
            throws ClientException {
        com.wangyu.tangdb.storage.ScanOptions opts = options == null
                ? com.wangyu.tangdb.storage.ScanOptions.plain(0) : options;
        ScanRequest.Builder builder = ScanRequest.newBuilder()
                .setStartKey(start == null ? ByteString.EMPTY : ByteString.copyFrom(start))
                .setEndKey(end == null ? ByteString.EMPTY : ByteString.copyFrom(end))
                .setLimit(Math.max(limit, 0))
                .setSnapshot(Math.max(snapshot, 0))
                .setBatchRows(Math.max(opts.batchRows(), 0))
                .setColumnMask(opts.columnMask())
                .setColumnarResult(opts.columnar());
        if (opts.columnSlots().length > 0) {
            builder.setColumnSlots(ByteString.copyFrom(opts.columnSlots()));
        }
        if (opts.rowFrame().length > 0) {
            builder.setRowFrame(ByteString.copyFrom(opts.rowFrame()));
        }
        if (opts.predicate().length > 0) {
            builder.setPredicate(ByteString.copyFrom(opts.predicate()));
        }

        Iterator<RowBatch> iterator;
        try {
            iterator = stubWithDeadline(scanDeadlineSeconds).scan(builder.build());
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }

        List<KeyValue> rows = new ArrayList<>();
        try {
            while (iterator.hasNext()) {
                RowBatch batch = iterator.next();
                if (batch.getStatus() != StatusCode.OK) {
                    String detail = batch.getMessage();
                    throw new ClientException("BE 扫描失败"
                            + (detail == null || detail.isBlank() ? "" : ": " + detail), "SCAN_FAILED");
                }
                if (!batch.getColumnarBlock().isEmpty()) {
                    if (!batch.getRowsList().isEmpty()) {
                        throw new ClientException("BE 同一批混用列式块与逐 KV(内部错误)", "SCAN_FAILED");
                    }
                    java.util.List<KeyValue> decoded = com.wangyu.tangdb.storage.ProjectedCodec
                            .decodeBlock(batch.getColumnarBlock().toByteArray());
                    for (KeyValue kv : decoded) {
                        scanPayloadBytes.addAndGet(kv.key().length + kv.value().length);
                    }
                    rows.addAll(decoded);
                    continue;
                }
                for (com.wangyu.tangdb.proto.Kv kv : batch.getRowsList()) {
                    scanPayloadBytes.addAndGet(kv.getKey().size() + kv.getValue().size());
                    rows.add(new KeyValue(kv.getKey().toByteArray(), kv.getValue().toByteArray()));
                }
            }
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
        return rows;
    }

    // ------------------------------------------------------------ 分批扫描游标(FR-715)

    /**
     * 打开可取消的分批扫描游标(SPEC-P6.1 FR-715)。
     *
     * <p>用 async server-streaming call 而不是 blocking stub 的 {@code Iterator}:
     * 只有拿到 {@code ClientCall} 才能在 {@link ScanCursor#close()} 时真正 cancel 流,
     * BE 侧才会停止继续扫描(而不是把整个范围跑完再丢弃)。</p>
     */
    @Override
    public com.wangyu.tangdb.storage.ScanCursor openScan(byte[] start, byte[] end, long snapshot,
                                                         int limit,
                                                         com.wangyu.tangdb.storage.ScanOptions options) {
        com.wangyu.tangdb.storage.ScanOptions opts = options == null
                ? com.wangyu.tangdb.storage.ScanOptions.plain(0) : options;
        ScanRequest.Builder builder = ScanRequest.newBuilder()
                .setStartKey(start == null ? ByteString.EMPTY : ByteString.copyFrom(start))
                .setEndKey(end == null ? ByteString.EMPTY : ByteString.copyFrom(end))
                .setLimit(Math.max(limit, 0))
                .setSnapshot(Math.max(snapshot, 0))
                .setBatchRows(Math.max(opts.batchRows(), 0))
                .setColumnMask(opts.columnMask())
                .setColumnarResult(opts.columnar());
        if (opts.columnSlots().length > 0) {
            builder.setColumnSlots(ByteString.copyFrom(opts.columnSlots()));
        }
        if (opts.rowFrame().length > 0) {
            builder.setRowFrame(ByteString.copyFrom(opts.rowFrame()));
        }
        if (opts.predicate().length > 0) {
            builder.setPredicate(ByteString.copyFrom(opts.predicate()));
        }
        io.grpc.CallOptions callOptions = stub.getCallOptions()
                .withDeadlineAfter(scanDeadlineSeconds, TimeUnit.SECONDS);
        io.grpc.ClientCall<ScanRequest, RowBatch> call =
                channel.newCall(TangDbGrpc.getScanMethod(), callOptions);
        return new StreamCursor(call, builder.build());
    }

    /**
     * 把 server-streaming 的 {@code RowBatch} 桥接成阻塞式分批游标。
     *
     * <p>背压:每消费一批才 {@code request(1)},上游最多领先一批;关闭时 cancel。</p>
     */
    private final class StreamCursor implements com.wangyu.tangdb.storage.ScanCursor {

        private static final int QUEUE_CAPACITY = 8;
        private static final Object DONE = new Object();

        private final io.grpc.ClientCall<ScanRequest, RowBatch> call;
        private final java.util.concurrent.BlockingQueue<Object> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private volatile boolean closed;

        StreamCursor(io.grpc.ClientCall<ScanRequest, RowBatch> call, ScanRequest request) {
            this.call = call;
            call.start(new io.grpc.ClientCall.Listener<RowBatch>() {
                @Override
                public void onMessage(RowBatch batch) {
                    enqueue(batch);
                }

                @Override
                public void onClose(io.grpc.Status status, io.grpc.Metadata trailers) {
                    enqueue(status.isOk() ? DONE : status.asRuntimeException(trailers));
                }
            }, new io.grpc.Metadata());
            call.request(1);
            call.sendMessage(request);
            call.halfClose();
        }

        private void enqueue(Object item) {
            try {
                queue.put(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public List<KeyValue> nextBatch() {
            while (!closed) {
                Object item;
                try {
                    item = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new StoreException("流式扫描被中断", e);
                }
                if (item == DONE) {
                    return null;
                }
                if (item instanceof StatusRuntimeException failure) {
                    throw new StoreException(translate(failure, host, port).getMessage(), failure);
                }
                if (item instanceof Throwable failure) {
                    throw new StoreException("流式扫描失败: " + failure.getMessage(), failure);
                }
                RowBatch batch = (RowBatch) item;
                if (batch.getStatus() != StatusCode.OK) {
                    String detail = batch.getMessage();
                    throw new StoreException("BE 扫描失败"
                            + (detail == null || detail.isBlank() ? "" : ": " + detail));
                }
                List<KeyValue> rows = batchRows(batch);
                call.request(1); // 背压:消费一批再要下一批
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
            return null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                call.cancel("结果集已提前结束(不再向上游取数)", null);
            } catch (RuntimeException ignored) {
                // 已结束 / 已取消:不影响结果
            }
            queue.clear();
            queue.offer(DONE); // 唤醒可能正在 take() 的消费方
        }
    }

    /** 把一个 {@code RowBatch}(列式块或逐 KV)解成 KeyValue 列表。 */
    private List<KeyValue> batchRows(RowBatch batch) {
        if (!batch.getColumnarBlock().isEmpty()) {
            if (!batch.getRowsList().isEmpty()) {
                throw new StoreException("BE 同一批混用列式块与逐 KV(内部错误)");
            }
            List<KeyValue> decoded = com.wangyu.tangdb.storage.ProjectedCodec
                    .decodeBlock(batch.getColumnarBlock().toByteArray());
            for (KeyValue kv : decoded) {
                scanPayloadBytes.addAndGet(kv.key().length + kv.value().length);
            }
            return decoded;
        }
        List<KeyValue> rows = new ArrayList<>(batch.getRowsCount());
        for (com.wangyu.tangdb.proto.Kv kv : batch.getRowsList()) {
            scanPayloadBytes.addAndGet(kv.getKey().size() + kv.getValue().size());
            rows.add(new KeyValue(kv.getKey().toByteArray(), kv.getValue().toByteArray()));
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
            response = stubWithDeadline(bulkDeadlineSeconds).commitTxn(builder.build());
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

    // ------------------------------------------------------------ SPEC-P8 D 组:备份 / 恢复

    @Override
    public String startBackup(String backupDir, String incrementalFrom, long snapshotRows,
                              boolean force) {
        try {
            com.wangyu.tangdb.proto.BackupResponse response =
                    stubWithDeadline(bulkDeadlineSeconds).backup(
                            com.wangyu.tangdb.proto.BackupRequest.newBuilder()
                                    .setBackupDir(backupDir)
                                    .setIncrementalFrom(incrementalFrom == null ? ""
                                            : incrementalFrom)
                                    .setSnapshotRows(snapshotRows)
                                    .setForce(force)
                                    .build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException(response.getMessage());
            }
            return response.getJobId();
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    @Override
    public List<com.wangyu.tangdb.backup.BackupChannel.RemoteJob> remoteJobs(String jobId) {
        try {
            com.wangyu.tangdb.proto.BackupJobResponse response =
                    stubWithDeadline(rpcDeadlineSeconds).backupJob(
                            com.wangyu.tangdb.proto.BackupJobRequest.newBuilder()
                                    .setJobId(jobId == null ? "" : jobId).build());
            if (response.getStatus() == StatusCode.NOT_FOUND) {
                return List.of();
            }
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException(response.getMessage());
            }
            List<com.wangyu.tangdb.backup.BackupChannel.RemoteJob> jobs = new ArrayList<>();
            for (com.wangyu.tangdb.proto.BackupJobInfo info : response.getJobsList()) {
                jobs.add(new com.wangyu.tangdb.backup.BackupChannel.RemoteJob(
                        info.getJobId(), info.getBackupDir(), info.getStatus(),
                        info.getCopiedBytes(), info.getTotalBytes(), info.getMessage(),
                        info.getIncremental(), info.getStartedUnixMillis(),
                        info.getFinishedUnixMillis()));
            }
            return jobs;
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    @Override
    public com.wangyu.tangdb.backup.BackupChannel.RestoreOutcome restore(String sourceDir,
                                                                         String targetDir,
                                                                         boolean force) {
        try {
            com.wangyu.tangdb.proto.RestoreResponse response =
                    stubWithDeadline(bulkDeadlineSeconds).restore(
                            com.wangyu.tangdb.proto.RestoreRequest.newBuilder()
                                    .setSourceDir(sourceDir)
                                    .setTargetDir(targetDir)
                                    .setForce(force)
                                    .build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException(response.getMessage());
            }
            return new com.wangyu.tangdb.backup.BackupChannel.RestoreOutcome(
                    response.getFiles(), response.getBytes(), response.getMessage());
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
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
            throw asStoreException(e);
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

    // ------------------------------------------------------------ SPEC-P7:向量索引

    /**
     * 注册向量索引定义并让 BE 为缺失的 segment 后台建图(FR-736 / FR-748)。
     * 只传字节与数字:不出现表名 / 列名 / 类型名(K161)。
     */
    @Override
    public void registerVectorIndex(com.wangyu.tangdb.storage.VectorStore.VectorIndexDefinition def) {
        try {
            com.wangyu.tangdb.proto.VectorIndexDefinition proto =
                    com.wangyu.tangdb.proto.VectorIndexDefinition.newBuilder()
                    .setIndexId(def.indexId())
                    .setTableId(def.tableId())
                    .setStartKey(ByteString.copyFrom(def.startKey()))
                    .setEndKey(ByteString.copyFrom(def.endKey()))
                    .setColumnSlots(ByteString.copyFrom(def.columnSlots()))
                    .setRowFrame(ByteString.copyFrom(def.rowFrame()))
                    .setVectorOrdinal(def.vectorOrdinal())
                    .setDim(def.dim())
                    .setMetric(def.metric())
                    .setM(def.m())
                    .setEfConstruction(def.efConstruction())
                    .build();
            RegisterVectorIndexResponse response = stubWithDeadline()
                    .registerVectorIndex(RegisterVectorIndexRequest.newBuilder()
                            .setDefinition(proto).build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException("注册向量索引失败: " + response.getMessage());
            }
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    /** 显式重建(FR-750):BE 侧删旧图,并把同一份定义重新排队。 */
    @Override
    public void rebuildVectorIndex(long indexId) {
        dropVectorIndex(indexId);
    }

    /** 删除向量索引的全部图文件(FR-737)。 */
    @Override
    public void dropVectorIndex(long indexId) {
        try {
            DropVectorIndexResponse response = stubWithDeadline()
                    .dropVectorIndex(DropVectorIndexRequest.newBuilder()
                            .setIndexId(indexId).build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException("删除向量索引失败: " + response.getMessage());
            }
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    /** 段级状态(SHOW VECTOR INDEXES)。 */
    @Override
    public com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus vectorIndexStatus(long indexId) {
        try {
            VectorIndexStatusResponse response = stubWithDeadline()
                    .vectorIndexStatus(VectorIndexStatusRequest.newBuilder()
                            .setIndexId(indexId).build());
            if (response.getStatus() != StatusCode.OK) {
                return com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty();
            }
            return new com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus(
                    response.getSegmentCount(), response.getReadySegments(),
                    response.getPendingSegments(), response.getNodeCount(),
                    response.getGraphBytes(), response.getMetric(), response.getDim());
        } catch (StatusRuntimeException e) {
            return com.wangyu.tangdb.storage.VectorStore.VectorIndexStatus.empty();
        }
    }

    /**
     * 批量点查(ANN 候选回表,K180):一次 RPC 取多行,返回**与 keys 对齐**的值
     * (不存在的键给 null),调用方不需要 Map 做中间装配。
     *
     * <p>BE 按请求顺序回行;这里仍按 key 建一次索引对齐,因为 stubbed 协议不保证顺序。
     * 索引只在本方法内使用,且键是同一批候选(≤ ef),规模有界。</p>
     */
    @Override
    public BatchResult batchGetOrdered(List<byte[]> keys, long snapshot, boolean profile) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return new BatchResult(out, "");
        }
        com.wangyu.tangdb.proto.BatchGetRequest.Builder request =
                com.wangyu.tangdb.proto.BatchGetRequest.newBuilder()
                        .setSnapshot(Math.max(snapshot, 0))
                        .setProfile(profile);
        for (byte[] key : keys) {
            request.addKeys(ByteString.copyFrom(key));
            out.add(null);
        }
        java.util.Map<String, Integer> positions = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            positions.putIfAbsent(java.util.Base64.getEncoder().encodeToString(keys.get(i)), i);
        }
        try {
            com.wangyu.tangdb.proto.BatchGetResponse response =
                    stubWithDeadline(scanDeadlineSeconds).batchGet(request.build());
            if (response.getStatus() != StatusCode.OK) {
                throw new StoreException("批量读取失败: " + response.getMessage());
            }
            for (com.wangyu.tangdb.proto.Kv kv : response.getRowsList()) {
                Integer pos = positions.get(java.util.Base64.getEncoder()
                        .encodeToString(kv.getKey().toByteArray()));
                if (pos != null) {
                    out.set(pos, kv.getValue().toByteArray());
                }
            }
            return new BatchResult(out, response.getProfile());
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    /**
     * 近似检索:BE 每段 top-ef → 归并 → MVCC 过滤,返回候选用户键(FR-754 / FR-757)。
     */
    @Override
    public VectorSearchResult searchVector(long indexId, byte[] query, int ef, int k,
                                           long snapshot, byte[] allow, boolean bloom,
                                           boolean profile) {
        try {
            SearchVectorIndexRequest.Builder request = SearchVectorIndexRequest.newBuilder()
                    .setIndexId(indexId)
                    .setQuery(ByteString.copyFrom(query))
                    .setEf(ef)
                    .setK(k)
                    .setSnapshot(snapshot)
                    .setProfile(profile);
            if (allow != null && allow.length > 0) {
                request.setAllow(ByteString.copyFrom(allow)).setAllowBloom(bloom);
            }
            SearchVectorIndexResponse response = stubWithDeadline(scanDeadlineSeconds)
                    .searchVectorIndex(request.build());
            if (response.getStatus() != StatusCode.OK) {
                if (response.getStatus() == StatusCode.NOT_FOUND) {
                    return VectorSearchResult.empty();
                }
                throw new StoreException("向量检索失败: " + response.getMessage());
            }
            List<byte[]> keys = new ArrayList<>(response.getCandidatesCount());
            for (VectorCandidate candidate : response.getCandidatesList()) {
                keys.add(candidate.getKey().toByteArray());
            }
            return new VectorSearchResult(keys, response.getCandidatesExamined(),
                    response.getPendingSegments(), response.getProfile());
        } catch (StatusRuntimeException e) {
            throw asStoreException(e);
        }
    }

    /**
     * 每次 RPC 都重新计算截止时间，避免固定 deadline 在客户端长时间运行后失效。
     */
    private TangDbGrpc.TangDbBlockingStub stubWithDeadline() {
        return stubWithDeadline(rpcDeadlineSeconds);
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
    /** 向量索引 RPC 的异常出口(接口方法不能抛受检异常)。 */
    private StoreException asStoreException(StatusRuntimeException e) {
        ClientException translated = translate(e, host, port);
        return new StoreException(translated.getMessage(), translated);
    }

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
