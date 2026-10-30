// Package segment 实现列式 segment 文件的写入与读取。
//
// 文件布局(v2,SPEC-P5 §5.1 / FR-563 ~ FR-570,从文件头到文件尾):
//
//	Chunk Area:  每条记录 = 一个 column chunk(键列 / 值列各一段)
//	Index Block: 每 chunk 的首 key、min/max、chunk 偏移/长度/头长、行数、编码与字节统计
//	Footer:      固定长度,位于文件末尾,含各区块偏移、总行数、编码命中数与校验和
//
// 每个 chunk 自带:编码类型、行数、原始字节、压缩后字节、min/max、null_count、
// validity 位图、可选布隆过滤、块内偏移数组(FR-568)。
//
// 压缩只作用在"保序编码后的字节"上:解压后逐字节相等,比较语义不变(INV-18)。
// v1 文件(P1~P4 产生)仍按老布局读取,不需要清空数据目录。
package segment

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	chunkRows = 4096
	magicV1   = "TANSEG01"
	magicV2   = "TANSEG02"

	// footerSizeV1 是旧布局的 footer 长度:magic + version + 8 个 offset/len + rows + crc。
	footerSizeV1 = 8 + 4 + 8*8 + 8 + 4
	// footerSizeV2:magic + version + 4 个 offset/len + rows + raw + comp + 5 个编码计数 + crc。
	footerSizeV2 = 8 + 4 + 8*4 + 8 + 8 + 8 + 8*encodingCount + 4

	formatV1 = 1
	formatV2 = 2
	version  = formatV2
)

var castagnoli = crc32.MakeTable(crc32.Castagnoli)

// Builder 将有序 KV 序列写入一个 segment 文件。
type Builder struct {
	path         string
	rows         []kv
	bloomEnabled bool
}

type kv struct {
	key   []byte
	value []byte
}

// Footer 是文件尾部的元信息。
type Footer struct {
	Version          uint32
	IndexBlockOffset uint64
	IndexBlockLen    uint64
	ChunkAreaOffset  uint64
	ChunkAreaLen     uint64
	TotalRows        uint64
	RawBytes         uint64
	CompBytes        uint64
	EncodingCounts   [encodingCount]uint64

	// 仅 v1 需要:两列各自的起点/总长(offset 相对列起点)。
	ValueColumnOffset uint64
	KeyColumnLen      uint64
	ValueColumnLen    uint64
}

// NewBuilder 创建指定路径的 segment 构建器(chunk 级布隆默认开启)。
func NewBuilder(path string) *Builder {
	return &Builder{path: path, bloomEnabled: true}
}

// NewBloomAwareBuilder 按配置决定是否写 chunk 级布隆(compaction_bloom_enabled)。
func NewBloomAwareBuilder(path string, bloomEnabled bool) *Builder {
	return &Builder{path: path, bloomEnabled: bloomEnabled}
}

// Add 追加一条有序 KV。调用方须保证按 key 升序加入。
func (b *Builder) Add(key, value []byte) {
	b.rows = append(b.rows, kv{key: append([]byte(nil), key...), value: append([]byte(nil), value...)})
}

// Len 返回当前已添加的行数。
func (b *Builder) Len() int { return len(b.rows) }

