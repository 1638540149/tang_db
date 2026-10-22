package exec

import (
	"bytes"
	"path/filepath"
	"testing"

	"github.com/1638540149/tang_db/be/internal/memtable"
	"github.com/1638540149/tang_db/be/internal/segment"
)

// buildSegment 在 dir 下写一个 segment 并返回只读句柄。
func buildSegment(t *testing.T, dir, name string, rows [][2]string) *segment.Reader {
	t.Helper()
	path := filepath.Join(dir, name)
	b := segment.NewBuilder(path)
	for _, r := range rows {
		b.Add([]byte(r[0]), []byte(r[1]))
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish segment: %v", err)
	}
	r, err := segment.Open(path)
	if err != nil {
		t.Fatalf("open segment: %v", err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r
}

func buildMem(rows [][2]string) *memtable.MemTable {
	m := memtable.New()
	for _, r := range rows {
		m.Put([]byte(r[0]), []byte(r[1]))
	}
	return m
}

func collect(t *testing.T, sc *Scanner) (keys, values []string) {
	t.Helper()
	for sc.Next() {
		keys = append(keys, string(sc.Key()))
		values = append(values, string(sc.Value()))
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan error: %v", err)
	}
	return keys, values
}

func TestScannerMergesNewestWins(t *testing.T) {
	dir := t.TempDir()
	oldSeg := buildSegment(t, dir, "old.seg", [][2]string{
		{"a", "old-a"},
		{"b", "old-b"},
		{"c", "old-c"},
	})
	newSeg := buildSegment(t, dir, "new.seg", [][2]string{
		{"c", "seg-c"},
		{"e", "seg-e"},
	})
	mem := buildMem([][2]string{
		{"d", "mem-d"},
		{"e", "mem-e"},
		{"f", "mem-f"},
	})

	sc := NewScanner([]Cursor{
		NewMemCursor(mem),
		NewSegmentCursor(newSeg),
		NewSegmentCursor(oldSeg),
	}, nil, nil, 0)

	keys, values := collect(t, sc)
	wantKeys := []string{"a", "b", "c", "d", "e", "f"}
	wantVals := []string{"old-a", "old-b", "seg-c", "mem-d", "mem-e", "mem-f"}
	if !equal(keys, wantKeys) {
		t.Fatalf("keys = %v, want %v", keys, wantKeys)
	}
	if !equal(values, wantVals) {
		t.Fatalf("values = %v, want %v", values, wantVals)
	}
}

func TestScannerRangeBoundaries(t *testing.T) {
	dir := t.TempDir()
	seg := buildSegment(t, dir, "s.seg", [][2]string{
		{"a", "1"}, {"c", "3"}, {"e", "5"},
	})
	mem := buildMem([][2]string{
		{"b", "2"}, {"d", "4"}, {"f", "6"},
	})

	cases := []struct {
		name     string
		start    string
		end      string
		wantKeys []string
	}{
		{"含起点不含终点", "b", "e", []string{"b", "c", "d"}},
		{"起点恰为键", "c", "f", []string{"c", "d", "e"}},
		{"终点恰为键", "a", "c", []string{"a", "b"}},
		{"起点不存在", "bb", "dd", []string{"c", "d"}},
		{"终点不存在", "d", "ee", []string{"d", "e"}},
		{"起点等于终点", "c", "c", nil},
		{"起点大于终点", "e", "b", nil},
		{"无限起点", "", "b", []string{"a"}},
		{"无限终点", "e", "", []string{"e", "f"}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var start, end []byte
			if tc.start != "" {
				start = []byte(tc.start)
			}
			if tc.end != "" {
				end = []byte(tc.end)
			}
			sc := NewScanner([]Cursor{
				NewMemCursor(mem),
				NewSegmentCursor(seg),
			}, start, end, 0)
			keys, _ := collect(t, sc)
			if !equal(keys, tc.wantKeys) {
				t.Fatalf("keys = %v, want %v", keys, tc.wantKeys)
			}
		})
	}
}

func TestScannerLimit(t *testing.T) {
	dir := t.TempDir()
	seg := buildSegment(t, dir, "s.seg", [][2]string{
		{"a", "1"}, {"b", "2"}, {"c", "3"}, {"d", "4"},
	})
	mem := buildMem([][2]string{{"e", "5"}})

	sc := NewScanner([]Cursor{NewMemCursor(mem), NewSegmentCursor(seg)}, nil, nil, 2)
	keys, _ := collect(t, sc)
	if !equal(keys, []string{"a", "b"}) {
		t.Fatalf("limit 2 keys = %v", keys)
	}

	// limit 大于总数时全量返回。
	sc = NewScanner([]Cursor{NewMemCursor(mem), NewSegmentCursor(seg)}, nil, nil, 99)
	keys, _ = collect(t, sc)
	if len(keys) != 5 {
		t.Fatalf("limit 99 keys = %v", keys)
	}
}

func TestScannerPassesTombstoneThrough(t *testing.T) {
	tombstone := []byte{0x02, 0x10, 0x00, 0x00, 0x00}
	mem := memtable.New()
	mem.Put([]byte("k1"), []byte("live"))
	mem.Put([]byte("k2"), tombstone)

	sc := NewScanner([]Cursor{NewMemCursor(mem)}, nil, nil, 0)
	var got [][]byte
	for sc.Next() {
		got = append(got, append([]byte(nil), sc.Value()...))
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("rows = %d, want 2", len(got))
	}
	if !bytes.Equal(got[1], tombstone) {
		t.Fatalf("墓碑被改写: %v", got[1])
	}
}

func TestScannerEmptySources(t *testing.T) {
	sc := NewScanner(nil, nil, nil, 0)
	if sc.Next() {
		t.Fatal("空源不应返回数据")
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}

	sc = NewScanner([]Cursor{NewMemCursor(memtable.New())}, []byte("a"), []byte("z"), 0)
	if sc.Next() {
		t.Fatal("空表不应返回数据")
	}
}

func TestScannerSegmentChunkBoundary(t *testing.T) {
	// 制造跨 chunk(每 chunk 4096 行)的 segment,验证 Seek 与 Next 跨块正确。
	dir := t.TempDir()
	rows := make([][2]string, 0, 4100)
	for i := 0; i < 4100; i++ {
		rows = append(rows, [2]string{padKey(i), padKey(i)})
	}
	seg := buildSegment(t, dir, "big.seg", rows)

	sc := NewScanner([]Cursor{NewSegmentCursor(seg)}, []byte(padKey(4090)), []byte(padKey(4095)), 0)
	keys, _ := collect(t, sc)
	if len(keys) != 5 {
		t.Fatalf("跨 chunk 边界返回 %d 行, want 5", len(keys))
	}
	if keys[0] != padKey(4090) || keys[4] != padKey(4094) {
		t.Fatalf("边界不对: %v", keys)
	}
}

func padKey(i int) string {
	const digits = "0123456789"
	buf := make([]byte, 8)
	for p := 7; p >= 0; p-- {
		buf[p] = digits[i%10]
		i /= 10
	}
	return string(buf)
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
