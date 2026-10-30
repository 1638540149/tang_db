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
    BOOLEAN(6, "BOOLEAN"),
    /** P5:BSON 式二进制 JSON(FR-609);不能做主键 / 索引(FR-611)。 */
    JSON(7, "JSON"),
    /**
     * P7:定长 float32 向量(SPEC-P7 FR-731 / K157 / K158)。
     *
     * <p>{@code length} 存的是维度 n(1~4,096),行内定长 4×n 字节小端 float32。
     * 不能做主键、不能建普通索引(报 1235,K172);只允许距离函数参与比较(FR-734)。</p>
     */
    VECTOR(8, "VECTOR");

    /** 向量维度上界(K158):4,096 × 4B = 16KB/行。 */
    public static final int MAX_VECTOR_DIM = 4096;

    public boolean isVector() {
        return this == VECTOR;
    }

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
