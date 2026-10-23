package com.wangyu.tangdb.storage;

/**
 * 写写冲突(SPEC-P3 FR-329 / FR-359)。
 *
 * <p>由 BE 的 CONFLICT 状态翻译而来;{@code conflictKey} 是 BE 回的**用户键原始字节**,
 * 执行器负责把它翻译成"表 t 主键 x 写写冲突"。message 本身已经是可以直接展示的中文。</p>
 */
public class ConflictException extends StoreException {

    private static final long serialVersionUID = 1L;

    private final byte[] conflictKey;

    public ConflictException(String message, byte[] conflictKey) {
        super(message);
        this.conflictKey = conflictKey == null ? null : conflictKey.clone();
    }

    /** @return 冲突的用户键原始字节(可能为 null) */
    public byte[] conflictKey() {
        return conflictKey == null ? null : conflictKey.clone();
    }
}
