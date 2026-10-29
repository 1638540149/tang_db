package com.wangyu.tangdb.storage;

/**
 * 支持"分批扫描游标"的存储(SPEC-P6.1 FR-715)。
 *
 * <p>真实客户端({@code TangDbClient})实现它,把 BE 的 server-streaming 扫描暴露成
 * 可逐批消费、可取消的 {@link ScanCursor};内存实现 / 事务视图没有游标能力,
 * 调用方退回一次扫描的物化路径。</p>
 */
public interface CursoredStore {

    /**
     * 打开一个分批扫描游标。
     *
     * @param start    起始用户键(含)
     * @param end      结束用户键(不含);null / 空表示到末尾
     * @param snapshot 快照号;0 = 最新已提交
     * @param limit    行数上限;0 = 不限
     * @param options  列投影 / 谓词下推参数;null 表示逐 KV 全行
     */
    ScanCursor openScan(byte[] start, byte[] end, long snapshot, int limit, ScanOptions options);
}
