package com.wangyu.tangdb.execute;

import java.util.List;

/**
 * 流式结果行源(SPEC-P6.1 FR-715)。
 *
 * <p>协议层逐批写入 socket,不再先把整张结果集物化;{@link #close()} 会终止上游扫描
 * (LIMIT 命中或客户端断开时调用)。</p>
 */
public interface RowStream extends AutoCloseable {

    /** 下一批结果行;返回 {@code null} 表示结果集结束。 */
    List<Object[]> nextBatch();

    /** 结果列名(构造时即可用;空结果也要能给出列名)。 */
    List<String> columns();

    @Override
    void close();
}
