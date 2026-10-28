package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.ParseException;
import com.wangyu.tangdb.storage.ConflictException;
import com.wangyu.tangdb.storage.StoreException;

/**
 * 面向 MySQL 协议的错误(SQLSTATE + 错误码),收口 SPEC-P3.5 §5.3 的全套映射。
 *
 * <p>协议层把任何执行失败都转成 {@link SqlError} 再编码成 ERR 包;CLI 只读 message。
 * 错误码常量命名沿用 MySQL 的 ER_* 符号,便于对照。</p>
 */
public class SqlError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------ SPEC-P3.5 §5.3
    public static final int ER_SYNTAX_ERROR = 1064;        // 语法错
    public static final int ER_NO_SUCH_TABLE = 1146;       // 表不存在
    public static final int ER_BAD_DB_ERROR = 1049;        // 库不存在
    public static final int ER_DB_CREATE_EXISTS = 1007;    // 库已存在
    public static final int ER_BAD_FIELD_ERROR = 1054;     // 未知列
    public static final int ER_DUP_ENTRY = 1062;           // 唯一 / 主键冲突
    public static final int ER_LOCK_DEADLOCK = 1213;       // 写写冲突(请重试)
    public static final int ER_ACCESS_DENIED_ERROR = 1045; // 认证失败
    public static final int ER_UNSUPPORTED_PS = 1295;      // 不支持的预编译命令
    public static final int ER_NOT_SUPPORTED_YET = 1235;   // 不支持的语句 / 命令
    public static final int ER_ROW_IS_REFERENCED_2 = 1451; // 库非空不可删
    public static final int ER_UNKNOWN_ERROR = 1105;       // 内部错误(兜底)

    // 未列进 §5.3 但语义明确的补充码。
    public static final int ER_TABLE_EXISTS_ERROR = 1050;  // 表已存在
    public static final int ER_DB_DROP_EXISTS = 1008;      // 库不存在(删除时)
    public static final int ER_DUP_KEYNAME = 1061;         // 索引名重复(SPEC-P4 FR-458)
    public static final int ER_CANT_DROP_FIELD_OR_KEY = 1091; // 索引不存在(SPEC-P4 FR-461)
    public static final int ER_NO_SUCH_THREAD = 1094;      // KILL 的会话 id 不存在(SPEC-P4 FR-541)
    public static final int ER_QUERY_INTERRUPTED = 1317;   // 会话已被 KILL

    // SPEC-P5 查询语义补充码(与 MySQL 同名同号)。
    public static final int ER_NON_UNIQ_ERROR = 1052;         // 列名不明确
    public static final int ER_WRONG_FIELD_WITH_GROUP = 1055; // 非分组非聚合列(FR-586)
    public static final int ER_MIX_OF_GROUP_FUNC_AND_FIELDS = 1140; // 聚合与普通列混用
    public static final int ER_WRONG_ARGUMENTS = 1210;        // 函数参数 / 类型不可比较
    public static final int ER_SUBQUERY_NO_1_ROW = 1242;      // 标量子查询返回多行(K108)
    public static final int ER_WRONG_VALUE_COUNT_ON_ROW = 1136; // INSERT 取值个数与列数不符
    /** 集合运算两支列数 / 类型无法对齐(SPEC-P6 FR-650)。 */
    public static final int ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT = 1222;

    private final int code;
    private final String sqlState;

    /**
     * 未知列(1054)。带上限定名与列名,便于 QueryEngine 判定"这是外层列引用"并转成相关子查询
     * (SPEC-P6 FR-656):普通 1054 与相关引用在错误码上相同,但语义完全不同。
     */
    public static final class BadField extends SqlError {
        private static final long serialVersionUID = 1L;
        private final String qualifier;
        private final String name;

        public BadField(String qualifier, String name, String message) {
            super(ER_BAD_FIELD_ERROR, "42S22", message);
            this.qualifier = qualifier;
            this.name = name;
        }

        public String qualifier() {
            return qualifier;
        }

        public String name() {
            return name;
        }
    }

    public SqlError(int code, String sqlState, String message) {
        super(message);
        this.code = code;
        this.sqlState = sqlState;
    }

    public int code() {
        return code;
    }

    public String sqlState() {
        return sqlState;
    }

    /** 把执行层抛出的各类异常映射成带错误码的 {@link SqlError}(FR-440)。 */
    public static SqlError from(Throwable t) {
        if (t instanceof SqlError se) {
            return se;
        }
        if (t instanceof ParseException pe) {
            if (pe.code() != 0) {
                return new SqlError(pe.code(), "42000", pe.getMessage());
            }
            return new SqlError(ER_SYNTAX_ERROR, "42000", t.getMessage());
        }
        if (t instanceof ConflictException) {
            return new SqlError(ER_LOCK_DEADLOCK, "40001", t.getMessage());
        }
        if (t instanceof CatalogException ce) {
            return new SqlError(ce.code() != 0 ? ce.code() : ER_UNKNOWN_ERROR,
                    sqlStateFor(ce.code() != 0 ? ce.code() : ER_UNKNOWN_ERROR), ce.getMessage());
        }
        if (t instanceof StoreException se) {
            return new SqlError(se.code() != 0 ? se.code() : ER_UNKNOWN_ERROR,
                    sqlStateFor(se.code() != 0 ? se.code() : ER_UNKNOWN_ERROR), se.getMessage());
        }
        String message = t.getMessage();
        return new SqlError(ER_UNKNOWN_ERROR, "HY000",
                message == null || message.isBlank() ? "内部错误" : message);
    }

    private static String sqlStateFor(int code) {
        return switch (code) {
            case ER_SYNTAX_ERROR -> "42000";
            case ER_NO_SUCH_TABLE -> "42S02";
            case ER_BAD_DB_ERROR -> "42000";
            case ER_DB_CREATE_EXISTS -> "HY000";
            case ER_BAD_FIELD_ERROR -> "42S22";
            case ER_DUP_ENTRY -> "23000";
            case ER_LOCK_DEADLOCK -> "40001";
            case ER_ACCESS_DENIED_ERROR -> "28000";
            case ER_UNSUPPORTED_PS -> "HY000";
            case ER_NOT_SUPPORTED_YET -> "42000";
            case ER_ROW_IS_REFERENCED_2 -> "23000";
            case ER_TABLE_EXISTS_ERROR -> "42S01";
            case ER_DB_DROP_EXISTS -> "HY000";
            case ER_DUP_KEYNAME -> "42000";
            case ER_CANT_DROP_FIELD_OR_KEY -> "42000";
            case ER_NO_SUCH_THREAD -> "HY000";
            case ER_QUERY_INTERRUPTED -> "70100";
            case ER_NON_UNIQ_ERROR -> "23000";
            case ER_WRONG_FIELD_WITH_GROUP -> "42000";
            case ER_MIX_OF_GROUP_FUNC_AND_FIELDS -> "42000";
            case ER_WRONG_ARGUMENTS -> "HY000";
            case ER_SUBQUERY_NO_1_ROW -> "21000";
            case ER_WRONG_VALUE_COUNT_ON_ROW -> "21S01";
            case ER_WRONG_NUMBER_OF_COLUMNS_IN_SELECT -> "21000";
            default -> "HY000";
        };
    }
}
