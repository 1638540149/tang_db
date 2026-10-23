package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * FE 侧对 BE 的最小 KV 访问抽象。
 *
 * <p>catalog、执行器、测试都只依赖这个接口;真实实现是走 gRPC 的
 * {@code com.wangyu.tangdb.client.TangDbClient},测试里用内存假实现。
 * FE 绝不直接读写 BE 的数据目录(宪法第一条)。</p>
 *
 * <p>P3 起:key 是**用户键**(不含版本段);扫描返回的 key 由实现方剥掉
 * BE 追加的 8 字节版本段,调用方只看到用户键。</p>
 */
public interface KvStore {

    /** 读取一个 key(最新已提交);不存在返回 null。 */
    byte[] get(byte[] key);

    /** 写入一个 key(单 key 自动提交)。返回即代表 BE 已完成 WAL fsync。 */
    void put(byte[] key, byte[] value);

    /** 扫描 [start, end);end 为空表示到末尾。按 key 升序,每个 key 只出现一次。 */
    List<KeyValue> scan(byte[] start, byte[] end, int limit);

    /**
     * 在指定快照下读取;snapshot = 0 表示最新已提交。
     *
     * <p>默认实现忽略快照(内存假实现用),真实实现必须带上 snapshot。</p>
     */
    default byte[] get(byte[] key, long snapshot) {
        return get(key);
    }

    /** 在指定快照下扫描;snapshot = 0 表示最新已提交。 */
    default List<KeyValue> scan(byte[] start, byte[] end, long snapshot, int limit) {
        return scan(start, end, limit);
    }

    /**
     * 原子写入一批 key(用于 DDL 的多条元数据记录,SPEC-P3 FR-355)。
     *
     * <p>默认实现逐条写(内存假实现用);真实实现必须走一条 CommitTxn 批记录。</p>
     */
    default void writeBatch(List<KeyValue> writes) {
        for (KeyValue kv : writes) {
            put(kv.key(), kv.value());
        }
    }
}