// Finish 写入文件并返回 Footer。文件落盘后只读。
func (b *Builder) Finish() (*Footer, error) {
	if len(b.rows) == 0 {
		return nil, errors.New("segment: empty segment")
	}
	_ = os.MkdirAll(filepath.Dir(b.path), 0750)
	tmp := b.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_RDWR|os.O_TRUNC, 0640)
	if err != nil {
		return nil, fmt.Errorf("segment: create temp file: %w", err)
	}
	defer os.Remove(tmp)

	numChunks := (len(b.rows) + chunkRows - 1) / chunkRows
	entries := make([]indexEntry, numChunks)
	chunkArea := &bytes.Buffer{}
	footer := &Footer{Version: version, TotalRows: uint64(len(b.rows))}

	for ci := 0; ci < numChunks; ci++ {
		start := ci * chunkRows
		end := start + chunkRows
		if end > len(b.rows) {
			end = len(b.rows)
		}
		rows := end - start
		keys := make([][]byte, rows)
		values := make([][]byte, rows)
		valid := make([]bool, rows)
		for i := 0; i < rows; i++ {
			keys[i] = b.rows[start+i].key
			values[i] = b.rows[start+i].value
			valid[i] = true
		}

		keyChunk, keyInfo, err := encodeColumnChunk(columnIDKey, keys, valid, b.bloomEnabled)
		if err != nil {
			return nil, fmt.Errorf("segment: encode key chunk %d: %w", ci, err)
		}
		keyOff := uint64(chunkArea.Len())
		chunkArea.Write(keyChunk)

		valueChunk, valueInfo, err := encodeColumnChunk(columnIDValue, values, valid, b.bloomEnabled)
		if err != nil {
			return nil, fmt.Errorf("segment: encode value chunk %d: %w", ci, err)
		}
		valueOff := uint64(chunkArea.Len())
		chunkArea.Write(valueChunk)

		footer.RawBytes += uint64(keyInfo.RawBytes + valueInfo.RawBytes)
		footer.CompBytes += uint64(keyInfo.CompBytes + valueInfo.CompBytes)
		footer.EncodingCounts[keyInfo.Encoding]++
		footer.EncodingCounts[valueInfo.Encoding]++

		entries[ci] = indexEntry{
			firstKey:       append([]byte(nil), keys[0]...),
			keyMin:         append([]byte(nil), keyInfo.Min...),
			keyMax:         append([]byte(nil), keyInfo.Max...),
			keyOffset:      keyOff,
			keyLen:         len(keyChunk),
			keyHeaderLen:   keyInfo.headerLen,
			keyEnc:         keyInfo.Encoding,
			keyRaw:         keyInfo.RawBytes,
			keyComp:        keyInfo.CompBytes,
			keyNull:        keyInfo.NullCount,
			valueOffset:    valueOff,
			valueLen:       len(valueChunk),
			valueHeaderLen: valueInfo.headerLen,
			valueEnc:       valueInfo.Encoding,
			valueRaw:       valueInfo.RawBytes,
			valueComp:      valueInfo.CompBytes,
			valueNull:      valueInfo.NullCount,
			rowCount:       rows,
		}
	}

	chunkAreaOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	if _, err := f.Write(chunkArea.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write chunk area: %w", err)
	}

	indexOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	indexBuf := &bytes.Buffer{}
	for _, e := range entries {
		writeIndexEntry(indexBuf, e)
	}
	if _, err := f.Write(indexBuf.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write index: %w", err)
	}

	footer.ChunkAreaOffset = uint64(chunkAreaOffset)
	footer.ChunkAreaLen = uint64(chunkArea.Len())
	footer.ValueColumnOffset = uint64(chunkAreaOffset)
	footer.KeyColumnLen = uint64(chunkArea.Len())
	footer.ValueColumnLen = uint64(chunkArea.Len())
	footer.IndexBlockOffset = uint64(indexOffset)
	footer.IndexBlockLen = uint64(indexBuf.Len())
	if err := writeFooterV2(f, footer); err != nil {
		return nil, fmt.Errorf("segment: write footer: %w", err)
	}
	if err := f.Sync(); err != nil {
		return nil, fmt.Errorf("segment: fsync: %w", err)
	}
	if err := f.Close(); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, b.path); err != nil {
		return nil, fmt.Errorf("segment: rename: %w", err)
	}
	return footer, nil
}

// indexEntry 是 Index Block 里的一条(同时覆盖 v1/v2;v1 只有前几个字段有意义)。
type indexEntry struct {
	firstKey []byte
	keyMin   []byte
	keyMax   []byte

	keyOffset    uint64
	keyLen       int
	keyHeaderLen int
	keyEnc       Encoding
	keyRaw       int
	keyComp      int
	keyNull      int

	valueOffset    uint64
	valueLen       int
	valueHeaderLen int
	valueEnc       Encoding
	valueRaw       int
	valueComp      int
	valueNull      int

	rowCount int
}

func writeBytesWithLen(w *bytes.Buffer, b []byte) {
	var tmp [4]byte
	binary.LittleEndian.PutUint32(tmp[:], uint32(len(b)))
	w.Write(tmp[:])
	w.Write(b)
}

