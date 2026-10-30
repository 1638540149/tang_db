package backup

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/1638540149/tang_db/be/internal/store"
)

func openEngine(t *testing.T, dir string) *store.Engine {
	t.Helper()
	engine, _, err := store.OpenWithOptions(dir, store.Options{MemtableSize: 1 << 20})
	if err != nil {
		t.Fatalf("打开存储引擎失败: %v", err)
	}
	return engine
}

func putKeys(t *testing.T, engine *store.Engine, from, to int) {
	t.Helper()
	for i := from; i < to; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		if err := engine.Put(key, []byte(fmt.Sprintf("value-%04d-%s", i, strings.Repeat("x", 200)))); err != nil {
			t.Fatalf("写入失败: %v", err)
		}
	}
}

func waitJob(t *testing.T, manager *Manager, id string) Job {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		job, ok := manager.Job(id)
		if ok && job.Status != StatusRunning {
			return job
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("备份任务超时未结束: %s", id)
	return Job{}
}

func TestBackupRestoreRoundTrip(t *testing.T) {
	root := t.TempDir()
	dataDir := filepath.Join(root, "data")
	engine := openEngine(t, dataDir)
	defer engine.Close()
	putKeys(t, engine, 0, 200)

	manager := NewManager(engine)
	backupDir := filepath.Join(root, "bk-full")
	job, err := manager.Start(backupDir, "", 200, false)
	if err != nil {
		t.Fatalf("启动备份失败: %v", err)
	}
	job = waitJob(t, manager, job.ID)
	if job.Status != StatusSuccess {
		t.Fatalf("备份失败: %s %s", job.Status, job.Message)
	}

	meta, err := ReadMeta(backupDir)
	if err != nil {
		t.Fatalf("读取 BACKUP_META 失败: %v", err)
	}
	if meta.FormatVersion != 3 {
		t.Fatalf("format_version 应为 3,实际 %d", meta.FormatVersion)
	}
	if len(meta.Files) == 0 {
		t.Fatal("BACKUP_META 文件清单为空")
	}
	for _, entry := range meta.Files {
		if entry.SHA256 == "" {
			t.Fatalf("文件 %s 缺少校验和", entry.Path)
		}
		if _, err := os.Stat(filepath.Join(backupDir, entry.Path)); err != nil {
			t.Fatalf("清单里的文件不存在: %s", entry.Path)
		}
	}
	for _, sub := range []string{"manifest", "sst", "wal"} {
		if _, err := os.Stat(filepath.Join(backupDir, sub)); err != nil {
			t.Fatalf("备份目录缺少子目录 %s: %v", sub, err)
		}
	}

	restored := filepath.Join(root, "restored")
	result, err := manager.Restore(backupDir, restored, false)
	if err != nil {
		t.Fatalf("恢复失败: %v", err)
	}
	if result.Files == 0 {
		t.Fatal("恢复的文件数为 0")
	}

	restoredEngine := openEngine(t, restored)
	defer restoredEngine.Close()
	for i := 0; i < 200; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value, ok, err := restoredEngine.Get(key)
		if err != nil || !ok {
			t.Fatalf("恢复后读不到 key %s: ok=%v err=%v", key, ok, err)
		}
		if string(value) != fmt.Sprintf("value-%04d-%s", i, strings.Repeat("x", 200)) {
			t.Fatalf("恢复后值不一致: %s", value)
		}
	}
}

func TestIncrementalBackupIsSmallerAndRestorable(t *testing.T) {
	root := t.TempDir()
	engine := openEngine(t, filepath.Join(root, "data"))
	defer engine.Close()
	putKeys(t, engine, 0, 2000)

	manager := NewManager(engine)
	full := filepath.Join(root, "bk-full")
	job, err := manager.Start(full, "", 2000, false)
	if err != nil {
		t.Fatalf("全量备份启动失败: %v", err)
	}
	if job = waitJob(t, manager, job.ID); job.Status != StatusSuccess {
		t.Fatalf("全量备份失败: %s", job.Message)
	}

	putKeys(t, engine, 2000, 2100)
	if err := engine.ForceFlushAll(); err != nil {
		t.Fatalf("刷盘失败: %v", err)
	}

	inc := filepath.Join(root, "bk-inc")
	job, err = manager.Start(inc, full, 2100, false)
	if err != nil {
		t.Fatalf("增量备份启动失败: %v", err)
	}
	if job = waitJob(t, manager, job.ID); job.Status != StatusSuccess {
		t.Fatalf("增量备份失败: %s", job.Message)
	}

	fullMeta, err := ReadMeta(full)
	if err != nil {
		t.Fatal(err)
	}
	incMeta, err := ReadMeta(inc)
	if err != nil {
		t.Fatal(err)
	}
	if !incMeta.Incremental {
		t.Fatal("增量备份的 BACKUP_META 应标记 incremental=true")
	}
	if len(incMeta.Inherited) == 0 {
		t.Fatal("增量备份应继承父备份的线段文件")
	}
	if incMeta.CopiedBytes >= fullMeta.CopiedBytes {
		t.Fatalf("增量备份字节数应小于全量: inc=%d full=%d",
			incMeta.CopiedBytes, fullMeta.CopiedBytes)
	}

	restored := filepath.Join(root, "restored-inc")
	if _, err := manager.Restore(inc, restored, false); err != nil {
		t.Fatalf("增量备份恢复失败: %v", err)
	}
	restoredEngine := openEngine(t, restored)
	defer restoredEngine.Close()
	for i := 0; i < 2100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		if _, ok, err := restoredEngine.Get(key); err != nil || !ok {
			t.Fatalf("增量恢复后读不到 key %s (ok=%v err=%v)", key, ok, err)
		}
	}
}

