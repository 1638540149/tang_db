package com.wangyu.tangdb.storage;

/** 存储层/编解码错误。message 是可直接展示给用户的中文。 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