func writeIndexEntry(w *bytes.Buffer, e indexEntry) {
	writeBytesWithLen(w, e.firstKey)
	writeBytesWithLen(w, e.keyMin)
	writeBytesWithLen(w, e.keyMax)
	binary.Write(w, binary.LittleEndian, e.keyOffset)
	binary.Write(w, binary.LittleEndian, uint32(e.keyLen))
	binary.Write(w, binary.LittleEndian, uint32(e.keyHeaderLen))
	binary.Write(w, binary.LittleEndian, e.valueOffset)
	binary.Write(w, binary.LittleEndian, uint32(e.valueLen))
	binary.Write(w, binary.LittleEndian, uint32(e.valueHeaderLen))
	binary.Write(w, binary.LittleEndian, uint32(e.rowCount))
	w.WriteByte(byte(e.keyEnc))
	w.WriteByte(byte(e.valueEnc))
	binary.Write(w, binary.LittleEndian, uint32(e.keyRaw))
	binary.Write(w, binary.LittleEndian, uint32(e.keyComp))
	binary.Write(w, binary.LittleEndian, uint32(e.valueRaw))
	binary.Write(w, binary.LittleEndian, uint32(e.valueComp))
	binary.Write(w, binary.LittleEndian, uint32(e.keyNull))
	binary.Write(w, binary.LittleEndian, uint32(e.valueNull))
}

func readIndexEntryV2(buf []byte, pos *int) (indexEntry, error) {
	e := indexEntry{}
	readBytes := func() ([]byte, error) {
		if *pos+4 > len(buf) {
			return nil, errors.New("segment: index entry truncated")
		}
		l := int(binary.LittleEndian.Uint32(buf[*pos:]))
		*pos += 4
		if *pos+l > len(buf) {
			return nil, errors.New("segment: index entry truncated")
		}
		b := make([]byte, l)
		copy(b, buf[*pos:*pos+l])
		*pos += l
		return b, nil
	}
	var err error
	if e.firstKey, err = readBytes(); err != nil {
		return e, err
	}
	if e.keyMin, err = readBytes(); err != nil {
		return e, err
	}
	if e.keyMax, err = readBytes(); err != nil {
		return e, err
	}
	need := func(n int) error {
		if *pos+n > len(buf) {
			return errors.New("segment: index entry truncated")
		}
		return nil
	}
	if err := need(24); err != nil {
		return e, err
	}
	e.keyOffset = binary.LittleEndian.Uint64(buf[*pos:])
	*pos += 8
	e.keyLen = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.keyHeaderLen = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.valueOffset = binary.LittleEndian.Uint64(buf[*pos:])
	*pos += 8
	if err := need(4); err != nil {
		return e, err
	}
	e.valueLen = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	if err := need(4); err != nil {
		return e, err
	}
	e.valueHeaderLen = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	if err := need(4); err != nil {
		return e, err
	}
	e.rowCount = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	if err := need(2); err != nil {
		return e, err
	}
	e.keyEnc = Encoding(buf[*pos])
	e.valueEnc = Encoding(buf[*pos+1])
	*pos += 2
	if !validEncoding(e.keyEnc) || !validEncoding(e.valueEnc) {
		return e, fmt.Errorf("segment: index 编码非法(%d,%d)", e.keyEnc, e.valueEnc)
	}
	if err := need(24); err != nil {
		return e, err
	}
	e.keyRaw = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.keyComp = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.valueRaw = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.valueComp = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.keyNull = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	e.valueNull = int(binary.LittleEndian.Uint32(buf[*pos:]))
	*pos += 4
	return e, nil
}

func writeFooterV2(f *os.File, footer *Footer) error {
	buf := make([]byte, footerSizeV2)
	copy(buf[0:8], magicV2)
	binary.LittleEndian.PutUint32(buf[8:12], footer.Version)
	binary.LittleEndian.PutUint64(buf[12:20], footer.IndexBlockOffset)
	binary.LittleEndian.PutUint64(buf[20:28], footer.IndexBlockLen)
	binary.LittleEndian.PutUint64(buf[28:36], footer.ChunkAreaOffset)
	binary.LittleEndian.PutUint64(buf[36:44], footer.ChunkAreaLen)
	binary.LittleEndian.PutUint64(buf[44:52], footer.TotalRows)
	binary.LittleEndian.PutUint64(buf[52:60], footer.RawBytes)
	binary.LittleEndian.PutUint64(buf[60:68], footer.CompBytes)
	pos := 68
	for _, n := range footer.EncodingCounts {
		binary.LittleEndian.PutUint64(buf[pos:pos+8], n)
		pos += 8
	}
	crc := crc32.Checksum(buf[:footerSizeV2-4], castagnoli)
	binary.LittleEndian.PutUint32(buf[footerSizeV2-4:], crc)
	_, err := f.Write(buf)
	return err
}

