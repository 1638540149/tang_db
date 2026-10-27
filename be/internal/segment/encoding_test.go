package segment

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"math"
	"hash/crc32"
	"math/rand"
	"os"
	"path/filepath"
	"testing"
)

// ---------------------------------------------------------------- 6 种列类型的字节样本

func beInt32(v int32) []byte {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(v))
	return b[:]
}

func beInt64(v int64) []byte {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], uint64(v))
	return b[:]
}

func beFloat64(v float64) []byte {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], math.Float64bits(v))
	return b[:]
}

// typeSamples 返回某类型的正常值 / 极值 / 空串 / 超长串序列(按列类型 6 种)。
func typeSamples(kind string) [][]byte {
	switch kind {
	case "INT":
		return [][]byte{beInt32(0), beInt32(-1), beInt32(math.MinInt32), beInt32(math.MaxInt32), beInt32(42)}
	case "BIGINT":
		return [][]byte{beInt64(0), beInt64(-1), beInt64(math.MinInt64), beInt64(math.MaxInt64), beInt64(1 << 40)}
	case "DOUBLE":
		return [][]byte{beFloat64(0), beFloat64(-1.5), beFloat64(math.SmallestNonzeroFloat64), beFloat64(math.MaxFloat64), beFloat64(3.1415926)}
	case "DATE":
		return [][]byte{beInt32(0), beInt32(-719162), beInt32(2932896), beInt32(1), beInt32(-1)}
	case "BOOLEAN":
		return [][]byte{{0}, {1}, {0}, {1}, {1}}
	case "VARCHAR":
		long := bytes.Repeat([]byte("中文abc"), 9000) // 超长串(≤ 65535 字节上限)
		return [][]byte{[]byte(""), []byte("a"), []byte("wangyu"), []byte("中文值"), long}
	default:
		panic("unknown kind " + kind)
	}
}

var allKinds = []string{"INT", "BIGINT", "DOUBLE", "DATE", "BOOLEAN", "VARCHAR"}

// SC-348 / INV-18:4 种编码(外加 RAW)× 6 种列类型,含 NULL/极值/空串/超长串,
// 解压后逐字节相等。
func TestEncodingRoundTripAllTypes(t *testing.T) {
	encodings := []Encoding{EncRaw, EncRLE, EncDict, EncBitpack, EncPrefix}
	for _, kind := range allKinds {
		samples := typeSamples(kind)
		for _, enc := range encodings {
			t.Run(fmt.Sprintf("%s/%s", kind, enc), func(t *testing.T) {
				data, err := EncodeWith(enc, samples)
				if enc == EncBitpack && kind != "INT" && kind != "BIGINT" && kind != "DOUBLE" && kind != "DATE" {
					if err == nil {
						t.Fatalf("%s 类型不应适用 BITPACK", kind)
					}
					return
				}
				if err != nil {
					t.Fatalf("encode: %v", err)
				}
				got, err := DecodeWith(enc, data, len(samples))
				if err != nil {
					t.Fatalf("decode: %v", err)
				}
				if len(got) != len(samples) {
					t.Fatalf("got %d values, want %d", len(got), len(samples))
				}
				for i := range samples {
					if !bytes.Equal(got[i], samples[i]) {
						t.Fatalf("值 %d 往返不一致: got %x, want %x", i, got[i], samples[i])
					}
				}
			})
		}
	}
}

