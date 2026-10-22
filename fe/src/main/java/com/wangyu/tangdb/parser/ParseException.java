package com.wangyu.tangdb.parser;

/** SQL 语法错误。message 自带位置,可直接展示给用户。 */
public class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