// Reader 打开一个 segment 文件进行读取。
type Reader struct {
	path   string
	file   *os.File
	footer *Footer
	format int

	index   []indexEntry
	zoneMap []zoneEntry // v1 兼容

	statMu        sync.Mutex
	chunkTouches  int
	headerTouches int
	bytesRead     int64

	// SPEC-P6.1 FR-706:chunk 解码次数 / 因 key 范围被跳过的 chunk 数(SHOW SEGMENTS 用)。
	chunksRead    atomic.Uint64
	chunksSkipped atomic.Uint64

	// SPEC-P7.1:chunk 解码缓存的读者标识(见 chunkcache.go)。
	cacheID uint64
}

type zoneEntry struct { // 仅 v1
	keyMin   []byte
	keyMax   []byte
	valueMin []byte
	valueMax []byte
	rowCount uint32
}

// Open 打开 segment 文件(v1 与 v2 都支持)。
func Open(path string) (*Reader, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("segment: open %s: %w", path, err)
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	if info.Size() < int64(footerSizeV1) {
		f.Close()
		return nil, fmt.Errorf("segment: file too small %s", path)
	}
	footer, format, err := readFooter(f, info.Size())
	if err != nil {
		f.Close()
		return nil, err
	}
	r := &Reader{path: path, file: f, footer: footer, format: format,
		cacheID: readerSeq.Add(1)}
	if format == formatV1 {
		if err := r.loadIndexV1(); err != nil {
			r.Close()
			return nil, err
		}
		if err := r.loadZoneMapV1(); err != nil {
			r.Close()
			return nil, err
		}
	} else {
		if err := r.loadIndexV2(); err != nil {
			r.Close()
			return nil, err
		}
	}
	return r, nil
}

// Close 关闭 segment 文件。
func (r *Reader) Close() error {
	if r.file == nil {
		return nil
	}
	globalChunkCache.drop(r.cacheID)
	err := r.file.Close()
	r.file = nil
	return err
}

// Path 返回文件路径。
func (r *Reader) Path() string { return r.path }

// Footer 返回文件 footer 副本。
func (r *Reader) Footer() Footer { return *r.footer }

// Format 返回文件格式版本(1 或 2)。
func (r *Reader) Format() int { return r.format }

// Get 按 key 查找 value;不存在时 ok=false。
func (r *Reader) Get(key []byte) (value []byte, ok bool, err error) {
	if r.format == formatV1 {
		return r.getV1(key)
	}
	return r.getV2(key)
}

// getV2 走 Index Block 二分 → 块头布隆 → 块内二分,不做块内线性查找(FR-568 / FR-569)。
func (r *Reader) getV2(key []byte) ([]byte, bool, error) {
	if len(r.index) == 0 {
		return nil, false, nil
	}
	// 找到第一个 keyMax >= target 的 chunk。
	ci := sort.Search(len(r.index), func(i int) bool {
		return bytes.Compare(r.index[i].keyMax, key) >= 0
	})
	if ci >= len(r.index) {
		return nil, false, nil
	}
	e := r.index[ci]
	if bytes.Compare(key, e.keyMin) < 0 || bytes.Compare(key, e.keyMax) > 0 {
		return nil, false, nil
	}
	// 读 chunk 头做布隆过滤(误判不漏行)。
	header, err := r.readAt(r.footer.ChunkAreaOffset+e.keyOffset, e.keyHeaderLen, true)
	if err != nil {
		return nil, false, err
	}
	if len(header) >= 5 {
		// bloom 在块头里;parse 出 bloom 段(不读压缩数据)。
		if !bloomAllows(header, key) {
			return nil, false, nil
		}
	}
	keyChunk, err := r.readAt(r.footer.ChunkAreaOffset+e.keyOffset, e.keyLen, false)
	if err != nil {
		return nil, false, err
	}
	keyInfo, keys, err := decodeColumnChunk(keyChunk, columnIDKey)
	if err != nil {
		return nil, false, err
	}
	pos := sort.Search(len(keys), func(i int) bool { return bytes.Compare(keys[i], key) >= 0 })
	if pos >= len(keys) || !bytes.Equal(keys[pos], key) {
		return nil, false, nil
	}
	valueChunk, err := r.readAt(r.footer.ValueColumnOffset+e.valueOffset, e.valueLen, false)
	if err != nil {
		return nil, false, err
	}
	_, values, err := decodeColumnChunk(valueChunk, columnIDValue)
	if err != nil {
		return nil, false, err
	}
	if pos >= len(values) {
		return nil, false, fmt.Errorf("segment: value chunk 行数不足(key 命中第 %d 行,值 %d 行)",
			pos, len(values))
	}
	_ = keyInfo
	return values[pos], true, nil
}

