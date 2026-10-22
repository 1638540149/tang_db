package com.wangyu.tangdb.parser;

/**
 * 一个词法单元。line/column 从 1 开始,用于语法报错定位。
 */
public record Token(TokenType type, String text, int line, int column) {

    @Override
    public String toString() {
        return type + "(" + text + ")@" + line + ":" + column;
    }
}
