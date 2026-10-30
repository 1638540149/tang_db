package com.wangyu.tangdb.storage;

/**
 * 列定义。name 统一存小写(表名/列名大小写不敏感,K21)。
 *
 * @param ordinal 从 0 开始的列序号,决定行值里列值的排列顺序
 * @param length  VARCHAR 的最大字节数(K28)/ VECTOR 的维度(SPEC-P7 FR-731);其它类型为 0
 */
public record Column(String name, DataType type, int length, boolean nullable,
                     boolean primaryKey, int ordinal) {

    public Column {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        name = name.toLowerCase();
        if (type == DataType.VARCHAR && (length < 1 || length > 65535)) {
            throw new IllegalArgumentException("VARCHAR 长度必须在 1-65535 之间: " + name);
        }
        if (type == DataType.VECTOR
                && (length < 1 || length > DataType.MAX_VECTOR_DIM)) {
            throw new IllegalArgumentException("VECTOR 维度必须在 1-"
                    + DataType.MAX_VECTOR_DIM + " 之间: " + name);
        }
    }

    /** 形如 {@code VARCHAR(32)} / {@code VECTOR(128)} 的类型显示。 */
    public String typeName() {
        return switch (type) {
            case VARCHAR -> "VARCHAR(" + length + ")";
            case VECTOR -> "VECTOR(" + length + ")";
            default -> type.sqlName();
        };
    }

    /** 向量列的维度(其它类型返回 0)。 */
    public int vectorDim() {
        return type == DataType.VECTOR ? length : 0;
    }
}