func (r *Reader) getV1(key []byte) ([]byte, bool, error) {
	for i, z := range r.zoneMap {
		if bytes.Compare(key, z.keyMin) < 0 || bytes.Compare(key, z.keyMax) > 0 {
			continue
		}
		val, found, err := r.searchChunkV1(i, key)
		if err != nil {
			return nil, false, err
		}
		if found {
			return val, true, nil
		}
	}
	return nil, false, nil
}

func (r *Reader) searchChunkV1(chunkIdx int, key []byte) ([]byte, bool, error) {
	entry := r.index[chunkIdx]
	zone := r.zoneMap[chunkIdx]
	keyChunk, err := r.readAt(r.footer.ChunkAreaOffset+entry.keyOffset, entry.keyLen, false)
	if err != nil {
		return nil, false, err
	}
	valueChunk, err := r.readAt(r.footer.ValueColumnOffset+entry.valueOffset, entry.valueLen, false)
	if err != nil {
		return nil, false, err
	}
	keys := decodeBytesSeq(keyChunk)
	values := decodeBytesSeq(valueChunk)
	if len(keys) != int(zone.rowCount) || len(values) != int(zone.rowCount) {
		return nil, false, fmt.Errorf("segment: chunk %d row count mismatch", chunkIdx)
	}
	pos := sort.Search(len(keys), func(i int) bool { return bytes.Compare(keys[i], key) >= 0 })
	if pos < len(keys) && bytes.Equal(keys[pos], key) {
		return values[pos], true, nil
	}
	return nil, false, nil
}

// readAt 读取指定长度;track 为 true 时记作"只读了头部"。
func (r *Reader) readAt(offset uint64, length int, headerOnly bool) ([]byte, error) {
	buf := make([]byte, length)
	if _, err := r.file.ReadAt(buf, int64(offset)); err != nil {
		return nil, fmt.Errorf("segment: read block at %d: %w", offset, err)
	}
	r.statMu.Lock()
	if headerOnly {
		r.headerTouches++
	} else {
		r.chunkTouches++
	}
	r.bytesRead += int64(length)
	r.statMu.Unlock()
	return buf, nil
}

func (r *Reader) loadIndexV2() error {
	buf := make([]byte, r.footer.IndexBlockLen)
	if _, err := r.file.ReadAt(buf, int64(r.footer.IndexBlockOffset)); err != nil {
		return fmt.Errorf("segment: read index: %w", err)
	}
	r.index = nil
	pos := 0
	for pos < len(buf) {
		e, err := readIndexEntryV2(buf, &pos)
		if err != nil {
			return err
		}
		r.index = append(r.index, e)
	}
	if len(r.index) == 0 {
		return errors.New("segment: 空 index block")
	}
	return nil
}

func (r *Reader) loadIndexV1() error {
	buf := make([]byte, r.footer.IndexBlockLen)
	if _, err := r.file.ReadAt(buf, int64(r.footer.IndexBlockOffset)); err != nil {
		return fmt.Errorf("segment: read index: %w", err)
	}
	r.index = nil
	pos := 0
	for pos < len(buf) {
		if pos+4 > len(buf) {
			return errors.New("segment: index entry truncated")
		}
		keyLen := int(binary.LittleEndian.Uint32(buf[pos : pos+4]))
		pos += 4
		if pos+keyLen+16 > len(buf) {
			return errors.New("segment: index entry truncated")
		}
		key := make([]byte, keyLen)
		copy(key, buf[pos:pos+keyLen])
		pos += keyLen
		keyOff := binary.LittleEndian.Uint64(buf[pos : pos+8])
		pos += 8
		valOff := binary.LittleEndian.Uint64(buf[pos : pos+8])
		pos += 8
		r.index = append(r.index, indexEntry{firstKey: key, keyOffset: keyOff, valueOffset: valOff})
	}
	return nil
}

