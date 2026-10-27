package segment

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
)

// Column chunk 布局(SPEC-P5 §5.1):
//
//	[列ID 2B][编码 1B][行数 4B][原始字节 4B][压缩后字节 4B]
//	[min 值 2B 长度 + 字节][max 值 2B 长度 + 字节][null_count 4B]
//	[validity 位图 4B 长度 + 字节]
//	[bloom 4B 长度 + 字节]
//	[块内偏移数组 4B 长度 + (rows+1)×4B]
//	[压缩后的数据 compBytes]
//
// 压缩在"保序编码后的字节"上做,不改变比较语义(INV-18)。
// block 内偏移数组给随机访问用(行 i 的原始编码起点),RAW 编码时可直接定位单行。
const (
	columnIDKey   uint16 = 0
	columnIDValue uint16 = 1
)

// chunkInfoV2 是一个 column chunk 的自描述头信息。
type chunkInfoV2 struct {
	ColumnID  uint16
	Encoding  Encoding
	Rows      int
	RawBytes  int
	CompBytes int
	Min       []byte
	Max       []byte
	NullCount int
	Valid     []bool

	headerLen  int
	bloomK     int
	bloomBits  []byte
	offsets    []uint32
	hasOffsets bool
}

// encodeColumnChunk 把一个逻辑列(含 NULL)压成一个 chunk。
func encodeColumnChunk(columnID uint16, values [][]byte, valid []bool,
	bloomEnabled bool) ([]byte, chunkInfoV2, error) {
	if len(values) != len(valid) {
		return nil, chunkInfoV2{}, fmt.Errorf("segment: 列 %d 值数 %d 与 validity %d 不一致",
			columnID, len(values), len(valid))
	}
	info := chunkInfoV2{ColumnID: columnID, Rows: len(values), Valid: valid}
	enc, data := EncodeBest(values)
	info.Encoding = enc
	info.CompBytes = len(data)
	info.RawBytes = rawSize(values)

	offsets := make([]uint32, 0, len(values)+1)
	var off uint32
	offsets = append(offsets, 0)
	nulls := 0
	hasMin, hasMax := false, false
	for i, v := range values {
		if !valid[i] {
			nulls++
		} else {
			if !hasMin || bytes.Compare(v, info.Min) < 0 {
				info.Min = append([]byte(nil), v...)
				hasMin = true
			}
			if !hasMax || bytes.Compare(v, info.Max) > 0 {
				info.Max = append([]byte(nil), v...)
				hasMax = true
			}
		}
		off += uint32(uvarintLen(uint64(len(v))) + len(v))
		offsets = append(offsets, off)
	}
	info.NullCount = nulls
	info.offsets = offsets
	info.hasOffsets = true

	bitmap := packValidity(valid)
	var bloom []byte
	if bloomEnabled {
		bloom = buildBloom(values, valid)
		info.bloomK = 3
		info.bloomBits = bloom[5:]
	}

	out := make([]byte, 0, 32+len(bitmap)+len(bloom)+len(offsets)*4+len(data))
	out = appendU16(out, columnID)
	out = append(out, byte(enc))
	out = appendU32(out, uint32(len(values)))
	out = appendU32(out, uint32(info.RawBytes))
	out = appendU32(out, uint32(len(data)))
	out = appendBytes16(out, info.Min)
	out = appendBytes16(out, info.Max)
	out = appendU32(out, uint32(nulls))
	out = appendBlock(out, bitmap)
	out = appendBlock(out, bloom)
	offBytes := make([]byte, len(offsets)*4)
	for i, v := range offsets {
		binary.LittleEndian.PutUint32(offBytes[i*4:], v)
	}
	out = appendBlock(out, offBytes)
	info.headerLen = len(out)
	out = append(out, data...)
	return out, info, nil
}

// parseChunkHeader 只解析 chunk 头(不读压缩数据),用于点查前做布隆过滤。
func parseChunkHeader(data []byte, expectedColumn uint16) (chunkInfoV2, error) {
	info := chunkInfoV2{}
	pos := 0
	need := func(n int) error {
		if pos+n > len(data) {
			return errors.New("segment: chunk 头被截断")
		}
		return nil
	}
	if err := need(16); err != nil {
		return info, err
	}
	info.ColumnID = binary.LittleEndian.Uint16(data[pos:])
	pos += 2
	if info.ColumnID != expectedColumn {
		return info, fmt.Errorf("segment: chunk 列 ID %d,期望 %d", info.ColumnID, expectedColumn)
	}
	enc := Encoding(data[pos])
	pos++
	if !validEncoding(enc) {
		return info, fmt.Errorf("segment: chunk 编码 %d 无法识别", enc)
	}
	info.Encoding = enc
	info.Rows = int(binary.LittleEndian.Uint32(data[pos:]))
	pos += 4
	info.RawBytes = int(binary.LittleEndian.Uint32(data[pos:]))
	pos += 4
	info.CompBytes = int(binary.LittleEndian.Uint32(data[pos:]))
	pos += 4

	read16 := func() ([]byte, error) {
		if err := need(2); err != nil {
			return nil, err
		}
		l := int(binary.LittleEndian.Uint16(data[pos:]))
		pos += 2
		if err := need(l); err != nil {
			return nil, err
		}
		b := make([]byte, l)
		copy(b, data[pos:pos+l])
		pos += l
		return b, nil
	}
	var err error
	info.Min, err = read16()
	if err != nil {
		return info, err
	}
	info.Max, err = read16()
	if err != nil {
		return info, err
	}
	if err := need(4); err != nil {
		return info, err
	}
	info.NullCount = int(binary.LittleEndian.Uint32(data[pos:]))
	pos += 4

	readBlock := func() ([]byte, error) {
		if err := need(4); err != nil {
			return nil, err
		}
		l := int(binary.LittleEndian.Uint32(data[pos:]))
		pos += 4
		if err := need(l); err != nil {
			return nil, err
		}
		b := data[pos : pos+l]
		pos += l
		return b, nil
	}
	bitmap, err := readBlock()
	if err != nil {
		return info, err
	}
	info.Valid = unpackValidity(bitmap, info.Rows)
	bloom, err := readBlock()
	if err != nil {
		return info, err
	}
	if len(bloom) > 0 {
		info.bloomK = int(bloom[0])
		info.bloomBits = bloom[5:]
	}
	offBytes, err := readBlock()
	if err != nil {
		return info, err
	}
	if len(offBytes) != (info.Rows+1)*4 {
		return info, fmt.Errorf("segment: chunk 偏移数组长度 %d,期望 %d", len(offBytes), (info.Rows+1)*4)
	}
	info.offsets = make([]uint32, info.Rows+1)
	for i := range info.offsets {
		info.offsets[i] = binary.LittleEndian.Uint32(offBytes[i*4:])
	}
	info.hasOffsets = true
	info.headerLen = pos
	return info, nil
}