func TestIncompleteBackupIsRejectedByRestore(t *testing.T) {
	root := t.TempDir()
	engine := openEngine(t, filepath.Join(root, "data"))
	defer engine.Close()
	putKeys(t, engine, 0, 20)

	manager := NewManager(engine)
	broken := filepath.Join(root, "bk-broken")
	if err := os.MkdirAll(filepath.Join(broken, "sst"), 0o750); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(broken, filepath.Join(root, "target"), false); err == nil {
		t.Fatal("缺少 BACKUP_META 的备份必须被 RESTORE 拒绝(SC-452)")
	}
}

func TestRestoreTargetMustBeEmptyOrForce(t *testing.T) {
	root := t.TempDir()
	engine := openEngine(t, filepath.Join(root, "data"))
	defer engine.Close()
	putKeys(t, engine, 0, 20)

	manager := NewManager(engine)
	backupDir := filepath.Join(root, "bk")
	job, err := manager.Start(backupDir, "", 20, false)
	if err != nil {
		t.Fatal(err)
	}
	waitJob(t, manager, job.ID)

	target := filepath.Join(root, "target")
	if err := os.MkdirAll(target, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "junk.txt"), []byte("x"), 0o640); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(backupDir, target, false); err == nil {
		t.Fatal("目标非空且未 FORCE 时必须拒绝(边界 176/K194)")
	}
	if _, err := manager.Restore(backupDir, target, true); err != nil {
		t.Fatalf("FORCE 恢复应成功: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, "junk.txt")); !os.IsNotExist(err) {
		t.Fatal("FORCE 恢复后目标目录里不应残留旧文件")
	}
}

func TestBackupRefusesDataDirAndNonEmptyDir(t *testing.T) {
	root := t.TempDir()
	dataDir := filepath.Join(root, "data")
	engine := openEngine(t, dataDir)
	defer engine.Close()
	putKeys(t, engine, 0, 10)
	manager := NewManager(engine)

	if _, err := manager.Start(dataDir, "", 0, false); err == nil {
		t.Fatal("备份到正在使用的数据目录必须失败")
	}
	nonEmpty := filepath.Join(root, "nonempty")
	if err := os.MkdirAll(nonEmpty, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(nonEmpty, "x"), []byte("x"), 0o640); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Start(nonEmpty, "", 0, false); err == nil {
		t.Fatal("备份到非空目录且未 FORCE 必须失败(边界 176)")
	}
}

func TestBackupWritesMetaLast(t *testing.T) {
	root := t.TempDir()
	engine := openEngine(t, filepath.Join(root, "data"))
	defer engine.Close()
	putKeys(t, engine, 0, 50)
	manager := NewManager(engine)
	dir := filepath.Join(root, "bk")
	job, err := manager.Start(dir, "", 50, false)
	if err != nil {
		t.Fatal(err)
	}
	// 任务刚开始时 BACKUP_META 还不该存在(允许竞态:只在 RUNNING 阶段断言)
	if current, ok := manager.Job(job.ID); ok && current.Status == StatusRunning {
		if _, err := os.Stat(filepath.Join(dir, MetaName)); err == nil {
			t.Fatal("备份完成前不得写 BACKUP_META(FR-843)")
		}
	}
	waitJob(t, manager, job.ID)
	if _, err := os.Stat(filepath.Join(dir, MetaName)); err != nil {
		t.Fatalf("备份完成后必须有 BACKUP_META: %v", err)
	}
}
