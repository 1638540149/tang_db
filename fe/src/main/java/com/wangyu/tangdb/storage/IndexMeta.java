package com.wangyu.tangdb.storage;

/**
 * 一条索引元数据(SPEC-P4 §5.4 / FR-464 ~ FR-471)。
 *
 * <p>索引 ID 每表独立、从 1 起(0 永远表示主键);索引名表内唯一;本期只支持单列索引。</p>
 */
public record IndexMeta(long tableId, int indexId, String name, String columnName,
                        boolean unique, int state, String buildCursor, String createdAt) {

    public static final int STATE_BUILDING = 0;
    public static final int STATE_ACTIVE = 1;
    public static final int STATE_DROPPED = 2;

    public IndexMeta {
        if (tableId <= 0) {
            throw new IllegalArgumentException("索引的表 ID 非法: " + tableId);
        }
        if (indexId <= 0) {
            throw new IllegalArgumentException("索引 ID 必须 ≥1(0 是主键): " + indexId);
        }
        if (name == null) {
            throw new IllegalArgumentException("索引名不能为空");
        }
        name = name.toLowerCase();
        if (columnName == null) {
            throw new IllegalArgumentException("索引列不能为空");
        }
        columnName = columnName.toLowerCase();
        if (state != STATE_BUILDING && state != STATE_ACTIVE && state != STATE_DROPPED) {
            throw new IllegalArgumentException("索引状态值非法: " + state);
        }
        buildCursor = buildCursor == null ? "" : buildCursor;
        createdAt = createdAt == null ? "" : createdAt;
    }

    /** 查询可用:只有 ACTIVE 参与索引选择(§5.6)。 */
    public boolean queryable() {
        return state == STATE_ACTIVE;
    }

    /** 写入维护:ACTIVE 与 BUILDING 都维护,只有 DROPPED 不维护(FR-491 / K89)。 */
    public boolean maintainedOnWrite() {
        return state == STATE_ACTIVE || state == STATE_BUILDING;
    }

    public boolean dropped() {
        return state == STATE_DROPPED;
    }

    public String stateName() {
        return switch (state) {
            case STATE_BUILDING -> "BUILDING";
            case STATE_ACTIVE -> "ACTIVE";
            default -> "DROPPED";
        };
    }

    public IndexMeta withState(int newState) {
        return new IndexMeta(tableId, indexId, name, columnName, unique, newState, buildCursor, createdAt);
    }

    public IndexMeta withBuildCursor(String cursor) {
        return new IndexMeta(tableId, indexId, name, columnName, unique, state, cursor, createdAt);
    }

    @Override
    public String toString() {
        return "IndexMeta{" + tableId + "/" + indexId + " " + name + "(" + columnName + ")"
                + (unique ? " UNIQUE" : "") + " " + stateName() + "}";
    }
}
