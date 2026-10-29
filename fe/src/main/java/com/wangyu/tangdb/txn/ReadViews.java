package com.wangyu.tangdb.txn;

import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.ProjectingStore;
import com.wangyu.tangdb.storage.ScanOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 读视图实现与归并工具。 */
public final class ReadViews {

    private ReadViews() {
    }

    /** 事务外:最新已提交。 */
    public static ReadView latest(KvStore kv) {
        return new ReadView() {
            @Override
            public byte[] get(byte[] userKey) {
                return kv.get(userKey, 0);
            }

            @Override
            public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                // KvStore 的契约就是"返回用户键";剥版本段由真实客户端(TangDbClient)完成。
                return kv.scan(start, end, 0, limit);
            }

            @Override
            public boolean isLatest() {
                return true;
            }

            @Override
            public List<KeyValue> scanProjected(byte[] start, byte[] end, int limit,
                                                ScanOptions options) {
                if (kv instanceof ProjectingStore projecting) {
                    try {
                        return projecting.scanProjected(start, end, 0, limit, options);
                    } catch (com.wangyu.tangdb.client.ClientException e) {
                        throw new com.wangyu.tangdb.storage.StoreException(e.getMessage(), e);
                    }
                }
                return kv.scan(start, end, 0, limit);
            }
        };
    }

    /** 固定快照读(ANALYZE 用:统计与用户数据同一快照,FR-621 的同事务语义)。 */
    public static ReadView at(KvStore kv, long snapshot) {
        return new ReadView() {
            @Override
            public byte[] get(byte[] userKey) {
                return kv.get(userKey, snapshot);
            }

            @Override
            public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                return kv.scan(start, end, snapshot, limit);
            }
        };
    }

    /** 事务内:固定快照 + 写集叠加(读己之写)。 */
    public static ReadView ofTransaction(KvStore kv, Transaction txn) {
        return new ReadView() {
            @Override
            public byte[] get(byte[] userKey) {
                byte[] own = txn.writes().get(userKey);
                if (own != null) {
                    return own;
                }
                return kv.get(userKey, txn.snapshot());
            }

            @Override
            public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                List<KeyValue> base = kv.scan(start, end, txn.snapshot(), 0);
                return merge(base, txn.writes().range(start, end), limit);
            }
        };
    }

    /** 在 base 视图上再叠一层临时写集。 */
    public static ReadView layered(ReadView base, WriteSet overlay) {
        return new ReadView() {
            @Override
            public byte[] get(byte[] userKey) {
                byte[] own = overlay.get(userKey);
                return own != null ? own : base.get(userKey);
            }

            @Override
            public List<KeyValue> scan(byte[] start, byte[] end, int limit) {
                List<KeyValue> baseRows = base.scan(start, end, 0);
                return merge(baseRows, overlay.range(start, end), limit);
            }
        };
    }

    /**
     * 归并"底层行 + 叠加写入":后者覆盖前者,结果按用户键升序,limit 在归并之后生效。
     */
    static List<KeyValue> merge(List<KeyValue> base, List<KeyValue> overlay, int limit) {
        TreeMap<byte[], byte[]> merged = new TreeMap<>(Arrays::compareUnsigned);
        for (KeyValue row : base) {
            merged.put(row.key(), row.value());
        }
        for (KeyValue row : overlay) {
            merged.put(row.key(), row.value());
        }
        List<KeyValue> out = new ArrayList<>(merged.size());
        for (Map.Entry<byte[], byte[]> e : merged.entrySet()) {
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            out.add(new KeyValue(e.getKey().clone(), e.getValue().clone()));
        }
        return out;
    }
}
