package com.wangyu.tangdb.client;

import com.wangyu.tangdb.storage.Values;

import java.util.List;

/**
 * 结果表格化:列宽自适应(CJK 按 2 个宽度计算)+ 行数统计(FR-174 / FR-146)。
 */
public final class TableFormatter {

    private TableFormatter() {
    }

    public static String format(List<String> columns, List<Object[]> rows) {
        int n = columns.size();
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            widths[i] = displayWidth(columns.get(i));
        }
        String[][] cells = new String[rows.size()][n];
        for (int r = 0; r < rows.size(); r++) {
            Object[] row = rows.get(r);
            for (int i = 0; i < n; i++) {
                Object value = i < row.length ? row[i] : null;
                cells[r][i] = Values.display(value);
                widths[i] = Math.max(widths[i], displayWidth(cells[r][i]));
            }
        }

        StringBuilder sb = new StringBuilder();
        appendBorder(sb, widths);
        appendRow(sb, columns.toArray(new String[0]), widths);
        appendBorder(sb, widths);
        for (String[] row : cells) {
            appendRow(sb, row, widths);
        }
        appendBorder(sb, widths);
        sb.append('(').append(rows.size()).append(rows.size() == 1 ? " row)" : " rows)").append('\n');
        return sb.toString();
    }

    private static void appendBorder(StringBuilder sb, int[] widths) {
        sb.append('+');
        for (int width : widths) {
            sb.append("-".repeat(width + 2)).append('+');
        }
        sb.append('\n');
    }

    private static void appendRow(StringBuilder sb, String[] cells, int[] widths) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.length ? cells[i] : "";
            sb.append(' ').append(cell);
            sb.append(" ".repeat(widths[i] - displayWidth(cell)));
            sb.append(' ').append('|');
        }
        sb.append('\n');
    }

    /** 终端里一个字符占几列:CJK / 全角字符算 2。 */
    static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