// decodeColumnChunk 解压整个 chunk(头部 + 数据)。
func decodeColumnChunk(data []byte, expectedColumn uint16) (chunkInfoV2, [][]byte, error) {
	info, err := parseChunkHeader(data, expectedColumn)
	if err != nil {
		return info, nil, err
	}
	if info.Encoding == EncBitpack && info.NullCount > 0 {
		// BITPACK 需要定长整数;NULL 用空值表示会破坏定长,写入端不会选它。
		return info, nil, errors.New("segment: BITPACK chunk 不允许含 NULL")
	}
	if info.headerLen+info.CompBytes != len(data) {
		return info, nil, fmt.Errorf("segment: chunk 数据长度 %d,头 %d + 压缩 %d",
			len(data), info.headerLen, info.CompBytes)
	}
	values, err := DecodeWith(info.Encoding, data[info.headerLen:], info.Rows)
	if err != nil {
		return info, nil, err
	}
	return info, values, nil
}

// bloomContains 判断值是否**可能**存在。false = 一定不存在(不漏行,FR-569)。
func bloomContains(bloom []byte, value []byte) bool {
	if len(bloom) < 5 {
		return true // 没开布隆:保守返回"可能"
	}
	k := int(bloom[0])
	mBits := int(binary.LittleEndian.Uint32(bloom[1:5])) * 8
	bits := bloom[5:]
	if k <= 0 || mBits <= 0 || len(bits)*8 < mBits {
		return true
	}
	h1, h2 := bloomHashes(value)
	for i := 0; i < k; i++ {
		pos := int((h1 + uint64(i)*h2) % uint64(mBits))
		if bits[pos/8]&(1<<(pos%8)) == 0 {
			return false
		}
	}
	return true
}

func buildBloom(values [][]byte, valid []bool) []byte {
	mBits := len(values) * 8
	if mBits < 64 {
		mBits = 64
	}
	mBytes := (mBits + 7) / 8
	bits := make([]byte, mBytes)
	const k = 3
	for i, v := range values {
		if !valid[i] {
			continue
		}
		h1, h2 := bloomHashes(v)
		for j := 0; j < k; j++ {
			pos := int((h1 + uint64(j)*h2) % uint64(mBytes*8))
			bits[pos/8] |= 1 << (pos % 8)
		}
	}
	out := make([]byte, 5+len(bits))
	out[0] = k
	binary.LittleEndian.PutUint32(out[1:], uint32(mBytes))
	copy(out[5:], bits)
	return out
}

// bloomHashes 是 FNV-1a 64 与一个独立混合的第二个哈希(双哈希构造)。
func bloomHashes(value []byte) (uint64, uint64) {
	const offset64 = 14695981039346656037
	const prime64 = 1099511628211
	h := uint64(offset64)
	for _, b := range value {
		h ^= uint64(b)
		h *= prime64
	}
	h2 := h
	h2 ^= h2 >> 33
	h2 *= 0xff51afd7ed558ccd
	h2 ^= h2 >> 33
	if h2 == 0 {
		h2 = 0x9E3779B97F4A7C15
	}
	return h, h2 | 1
}

// ---------------------------------------------------------------- 工具

func appendU16(dst []byte, v uint16) []byte {
	var buf [2]byte
	binary.LittleEndian.PutUint16(buf[:], v)
	return append(dst, buf[:]...)
}

func appendU32(dst []byte, v uint32) []byte {
	var buf [4]byte
	binary.LittleEndian.PutUint32(buf[:], v)
	return append(dst, buf[:]...)
}

func appendBytes16(dst []byte, b []byte) []byte {
	if len(b) > 0xFFFF {
		b = b[:0xFFFF]
	}
	dst = appendU16(dst, uint16(len(b)))
	return append(dst, b...)
}

func appendBlock(dst []byte, block []byte) []byte {
	dst = appendU32(dst, uint32(len(block)))
	return append(dst, block...)
}

func packValidity(valid []bool) []byte {
	out := make([]byte, (len(valid)+7)/8)
	for i, ok := range valid {
		if ok {
			out[i/8] |= 1 << (i % 8)
		}
	}
	return out
}

func unpackValidity(bitmap []byte, rows int) []bool {
	out := make([]bool, rows)
	for i := 0; i < rows; i++ {
		if i/8 < len(bitmap) && bitmap[i/8]&(1<<(i%8)) != 0 {
			out[i] = true
		}
	}
	return out
}
