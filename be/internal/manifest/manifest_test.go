package manifest

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestEmptyDirWritesFormatVersion 空目录首次启动必须写入 format_version = 2。
func TestEmptyDirWritesFormatVersion(t *testing.T) {
	dir := t.TempDir()
	_, m, err := Open(dir)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if m.FormatVersion != FormatVersion {
		t.Fatalf("format_version = %d, want %d", m.FormatVersion, FormatVersion)
	}
	reopened, m2, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if reopened == nil || m2.FormatVersion != FormatVersion {
		t.Fatalf("reopen format_version = %d", m2.FormatVersion)
	}
}

// TestRejectMissingFormatVersion 老目录(MANIFEST 没有 format_version)必须拒绝启动。
func TestRejectMissingFormatVersion(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := Open(dir); err != nil {
		t.Fatalf("open: %v", err)
	}
	rewriteCurrentManifest(t, dir, func(s string) string {
		return strings.Replace(s, `"format_version": 2,`, "", 1)
	})
	_, _, err := Open(dir)
	if err == nil {
		t.Fatal("expected startup failure for manifest without format_version")
	}
	if !strings.Contains(err.Error(), "格式版本") || !strings.Contains(err.Error(), "不兼容") {
		t.Fatalf("error = %v, want format-version guard message", err)
	}
}

// TestRejectFormatVersionOne format_version = 1(P2 及以前)同样拒绝启动。
func TestRejectFormatVersionOne(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := Open(dir); err != nil {
		t.Fatalf("open: %v", err)
	}
	rewriteCurrentManifest(t, dir, func(s string) string {
		return strings.Replace(s, `"format_version": 2`, `"format_version": 1`, 1)
	})
	_, _, err := Open(dir)
	if err == nil {
		t.Fatal("expected startup failure for format_version = 1")
	}
	if !strings.Contains(err.Error(), "数据目录格式版本 1 与本期(2)不兼容") {
		t.Fatalf("error = %v, want FR-362 message", err)
	}
	if !strings.Contains(err.Error(), "请清空数据目录后重启") {
		t.Fatalf("error = %v, want clear-data-dir hint", err)
	}
}

// TestLastCommitTSRoundTrip last_commit_ts 必须持久化。
func TestLastCommitTSRoundTrip(t *testing.T) {
	dir := t.TempDir()
	reg, m, err := Open(dir)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	m.LastCommitTS = 99
	if err := reg.Save(m); err != nil {
		t.Fatalf("save: %v", err)
	}
	_, loaded, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if loaded.LastCommitTS != 99 {
		t.Fatalf("last_commit_ts = %d, want 99", loaded.LastCommitTS)
	}
}

// rewriteCurrentManifest 修改 CURRENT 指向的 MANIFEST 文件内容。
func rewriteCurrentManifest(t *testing.T, dir string, transform func(string) string) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "CURRENT"))
	if err != nil {
		t.Fatalf("read CURRENT: %v", err)
	}
	name := strings.TrimSpace(string(data))
	path := filepath.Join(dir, name)
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	if err := os.WriteFile(path, []byte(transform(string(body))), 0640); err != nil {
		t.Fatalf("write manifest: %v", err)
	}
}
