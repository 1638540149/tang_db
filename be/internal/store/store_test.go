package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/1638540149/tang_db/be/internal/manifest"
)

func TestPutGet(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if err := e.Put([]byte("name"), []byte("wangyu")); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, ok, err := e.Get([]byte("name"))
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if !ok || !bytes.Equal(got, []byte("wangyu")) {
		t.Fatalf("get mismatch: %v %v", got, ok)
	}
}

func TestEmptyKey(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()
	if err := e.Put([]byte{}, []byte("v")); err == nil {
		t.Fatal("expected error for empty key")
	}
}

func TestRecovery(t *testing.T) {
	dir := t.TempDir()
	e1, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e1.Put(key, value); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
	}
	if err := e1.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	e2, stats, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	if stats.WALReplayed != 100 {
		t.Fatalf("wal replayed %d, want 100", stats.WALReplayed)
	}
	if stats.MaxCommitTS < 100 {
		t.Fatalf("max commit_ts = %d, want >= 100", stats.MaxCommitTS)
	}

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok, err := e2.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", i, err)
		}
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("get %d: %v %v, want %v", i, got, ok, expected)
		}
	}
}

func TestRecoveryWithTruncatedWAL(t *testing.T) {
	dir := t.TempDir()
	e1, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 10; i++ {
		key := []byte(fmt.Sprintf("key-%d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e1.Put(key, value); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	if err := e1.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	// 截断 WAL 尾部。
	walPath := filepath.Join(dir, "wal", WalName(1))
	info, _ := os.Stat(walPath)
	f, _ := os.OpenFile(walPath, os.O_RDWR, 0)
	f.Truncate(info.Size() - 3)
	f.Close()

	e2, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("reopen after truncate: %v", err)
	}
	defer e2.Close()

	// 至少前几条能恢复。
	found := 0
	for i := 0; i < 10; i++ {
		key := []byte(fmt.Sprintf("key-%d", i))
		if _, ok, _ := e2.Get(key); ok {
			found++
		}
	}
	if found == 0 {
		t.Fatal("expected some records after truncated replay")
	}
}

func TestFlushAndReadFromSegment(t *testing.T) {
	dir := t.TempDir()
	// 阈值设得很小,触发刷盘。
	e, _, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		value := []byte(fmt.Sprintf("value-%d", i))
		if err := e.Put(key, value); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("force flush: %v", err)
	}

	// 验证 segment 文件已生成。
	segDir := filepath.Join(dir, "sst")
	entries, err := os.ReadDir(segDir)
	if err != nil {
		t.Fatalf("read sst dir: %v", err)
	}
	if len(entries) == 0 {
		t.Fatal("expected segment file after flush")
	}

	// 从 segment 读回。
	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok, err := e.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", i, err)
		}
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("get %d: %v %v, want %v", i, got, ok, expected)
		}
	}
}

func TestManifestAndCurrent(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 10; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%d", i)), []byte("v")); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	if err := e.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	currentPath := filepath.Join(dir, "CURRENT")
	data, err := os.ReadFile(currentPath)
	if err != nil {
		t.Fatalf("read CURRENT: %v", err)
	}
	if len(data) == 0 {
		t.Fatal("CURRENT is empty")
	}
	manifestPath := filepath.Join(dir, strings.TrimSpace(string(data)))
	if _, err := os.Stat(manifestPath); err != nil {
		t.Fatalf("manifest missing: %v", err)
	}
}

// ---------------------------------------------------------------- P3:版本与快照

// TestVersionSuffixOrdering 新版本的字节序必须更小(^commit_ts,倒序存放)。
func TestVersionSuffixOrdering(t *testing.T) {
	oldKey := AppendVersion([]byte("user"), 15)
	newKey := AppendVersion([]byte("user"), 20)
	if bytes.Compare(newKey, oldKey) >= 0 {
		t.Fatalf("newer version must sort first: new=%x old=%x", newKey, oldKey)
	}
	uk, ts, err := SplitVersion(newKey)
	if err != nil {
		t.Fatalf("split: %v", err)
	}
	if string(uk) != "user" || ts != 20 {
		t.Fatalf("split = %q %d", uk, ts)
	}
	if _, _, err := SplitVersion([]byte("short")); err == nil {
		t.Fatal("expected error for key without version segment")
	}
}

