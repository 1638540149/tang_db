package com.wangyu.tangdb.storage;

import java.util.List;

/**
 * 支持显式事务的 {@link KvStore}(SPEC-P3 FR-301 ~ FR-303)。
 *
 * <p>真正的实现是 {@code TangDbClient};内存假实现不实现它,执行器会自动退回
 * "本地写集 + 逐条写"的兼容模式(仍然有 BEGIN/COMMIT/ROLLBACK 状态机与读己之写)。</p>
 */
public interface TransactionStore extends KvStore {

    /** 向 BE 取快照并领取事务句柄。 */
    TxnHandle beginTxn();

    /**
     * 提交一个写集:注意 snapshot 是本事务开始时的水位,不是 0。
     *
     * @return commit_ts;写集为空时可能为 0
     * @throws ConflictException 写写冲突(conflictKey 为冲突的用户键)
     */
    long commit(long txnId, long snapshot, List<KeyValue> writes);

    /** 释放 BE 侧事务句柄(不产生任何 I/O)。 */
    void abort(long txnId);

    /** 事务句柄 + 快照水位。 */
    record TxnHandle(long txnId, long snapshot) {
    }
}
