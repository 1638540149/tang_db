package com.wangyu.tangdb.txn;

import com.wangyu.tangdb.storage.KeyValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 写集:用户键 → 行值(或墓碑)。
 *
 * <p>按 key 覆盖:同一事务多次改同一行只保留**最终**版本(SPEC-P3 FR-342),
 * 不留中间版本。用有序 TreeMap 是便于与 BE 的扫描结果做归并。</p>
 */
public final class WriteSet {

    private final TreeMap<byte[], byte[]> rows = new TreeMap<>(Arrays::compareUnsigned);

    /** 读取写集里的最新值;不存在返回 null。 */
    public byte[] get(byte[] key) {
        return rows.get(key);
    }

    /** 覆盖写入(同一 key 只留最后一条)。 */
    public void put(byte[] key, byte[] value) {
        rows.put(key.clone(), value == null ? new byte[0] : value.clone());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    public void clear() {
        rows.clear();
    }

    /** 按 key 升序返回全部写入。 */
    public List<KeyValue> entries() {
        List<KeyValue> list = new ArrayList<>(rows.size());
        for (Map.Entry<byte[], byte[]> e : rows.entrySet()) {
            list.add(new KeyValue(e.getKey().clone(), e.getValue().clone()));
        }
        return list;
    }

    /** 只取 [start, end) 范围内的写入(用户键级)。 */
    public List<KeyValue> range(byte[] start, byte[] end) {
        List<KeyValue> list = new ArrayList<>();
        for (Map.Entry<byte[], byte[]> e : rows.entrySet()) {
            byte[] key = e.getKey();
            if (start != null && start.length > 0 && Arrays.compareUnsigned(key, start) < 0) {
                continue;
            }
            if (end != null && end.length > 0 && Arrays.compareUnsigned(key, end) >= 0) {
                break;
            }
            list.add(new KeyValue(key.clone(), e.getValue().clone()));
        }
        return list;
    }

    /** 把另一个写集合并进来(后者覆盖前者)。 */
    public void putAll(WriteSet other) {
        rows.putAll(other.rows);
    }
}
