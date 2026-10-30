package store

import (
	"encoding/binary"
	"fmt"
	"math"
	"testing"

	"github.com/1638540149/tang_db/be/internal/vecidx"
)

// SPEC-P7.1 K180:跨段归并去重(同一 key 保留最小距离)+ (距离, key) 排序。
func TestMergeCandidatesDedupesAndSorts(t *testing.T) {
	a := []byte{0x00, 0x01}
	b := []byte{0x00, 0x02}
	cands := []vecCandidate{
		{key: b, dist: 0.5, ord: 1},
		{key: a, dist: 0.5, ord: 2}, // 同距离按 key 字节序
		{key: b, dist: 0.1, ord: 3}, // 同 key 取最小距离
	}
	out := mergeCandidates(cands, 10)
	if len(out) != 2 {
		t.Fatalf("len=%d, want 2(去重后)", len(out))
	}
	if string(out[0].Key) != string(b) || out[0].Distance != 0.1 {
		t.Fatalf("first = %x/%v, want %x/0.1", out[0].Key, out[0].Distance, b)
	}
	if string(out[1].Key) != string(a) {
		t.Fatalf("second = %x, want %x", out[1].Key, a)
	}
	if out[0].Ordinal != 0 {
		// 保留的是第一条(ord=1)的最小距离覆盖,ord 不参与语义,只做诊断。
		t.Logf("ordinal=%d(诊断字段)", out[0].Ordinal)
	}
	// ef 截断
	if got := mergeCandidates(cands, 1); len(got) != 1 {
		t.Fatalf("ef=1 → len=%d", len(got))
	}
}

// SPEC-P7.1 K182:允许集解析 —— 键集合命中的行才保留。
func TestParseVecAllowKeys(t *testing.T) {
	raw := encodeTestKeys([][]byte{[]byte("a"), []byte("bb")})
	allow := parseVecAllow(raw, false)
	if allow == nil || allow.bloom != nil {
		t.Fatal("want key-set allow")
	}
	if !allow.contains([]byte("a")) || !allow.contains([]byte("bb")) {
		t.Fatal("keys should be contained")
	}
	if allow.contains([]byte("c")) {
		t.Fatal("unexpected key contained")
	}
	if parseVecAllow(nil, false) != nil {
		t.Fatal("empty allow must be nil(= 不过滤)")
	}
	if parseVecAllow([]byte{0x01}, false) != nil {
		t.Fatal("truncated allow must degrade to nil instead of empty set")
	}
}

// SPEC-P7.1 K182:布隆过滤器编码与 FE AllowListCodec 必须逐位一致。
//
// 期望值由 FE 侧 AllowListCodecTest 用同一组 key 断言(同一段十六进制)。
func TestParseVecAllowBloomMatchesFE(t *testing.T) {
	keys := [][]byte{[]byte("alpha"), []byte("beta"), []byte("gamma")}
	raw := encodeTestBloom(keys)
	if got := hexString(raw); got != expectedBloomHex {
		t.Fatalf("bloom bytes = %s, want %s(与 FE AllowListCodec 必须逐位一致)", got,
			expectedBloomHex)
	}
	allow := parseVecAllow(raw, true)
	if allow == nil || allow.bloom == nil {
		t.Fatal("want bloom allow")
	}
	for _, k := range keys {
		if !allow.contains(k) {
			t.Fatalf("bloom must contain %q", k)
		}
	}
	if allow.contains([]byte("delta")) {
		t.Fatal("bloom false positive for delta(3 个 key 下不应该发生)")
	}
}

// 期望的布隆编码:mbits=64(3 key × 10 bit 向上取整到 64)、k=7。
const expectedBloomHex = "40000000" + "07000000" + "04228c79d24c0001"

