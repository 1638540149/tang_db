package com.wangyu.tangdb.testing;

import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内存版 KvStore,用于 catalog/执行器单元测试。
 *
 * <p>按字节序排列,模拟 BE 的有序语义;不涉及任何磁盘或网络。</p>
 */
public final class FakeKvStore implements KvStore {

    private final TreeMap<byte[], byte[]> data = new TreeMap<>(Arrays::compareUnsigned);

    @Override
    public byte[] get(byte[] key) {
        byte[] value = data.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public void put(byte[] key, byte[] value) {
        data.put(key.clone(), value == null ? new byte[0] : value.clone());
    }

    @Override
    public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
        List<KeyValue> rows = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> entry : data.entrySet()) {
            if (start != null && start.length > 0 && Arrays.compareUnsigned(entry.getKey(), start) < 0) {
                continue;
            }
            if (end != null && end.length > 0 && Arrays.compareUnsigned(entry.getKey(), end) >= 0) {
                break;
            }
            rows.add(new KeyValue(entry.getKey().clone(), entry.getValue().clone()));
            if (limit > 0 && rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    public void remove(byte[] key) {
        data.remove(key);
    }

    public int size() {
        return data.size();
    }
}
