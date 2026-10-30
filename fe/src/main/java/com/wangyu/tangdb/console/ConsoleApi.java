package com.wangyu.tangdb.console;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * 控制台后端接口(SPEC-P8 FR-853 ~ FR-860)。
 *
 * <p>全部请求最终都走 {@code SqlEngine} 这一个 SQL 入口(FR-861),不新增第二个执行路径;
 * 控制台会话登记在独立的会话表里(FR-862),不占用 MySQL 连接额度。</p>
 */
public interface ConsoleApi {

    /** @return true = 已处理;false = 未知接口(回 404) */
    boolean handle(HttpExchange exchange) throws IOException;

    /** 极简 JSON 字符串转义(手写序列化,零第三方库)。 */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