func (r *Reader) loadZoneMapV1() error {
	// v1: ZoneMap 紧接在 Index Block 之后。
	zoneOffset := r.footer.IndexBlockOffset + r.footer.IndexBlockLen
	zoneLen := r.footer.ChunkAreaLen // v1 里 ChunkAreaLen 复用为 ZoneMapLen
	if zoneLen == 0 {
		return nil
	}
	buf := make([]byte, zoneLen)
	if _, err := r.file.ReadAt(buf, int64(zoneOffset)); err != nil {
		return fmt.Errorf("segment: read zonemap: %w", err)
	}
	r.zoneMap = nil
	pos := 0
	for pos < len(buf) {
		readField := func() ([]byte, error) {
			if pos+4 > len(buf) {
				return nil, errors.New("segment: zonemap field truncated")
			}
			l := int(binary.LittleEndian.Uint32(buf[pos : pos+4]))
			pos += 4
			if pos+l > len(buf) {
				return nil, errors.New("segment: zonemap field truncated")
			}
			b := make([]byte, l)
			copy(b, buf[pos:pos+l])
			pos += l
			return b, nil
		}
		var err error
		z := zoneEntry{}
		if z.keyMin, err = readField(); err != nil {
			return err
		}
		if z.keyMax, err = readField(); err != nil {
			return err
		}
		if z.valueMin, err = readField(); err != nil {
			return err
		}
		if z.valueMax, err = readField(); err != nil {
			return err
		}
		if pos+4 > len(buf) {
			return errors.New("segment: zonemap rowcount truncated")
		}
		z.rowCount = binary.LittleEndian.Uint32(buf[pos : pos+4])
		pos += 4
		r.zoneMap = append(r.zoneMap, z)
		// 补齐 v1 index 的 keyMin/keyMax/rowCount 以便统一迭代器。
		idx := len(r.zoneMap) - 1
		if idx < len(r.index) {
			r.index[idx].keyMin = z.keyMin
			r.index[idx].keyMax = z.keyMax
			r.index[idx].rowCount = int(z.rowCount)
			r.index[idx].keyEnc = EncRaw
			r.index[idx].valueEnc = EncRaw
		}
	}
	// v1 的 chunk 长度在 index 里没有记录,由相邻 chunk 起点差推出来。
	for i := range r.index {
		if i+1 < len(r.index) {
			r.index[i].keyLen = int(r.index[i+1].keyOffset - r.index[i].keyOffset)
			r.index[i].valueLen = int(r.index[i+1].valueOffset - r.index[i].valueOffset)
		} else {
			r.index[i].keyLen = int(r.footer.KeyColumnLen - r.index[i].keyOffset)
			r.index[i].valueLen = int(r.footer.ValueColumnLen - r.index[i].valueOffset)
		}
	}
	return nil
}

// ---------------------------------------------------------------- 诊断(FR-566)

// SegmentStats 是一个 segment 文件的真实字节统计。
type SegmentStats struct {
	Path      string
	Format    int
	Rows      int
	Chunks    int
	RawBytes  uint64
	CompBytes uint64
	Ratio     float64
	Encodings map[string]int
}

// Stats 返回该文件的真实统计(读自 Index Block 与 Footer)。
func (r *Reader) Stats() SegmentStats {
	st := SegmentStats{
		Path:      r.path,
		Format:    r.format,
		Rows:      len(r.index) * chunkRows,
		Chunks:    len(r.index),
		RawBytes:  r.footer.RawBytes,
		CompBytes: r.footer.CompBytes,
		Encodings: map[string]int{},
	}
	total := 0
	for _, e := range r.index {
		total += e.rowCount
		st.Encodings[e.keyEnc.String()]++
		st.Encodings[e.valueEnc.String()]++
	}
	st.Rows = total
	if r.format == formatV1 {
		// v1 无压缩:原始=压缩(含 4 字节长度前缀)。
		var raw uint64
		for _, e := range r.index {
			raw += uint64(e.keyLen + e.valueLen)
		}
		st.RawBytes, st.CompBytes = raw, raw
	}
	if st.CompBytes > 0 {
		st.Ratio = float64(st.RawBytes) / float64(st.CompBytes)
	}
	return st
}

