package com.wangyu.tangdb.server;

/** FE 启动失败(端口占用等)。message 可直接展示给用户。 */
public class FeStartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FeStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
