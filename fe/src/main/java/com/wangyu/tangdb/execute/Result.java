package com.wangyu.tangdb.execute;

import java.util.List;

/** 一条语句的执行结果:要么是一段提示(DDL/DML),要么是一张表(SELECT/SHOW/DESC)。 */
public sealed interface Result permits Result.Message, Result.Table {

    record Message(String text) implements Result {
    }

    record Table(List<String> columns, List<Object[]> rows) implements Result {
    }
}
