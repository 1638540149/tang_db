package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * 分批扫描游标(SPEC-P6.1 FR-715)。
 *
 * <p>与 {@link KvStore#scan} 的区别:后者一次交付整段范围的全部行,游标一次只交付一批,
 * 调用方处理完即丢弃;{@link #close()} 取消上游扫描(真实客户端会 cancel gRPC 流),
 * 这是"LIMIT 命中即停止向上游取数"的实现基础。</p>
 */
public interface ScanCursor extends AutoCloseable {

    /**
     * 取下一批行(按 key 升序)。
     *
     * @return 一批行;返回 {@code null} 表示扫描结束(或游标已关闭)
     */
    List<KeyValue> nextBatch();

    @Override
    void close();
}
