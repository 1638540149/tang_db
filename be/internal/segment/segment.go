// Package segment 实现列式 segment 文件的写入与读取。
//
// 文件布局(从文件头到文件尾):
//
//	Index Block: 每 chunk 的首 key + key chunk 偏移 + value chunk 偏移
//	ZoneMap:     每 chunk 的 key/value min/max 与行数
//	Key Column:  所有 key chunk 依次排列(每 chunk 最多 4096 行)
//	Value Column:所有 value chunk 依次排列
//	Footer:      固定长度,位于文件末尾,含各区块偏移与校验和
//
// 本期使用原始编码(无压缩),但结构按列组织,便于 P5 替换编码方式。
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
)

const (
	chunkRows   = 4096
	magic       = "TANSEG01"
	footerSize  = 8 + 4 + 8*8 + 8 + 4 // magic + version + 8 offsets/lens + totalRows + checksum
	version     = 1
)

var castagnoli = crc32.MakeTable(crc32.Castagnoli)

// Builder 将有序 KV 序列写入一个 segment 文件。
type Builder struct {
	path   string
	rows   []kv
}

type kv struct {
	key   []byte
	value []byte
}

// Footer 是文件尾部的元信息。
type Footer struct {
	IndexBlockOffset  uint64
	IndexBlockLen     uint64
	ZoneMapOffset     uint64
	ZoneMapLen        uint64
	KeyColumnOffset   uint64
	KeyColumnLen      uint64
	ValueColumnOffset uint64
	ValueColumnLen    uint64
	TotalRows         uint64
}

