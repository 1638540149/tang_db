package com.wangyu.tangdb.catalog;

/** catalog 加载/校验/DDL 错误。message 是可直接展示的中文。 */
public class CatalogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** MySQL 错误码(SPEC-P3.5 §5.3);0 表示未指定。 */
    private final int code;

    public CatalogException(String message) {
        this(message, 0, null);
    }

    public CatalogException(String message, int code) {
        this(message, code, null);
    }

    public CatalogException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public CatalogException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
