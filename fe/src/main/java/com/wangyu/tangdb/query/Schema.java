package com.wangyu.tangdb.query;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询中间结果的列名表(SPEC-P5 B 组)。
 *
 * <p>每列 = (限定名, 列名)。限定名是表别名或表名,用于解析 {@code t.col}。</p>
 */
public final class Schema {

    private final List<String> qualifiers;
    private final List<String> names;
    /** 列类型(可为 null:表达式结果没有静态类型)。 */
    private final List<DataType> types;

    private Schema(List<String> qualifiers, List<String> names, List<DataType> types) {
        // 限定名可以为 null(表达式投影没有表别名),所以不用 List.copyOf。
        this.qualifiers = java.util.Collections.unmodifiableList(new ArrayList<>(qualifiers));
        this.names = java.util.Collections.unmodifiableList(new ArrayList<>(names));
        this.types = java.util.Collections.unmodifiableList(new ArrayList<>(types));
    }

    public static Schema of(List<String> qualifiers, List<String> names) {
        if (qualifiers.size() != names.size()) {
            throw new IllegalArgumentException("schema 限定名与列名数量不一致");
        }
        List<DataType> types = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            types.add(null);
        }
        return new Schema(qualifiers, names, types);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以表定义构造 schema;限定名默认用表名(别名在外层替换)。 */
    public static Schema ofTable(String qualifier, List<Column> columns) {
        List<String> quals = new ArrayList<>(columns.size());
        List<String> names = new ArrayList<>(columns.size());
        List<DataType> types = new ArrayList<>(columns.size());
        for (Column column : columns) {
            quals.add(qualifier);
            names.add(column.name());
            types.add(column.type());
        }
        return new Schema(quals, names, types);
    }

    /** 重写全部限定名(给表别名用)。 */
    public Schema withQualifier(String qualifier) {
        List<String> quals = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            quals.add(qualifier);
        }
        return new Schema(quals, names, types);
    }

    /** 追加列(投影 / 聚合结果)。 */
    public Schema append(String qualifier, String name) {
        return append(qualifier, name, null);
    }

    public Schema append(String qualifier, String name, DataType type) {
        List<String> quals = new ArrayList<>(qualifiers);
        List<String> newNames = new ArrayList<>(names);
        List<DataType> newTypes = new ArrayList<>(types);
        quals.add(qualifier);
        newNames.add(name);
        newTypes.add(type);
        return new Schema(quals, newNames, newTypes);
    }

    public Schema concat(Schema other) {
        List<String> quals = new ArrayList<>(qualifiers);
        List<String> newNames = new ArrayList<>(names);
        List<DataType> newTypes = new ArrayList<>(types);
        quals.addAll(other.qualifiers);
        newNames.addAll(other.names);
        newTypes.addAll(other.types);
        return new Schema(quals, newNames, newTypes);
    }

    /** 某列的类型;未知(表达式结果)返回 null。 */
    public DataType type(int i) {
        return types.get(i);
    }

    public int size() {
        return names.size();
    }

    public String name(int i) {
        return names.get(i);
    }

    public String qualifier(int i) {
        return qualifiers.get(i);
    }

    /**
     * 解析列引用:未限定按名字匹配;限定按 (限定名, 列名) 匹配。
     * 找不到报 1054,多个候选报 1052。
     */
    public int resolve(String qualifier, String name) {
        if (name == null) {
            throw new SqlError.BadField(qualifier, null, "列名不能为空");
        }
        int found = -1;
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).equalsIgnoreCase(name)) {
                continue;
            }
            if (qualifier != null && !qualifier.equalsIgnoreCase(qualifiers.get(i))) {
                continue;
            }
            if (found >= 0) {
                throw new SqlError(SqlError.ER_NON_UNIQ_ERROR, "23000",
                        "列 " + name + " 不明确(可加表别名限定)");
            }
            found = i;
        }
        if (found < 0) {
            String label = qualifier == null ? name : qualifier + "." + name;
            throw new SqlError.BadField(qualifier, name, "列 " + label + " 不存在");
        }
        return found;
    }

    public boolean has(String qualifier, String name) {
        try {
            resolve(qualifier, name);
            return true;
        } catch (SqlError e) {
            return false;
        }
    }

    /** 输出列名(K107:有别名用别名,否则用表达式原文)。 */
    public List<String> names() {
        return names;
    }

    /** 可变 builder(仅构造期使用)。 */
    public static final class Builder {
        private final List<String> qualifiers = new ArrayList<>();
        private final List<String> names = new ArrayList<>();

        private final List<DataType> types = new ArrayList<>();

        public Builder add(String qualifier, String name) {
            return add(qualifier, name, null);
        }

        public Builder add(String qualifier, String name, DataType type) {
            qualifiers.add(qualifier);
            names.add(name);
            types.add(type);
            return this;
        }

        public Schema build() {
            return new Schema(qualifiers, names, types);
        }
    }
}
