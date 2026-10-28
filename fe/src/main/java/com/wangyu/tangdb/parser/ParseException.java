package com.wangyu.tangdb.parser;

/**
 * SQL 语法错误。message 自带位置,可直接展示给用户。
 *
 * <p>P6 起支持携带 MySQL 错误码:语法错仍是 1064(码 0),而"语法合法但本期明确不支持"的
 * 形态(如派生表缺别名 1248、`INTERSECT ALL` 1235)在解析期就能给出准确码与文案(FR-670)。</p>
 */
public class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;
    private final int code;

    public ParseException(String message, int line, int column) {
        this(message, line, column, 0);
    }

    public ParseException(String message, int line, int column, int code) {
        super(message);
        this.line = line;
        this.column = column;
        this.code = code;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /** MySQL 错误码;0 表示语法错(由上层映射成 1064)。 */
    public int code() {
        return code;
    }
}
