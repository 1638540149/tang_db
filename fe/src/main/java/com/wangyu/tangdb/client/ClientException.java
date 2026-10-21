package com.wangyu.tangdb.client;

/**
 * 客户端面向用户的受检异常。
 *
 * <p>{@code message} 是可以直接展示给用户的中文提示，不得包含 Java 异常堆栈；
 * {@code errorCode} 是可选错误码（如 {@code NOT_FOUND}、{@code INVALID_ARGUMENT}、
 * {@code UNAVAILABLE}），供调用方做程序化判断。</p>
 */
public class ClientException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public ClientException(String message) {
        this(message, null, null);
    }

    public ClientException(String message, String errorCode) {
        this(message, errorCode, null);
    }

    public ClientException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * @return 错误码；未设置时返回 {@code null}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