// TestCommitReadImmediately 提交返回后立即可读(OK 只在 fsync + memtable 之后)。
func TestCommitReadImmediately(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	ts, conflict, err := e.Commit(0, 0, []Write{{Key: []byte("k"), Value: []byte("v")}})
	if err != nil || conflict != nil {
		t.Fatalf("commit: ts=%d conflict=%x err=%v", ts, conflict, err)
	}
	if ts != 1 {
		t.Fatalf("first commit_ts = %d, want 1", ts)
	}
	v, ok, err := e.Get([]byte("k"))
	if err != nil || !ok || string(v) != "v" {
		t.Fatalf("get = %q %v %v", v, ok, err)
	}
	if got := e.MaxCommittedTS(); got != ts {
		t.Fatalf("MaxCommittedTS = %d, want %d", got, ts)
	}
}

// TestCommitTSMonotonic 序号单调递增,允许跳号。
func TestCommitTSMonotonic(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	var last uint64
	for i := 0; i < 5; i++ {
		ts, conflict, err := e.Commit(0, 0, []Write{{Key: []byte(fmt.Sprintf("k%d", i)), Value: []byte("v")}})
		if err != nil || conflict != nil {
			t.Fatalf("commit %d: %v %x", i, err, conflict)
		}
		if ts <= last {
			t.Fatalf("commit_ts regressed: %d <= %d", ts, last)
		}
		last = ts
	}
}

// TestSnapshotVisibilityMultiVersion 同一主键三版本 + 多档快照对拍(INV-6 / SC-207)。
func TestSnapshotVisibilityMultiVersion(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	// 先提交一个无关 key,让 row 的版本从 ts >= 2 开始;否则 ts1-1 == 0 会被
	// 当成"最新已提交"这个特殊快照,无法表达"早于第一个版本"。
	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("seed"), Value: []byte("s")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	var ts1, ts2, ts3 uint64
	for i, v := range []string{"v1", "v2", "v3"} {
		ts, conflict, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte(v)}})
		if err != nil || conflict != nil {
			t.Fatalf("commit %d: %v %x", i, err, conflict)
		}
		switch i {
		case 0:
			ts1 = ts
		case 1:
			ts2 = ts
		case 2:
			ts3 = ts
		}
	}

	cases := []struct {
		snapshot uint64
		want     string
		visible  bool
	}{
		{snapshot: 0, want: "v3", visible: true},   // 0 = 最新
		{snapshot: ts1 - 1, visible: false},        // 早于第一个版本
		{snapshot: ts1, want: "v1", visible: true}, // 恰好 v1
		{snapshot: ts2 - 1, want: "v1", visible: true},
		{snapshot: ts2, want: "v2", visible: true},
		{snapshot: ts3 - 1, want: "v2", visible: true},
		{snapshot: ts3, want: "v3", visible: true},
		{snapshot: math.MaxUint64, want: "v3", visible: true}, // 乱填未来快照只读到已提交的
	}
	for _, c := range cases {
		got, ok, err := e.GetSnapshot(key, c.snapshot)
		if err != nil {
			t.Fatalf("snapshot %d: %v", c.snapshot, err)
		}
		if ok != c.visible {
			t.Fatalf("snapshot %d visible = %v, want %v", c.snapshot, ok, c.visible)
		}
		if ok && string(got) != c.want {
			t.Fatalf("snapshot %d = %q, want %q", c.snapshot, got, c.want)
		}
	}
}

// TestTombstoneVisibility 墓碑与正常版本同规则:先选可见版本,再解释墓碑。
func TestTombstoneVisibility(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	tombstone := []byte{0x02, 0, 0, 0, 1}
	ts1, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("alive")}})
	if err != nil {
		t.Fatalf("commit v1: %v", err)
	}
	ts2, _, err := e.Commit(0, 0, []Write{{Key: key, Value: tombstone}})
	if err != nil {
		t.Fatalf("commit tombstone: %v", err)
	}
	if ts2 <= ts1 {
		t.Fatalf("tombstone ts %d <= value ts %d", ts2, ts1)
	}

	if v, ok, err := e.GetSnapshot(key, ts1); err != nil || !ok || string(v) != "alive" {
		t.Fatalf("snapshot before tombstone = %q %v %v", v, ok, err)
	}
	if v, ok, err := e.GetSnapshot(key, ts2); err != nil || !ok || !bytes.Equal(v, tombstone) {
		t.Fatalf("snapshot at tombstone = %x %v %v", v, ok, err)
	}
	if v, ok, err := e.Get(key); err != nil || !ok || !bytes.Equal(v, tombstone) {
		t.Fatalf("latest = %x %v %v", v, ok, err)
	}
}

