package com.wangyu.tangdb.storage;

/**
 * P2 支持的列类型。code 是写进 {@code sys_columns.type} 的枚举值,一经定义不得复用。
 */
public enum DataType {
    INT(1, "INT"),
    BIGINT(2, "BIGINT"),
    DOUBLE(3, "DOUBLE"),
    VARCHAR(4, "VARCHAR"),
    DATE(5, "DATE"),
    BOOLEAN(6, "BOOLEAN");

    private final int code;
    private final String sqlName;

    DataType(int code, String sqlName) {
        this.code = code;
        this.sqlName = sqlName;
    }

    public int code() {
        return code;
    }

    public String sqlName() {
        return sqlName;
    }

    public boolean isNumeric() {
        return this == INT || this == BIGINT || this == DOUBLE;
    }

    /** 能否作为主键(规约 K16:只有这四种能写出严格保序编码)。 */
    public boolean canBePrimaryKey() {
        return this == INT || this == BIGINT || this == VARCHAR || this == DATE;
    }

    public static DataType fromCode(int code) {
        for (DataType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new StoreException("未知的列类型枚举值: " + code);
    }

    /** 解析类型名(大小写不敏感);无法识别返回 null。 */
    public static DataType parse(String name) {
        for (DataType t : values()) {
            if (t.sqlName.equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }
}