// m 不是 2 的幂时的编码(10 key → 104 bit):这个用例专门钉住
// "有符号 floorMod ≠ 无符号取模" 那个跨语言 bug(Java 侧同样断言这串)。
func TestParseVecAllowBloomNonPowerOfTwo(t *testing.T) {
	keys := make([][]byte, 0, 10)
	for i := 400; i < 410; i++ {
		keys = append(keys, []byte(fmt.Sprintf("key%d", i)))
	}
	raw := encodeTestBloom(keys)
	if got := hexString(raw); got != expectedBloomHex104 {
		t.Fatalf("bloom bytes = %s, want %s", got, expectedBloomHex104)
	}
	allow := parseVecAllow(raw, true)
	if allow == nil || allow.bloom == nil {
		t.Fatal("want bloom allow")
	}
	for _, k := range keys {
		if !allow.contains(k) {
			t.Fatalf("bloom must contain %q", k)
		}
	}
}

const expectedBloomHex104 = "68000000" + "07000000" + "6cf2b4bb0eb433c88389422d57"

func encodeTestKeys(keys [][]byte) []byte {
	out := make([]byte, 0, 32)
	for _, k := range keys {
		var tmp [4]byte
		binary.LittleEndian.PutUint32(tmp[:], uint32(len(k)))
		out = append(out, tmp[:]...)
		out = append(out, k...)
	}
	return out
}

// encodeTestBloom 是 FE AllowListCodec.encodeBloom 的等价实现(测试里独立写一遍,
// 保证"两端同一套哈希 + 同一套位图布局")。
func encodeTestBloom(keys [][]byte) []byte {
	const bitsPerKey = 10
	const hashCount = 7
	bits := uint64(len(keys) * bitsPerKey)
	if bits < 64 {
		bits = 64
	}
	bits = (bits + 7) &^ 7
	bitmap := make([]byte, (bits+7)/8)
	for _, k := range keys {
		h1 := fnv1a64(k)
		h2 := (h1 >> 32) | 1
		for i := uint64(0); i < hashCount; i++ {
			bit := (h1 + i*h2) % bits
			bitmap[bit>>3] |= 1 << (bit & 7)
		}
	}
	out := make([]byte, 0, 8+len(bitmap))
	var tmp [4]byte
	binary.LittleEndian.PutUint32(tmp[:], uint32(bits))
	out = append(out, tmp[:]...)
	binary.LittleEndian.PutUint32(tmp[:], hashCount)
	out = append(out, tmp[:]...)
	return append(out, bitmap...)
}

func hexString(b []byte) string {
	const hexdigits = "0123456789abcdef"
	out := make([]byte, 0, len(b)*2)
	for _, c := range b {
		out = append(out, hexdigits[c>>4], hexdigits[c&0x0f])
	}
	return string(out)
}

