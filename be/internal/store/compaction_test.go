package store

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/1638540149/tang_db/be/internal/segment"
)

// 打开一个 memtable 很小的引擎,方便制造多个 L0 文件。
func openSmall(t *testing.T, dir string) *Engine {
	t.Helper()
	e, _, err := OpenWithOptions(dir, Options{
		MemtableSize:      1024,
		CompactionL0Files: 1000, // 关闭阈值自动触发,测试里手动 ForceCompact
		BloomEnabled:      true,
	})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	return e
}

func putN(t *testing.T, e *Engine, key string, value string) {
	t.Helper()
	if err := e.Put([]byte(key), []byte(value)); err != nil {
		t.Fatalf("put %s: %v", key, err)
	}
}

func mustGet(t *testing.T, e *Engine, key string) string {
	t.Helper()
	v, ok, err := e.Get([]byte(key))
	if err != nil {
		t.Fatalf("get %s: %v", key, err)
	}
	if !ok {
		return ""
	}
	return string(v)
}

// INV-17 / SC-358:合并前后同一查询结果一致,MANIFEST 引用文件全部存在。
func TestCompactionKeepsQueryResultsAndDeletesOldFiles(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	defer e.Close()

	for round := 0; round < 4; round++ {
		for i := 0; i < 200; i++ {
			putN(t, e, fmt.Sprintf("k-%04d", i), fmt.Sprintf("v-%d-%d", round, i))
		}
		if err := e.ForceFlushAll(); err != nil {
			t.Fatalf("flush: %v", err)
		}
	}
	before := map[string]string{}
	for i := 0; i < 50; i++ {
		key := fmt.Sprintf("k-%04d", i)
		before[key] = mustGet(t, e, key)
	}
	levelsBefore := e.LevelsSnapshot()
	if len(levelsBefore[0]) < 2 {
		t.Fatalf("期望至少 2 个 L0 文件,实际 %d", len(levelsBefore[0]))
	}
	oldFiles := map[uint64]bool{}
	for _, n := range levelsBefore[0] {
		oldFiles[n] = true
	}

	if err := e.ForceCompact(); err != nil {
		t.Fatalf("compact: %v", err)
	}
	levelsAfter := e.LevelsSnapshot()
	if len(levelsAfter) < 2 || len(levelsAfter[0]) != 0 {
		t.Fatalf("合并后 L0 应为空、L1 有文件: %v", levelsAfter)
	}
	for i := 0; i < 50; i++ {
		key := fmt.Sprintf("k-%04d", i)
		if got := mustGet(t, e, key); got != before[key] {
			t.Fatalf("%s 合并后结果变化: %q → %q", key, before[key], got)
		}
	}
	// 旧文件已删除,MANIFEST 引用的文件都在。
	for num := range oldFiles {
		path := filepath.Join(dir, "sst", segment.Name(num))
		if _, err := os.Stat(path); err == nil {
			t.Fatalf("旧 segment %s 仍存在(应在新 MANIFEST 落盘后删除)", path)
		}
	}
	for _, level := range levelsAfter {
		for _, num := range level {
			path := filepath.Join(dir, "sst", segment.Name(num))
			if _, err := os.Stat(path); err != nil {
				t.Fatalf("MANIFEST 引用的 segment %s 不存在: %v", path, err)
			}
		}
	}
}

// SC-359 / INV-7 / 边界 46:长事务持快照期间旧版本不被回收;等于水位的版本必须保留。
func TestCompactionRespectsOldestActiveSnapshot(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	defer e.Close()

	putN(t, e, "row", "v1")
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	snapshot := e.BeginSnapshot() // 长事务开始(水位 = 当前最大 commit_ts)
	putN(t, e, "row", "v2")
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	if err := e.ForceCompact(); err != nil {
		t.Fatalf("compact with pinned snapshot: %v", err)
	}

	// 旧快照仍能读到 v1(旧版本未被回收)
	v, ok, err := e.GetSnapshot([]byte("row"), snapshot)
	if err != nil || !ok || string(v) != "v1" {
		t.Fatalf("长事务快照读到的应为 v1,实际 %q ok=%v err=%v", v, ok, err)
	}
	if got := mustGet(t, e, "row"); got != "v2" {
		t.Fatalf("最新读应为 v2,实际 %q", got)
	}

	// 边界 46:commit_ts 等于水位的版本必须保留。
	// 释放后再对"当前最新"取快照并合并,该版本(恰等于水位)仍必须存在。
	e.ReleaseSnapshot(snapshot)
	if n := e.ActiveSnapshotCount(); n != 0 {
		t.Fatalf("活跃快照应为 0,实际 %d", n)
	}
	ts2 := e.BeginSnapshot()
	v, ok, err = e.GetSnapshot([]byte("row"), ts2)
	if err != nil || !ok || string(v) != "v2" {
		t.Fatalf("等于水位的版本必须保留: %q ok=%v err=%v", v, ok, err)
	}
	if err := e.ForceCompact(); err != nil {
		t.Fatal(err)
	}
	v, ok, err = e.GetSnapshot([]byte("row"), ts2)
	if err != nil || !ok || string(v) != "v2" {
		t.Fatalf("合并后等于水位的版本被误删: %q ok=%v err=%v", v, ok, err)
	}
	e.ReleaseSnapshot(ts2)
}