// TestVersionsAcrossMemtableAndSegment 多版本跨 memtable / segment 分组正确。
func TestVersionsAcrossMemtableAndSegment(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 1) // 阈值 1:每条提交都触发刷盘
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	ts1, _, _ := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v1")}})
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	ts2, _, _ := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v2")}})
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	ts3, _, _ := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v3")}})

	for _, c := range []struct {
		snapshot uint64
		want     string
	}{{ts1, "v1"}, {ts2, "v2"}, {ts3, "v3"}, {0, "v3"}} {
		got, ok, err := e.GetSnapshot(key, c.snapshot)
		if err != nil || !ok || string(got) != c.want {
			t.Fatalf("snapshot %d = %q %v %v, want %q", c.snapshot, got, ok, err, c.want)
		}
	}

	// 扫描同样要跨源分组,每个用户键只输出一次。
	sc := e.NewMVCCScanner([]byte("row"), []byte("row\x00"), 0, 0)
	count := 0
	for sc.Next() {
		count++
		if string(sc.Key()) != "row" || string(sc.Value()) != "v3" {
			t.Fatalf("scan row = %q=%q", sc.Key(), sc.Value())
		}
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if count != 1 {
		t.Fatalf("scan emitted %d rows, want 1", count)
	}
}

// TestScanRangeBoundariesAndLimit 范围 [start,end) 与分组之后的 limit。
func TestScanRangeBoundariesAndLimit(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for i := 1; i <= 10; i++ {
		key := []byte(fmt.Sprintf("k%02d", i))
		if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v")}}); err != nil {
			t.Fatalf("commit %d: %v", i, err)
		}
		// 同一 key 再写一次,验证分组后不会重复输出。
		if i%2 == 0 {
			if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("w")}}); err != nil {
				t.Fatalf("commit2 %d: %v", i, err)
			}
		}
	}

	var got []string
	sc := e.NewMVCCScanner([]byte("k03"), []byte("k07"), 0, 0)
	for sc.Next() {
		got = append(got, string(sc.Key()))
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}
	want := []string{"k03", "k04", "k05", "k06"}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("range = %v, want %v", got, want)
	}

	got = nil
	sc = e.NewMVCCScanner(nil, nil, 0, 3)
	for sc.Next() {
		got = append(got, string(sc.Key()))
	}
	if fmt.Sprint(got) != fmt.Sprint([]string{"k01", "k02", "k03"}) {
		t.Fatalf("limit scan = %v", got)
	}
}

func TestScanAcrossMemoryAndSegments(t *testing.T) {
	dir := t.TempDir()
	// 1KB 阈值,保证前 100 条会被刷进 segment。
	e, _, err := Open(dir, 1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for i := 0; i < 100; i++ {
		key := []byte(fmt.Sprintf("k%04d", i))
		if err := e.Put(key, []byte("old")); err != nil {
			t.Fatalf("put old %d: %v", i, err)
		}
	}
	if err := e.ForceFlush(); err != nil {
		t.Fatalf("force flush: %v", err)
	}

	// 覆盖一部分(新值在 memtable),再写一批只在内存里的 key。
	for i := 0; i < 50; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%04d", i)), []byte("new")); err != nil {
			t.Fatalf("put new %d: %v", i, err)
		}
	}
	for i := 100; i < 150; i++ {
		if err := e.Put([]byte(fmt.Sprintf("k%04d", i)), []byte("mem")); err != nil {
			t.Fatalf("put mem %d: %v", i, err)
		}
	}

	sc := e.NewMVCCScanner([]byte("k0040"), []byte("k0110"), 0, 0)
	var got []string
	for sc.Next() {
		got = append(got, string(sc.Key())+"="+string(sc.Value()))
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}
	if len(got) != 70 {
		t.Fatalf("rows = %d, want 70", len(got))
	}
	if got[0] != "k0040=new" {
		t.Fatalf("first = %s, want k0040=new", got[0])
	}
	if got[9] != "k0049=new" || got[10] != "k0050=old" {
		t.Fatalf("memtable/segment 边界取值错误: %v", got[8:12])
	}
	if got[len(got)-1] != "k0109=mem" {
		t.Fatalf("last = %s", got[len(got)-1])
	}

	// 空范围
	sc = e.NewMVCCScanner([]byte("z"), []byte("zz"), 0, 0)
	if sc.Next() {
		t.Fatal("空范围不应有结果")
	}
}

