package com.wangyu.tangdb.query;

/**
 * IN 子查询求值后的集合:值集合 + 是否含 NULL(三值逻辑)。
 */
public final class InSet {

    private final java.util.Set<Object> values = new java.util.HashSet<>();
    private boolean hasNull;

    public void add(Object value) {
        if (value == null) {
            hasNull = true;
            return;
        }
        values.add(ValueOps.key(value));
    }

    public boolean contains(Object value) {
        return values.contains(ValueOps.key(value));
    }

    public boolean hasNull() {
        return hasNull;
    }

    public int size() {
        return values.size();
    }
}
