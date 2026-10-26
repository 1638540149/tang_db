package com.wangyu.tangdb.execute;

import java.util.List;

/** 一条语句的执行结果:要么是一段提示(DDL/DML),要么是一张表(SELECT/SHOW/DESC)。 */
public sealed interface Result permits Result.Message, Result.Table {

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
}
