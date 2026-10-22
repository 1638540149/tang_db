package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * FE 侧对 BE 的最小 KV 访问抽象。
 *
 * <p>catalog、执行器、测试都只依赖这个接口;真实实现是走 gRPC 的
 * {@code com.wangyu.tangdb.client.TangDbClient},测试里用内存假实现。
 * FE 绝不直接读写 BE 的数据目录(宪法第一条)。</p>
 */
public interface KvStore {

    /** 读取一个 key;不存在返回 null。 */
    byte[] get(byte[] key);

    /** 写入一个 key。返回即代表 BE 已完成 WAL fsync。 */
    void put(byte[] key, byte[] value);

    /** 扫描 [start, end);end 为空表示到末尾。按 key 升序。 */
    List<KeyValue> scan(byte[] start, byte[] end, int limit);
}