// TestSnapshotReadDoesNotBlockOnConcurrentWrite 长事务挂住时并发写不影响它读数。
func TestSnapshotReadDoesNotBlockOnConcurrentWrite(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	ts1, _, _ := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v1")}})
	snapshot := ts1

	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("v2")}}); err != nil {
		t.Fatalf("concurrent commit: %v", err)
	}
	got, ok, err := e.GetSnapshot(key, snapshot)
	if err != nil || !ok || string(got) != "v1" {
		t.Fatalf("snapshot read = %q %v %v, want v1", got, ok, err)
	}
}

// ---------------------------------------------------------------- P3:冲突检测

func TestConflictSameKey(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("orig")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()

	// 另一个事务先提交(模拟并发中的先到者)。
	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("winner")}}); err != nil {
		t.Fatalf("winner: %v", err)
	}
	afterWinner := e.MaxCommittedTS()

	ts, conflictKey, err := e.Commit(snapshot, 0, []Write{{Key: key, Value: []byte("loser")}})
	if err != nil {
		t.Fatalf("conflict commit: %v", err)
	}
	if ts != 0 {
		t.Fatalf("conflict commit returned ts=%d, want 0", ts)
	}
	if !bytes.Equal(conflictKey, key) {
		t.Fatalf("conflict key = %x, want %x", conflictKey, key)
	}
	// 冲突不取号、不写任何数据。
	if got := e.MaxCommittedTS(); got != afterWinner {
		t.Fatalf("commit_ts advanced on conflict: %d -> %d", afterWinner, got)
	}
	v, ok, _ := e.Get(key)
	if !ok || string(v) != "winner" {
		t.Fatalf("value after conflict = %q, want winner (loser must not overwrite)", v)
	}
}

func TestConflictTombstoneCountsAsModified(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("alive")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()
	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte{0x02}}}); err != nil {
		t.Fatalf("delete: %v", err)
	}

	_, conflictKey, err := e.Commit(snapshot, 0, []Write{{Key: key, Value: []byte("resurrect")}})
	if err != nil {
		t.Fatalf("commit: %v", err)
	}
	if conflictKey == nil {
		t.Fatal("tombstone must count as a modification and cause a conflict")
	}
}

func TestDifferentKeysDoNotConflict(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("seed"), Value: []byte("s")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()
	ts1, c1, err := e.Commit(snapshot, 0, []Write{{Key: []byte("a"), Value: []byte("1")}})
	if err != nil || c1 != nil || ts1 == 0 {
		t.Fatalf("commit a: ts=%d conflict=%x err=%v", ts1, c1, err)
	}
	ts2, c2, err := e.Commit(snapshot, 0, []Write{{Key: []byte("b"), Value: []byte("2")}})
	if err != nil || c2 != nil || ts2 == 0 {
		t.Fatalf("commit b: ts=%d conflict=%x err=%v", ts2, c2, err)
	}
}

// TestConflictFirstKeyInWriteOrder 多个 key 都冲突时返回写集里第一个。
func TestConflictFirstKeyInWriteOrder(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("a"), Value: []byte("1")}, {Key: []byte("b"), Value: []byte("2")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()
	if _, _, err := e.Commit(0, 0, []Write{{Key: []byte("b"), Value: []byte("x")}, {Key: []byte("a"), Value: []byte("y")}}); err != nil {
		t.Fatalf("modify: %v", err)
	}

	_, conflictKey, err := e.Commit(snapshot, 0, []Write{
		{Key: []byte("a"), Value: []byte("z")},
		{Key: []byte("b"), Value: []byte("z")},
	})
	if err != nil {
		t.Fatalf("commit: %v", err)
	}
	if string(conflictKey) != "a" {
		t.Fatalf("first conflict key = %q, want a", conflictKey)
	}
}