// RLE / DICT / PREFIX 对"重复 / 低基数 / 共享前缀"必须有实际收益(否则 chunk 自选没意义)。
func TestEncodeBestChoosesCompressingEncoding(t *testing.T) {
	repeated := make([][]byte, 1000)
	for i := range repeated {
		repeated[i] = []byte("same-value")
	}
	if enc, data := EncodeBest(repeated); enc != EncRLE && enc != EncDict {
		t.Fatalf("重复值应选 RLE/DICT,实际 %s(len=%d)", enc, len(data))
	}
	if _, data := EncodeBest(repeated); len(data) > 32 {
		t.Fatalf("RLE 压缩后仍过大: %d", len(data))
	}

	lowCardinality := make([][]byte, 1000)
	for i := range lowCardinality {
		lowCardinality[i] = []byte(fmt.Sprintf("city-%d", i%4))
	}
	if enc, _ := EncodeBest(lowCardinality); enc != EncDict && enc != EncRLE {
		t.Fatalf("低基数列应选 DICT/RLE,实际 %s", enc)
	}

	prefixes := make([][]byte, 1000)
	for i := range prefixes {
		prefixes[i] = []byte(fmt.Sprintf("tangdb/internal/store/table-000000/%08d", i))
	}
	if enc, _ := EncodeBest(prefixes); enc != EncPrefix && enc != EncDict {
		t.Fatalf("共享前缀列应选 PREFIX/DICT,实际 %s", enc)
	}

	// 随机高熵数据:必须仍能往返,且不应比 RAW 更差。
	rnd := rand.New(rand.NewSource(7))
	random := make([][]byte, 200)
	for i := range random {
		b := make([]byte, 24)
		rnd.Read(b)
		random[i] = b
	}
	_, randomData := EncodeBest(random)
	rawData, _ := encodeRaw(random)
	if len(randomData) > len(rawData) {
		t.Fatalf("自选编码比 RAW 更大: %d > %d", len(randomData), len(rawData))
	}
	if enc, _ := EncodeBest(random); enc == EncRLE || enc == EncDict {
		t.Fatalf("高熵随机值不应选 %s", enc)
	}
}

// chunk 头必须携带 encoding / 原始字节 / 压缩后字节 / 行数 / min / max / null_count。
func TestColumnChunkHeaderAndNulls(t *testing.T) {
	values := [][]byte{[]byte("a"), []byte(""), nil, []byte("z"), nil}
	valid := []bool{true, true, false, true, false}
	chunk, info, err := encodeColumnChunk(columnIDValue, values, valid, true)
	if err != nil {
		t.Fatalf("encode chunk: %v", err)
	}
	decoded, got, err := decodeColumnChunk(chunk, columnIDValue)
	if err != nil {
		t.Fatalf("decode chunk: %v", err)
	}
	if decoded.Rows != 5 || decoded.RawBytes != info.RawBytes || decoded.CompBytes != info.CompBytes {
		t.Fatalf("chunk 头统计不一致: %+v vs %+v", decoded, info)
	}
	if decoded.NullCount != 2 {
		t.Fatalf("null_count=%d, want 2", decoded.NullCount)
	}
	if len(decoded.Min) != 0 || string(decoded.Max) != "z" {
		t.Fatalf("min/max 只统计非 NULL(空串是合法最小值): %q/%q", decoded.Min, decoded.Max)
	}
	for i := range values {
		if decoded.Valid[i] != valid[i] {
			t.Fatalf("validity[%d]=%v, want %v", i, decoded.Valid[i], valid[i])
		}
		if valid[i] && !bytes.Equal(got[i], values[i]) {
			t.Fatalf("值 %d 往返不一致: %q vs %q", i, got[i], values[i])
		}
	}
}

// FR-569:布隆误判只允许"多读",不允许漏行(false → 一定不存在)。
func TestBloomHasNoFalseNegatives(t *testing.T) {
	values := make([][]byte, 0, 500)
	for i := 0; i < 500; i++ {
		values = append(values, []byte(fmt.Sprintf("key-%06d", i)))
	}
	valid := make([]bool, len(values))
	for i := range valid {
		valid[i] = true
	}
	bloom := buildBloom(values, valid)
	for _, v := range values {
		if !bloomContains(bloom, v) {
			t.Fatalf("布隆漏掉了存在的值 %q", v)
		}
	}
	absent := 0
	for i := 0; i < 1000; i++ {
		if !bloomContains(bloom, []byte(fmt.Sprintf("absent-%06d", i))) {
			absent++
		}
	}
	if absent == 0 {
		t.Fatalf("10 万位布隆对 1000 个不存在的值全部命中,疑似实现错误")
	}
}