// ChunksRead 返回已解码的 chunk 数(SPEC-P6.1 FR-706)。
func (r *Reader) ChunksRead() uint64 { return r.chunksRead.Load() }

// ChunksSkipped 返回因 key 范围(Index/ZoneMap 二分)被跳过的 chunk 数。
func (r *Reader) ChunksSkipped() uint64 { return r.chunksSkipped.Load() }

// AddSkippedChunks 由迭代器在 Seek 跳过前缀 chunk 时调用。
func (r *Reader) AddSkippedChunks(n int) {
	if n > 0 {
		r.chunksSkipped.Add(uint64(n))
	}
}

// AddReadChunk 由迭代器在解码一个 chunk 时调用。
func (r *Reader) AddReadChunk() { r.chunksRead.Add(1) }

// ChunkTouches/HeaderTouches/BytesRead 是读放大观测口径(INV 观测清单)。
func (r *Reader) ChunkTouches() int {
	r.statMu.Lock()
	defer r.statMu.Unlock()
	return r.chunkTouches
}

// HeaderTouches 返回只读了块头的次数。
func (r *Reader) HeaderTouches() int {
	r.statMu.Lock()
	defer r.statMu.Unlock()
	return r.headerTouches
}

// BytesRead 返回累计读取字节数。
func (r *Reader) BytesRead() int64 {
	r.statMu.Lock()
	defer r.statMu.Unlock()
	return r.bytesRead
}

// ResetStats 清零读统计。
func (r *Reader) ResetStats() {
	r.statMu.Lock()
	r.chunkTouches = 0
	r.headerTouches = 0
	r.bytesRead = 0
	r.statMu.Unlock()
}

// bloomAllows 从 chunk 头里取布隆段并判断;头部残缺时保守返回 true。
func bloomAllows(header []byte, key []byte) bool {
	info, err := parseChunkHeader(header, columnIDKey)
	if err != nil {
		return true
	}
	if len(info.bloomBits) == 0 {
		return true
	}
	// 重新拼出 bloom 段([k][mBytes][bits])以便复用 bloomContains。
	bloom := make([]byte, 0, 5+len(info.bloomBits))
	bloom = append(bloom, byte(info.bloomK))
	var m [4]byte
	binary.LittleEndian.PutUint32(m[:], uint32(len(info.bloomBits)))
	bloom = append(bloom, m[:]...)
	bloom = append(bloom, info.bloomBits...)
	return bloomContains(bloom, key)
}

func decodeBytesSeq(data []byte) [][]byte {
	var out [][]byte
	pos := 0
	for pos < len(data) {
		if pos+4 > len(data) {
			break
		}
		l := int(binary.LittleEndian.Uint32(data[pos : pos+4]))
		pos += 4
		if pos+l > len(data) {
			break
		}
		b := make([]byte, l)
		copy(b, data[pos:pos+l])
		pos += l
		out = append(out, b)
	}
	return out
}

// ---------------------------------------------------------------- Footer 读写(v1/v2)