// ---------------------------------------------------------------- P3:并发

// TestConcurrentDifferentKeys 并发提交不同用户键都能成功。
func TestConcurrentDifferentKeys(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	const n = 20
	errCh := make(chan error, n)
	for i := 0; i < n; i++ {
		go func(i int) {
			_, conflict, err := e.Commit(0, 0, []Write{{
				Key:   []byte(fmt.Sprintf("key-%02d", i)),
				Value: []byte("v"),
			}})
			if err != nil {
				errCh <- err
				return
			}
			if conflict != nil {
				errCh <- fmt.Errorf("unexpected conflict on key %d", i)
				return
			}
			errCh <- nil
		}(i)
	}
	for i := 0; i < n; i++ {
		if err := <-errCh; err != nil {
			t.Fatalf("concurrent commit: %v", err)
		}
	}
}

// TestConcurrentSameKeyExactlyOneWins 同一 key 并发提交,只有一个成功。
func TestConcurrentSameKeyExactlyOneWins(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	key := []byte("row")
	if _, _, err := e.Commit(0, 0, []Write{{Key: key, Value: []byte("seed")}}); err != nil {
		t.Fatalf("seed: %v", err)
	}
	snapshot := e.MaxCommittedTS()

	const n = 8
	type res struct {
		ts       uint64
		conflict bool
	}
	ch := make(chan res, n)
	for i := 0; i < n; i++ {
		go func(i int) {
			ts, conflict, err := e.Commit(snapshot, 0, []Write{{Key: key, Value: []byte(fmt.Sprintf("v%d", i))}})
			if err != nil {
				ch <- res{}
				return
			}
			ch <- res{ts: ts, conflict: conflict != nil}
		}(i)
	}
	wins := 0
	for i := 0; i < n; i++ {
		if r := <-ch; !r.conflict && r.ts != 0 {
			wins++
		}
	}
	if wins != 1 {
		t.Fatalf("winners = %d, want exactly 1", wins)
	}
}

// ---------------------------------------------------------------- P3:序号持久性

// TestSequenceNoRegressionWhenDataOnlyInSegments 数据只在 segment、WAL 已回收后重启,
// 序号仍不回退(INV-5 / SC-206)。
func TestSequenceNoRegressionWhenDataOnlyInSegments(t *testing.T) {
	dir := t.TempDir()
	e1, _, err := Open(dir, 1) // 阈值 1:每条提交都冻结 memtable
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	var maxTS uint64
	for i := 0; i < 20; i++ {
		ts, _, err := e1.Commit(0, 0, []Write{{Key: []byte(fmt.Sprintf("k%02d", i)), Value: []byte("v")}})
		if err != nil {
			t.Fatalf("commit %d: %v", i, err)
		}
		maxTS = ts
	}
	if err := e1.ForceFlush(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	if err := e1.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	// 确认数据确实在 segment 里、且旧 WAL 已被回收。
	wals, _ := filepath.Glob(filepath.Join(dir, "wal", "*.wal"))
	segs, _ := filepath.Glob(filepath.Join(dir, "sst", "*.seg"))
	if len(segs) == 0 {
		t.Fatal("expected segments on disk")
	}
	if len(wals) > 1 {
		t.Fatalf("expected old WALs to be reclaimed, got %d files", len(wals))
	}

	e2, stats, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	if stats.MaxCommitTS < maxTS {
		t.Fatalf("recovered MaxCommitTS = %d, want >= %d", stats.MaxCommitTS, maxTS)
	}
	if got := e2.MaxCommittedTS(); got < maxTS {
		t.Fatalf("MaxCommittedTS after restart = %d, want >= %d", got, maxTS)
	}
	ts, _, err := e2.Commit(0, 0, []Write{{Key: []byte("after-restart"), Value: []byte("v")}})
	if err != nil {
		t.Fatalf("commit after restart: %v", err)
	}
	if ts <= maxTS {
		t.Fatalf("commit_ts after restart = %d, must be > %d", ts, maxTS)
	}

	// MANIFEST.last_commit_ts >= 已落盘数据的最大 commit_ts。
	m := loadManifest(t, dir)
	if m.LastCommitTS < maxTS {
		t.Fatalf("manifest last_commit_ts = %d, want >= %d", m.LastCommitTS, maxTS)
	}
}

// ---------------------------------------------------------------- P3:格式与限额

func TestFormatVersionGuard(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := e.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	rewriteManifest(t, dir, func(s string) string {
		return strings.Replace(s, `"format_version": 2`, `"format_version": 1`, 1)
	})
	if _, _, err := Open(dir, 64*1024*1024); err == nil {
		t.Fatal("expected startup failure for old format")
	} else if !strings.Contains(err.Error(), "不兼容") {
		t.Fatalf("error = %v", err)
	}
}

func TestMaxTxnWriteBytesRejected(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{
		MemtableSize:     64 * 1024 * 1024,
		MaxTxnWriteBytes: 64,
	})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	_, _, err = e.Commit(0, 0, []Write{{Key: []byte("big"), Value: bytes.Repeat([]byte("x"), 200)}})
	if err == nil || !errors.Is(err, ErrTxnTooLarge) {
		t.Fatalf("err = %v, want ErrTxnTooLarge", err)
	}
	if e.MaxCommittedTS() != 0 {
		t.Fatalf("commit_ts advanced on rejected write: %d", e.MaxCommittedTS())
	}
	if _, ok, _ := e.Get([]byte("big")); ok {
		t.Fatal("rejected write must not be visible")
	}
}