// FR-563 / FR-566:segment 统计里的原始/压缩字节与编码分布来自真实 chunk。
func TestSegmentV2Stats(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000900.seg")
	b := NewBuilder(path)
	for i := 0; i < 5000; i++ {
		b.Add([]byte(fmt.Sprintf("key-%08d", i)), []byte("same-value"))
	}
	footer, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	if footer.Version != formatV2 {
		t.Fatalf("footer version=%d, want 2", footer.Version)
	}
	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()
	if r.Format() != formatV2 {
		t.Fatalf("format=%d, want 2", r.Format())
	}
	st := r.Stats()
	if st.Rows != 5000 || st.Chunks != 2 {
		t.Fatalf("rows/chunks = %d/%d, want 5000/2", st.Rows, st.Chunks)
	}
	if st.CompBytes == 0 || st.RawBytes < st.CompBytes {
		t.Fatalf("压缩统计不合理: raw=%d comp=%d", st.RawBytes, st.CompBytes)
	}
	if st.Ratio <= 1 {
		t.Fatalf("高度可压缩数据应 ratio>1,实际 %.2f", st.Ratio)
	}
	if st.Encodings["PREFIX"]+st.Encodings["DICT"]+st.Encodings["RLE"] == 0 {
		t.Fatalf("编码分布应至少命中一种压缩编码: %v", st.Encodings)
	}
	// 数据仍可按 key 点查(压缩不改语义)。
	got, ok, err := r.Get([]byte("key-00001234"))
	if err != nil || !ok || string(got) != "same-value" {
		t.Fatalf("压缩后点查失败: %q %v %v", got, ok, err)
	}
}

// FR-568 / SC-357:100 万行随机点查,单次只允许触碰 1 个 key chunk + 1 个 value chunk。
func TestPointLookupTouchesAtMostTwoChunks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "001000.seg")
	b := NewBuilder(path)
	const rows = 1_000_000
	for i := 0; i < rows; i++ {
		b.Add([]byte(fmt.Sprintf("key-%012d", i)), []byte(fmt.Sprintf("v-%d", i)))
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish: %v", err)
	}
	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()

	rnd := rand.New(rand.NewSource(42))
	for i := 0; i < 10_000; i++ {
		target := rnd.Intn(rows)
		r.ResetStats()
		key := []byte(fmt.Sprintf("key-%012d", target))
		got, ok, err := r.Get(key)
		if err != nil {
			t.Fatalf("get %d: %v", target, err)
		}
		if !ok || string(got) != fmt.Sprintf("v-%d", target) {
			t.Fatalf("get %d: got %q ok=%v", target, got, ok)
		}
		if n := r.ChunkTouches(); n > 2 {
			t.Fatalf("点查触碰 %d 个 chunk(上限 2)", n)
		}
	}
	// 不存在的 key:布隆/二分应能剪枝,同样不超上限。
	for i := 0; i < 1000; i++ {
		r.ResetStats()
		key := []byte(fmt.Sprintf("missing-%011d", i))
		if _, ok, err := r.Get(key); err != nil || ok {
			t.Fatalf("missing key: ok=%v err=%v", ok, err)
		}
		if n := r.ChunkTouches(); n > 2 {
			t.Fatalf("不存在的 key 触碰 %d 个 chunk(上限 2)", n)
		}
	}
}

// 排序/扫描下所有键值必须与写入完全一致(压缩不改比较语义)。
func TestIteratorAfterCompression(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000901.seg")
	b := NewBuilder(path)
	const rows = 9000
	for i := 0; i < rows; i++ {
		b.Add([]byte(fmt.Sprintf("k-%06d", i)), []byte(fmt.Sprintf("value-%d", i%37)))
	}
	if _, err := b.Finish(); err != nil {
		t.Fatalf("finish: %v", err)
	}
	r, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer r.Close()
	it := r.NewIterator()
	i := 0
	for it.First(); it.Valid(); it.Next() {
		if string(it.Key()) != fmt.Sprintf("k-%06d", i) {
			t.Fatalf("第 %d 条 key=%q", i, it.Key())
		}
		if string(it.Value()) != fmt.Sprintf("value-%d", i%37) {
			t.Fatalf("第 %d 条 value=%q", i, it.Value())
		}
		i++
	}
	if err := it.Err(); err != nil {
		t.Fatalf("iter: %v", err)
	}
	if i != rows {
		t.Fatalf("扫描到 %d 行,期望 %d", i, rows)
	}
	// Seek 到中间。
	it2 := r.NewIterator()
	it2.Seek([]byte("k-004500"))
	if !it2.Valid() || string(it2.Key()) != "k-004500" {
		t.Fatalf("Seek 定位错误: %q valid=%v", it2.Key(), it2.Valid())
	}
}

