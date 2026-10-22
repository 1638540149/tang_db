package com.wangyu.tangdb.storage;

/** 一条原始键值(与 BE 的 Kv 一一对应)。 */
public record KeyValue(byte[] key, byte[] value) {
}