func TestCommitEmptyWritesIsNoop(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()
	ts, conflict, err := e.Commit(0, 0, nil)
	if err != nil || conflict != nil || ts != 0 {
		t.Fatalf("empty commit = %d %x %v", ts, conflict, err)
	}
	if e.MaxCommittedTS() != 0 {
		t.Fatalf("empty commit consumed a sequence number")
	}
}

// ---------------------------------------------------------------- helpers

func rewriteManifest(t *testing.T, dir string, transform func(string) string) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "CURRENT"))
	if err != nil {
		t.Fatalf("read CURRENT: %v", err)
	}
	path := filepath.Join(dir, strings.TrimSpace(string(data)))
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	if err := os.WriteFile(path, []byte(transform(string(body))), 0640); err != nil {
		t.Fatalf("write manifest: %v", err)
	}
}

func loadManifest(t *testing.T, dir string) manifest.Manifest {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "CURRENT"))
	if err != nil {
		t.Fatalf("read CURRENT: %v", err)
	}
	body, err := os.ReadFile(filepath.Join(dir, strings.TrimSpace(string(data))))
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	var m manifest.Manifest
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	return m
}

// TestPointGetWithPrefixRelatedUserKeys 用户键可能互为前缀(P1 的 crash-test 用 key-1/key-10 这种)。
// 完整 key = 用户键 + 8 字节版本段,前缀关系会让原始字节序与用户键序不一致;
// 点查与冲突检测必须仍然只看"用户键恰好相等"的版本。
func TestPointGetWithPrefixRelatedUserKeys(t *testing.T) {
	dir := t.TempDir()
	e, _, err := Open(dir, 64*1024*1024)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()

	for _, k := range []string{"key-0", "key-1", "key-2", "key-9", "key-10", "key-99", "key-100", "key-199"} {
		if err := e.Put([]byte(k), []byte("v:"+k)); err != nil {
			t.Fatalf("put %s: %v", k, err)
		}
	}
	for _, k := range []string{"key-0", "key-1", "key-2", "key-9", "key-10", "key-99", "key-100", "key-199"} {
		got, ok, err := e.Get([]byte(k))
		if err != nil || !ok || string(got) != "v:"+k {
			t.Fatalf("get %s = %q ok=%v err=%v", k, got, ok, err)
		}
	}

	// 冲突检测同样不能被前缀关系带偏。
	snapshot := e.MaxCommittedTS()
	if err := e.Put([]byte("key-1"), []byte("v2")); err != nil {
		t.Fatalf("overwrite: %v", err)
	}
	_, conflictKey, err := e.Commit(snapshot, 0, []Write{{Key: []byte("key-1"), Value: []byte("v3")}})
	if err != nil {
		t.Fatalf("commit: %v", err)
	}
	if string(conflictKey) != "key-1" {
		t.Fatalf("conflict key = %q, want key-1", conflictKey)
	}
}
