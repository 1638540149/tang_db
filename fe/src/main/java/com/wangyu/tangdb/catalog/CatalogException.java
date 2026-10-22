package com.wangyu.tangdb.catalog;

/** catalog 加载/校验/DDL 错误。message 是可直接展示的中文。 */
public class CatalogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
