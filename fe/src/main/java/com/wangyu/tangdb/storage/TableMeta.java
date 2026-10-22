package com.wangyu.tangdb.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 表定义(元数据的 1+列 部分)。表 ID 一经分配永不复用。
 */
public final class TableMeta {

    public static final int STATE_ACTIVE = 0;
    public static final int STATE_DROPPED = 1;

    /** 用户表的表 ID 从 16 起(1~15 保留给系统表,K14)。 */
    public static final long FIRST_USER_TABLE_ID = 16;

    private final long id;
    private final String name;
    private final int state;
    private final String createdAt;
    private final List<Column> columns;
    private final boolean system;

    public TableMeta(long id, String name, int state, String createdAt,
                     List<Column> columns, boolean system) {
        this.id = id;
        this.name = name == null ? null : name.toLowerCase();
        this.state = state;
        this.createdAt = createdAt;
        this.columns = List.copyOf(columns);
        this.system = system;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int state() {
        return state;
    }

    public boolean active() {
        return state == STATE_ACTIVE;
    }

    public String createdAt() {
        return createdAt;
    }

    public List<Column> columns() {
        return columns;
    }

    public boolean system() {
        return system;
    }

    public int columnCount() {
        return columns.size();
    }

    public Column primaryKey() {
        for (Column c : columns) {
            if (c.primaryKey()) {
                return c;
            }
        }
        return null;
    }

    /** 按列名(大小写不敏感)查找;不存在返回 null。 */
    public Column column(String columnName) {
        if (columnName == null) {
            return null;
        }
        String want = columnName.toLowerCase();
        for (Column c : columns) {
            if (c.name().equals(want)) {
                return c;
            }
        }
        return null;
    }

    /** 按列名取序号;不存在返回 -1。 */
    public int columnIndex(String columnName) {
        Column c = column(columnName);
        return c == null ? -1 : c.ordinal();
    }

    /** 校验列序号连续且从 0 开始。 */
    public boolean ordinalsContiguous() {
        List<Integer> seen = new ArrayList<>();
        for (Column c : columns) {
            seen.add(c.ordinal());
        }
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i) != i) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "TableMeta{" + id + "," + name + ",columns=" + columns.size() + "}";
    }
}
