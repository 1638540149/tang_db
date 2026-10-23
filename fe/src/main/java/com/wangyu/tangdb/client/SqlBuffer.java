package com.wangyu.tangdb.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 多行 SQL 缓冲:语句以分号结束;分号出现在字符串字面量或反引号标识符里不算结束;
 * {@code --} 之后到行尾是注释(FR-170 / FR-171 / K30 / FR-187)。
 */
public final class SqlBuffer {

    private final StringBuilder buf = new StringBuilder();
    private boolean inString;
    /** 是否处于反引号标识符内部(FR-187:与单引号同级跟踪)。 */
    private boolean inQuotedIdent;

    /**
     * 追加一行输入,返回本次凑齐的完整语句(可能多条,也可能为空)。
     * 返回的语句已去掉结尾分号。
     */
    public List<String> append(String line) {
        List<String> completed = new ArrayList<>();
        if (line == null) {
            return completed;
        }
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inString) {
                buf.append(c);
                if (c == '\'') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                        buf.append('\'');
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
                i++;
                continue;
            }
            if (inQuotedIdent) {
                buf.append(c);
                if (c == '`') {
                    // 双写反引号表示一个反引号,不结束标识符(FR-184)
                    if (i + 1 < line.length() && line.charAt(i + 1) == '`') {
                        buf.append('`');
                        i += 2;
                        continue;
                    }
                    inQuotedIdent = false;
                }
                i++;
                continue;
            }
            if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                break; // 行注释,丢弃本行剩余内容
            }
            if (c == '\'') {
                inString = true;
                buf.append(c);
                i++;
                continue;
            }
            if (c == '`') {
                inQuotedIdent = true;
                buf.append(c);
                i++;
                continue;
            }
            if (c == ';') {
                addStatement(completed);
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        if (inString || inQuotedIdent) {
            buf.append('\n');
        } else {
            buf.append(' ');
        }
        return completed;
    }

    private void addStatement(List<String> completed) {
        String statement = buf.toString().trim();
        buf.setLength(0);
        if (!statement.isEmpty()) {
            completed.add(statement);
        }
    }

    /** 是否还有未结束的语句(用于切换续行提示符)。 */
    public boolean hasPending() {
        return !buf.toString().isBlank();
    }

    /** 当前未结束的语句内容(用于退出提示,不做语法分析)。 */
    public String pending() {
        return buf.toString().trim();
    }

    /** 放弃当前未结束的语句。 */
    public void reset() {
        buf.setLength(0);
        inString = false;
        inQuotedIdent = false;
    }
}
