package com.wangyu.tangdb.parser;

/** 词法单元类型。 */
public enum TokenType {
    IDENT,
    INTEGER,
    DECIMAL,
    STRING,
    LPAREN,
    RPAREN,
    COMMA,
    SEMICOLON,
    STAR,
    MINUS,
    EQ,
    NEQ,
    LT,
    LTE,
    GT,
    GTE,
    EOF
}
