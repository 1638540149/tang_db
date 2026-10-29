package com.wangyu.tangdb.storage;

/**
 * 一次扫描的下推参数(SPEC-P6.1 §5.1 / FR-720)。
 *
 * <p>只含"列序号 / 偏移 / 操作码 / 字节字面量",不含表名/列名/类型名 ——
 * 与 {@code proto/tangdb.proto} 的字段一一对应。</p>
 */
public record ScanOptions(int batchRows, int columnMask, byte[] columnSlots, byte[] rowFrame,
                          byte[] predicate, boolean columnar) {

    public ScanOptions {
        if (batchRows < 0) {
            batchRows = 0;
        }
        columnSlots = columnSlots == null ? new byte[0] : columnSlots;
        rowFrame = rowFrame == null ? new byte[0] : rowFrame;
        predicate = predicate == null ? new byte[0] : predicate;
    }

    /** 全行、无谓词的老路径参数。 */
    public static ScanOptions plain(int batchRows) {
        return new ScanOptions(batchRows, 0, null, null, null, false);
    }
}