// G-4:墓碑在所有旧版本都可回收时随 compaction 删除,不复活。
func TestCompactionReclaimsTombstone(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	defer e.Close()

	putN(t, e, "gone", "value")
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	tombstone := append([]byte{0x02}, []byte{0, 0, 0, 1}...)
	putN(t, e, "gone", string(tombstone))
	// 推高 commit_ts,让水位严格大于墓碑的 commit_ts(等于水位必须保留,边界 46)。
	putN(t, e, "other", "x")
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	if got := mustGet(t, e, "gone"); got != string(tombstone) {
		t.Fatalf("墓碑应保留(最新读): %q", got)
	}
	if err := e.ForceCompact(); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := e.Get([]byte("gone")); err != nil || ok {
		t.Fatalf("墓碑应被回收: ok=%v err=%v", ok, err)
	}
}

// L1+ 层内 key 范围不重叠(FR-599)。
func TestCompactedLevelIsNonOverlapping(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	defer e.Close()
	for round := 0; round < 5; round++ {
		for i := 0; i < 200; i++ {
			putN(t, e, fmt.Sprintf("k-%05d", i), fmt.Sprintf("r%d-%d", round, i))
		}
		if err := e.ForceFlushAll(); err != nil {
			t.Fatal(err)
		}
	}
	if err := e.ForceCompact(); err != nil {
		t.Fatal(err)
	}
	levels := e.LevelsSnapshot()
	if len(levels) < 2 {
		t.Fatalf("期望 L1 存在: %v", levels)
	}
	type span struct {
		first, last []byte
	}
	spans := make([]span, 0, len(levels[1]))
	for _, num := range levels[1] {
		first, last, ok := e.SegmentRange(num)
		if !ok {
			t.Fatalf("segment %d 不在视图里", num)
		}
		spans = append(spans, span{first, last})
	}
	sort.Slice(spans, func(i, j int) bool {
		return bytes.Compare(spans[i].first, spans[j].first) < 0
	})
	for i := 1; i < len(spans); i++ {
		if bytes.Compare(spans[i-1].last, spans[i].first) >= 0 {
			t.Fatalf("L1 层内 key 范围重叠: [%x,%x] 与 [%x,%x]",
				spans[i-1].first, spans[i-1].last, spans[i].first, spans[i].last)
		}
	}
}

// 崩溃残留(不在 MANIFEST 里的 segment)不得被当成有效数据,启动时清理。
func TestOrphanSegmentRemovedOnStartup(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	putN(t, e, "k", "v")
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	if err := e.Close(); err != nil {
		t.Fatal(err)
	}
	orphan := filepath.Join(dir, "sst", segment.Name(999))
	builder := segment.NewBuilder(orphan)
	builder.Add([]byte("ghost"), []byte("x"))
	if _, err := builder.Finish(); err != nil {
		t.Fatal(err)
	}
	e2, _, err := OpenWithOptions(dir, Options{MemtableSize: 4 * 1024})
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	defer e2.Close()
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatalf("孤儿 segment 应被清理: %v", err)
	}
	if got := mustGet(t, e2, "k"); got != "v" {
		t.Fatalf("正常数据丢失: %q", got)
	}
	if got := mustGet(t, e2, "ghost"); got != "" {
		t.Fatalf("孤儿 segment 的数据不得可见: %q", got)
	}
}

// 合并与写入并发:不丢版本、不出现"旧版本复活"(边界 47)。
func TestCompactionConcurrentWithWrites(t *testing.T) {
	dir := t.TempDir()
	e := openSmall(t, dir)
	defer e.Close()
	for i := 0; i < 300; i++ {
		putN(t, e, fmt.Sprintf("k-%04d", i), fmt.Sprintf("v1-%d", i))
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; i < 300; i++ {
			putN(t, e, fmt.Sprintf("k-%04d", i), fmt.Sprintf("v2-%d", i))
		}
	}()
	if err := e.ForceCompact(); err != nil {
		t.Fatalf("concurrent compact: %v", err)
	}
	<-done
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	if err := e.ForceCompact(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 300; i++ {
		key := fmt.Sprintf("k-%04d", i)
		want := fmt.Sprintf("v2-%d", i)
		if got := mustGet(t, e, key); got != want {
			t.Fatalf("%s 旧版本复活: got %q want %q", key, got, want)
		}
	}
}
