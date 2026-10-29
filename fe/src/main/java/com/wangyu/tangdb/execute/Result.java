package com.wangyu.tangdb.execute;

import java.util.List;

/** 一条语句的执行结果:要么是一段提示(DDL/DML),要么是一张表(SELECT/SHOW/DESC)。 */
public sealed interface Result permits Result.Message, Result.Table, Result.StreamTable {

    /**
     * 一段提示(DDL/DML/事务/库级语句)。
     *
     * @param affectedRows 受影响行数(DML 用于 MySQL 协议 OK 包);-1 表示无此概念
     */
    record Message(String text, long affectedRows) implements Result {
        Message(String text) {
            this(text, -1);
        }
    }

    record Table(List<String> columns, List<Object[]> rows) implements Result {
    }

    /**
     * 流式结果集(SPEC-P6.1 FR-715):列名先给,行按批从 {@link RowStream} 取。
     *
     * <p>一次性 API(协议编码器的 forResult)会把它抽干成普通结果集。</p>
     */
    record StreamTable(List<String> columns, RowStream stream) implements Result {
    }
}
