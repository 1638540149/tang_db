package com.wangyu.tangdb.backup;

import java.util.List;

/**
 * FE → BE 的备份通道(SPEC-P8 D 组)。由 {@code TangDbClient} 实现(走 gRPC),测试可注入假实现。
 *
 * <p>契约里只有目录与数字:BE 不理解表/列/SQL(边界铁律)。</p>
 */
public interface BackupChannel {

    /** 启动一次后台备份,返回 BE 侧任务 id。 */
    String startBackup(String backupDir, String incrementalFrom, long snapshotRows, boolean force);

    /** 查任务;{@code jobId} 为空表示全部。BE 不认识该 id 时返回空列表(重启后)。 */
    List<RemoteJob> remoteJobs(String jobId);

    /** 同步恢复(文件复制 + 校验和);失败抛异常。 */
    RestoreOutcome restore(String sourceDir, String targetDir, boolean force);

    /** BE 侧任务状态。 */
    record RemoteJob(String jobId, String backupDir, String status, long copiedBytes,
                     long totalBytes, String message, boolean incremental,
                     long startedMillis, long finishedMillis) {
    }

    record RestoreOutcome(long files, long bytes, String message) {
    }
}