// P1~P4 的 v1 segment 必须仍可按老布局读取(P4 数据目录不需要清空)。
func TestLegacyV1SegmentStillReadable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "000001.seg")

	keys := [][]byte{[]byte("k-1"), []byte("k-2"), []byte("k-3")}
	values := [][]byte{[]byte("v1"), []byte("v2"), []byte("v3")}

	var keyCol, valCol bytes.Buffer
	for i := range keys {
		writeBytesWithLen(&keyCol, keys[i])
		writeBytesWithLen(&valCol, values[i])
	}
	index := &bytes.Buffer{}
	writeBytesWithLen(index, keys[0])
	binary.Write(index, binary.LittleEndian, uint64(0))
	binary.Write(index, binary.LittleEndian, uint64(0))

	zone := &bytes.Buffer{}
	writeBytesWithLen(zone, keys[0])
	writeBytesWithLen(zone, keys[len(keys)-1])
	writeBytesWithLen(zone, values[0])
	writeBytesWithLen(zone, values[len(values)-1])
	binary.Write(zone, binary.LittleEndian, uint32(len(keys)))

	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	indexOff, _ := f.Seek(0, 1)
	f.Write(index.Bytes())
	zoneOff, _ := f.Seek(0, 1)
	f.Write(zone.Bytes())
	keyOff, _ := f.Seek(0, 1)
	f.Write(keyCol.Bytes())
	valOff, _ := f.Seek(0, 1)
	f.Write(valCol.Bytes())

	buf := make([]byte, footerSizeV1)
	copy(buf[0:8], magicV1)
	binary.LittleEndian.PutUint32(buf[8:12], 1)
	binary.LittleEndian.PutUint64(buf[12:20], uint64(indexOff))
	binary.LittleEndian.PutUint64(buf[20:28], uint64(index.Len()))
	binary.LittleEndian.PutUint64(buf[28:36], uint64(zoneOff))
	binary.LittleEndian.PutUint64(buf[36:44], uint64(zone.Len()))
	binary.LittleEndian.PutUint64(buf[44:52], uint64(keyOff))
	binary.LittleEndian.PutUint64(buf[52:60], uint64(keyCol.Len()))
	binary.LittleEndian.PutUint64(buf[60:68], uint64(valOff))
	binary.LittleEndian.PutUint64(buf[68:76], uint64(valCol.Len()))
	binary.LittleEndian.PutUint64(buf[76:84], 3)
	binary.LittleEndian.PutUint32(buf[footerSizeV1-4:], crc32.Checksum(buf[:footerSizeV1-4], castagnoli))
	f.Write(buf)
	f.Close()

	r, err := Open(path)
	if err != nil {
		t.Fatalf("open v1: %v", err)
	}
	defer r.Close()
	if r.Format() != formatV1 {
		t.Fatalf("format=%d, want 1", r.Format())
	}
	for i := range keys {
		got, ok, err := r.Get(keys[i])
		if err != nil || !ok || !bytes.Equal(got, values[i]) {
			t.Fatalf("v1 get %q: %q %v %v", keys[i], got, ok, err)
		}
	}
	it := r.NewIterator()
	i := 0
	for it.First(); it.Valid(); it.Next() {
		if !bytes.Equal(it.Key(), keys[i]) || !bytes.Equal(it.Value(), values[i]) {
			t.Fatalf("v1 iter %d: %q=%q", i, it.Key(), it.Value())
		}
		i++
	}
	if i != len(keys) {
		t.Fatalf("v1 iter rows=%d", i)
	}
}
