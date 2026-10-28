package com.wangyu.tangdb.execute;

import com.wangyu.tangdb.storage.Column;

import java.util.List;

/**
 * 虚拟表访问接口(SPEC-P6 FR-667 / FR-668)。
 *
 * <p>{@code information_schema} 的虚拟表由 FE 内存直接构造(不访问 BE、不落盘);
 * 统一计划器只通过这个接口拿列定义与行,不理解它们的来源。估算用固定值,绝不为了估算去扫虚拟表。</p>
 */
public interface VirtualTables {

    /** 虚拟表的列定义(小写列名);表不存在返回 null。 */
    List<Column> columns(String tableName);

    /** 虚拟表的行(显示列名由列定义的大写形式给出)。 */
    List<Object[]> rows(String tableName);

    /** 固定行数估算(FR-668;不扫虚拟表)。 */
    long estimatedRows(String tableName);

    /** 空实现:没有虚拟表的环境(单测 / 内部调用)。 */
    VirtualTables NONE = new VirtualTables() {
        @Override
        public List<Column> columns(String tableName) {
            return null;
        }

        @Override
        public List<Object[]> rows(String tableName) {
            return List.of();
        }

        @Override
        public long estimatedRows(String tableName) {
            return 0;
        }
    };
}
