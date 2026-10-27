package com.wangyu.tangdb.query;

import java.util.Arrays;

/**
 * 向量化执行的基本单位(SPEC-P5 FR-571 / §5.2):
 * {@code {列数, 行数, 每列 values 数组 + validity 位图}}。
 *
 * <p>validity 用 {@code boolean[]}(true = 良,false = NULL);所有算子只对 NULL 做
 * 位图运算,不按行判断(FR-573)。</p>
 */
public final class Batch {

    public final Schema schema;
    public final int rows;
    /** cols[column][row] */
    public final Object[][] cols;
    /** valid[column][row] */
    public final boolean[][] valid;

    public Batch(Schema schema, int rows, Object[][] cols, boolean[][] valid) {
        this.schema = schema;
        this.rows = rows;
        this.cols = cols;
        this.valid = valid;
    }

    public int columns() {
        return cols.length;
    }

    /** 取某列为 (values, valid) 二元组。 */
    public Column column(int index) {
        return new Column(cols[index], valid[index]);
    }

    /** 一列的 values + validity。 */
    public record Column(Object[] values, boolean[] valid) {
        public Object get(int row) {
            return valid[row] ? values[row] : null;
        }
    }

    /** 由行列表构造一个 batch(scan 算子用)。 */
    public static Batch fromRows(Schema schema, java.util.List<Object[]> rows) {
        int n = schema.size();
        Object[][] cols = new Object[n][rows.size()];
        boolean[][] valid = new boolean[n][rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            Object[] row = rows.get(r);
            for (int c = 0; c < n; c++) {
                Object v = c < row.length ? row[c] : null;
                cols[c][r] = v;
                valid[c][r] = v != null;
            }
        }
        return new Batch(schema, rows.size(), cols, valid);
    }

    /** 把 batch 还原成行列表。 */
    public java.util.List<Object[]> toRows() {
        java.util.List<Object[]> out = new java.util.ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            Object[] row = new Object[cols.length];
            for (int c = 0; c < cols.length; c++) {
                row[c] = valid[c][r] ? cols[c][r] : null;
            }
            out.add(row);
        }
        return out;
    }

    /** 追加投影列(FILTER 后构造新 batch 用)。 */
    public Batch project(Object[][] newCols, boolean[][] newValid) {
        return new Batch(schema, rows, newCols, newValid);
    }

    /** 按行号挑选子集(Filter 用)。 */
    public Batch select(boolean[] keep) {
        int n = 0;
        for (boolean k : keep) {
            if (k) {
                n++;
            }
        }
        Object[][] newCols = new Object[cols.length][n];
        boolean[][] newValid = new boolean[cols.length][n];
        int out = 0;
        for (int r = 0; r < rows; r++) {
            if (!keep[r]) {
                continue;
            }
            for (int c = 0; c < cols.length; c++) {
                newCols[c][out] = cols[c][r];
                newValid[c][out] = valid[c][r];
            }
            out++;
        }
        return new Batch(schema, n, newCols, newValid);
    }

    @Override
    public String toString() {
        return "Batch{rows=" + rows + ", cols=" + cols.length + "}";
    }

    static boolean[] allValid(int n) {
        boolean[] v = new boolean[n];
        Arrays.fill(v, true);
        return v;
    }
}
