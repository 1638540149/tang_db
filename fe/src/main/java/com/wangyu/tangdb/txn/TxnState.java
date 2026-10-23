package com.wangyu.tangdb.txn;

/** 会话内事务状态机:同一时刻最多一个活动事务(SPEC-P3 FR-341)。 */
public enum TxnState {
    IDLE,
    ACTIVE
}
