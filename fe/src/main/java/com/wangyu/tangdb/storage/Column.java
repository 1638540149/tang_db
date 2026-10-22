package com.wangyu.tangdb.storage;

/**
 * 列定义。name 统一存小写(表名/列名大小写不敏感,K21)。
 *
 * @param ordinal 从 0 开始的列序号,决定行值里列值的排列顺序
 * @param length  VARCHAR 的最大字节数(K28);其它类型为 0
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
    }

    /** 形如 {@code VARCHAR(32)} 的类型显示。 */
    public String typeName() {
        return type == DataType.VARCHAR ? "VARCHAR(" + length + ")" : type.sqlName();
    }
}
