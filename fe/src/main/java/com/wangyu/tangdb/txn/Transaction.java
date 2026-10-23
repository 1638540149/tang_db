package com.wangyu.tangdb.txn;

/**
 * 一个活动事务:BE 句柄 + 固定快照 + 内存写集。
 *
 * <p>未提交的写入只活在这里,从未进过 BE 的 WAL / memtable(SPEC-P3 FR-342),
 * 所以 ROLLBACK 就是丢掉写集,零成本(FR-347)。</p>
 */
public final class Transaction {

    private final long txnId;
    private final long snapshot;
    private final WriteSet writes = new WriteSet();

    public Transaction(long txnId, long snapshot) {
        this.txnId = txnId;
        this.snapshot = snapshot;
    }

    /** 本地事务(内存假实现 / 不依赖 BE 句柄时使用)。 */
    public static Transaction local() {
        return new Transaction(0, 0);
    }

    public long txnId() {
        return txnId;
    }

    /** 事务开始时的已提交水位;0 = 最新(本地兼容模式)。 */
    public long snapshot() {
        return snapshot;
    }

    public WriteSet writes() {
        return writes;
    }

    public boolean hasWrites() {
        return !writes.isEmpty();
    }
}
