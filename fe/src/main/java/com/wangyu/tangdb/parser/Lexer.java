package com.wangyu.tangdb.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写词法分析器(P2 规约 D2:不引入任何第三方 SQL 解析库)。
 *
 * <p>支持:标识符、反引号标识符、整数/小数、单引号字符串(内部 {@code ''} 表示一个单引号)、
 * 运算符 {@code = != <> < <= > >=}、标点、以及 {@code --} 行注释。</p>
 */
public final class Lexer {

    private final String src;
    private int pos;
    private int line = 1;
    private int column = 1;

    public Lexer(String src) {
        this.src = src == null ? "" : src;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                advance();
                continue;
            }
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            // -- 行注释,直到行尾
            if (c == '-' && peek(1) == '-') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    advance();
                }
                continue;
            }
            int startLine = line;
            int startCol = column;
            switch (c) {
                case '(' -> {
                    advance();
                    tokens.add(new Token(TokenType.LPAREN, "(", startLine, startCol));
                }
                case ')' -> {
                    advance();
                    tokens.add(new Token(TokenType.RPAREN, ")", startLine, startCol));
                }
                case ',' -> {
                    advance();
                    tokens.add(new Token(TokenType.COMMA, ",", startLine, startCol));
                }
                case ';' -> {
                    advance();
                    tokens.add(new Token(TokenType.SEMICOLON, ";", startLine, startCol));
                }
                case '*' -> {
                    advance();
                    tokens.add(new Token(TokenType.STAR, "*", startLine, startCol));
                }
                case '-' -> {
                    advance();
                    tokens.add(new Token(TokenType.MINUS, "-", startLine, startCol));
                }
                case '=' -> {
                    advance();
                    tokens.add(new Token(TokenType.EQ, "=", startLine, startCol));
                }
                case '!' -> {
                    if (peek(1) == '=') {
                        advance();
                        advance();
                        tokens.add(new Token(TokenType.NEQ, "!=", startLine, startCol));
                    } else {
                        throw error(startLine, startCol, "无法识别的字符 '!'");
                    }
                }
                case '<' -> {
                    if (peek(1) == '=') {
                        advance();
                        advance();
                        tokens.add(new Token(TokenType.LTE, "<=", startLine, startCol));
                    } else if (peek(1) == '>') {
                        advance();
                        advance();
                        tokens.add(new Token(TokenType.NEQ, "<>", startLine, startCol));
                    } else {
                        advance();
                        tokens.add(new Token(TokenType.LT, "<", startLine, startCol));
                    }
                }
                case '>' -> {
                    if (peek(1) == '=') {
                        advance();
                        advance();
                        tokens.add(new Token(TokenType.GTE, ">=", startLine, startCol));
                    } else {
                        advance();
                        tokens.add(new Token(TokenType.GT, ">", startLine, startCol));
                    }
                }
                case '\'' -> tokens.add(readString(startLine, startCol));
                case '`' -> tokens.add(readQuotedIdent(startLine, startCol));
                default -> {
                    if (Character.isDigit(c)) {
                        tokens.add(readNumber(startLine, startCol));
                    } else if (isIdentStart(c)) {
                        tokens.add(readIdent(startLine, startCol));
                    } else {
                        throw error(startLine, startCol, "无法识别的字符 '" + c + "'");
                    }
                }
            }
        }
        tokens.add(new Token(TokenType.EOF, "<结束>", line, column));
        return tokens;
    }

    private Token readString(int startLine, int startCol) {
        advance(); // 跳过开头的 '
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\'') {
                if (peek(1) == '\'') {
                    sb.append('\'');
                    advance();
                    advance();
                    continue;
                }
                advance();
                return new Token(TokenType.STRING, sb.toString(), startLine, startCol);
            }
            sb.append(c);
            advance();
        }
        throw error(startLine, startCol, "字符串缺少结束的单引号");
    }

    /**
     * 读取用反引号包裹的标识符(FR-180 ~ FR-185)。
     *
     * <p>反引号内除反引号本身外任意字符都合法(含空格、中划线、中文、数字开头);
     * 双写反引号表示一个反引号;未闭合与空名字各有明确报错,不再报"无法识别的字符"。</p>
     */
    private Token readQuotedIdent(int startLine, int startCol) {
        advance(); // 跳过开头的 `
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '`') {
                if (peek(1) == '`') {
                    sb.append('`');
                    advance();
                    advance();
                    continue;
                }
                advance();
                if (sb.length() == 0) {
                    throw error(startLine, startCol, "标识符不能为空");
                }
                return new Token(TokenType.QUOTED_IDENT, sb.toString(), startLine, startCol);
            }
            sb.append(c);
            advance();
        }
        throw error(startLine, startCol, "标识符缺少结束的反引号");
    }

    private Token readNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            sb.append(src.charAt(pos));
            advance();
        }
        TokenType type = TokenType.INTEGER;
        if (pos < src.length() && src.charAt(pos) == '.' && Character.isDigit(peek(1))) {
            type = TokenType.DECIMAL;
            sb.append('.');
            advance();
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                sb.append(src.charAt(pos));
                advance();
            }
        }
        return new Token(type, sb.toString(), startLine, startCol);
    }

    private Token readIdent(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            sb.append(src.charAt(pos));
            advance();
        }
        return new Token(TokenType.IDENT, sb.toString(), startLine, startCol);
    }

    private char peek(int offset) {
        int i = pos + offset;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void advance() {
        if (pos < src.length()) {
            if (src.charAt(pos) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            pos++;
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static ParseException error(int line, int column, String message) {
        return new ParseException("语法错误: 第 " + line + " 行第 " + column + " 列 " + message, line, column);
    }
}
