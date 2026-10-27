package com.wangyu.tangdb.parser;

/** 词法单元类型。 */
public enum TokenType {
    IDENT,
    /** 反引号包裹的标识符(FR-180):进入 AST 后与普通标识符无差别。 */
    QUOTED_IDENT,
    INTEGER,
    DECIMAL,
    STRING,
    LPAREN,
    RPAREN,
    COMMA,
    SEMICOLON,
    STAR,
    DOT,
    MINUS,
    PLUS,
    SLASH,
    PERCENT,
    EQ,
    NEQ,
    LT,
    LTE,
    GT,
    GTE,
    EOF
}
