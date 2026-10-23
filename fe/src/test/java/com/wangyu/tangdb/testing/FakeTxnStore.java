package com.wangyu.tangdb.testing;

import com.wangyu.tangdb.storage.ConflictException;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.TransactionStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内存版 {@link TransactionStore}:带最小 MVCC(按 commit_ts 多版本 + 快照读 + 写写冲突),
 * 用于在没有 BE 的情况下测试 FE 的事务状态机、读己之写、快照稳定性与冲突翻译。
 *
 * <p>不是 BE 的替代实现,只覆盖 FE 单测需要的语义。</p>
 */
public final class FakeTxnStore implements TransactionStore {

    private static final class Version {
        final long ts;
        final byte[] value;

        Version(long ts, byte[] value) {
            this.ts = ts;
            this.value = value;
        }
    }

    private final TreeMap<byte[], List<Version>> data = new TreeMap<>(Arrays::compareUnsigned);
    private long currentTS;
    private long nextTxnId;
    private Runnable afterBeginHook;

    @Override
    public synchronized byte[] get(byte[] key) {
        return get(key, 0);
    }

    @Override
    public synchronized byte[] get(byte[] key, long snapshot) {
        List<Version> versions = data.get(key);
        if (versions == null) {
            return null;
        }
        long upper = snapshot == 0 ? currentTS : snapshot;
        byte[] visible = null;
        for (Version v : versions) {
            if (v.ts > upper) {
                break;
            }
            visible = v.value;
        }
        return visible == null ? null : visible.clone();
    }

    @Override
    public synchronized void put(byte[] key, byte[] value) {
        commit(0, 0, List.of(new KeyValue(key, value)));
    }

    @Override
    public synchronized List<KeyValue> scan(byte[] start, byte[] end, int limit) {
        return scan(start, end, 0, limit);
    }

    @Override
    public synchronized List<KeyValue> scan(byte[] start, byte[] end, long snapshot, int limit) {
        List<KeyValue> rows = new ArrayList<>();
        for (Map.Entry<byte[], List<Version>> entry : data.entrySet()) {
            byte[] key = entry.getKey();
            if (start != null && start.length > 0 && Arrays.compareUnsigned(key, start) < 0) {
                continue;
            }
            if (end != null && end.length > 0 && Arrays.compareUnsigned(key, end) >= 0) {
                break;
            }
            byte[] value = get(key, snapshot);
            if (value == null) {
                continue;
            }
            rows.add(new KeyValue(key.clone(), value));
            if (limit > 0 && rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    @Override
    public synchronized void writeBatch(List<KeyValue> writes) {
        commit(0, 0, writes);
    }

    @Override
    public synchronized TxnHandle beginTxn() {
        nextTxnId++;
        TxnHandle handle = new TxnHandle(nextTxnId, currentTS);
        // 测试钩子:模拟"刚取完快照,另一个事务就提交了"的并发窗口。
        if (afterBeginHook != null) {
            Runnable hook = afterBeginHook;
            afterBeginHook = null;
            hook.run();
        }
        return handle;
    }

    /** 只影响下一次 beginTxn;用于制造并发提交窗口。 */
    public synchronized void setAfterBeginHook(Runnable hook) {
        this.afterBeginHook = hook;
    }

    @Override
    public synchronized long commit(long txnId, long snapshot, List<KeyValue> writes) {
        if (writes == null || writes.isEmpty()) {
            return 0;
        }
        long effective = snapshot == 0 ? currentTS : snapshot;
        for (KeyValue write : writes) {
            long latest = latestTS(write.key());
            if (latest > effective) {
                throw new ConflictException("写写冲突", write.key());
            }
        }
        long ts = ++currentTS;
        for (KeyValue write : writes) {
            data.computeIfAbsent(write.key().clone(), k -> new ArrayList<>())
                    .add(new Version(ts, write.value() == null ? new byte[0] : write.value().clone()));
        }
        return ts;
    }

    @Override
    public void abort(long txnId) {
        // 本地假实现无需释放句柄。
    }

    public synchronized long currentTS() {
        return currentTS;
    }

    private long latestTS(byte[] key) {
        List<Version> versions = data.get(key);
        if (versions == null || versions.isEmpty()) {
            return 0;
        }
        return versions.get(versions.size() - 1).ts;
    }
}