func readFooter(f *os.File, size int64) (*Footer, int, error) {
	tail := make([]byte, 8)
	if _, err := f.ReadAt(tail, size-8); err != nil {
		return nil, 0, fmt.Errorf("segment: read footer tail: %w", err)
	}
	// 两个 magic 都以 "TANSEG0" 开头;用倒数第 8 字节所在位置判断不了,
	// 因此先按 v2 长度尝试,失败再按 v1(旧文件尾部 magic 在固定偏移)。
	if size >= int64(footerSizeV2) {
		buf := make([]byte, footerSizeV2)
		if _, err := f.ReadAt(buf, size-int64(footerSizeV2)); err == nil && string(buf[0:8]) == magicV2 {
			ver := binary.LittleEndian.Uint32(buf[8:12])
			if ver != formatV2 {
				return nil, 0, fmt.Errorf("segment: unsupported version %d", ver)
			}
			crcStored := binary.LittleEndian.Uint32(buf[footerSizeV2-4:])
			crcComputed := crc32.Checksum(buf[:footerSizeV2-4], castagnoli)
			if crcStored != crcComputed {
				return nil, 0, errors.New("segment: footer checksum mismatch")
			}
			ft := &Footer{
				Version:          ver,
				IndexBlockOffset: binary.LittleEndian.Uint64(buf[12:20]),
				IndexBlockLen:    binary.LittleEndian.Uint64(buf[20:28]),
				ChunkAreaOffset:  binary.LittleEndian.Uint64(buf[28:36]),
				ChunkAreaLen:     binary.LittleEndian.Uint64(buf[36:44]),
				TotalRows:        binary.LittleEndian.Uint64(buf[44:52]),
				RawBytes:         binary.LittleEndian.Uint64(buf[52:60]),
				CompBytes:        binary.LittleEndian.Uint64(buf[60:68]),
			}
			pos := 68
			for i := 0; i < encodingCount; i++ {
				ft.EncodingCounts[i] = binary.LittleEndian.Uint64(buf[pos : pos+8])
				pos += 8
			}
			return ft, formatV2, nil
		}
	}

	buf := make([]byte, footerSizeV1)
	if _, err := f.ReadAt(buf, size-int64(footerSizeV1)); err != nil {
		return nil, 0, fmt.Errorf("segment: read footer: %w", err)
	}
	if string(buf[0:8]) != magicV1 {
		return nil, 0, fmt.Errorf("segment: bad magic %q", buf[0:8])
	}
	ver := binary.LittleEndian.Uint32(buf[8:12])
	if ver != 1 {
		return nil, 0, fmt.Errorf("segment: unsupported version %d", ver)
	}
	crcStored := binary.LittleEndian.Uint32(buf[footerSizeV1-4:])
	crcComputed := crc32.Checksum(buf[:footerSizeV1-4], castagnoli)
	if crcStored != crcComputed {
		return nil, 0, errors.New("segment: footer checksum mismatch")
	}
	// v1 布局:Index/ZoneMap/KeyColumn/ValueColumn 四组 offset+len,再 rows。
	ft := &Footer{
		Version:           ver,
		IndexBlockOffset:  binary.LittleEndian.Uint64(buf[12:20]),
		IndexBlockLen:     binary.LittleEndian.Uint64(buf[20:28]),
		ChunkAreaOffset:   binary.LittleEndian.Uint64(buf[44:52]), // KeyColumnOffset
		ChunkAreaLen:      binary.LittleEndian.Uint64(buf[36:44]), // ZoneMapLen
		ValueColumnOffset: binary.LittleEndian.Uint64(buf[60:68]), // ValueColumnOffset
		KeyColumnLen:      binary.LittleEndian.Uint64(buf[52:60]),
		ValueColumnLen:    binary.LittleEndian.Uint64(buf[68:76]),
		TotalRows:         binary.LittleEndian.Uint64(buf[76:84]),
	}
	return ft, formatV1, nil
}

// ParseName 解析 segment 文件名,例如 000045.seg -> 45。
func ParseName(name string) (number uint64, ok bool) {
	if !strings.HasSuffix(name, ".seg") {
		return 0, false
	}
	n, err := strconv.ParseUint(name[:len(name)-4], 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}

// Name 返回 segment 文件名。
func Name(number uint64) string {
	return fmt.Sprintf("%06d.seg", number)
}

// ListNumbers 列出目录下所有 segment 编号并按新到旧排序(编号大的在前)。
func ListNumbers(dir string) ([]uint64, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var nums []uint64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if n, ok := ParseName(e.Name()); ok {
			nums = append(nums, n)
		}
	}
	sort.Slice(nums, func(i, j int) bool { return nums[i] > nums[j] })
	return nums, nil
}

// FirstKey 返回该 segment 的第一条 key(完整 key,含版本段)。
func (r *Reader) FirstKey() []byte {
	if len(r.index) == 0 {
		return nil
	}
	return r.index[0].firstKey
}

// LastKey 返回该 segment 的最后一条 key(完整 key,含版本段)。
func (r *Reader) LastKey() []byte {
	if len(r.index) == 0 {
		return nil
	}
	return r.index[len(r.index)-1].keyMax
}

// ContainsRange 判断 [first,last] 与 segment 的 key 范围是否重叠。
func (r *Reader) ContainsRange(first, last []byte) bool {
	if len(r.index) == 0 || first == nil || last == nil {
		return false
	}
	segFirst := r.index[0].firstKey
	segLast := r.index[len(r.index)-1].keyMax
	return bytes.Compare(last, segFirst) >= 0 && bytes.Compare(first, segLast) <= 0
}