// NewBuilder 创建指定路径的 segment 构建器。
func NewBuilder(path string) *Builder {
	return &Builder{path: path}
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

	// 1. 收集 chunk 元信息,写入 column chunks 到内存。
	numChunks := (len(b.rows) + chunkRows - 1) / chunkRows
	chunks := make([]chunkInfo, numChunks)
	keyColBuf := &bytes.Buffer{}
	valueColBuf := &bytes.Buffer{}

	for ci := 0; ci < numChunks; ci++ {
		start := ci * chunkRows
		end := start + chunkRows
		if end > len(b.rows) {
			end = len(b.rows)
		}
		info := &chunks[ci]
		info.firstKey = append([]byte(nil), b.rows[start].key...)
		info.keyOffset = uint64(keyColBuf.Len())
		info.valueOffset = uint64(valueColBuf.Len())
		info.rowCount = end - start

		for i := start; i < end; i++ {
			row := b.rows[i]
			if i == start {
				info.keyMin = append([]byte(nil), row.key...)
				info.valueMin = append([]byte(nil), row.value...)
			}
			info.keyMax = append([]byte(nil), row.key...)
			info.valueMax = append([]byte(nil), row.value...)
			writeBytesWithLen(keyColBuf, row.key)
			writeBytesWithLen(valueColBuf, row.value)
		}
	}

	// 2. 写入 Index Block。
	indexOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	indexBuf := &bytes.Buffer{}
	for _, c := range chunks {
		writeBytesWithLen(indexBuf, c.firstKey)
		binary.Write(indexBuf, binary.LittleEndian, c.keyOffset)
		binary.Write(indexBuf, binary.LittleEndian, c.valueOffset)
	}
	if _, err := f.Write(indexBuf.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write index: %w", err)
	}

	// 3. 写入 ZoneMap。
	zoneOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	zoneBuf := &bytes.Buffer{}
	for _, c := range chunks {
		writeBytesWithLen(zoneBuf, c.keyMin)
		writeBytesWithLen(zoneBuf, c.keyMax)
		writeBytesWithLen(zoneBuf, c.valueMin)
		writeBytesWithLen(zoneBuf, c.valueMax)
		binary.Write(zoneBuf, binary.LittleEndian, uint32(c.rowCount))
	}
	if _, err := f.Write(zoneBuf.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write zonemap: %w", err)
	}

	// 4. 写入 Key Column。
	keyOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	if _, err := f.Write(keyColBuf.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write key column: %w", err)
	}

	// 5. 写入 Value Column。
	valueOffset, err := f.Seek(0, io.SeekCurrent)
	if err != nil {
		return nil, err
	}
	if _, err := f.Write(valueColBuf.Bytes()); err != nil {
		return nil, fmt.Errorf("segment: write value column: %w", err)
	}

	// 6. 写入 Footer。
	footer := &Footer{
		IndexBlockOffset:  uint64(indexOffset),
		IndexBlockLen:     uint64(indexBuf.Len()),
		ZoneMapOffset:     uint64(zoneOffset),
		ZoneMapLen:        uint64(zoneBuf.Len()),
		KeyColumnOffset:   uint64(keyOffset),
		KeyColumnLen:      uint64(keyColBuf.Len()),
		ValueColumnOffset: uint64(valueOffset),
		ValueColumnLen:    uint64(valueColBuf.Len()),
		TotalRows:         uint64(len(b.rows)),
	}
	if err := writeFooter(f, footer); err != nil {
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

type chunkInfo struct {
	firstKey    []byte
	keyOffset   uint64
	valueOffset uint64
	rowCount    int
	keyMin      []byte
	keyMax      []byte
	valueMin    []byte
	valueMax    []byte
}

func writeBytesWithLen(w *bytes.Buffer, b []byte) {
	var tmp [4]byte
	binary.LittleEndian.PutUint32(tmp[:], uint32(len(b)))
	w.Write(tmp[:])
	w.Write(b)
}

func writeFooter(f *os.File, footer *Footer) error {
	buf := make([]byte, footerSize)
	copy(buf[0:8], magic)
	binary.LittleEndian.PutUint32(buf[8:12], version)
	binary.LittleEndian.PutUint64(buf[12:20], footer.IndexBlockOffset)
	binary.LittleEndian.PutUint64(buf[20:28], footer.IndexBlockLen)
	binary.LittleEndian.PutUint64(buf[28:36], footer.ZoneMapOffset)
	binary.LittleEndian.PutUint64(buf[36:44], footer.ZoneMapLen)
	binary.LittleEndian.PutUint64(buf[44:52], footer.KeyColumnOffset)
	binary.LittleEndian.PutUint64(buf[52:60], footer.KeyColumnLen)
	binary.LittleEndian.PutUint64(buf[60:68], footer.ValueColumnOffset)
	binary.LittleEndian.PutUint64(buf[68:76], footer.ValueColumnLen)
	binary.LittleEndian.PutUint64(buf[76:84], footer.TotalRows)
	crc := crc32.Checksum(buf[:footerSize-4], castagnoli)
	binary.LittleEndian.PutUint32(buf[footerSize-4:], crc)
	_, err := f.Write(buf)
	return err
}

// Reader 打开一个 segment 文件进行读取。
type Reader struct {
	path      string
	file      *os.File
	footer    *Footer
	index     []indexEntry
	zoneMap   []zoneEntry
}

type indexEntry struct {
	firstKey    []byte
	keyOffset   uint64
	valueOffset uint64
}

type zoneEntry struct {
	keyMin   []byte
	keyMax   []byte
	valueMin []byte
	valueMax []byte
	rowCount uint32
}

// Open 打开 segment 文件。
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
	if info.Size() < int64(footerSize) {
		f.Close()
		return nil, fmt.Errorf("segment: file too small %s", path)
	}
	footer, err := readFooter(f, info.Size())
	if err != nil {
		f.Close()
		return nil, err
	}
	r := &Reader{path: path, file: f, footer: footer}
	if err := r.loadIndex(); err != nil {
		r.Close()
		return nil, err
	}
	if err := r.loadZoneMap(); err != nil {
		r.Close()
		return nil, err
	}
	return r, nil
}

// Close 关闭 segment 文件。
func (r *Reader) Close() error {
	if r.file == nil {
		return nil
	}
	err := r.file.Close()
	r.file = nil
	return err
}

// Path 返回文件路径。
func (r *Reader) Path() string { return r.path }

// Footer 返回文件 footer 副本。
func (r *Reader) Footer() Footer { return *r.footer }

// Get 按 key 查找 value;不存在时 ok=false。
func (r *Reader) Get(key []byte) (value []byte, ok bool, err error) {
	for i, z := range r.zoneMap {
		// ZoneMap 过滤:只有 key 落在 [keyMin, keyMax] 区间才需要查该 chunk。
		if bytes.Compare(key, z.keyMin) < 0 || bytes.Compare(key, z.keyMax) > 0 {
			continue
		}
		val, found, err := r.searchChunk(i, key)
		if err != nil {
			return nil, false, err
		}
		if found {
			return val, true, nil
		}
	}
	return nil, false, nil
}

func (r *Reader) searchChunk(chunkIdx int, key []byte) ([]byte, bool, error) {
	entry := r.index[chunkIdx]
	zone := r.zoneMap[chunkIdx]

	// 读取 key chunk。
	keyChunk, err := r.readColumnBlock(r.footer.KeyColumnOffset+entry.keyOffset, r.keyChunkLen(chunkIdx))
	if err != nil {
		return nil, false, err
	}
	// 读取 value chunk。
	valueChunk, err := r.readColumnBlock(r.footer.ValueColumnOffset+entry.valueOffset, r.valueChunkLen(chunkIdx))
	if err != nil {
		return nil, false, err
	}

	keys := decodeBytesSeq(keyChunk)
	values := decodeBytesSeq(valueChunk)
	if len(keys) != int(zone.rowCount) || len(values) != int(zone.rowCount) {
		return nil, false, fmt.Errorf("segment: chunk %d row count mismatch", chunkIdx)
	}
	for i := 0; i < len(keys); i++ {
		cmp := bytes.Compare(key, keys[i])
		if cmp == 0 {
			return values[i], true, nil
		}
		if cmp < 0 {
			break
		}
	}
	return nil, false, nil
}

// keyChunkLen 计算某 chunk 的 key 列块长度。
func (r *Reader) keyChunkLen(idx int) uint64 {
	start := r.index[idx].keyOffset
	var end uint64
	if idx+1 < len(r.index) {
		end = r.index[idx+1].keyOffset
	} else {
		end = r.footer.KeyColumnLen
	}
	return end - start
}

// valueChunkLen 计算某 chunk 的 value 列块长度。
func (r *Reader) valueChunkLen(idx int) uint64 {
	start := r.index[idx].valueOffset
	var end uint64
	if idx+1 < len(r.index) {
		end = r.index[idx+1].valueOffset
	} else {
		end = r.footer.ValueColumnLen
	}
	return end - start
}

// readColumnBlock 从文件偏移处读取指定长度的字节。
func (r *Reader) readColumnBlock(offset, length uint64) ([]byte, error) {
	buf := make([]byte, length)
	if _, err := r.file.ReadAt(buf, int64(offset)); err != nil {
		return nil, fmt.Errorf("segment: read column block at %d: %w", offset, err)
	}
	return buf, nil
}

func (r *Reader) loadIndex() error {
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

func (r *Reader) loadZoneMap() error {
	buf := make([]byte, r.footer.ZoneMapLen)
	if _, err := r.file.ReadAt(buf, int64(r.footer.ZoneMapOffset)); err != nil {
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
		z.keyMin, err = readField()
		if err != nil {
			return err
		}
		z.keyMax, err = readField()
		if err != nil {
			return err
		}
		z.valueMin, err = readField()
		if err != nil {
			return err
		}
		z.valueMax, err = readField()
		if err != nil {
			return err
		}
		if pos+4 > len(buf) {
			return errors.New("segment: zonemap rowcount truncated")
		}
		z.rowCount = binary.LittleEndian.Uint32(buf[pos : pos+4])
		pos += 4
		r.zoneMap = append(r.zoneMap, z)
	}
	return nil
}

func readFooter(f *os.File, size int64) (*Footer, error) {
	buf := make([]byte, footerSize)
	if _, err := f.ReadAt(buf, size-int64(footerSize)); err != nil {
		return nil, fmt.Errorf("segment: read footer: %w", err)
	}
	if string(buf[0:8]) != magic {
		return nil, fmt.Errorf("segment: bad magic %q", buf[0:8])
	}
	ver := binary.LittleEndian.Uint32(buf[8:12])
	if ver != version {
		return nil, fmt.Errorf("segment: unsupported version %d", ver)
	}
	crcStored := binary.LittleEndian.Uint32(buf[footerSize-4:])
	crcComputed := crc32.Checksum(buf[:footerSize-4], castagnoli)
	if crcStored != crcComputed {
		return nil, errors.New("segment: footer checksum mismatch")
	}
	return &Footer{
		IndexBlockOffset:  binary.LittleEndian.Uint64(buf[12:20]),
		IndexBlockLen:     binary.LittleEndian.Uint64(buf[20:28]),
		ZoneMapOffset:     binary.LittleEndian.Uint64(buf[28:36]),
		ZoneMapLen:        binary.LittleEndian.Uint64(buf[36:44]),
		KeyColumnOffset:   binary.LittleEndian.Uint64(buf[44:52]),
		KeyColumnLen:      binary.LittleEndian.Uint64(buf[52:60]),
		ValueColumnOffset: binary.LittleEndian.Uint64(buf[60:68]),
		ValueColumnLen:    binary.LittleEndian.Uint64(buf[68:76]),
		TotalRows:         binary.LittleEndian.Uint64(buf[76:84]),
	}, nil
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
		out = append(out, b)
		pos += l
	}
	return out
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
