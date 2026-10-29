package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * 支持列投影 / 谓词下推的扫描入口(SPEC-P6.1 FR-701 ~ FR-707)。
 *
 * <p>与 {@link KvStore#scan} 的区别:返回的 value 是**投影帧**(不是整行),
 * 键仍是用户键。不支持投影的实现不必实现本接口 —— 调用方会回退到全行扫描。</p>
 */
public interface ProjectingStore {

    /**
     * 按范围 + 快照扫描,附带列投影 / 谓词 / 列式块参数。
     *
     * @return 每个 KV 的 key 是用户键,value 是 {@code [nullmap][len][bytes]×n} 帧
     */
    List<KeyValue> scanProjected(byte[] start, byte[] end, long snapshot, int limit,
                                 ScanOptions options) throws com.wangyu.tangdb.client.ClientException;
}