// SPEC-P7.1 K178:向量运行时缓存 —— 常驻、按字节计数、预算不足时按 LRU 淘汰且结果不变。
func TestVectorRuntimeCacheResidentAndEvictable(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()
	const tableID = 11
	def := VecDefinition{IndexID: 5, TableID: tableID, StartKey: prefixOf(tableID, 0),
		EndKey: prefixOf(tableID, 1), ColumnSlots: feSlots(2), RowFrame: []byte{1, 2, 0, 7, 0},
		VectorOrdinal: 1, Dim: 2, Metric: vecidx.MetricL2, M: 8, EfConstruction: 32}
	if err := e.RegisterVectorIndex(def); err != nil {
		t.Fatalf("register: %v", err)
	}
	writes := make([]Write, 0, 64)
	for i := 0; i < 64; i++ {
		key := append(prefixOf(tableID, 0), 0, 0, 0, byte(i))
		writes = append(writes, Write{Key: key,
			Value: feRow(tableID, int64(i), []float32{float32(i), 1})})
	}
	if _, _, err := e.Commit(0, 0, writes); err != nil {
		t.Fatalf("commit: %v", err)
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	waitReady(t, e, 5)

	query := make([]byte, 8)
	binary.LittleEndian.PutUint32(query[0:], math.Float32bits(3))
	binary.LittleEndian.PutUint32(query[4:], math.Float32bits(1))
	first, _, timings, err := e.SearchVectorIndex(5, query, 16, 3, 0, false, nil, false)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if e.VectorCacheBytes() == 0 {
		t.Fatalf("向量运行时缓存应占用字节(常驻),实际 0")
	}
	// 第二次:列部分命中缓存(数据准备段应为 0 字节回读)。
	second, _, timings2, err := e.SearchVectorIndex(5, query, 16, 3, 0, true, nil, false)
	if err != nil {
		t.Fatalf("search2: %v", err)
	}
	if timings2 == nil || timings2.CacheMisses != 0 {
		t.Fatalf("second search should hit cache: %+v", timings2)
	}
	if len(first) != len(second) || string(first[0].Key) != string(second[0].Key) {
		t.Fatalf("results differ between cached searches")
	}
	_ = timings

	// 预算压到 1KB → 列部分被淘汰(refs=0),再查结果必须不变。
	e.SetVectorCacheBudget(1024)
	if e.VectorCacheBytes() > 1024 {
		t.Fatalf("cache_bytes=%d should be evicted to <=1024", e.VectorCacheBytes())
	}
	third, _, _, err := e.SearchVectorIndex(5, query, 16, 3, 0, false, nil, false)
	if err != nil {
		t.Fatalf("search3: %v", err)
	}
	if len(third) != len(first) || string(third[0].Key) != string(first[0].Key) {
		t.Fatalf("results changed after cache eviction(SC-445 不许变)")
	}
}

// SPEC-P7.1 K182:允许集下推 —— 只有允许集内的主键出现在候选里。
func TestSearchVectorIndexAllowList(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer e.Close()
	const tableID = 13
	def := VecDefinition{IndexID: 7, TableID: tableID, StartKey: prefixOf(tableID, 0),
		EndKey: prefixOf(tableID, 1), ColumnSlots: feSlots(2), RowFrame: []byte{1, 2, 0, 7, 0},
		VectorOrdinal: 1, Dim: 2, Metric: vecidx.MetricL2, M: 8, EfConstruction: 32}
	if err := e.RegisterVectorIndex(def); err != nil {
		t.Fatalf("register: %v", err)
	}
	keys := make([][]byte, 0, 60)
	writes := make([]Write, 0, 60)
	for i := 0; i < 60; i++ {
		key := append(prefixOf(tableID, 0), 0, 0, 0, byte(i))
		keys = append(keys, key)
		writes = append(writes, Write{Key: key,
			Value: feRow(tableID, int64(i), []float32{float32(i), 0})})
	}
	if _, _, err := e.Commit(0, 0, writes); err != nil {
		t.Fatalf("commit: %v", err)
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatalf("flush: %v", err)
	}
	waitReady(t, e, 7)

	allowed := map[string]bool{}
	for _, i := range []int{5, 20, 40} {
		allowed[string(keys[i])] = true
	}
	allowRaw := encodeTestKeys(keys)
	// 只放行 3 个 key 的允许集(模拟路径 A 的 5% 选择率)
	allowRaw = encodeTestKeys([][]byte{keys[5], keys[20], keys[40]})
	query := make([]byte, 8)
	binary.LittleEndian.PutUint32(query[0:], math.Float32bits(0))
	binary.LittleEndian.PutUint32(query[4:], math.Float32bits(0))
	hits, _, _, err := e.SearchVectorIndex(7, query, 32, 10, 0, false, allowRaw, false)
	if err != nil {
		t.Fatalf("search: %v", err)
	}
	if len(hits) == 0 {
		t.Fatal("expected hits inside allow list")
	}
	for _, hit := range hits {
		if !allowed[string(hit.Key)] {
			t.Fatalf("hit %x outside allow list", hit.Key)
		}
	}
	if len(hits) > len(allowed) {
		t.Fatalf("hits=%d exceeds allow list size %d", len(hits), len(allowed))
	}
}
