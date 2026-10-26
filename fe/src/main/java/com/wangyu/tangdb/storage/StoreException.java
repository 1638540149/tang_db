package com.wangyu.tangdb.storage;

/** 存储层/编解码错误。message 是可直接展示给用户的中文。 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** MySQL 错误码(SPEC-P3.5 §5.3);0 表示未指定。 */
    private final int code;

    public StoreException(String message) {
        this(message, 0, null);
    }

    public StoreException(String message, int code) {
        this(message, code, null);
    }

    public StoreException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public StoreException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
