package com.wangyu.tangdb.txn;

import com.wangyu.tangdb.storage.KeyValue;

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
}
