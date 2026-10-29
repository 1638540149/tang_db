package com.wangyu.tangdb.txn;

import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.ScanOptions;

import java.util.List;

/**
 * 读视图:GET / SCAN 的统一入口。
 *
 * <p>三种实现:</p>
 * <ul>
 *   <li>{@link LatestReadView}:事务外,读最新已提交;</li>
 *   <li>{@link TransactionView}:事务内,按 BEGIN 时固定的快照读,并叠加本事务写集(读己之写);</li>
 *   <li>{@link LayeredView}:在任意视图上再叠一层"当前语句的临时写集"(多行 INSERT 用)。</li>
 * </ul>
 */
public interface ReadView {

    /** 读取用户键;不存在返回 null。 */
    byte[] get(byte[] userKey);

    /** 扫描用户键范围 [start, end);每个用户键只返回一次。 */
    List<KeyValue> scan(byte[] start, byte[] end, int limit);

    /**
     * 带列投影 / 谓词下推的扫描(SPEC-P6.1 FR-701 ~ FR-707)。
     *
     * <p>默认实现忽略下推参数、返回整行 —— 内存假实现与事务视图走这条回退路径,
     * 结果与全行扫描逐行一致;真实客户端(TangDbClient)覆盖它走 gRPC 投影。</p>
     */
    default List<KeyValue> scanProjected(byte[] start, byte[] end, int limit,
                                         ScanOptions options) {
        return scan(start, end, limit);
    }

    /** 是否"只读最新已提交"的视图(并发分片扫描只对这种视图安全)。 */
    default boolean isLatest() {
        return false;
    }
}
