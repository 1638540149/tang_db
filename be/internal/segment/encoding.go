package segment

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math/bits"
)

// Encoding 是 column chunk 的压缩编码枚举(SPEC-P5 §5.1)。
//
// 枚举值一经定义不得复用:0=RAW、1=RLE、2=DICT、3=BITPACK(整数差分)、
// 4=PREFIX(字符串前缀截断)。
//
// 所有编码都作用在 P1~P4 已经确定的**保序编码后的字节**上(INV-18):
// 编码只改变体积,解压后逐字节相等,因此比较语义不变。
type Encoding uint8

const (
	EncRaw     Encoding = 0
	EncRLE     Encoding = 1
	EncDict    Encoding = 2
	EncBitpack Encoding = 3
	EncPrefix  Encoding = 4

	encodingCount = 5
)

// String 返回编码名(SHOW SEGMENTS / 日志用)。
func (e Encoding) String() string {
	switch e {
	case EncRaw:
		return "RAW"
	case EncRLE:
		return "RLE"
	case EncDict:
		return "DICT"
	case EncBitpack:
		return "BITPACK"
	case EncPrefix:
		return "PREFIX"
	default:
		return "UNKNOWN"
	}
}

func validEncoding(e Encoding) bool { return e < encodingCount }

// ErrEncodingNotApplicable 表示该编码不适合这组值(例如 BITPACK 要求定长整数)。
var ErrEncodingNotApplicable = errors.New("segment: encoding not applicable")

// ---------------------------------------------------------------- 无符号变长整数

func putUvarint(dst []byte, v uint64) []byte {
	var buf [binary.MaxVarintLen64]byte
	n := binary.PutUvarint(buf[:], v)
	return append(dst, buf[:n]...)
}

func readUvarint(data []byte, pos *int) (uint64, error) {
	v, n := binary.Uvarint(data[*pos:])
	if n <= 0 {
		return 0, errors.New("segment: 变长整数被截断或非法")
	}
	*pos += n
	return v, nil
}

func zigzag(v int64) uint64  { return uint64(v<<1) ^ uint64(v>>63) }
func unzigzag(v uint64) int64 { return int64(v>>1) ^ -int64(v&1) }

// ---------------------------------------------------------------- RAW

func encodeRaw(values [][]byte) ([]byte, error) {
	out := make([]byte, 0, rawSize(values))
	for _, v := range values {
		out = putUvarint(out, uint64(len(v)))
		out = append(out, v...)
	}
	return out, nil
}

func decodeRaw(data []byte, count int) ([][]byte, error) {
	out := make([][]byte, 0, count)
	pos := 0
	for i := 0; i < count; i++ {
		l, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		if l > uint64(len(data)-pos) {
			return nil, fmt.Errorf("segment: RAW 值被截断(需要 %d 字节,剩余 %d)", l, len(data)-pos)
		}
		v := make([]byte, l)
		copy(v, data[pos:pos+int(l)])
		pos += int(l)
		out = append(out, v)
	}
	if pos != len(data) {
		return nil, fmt.Errorf("segment: RAW 解压后有多余字节(%d)", len(data)-pos)
	}
	return out, nil
}

func rawSize(values [][]byte) int {
	n := 0
	for _, v := range values {
		n += uvarintLen(uint64(len(v))) + len(v)
	}
	return n
}

func uvarintLen(v uint64) int {
	n := 1
	for v >= 0x80 {
		v >>= 7
		n++
	}
	return n
}

// ---------------------------------------------------------------- RLE(游程编码)

func encodeRLE(values [][]byte) ([]byte, error) {
	if len(values) == 0 {
		return []byte{}, nil
	}
	type run struct {
		count int
		value []byte
	}
	runs := make([]run, 0, len(values))
	for _, v := range values {
		if len(runs) > 0 && equalBytes(runs[len(runs)-1].value, v) {
			runs[len(runs)-1].count++
			continue
		}
		runs = append(runs, run{count: 1, value: v})
	}
	out := putUvarint(nil, uint64(len(runs)))
	for _, r := range runs {
		out = putUvarint(out, uint64(r.count))
		out = putUvarint(out, uint64(len(r.value)))
		out = append(out, r.value...)
	}
	return out, nil
}

func decodeRLE(data []byte, count int) ([][]byte, error) {
	pos := 0
	runCount, err := readUvarint(data, &pos)
	if err != nil {
		return nil, err
	}
	out := make([][]byte, 0, count)
	for i := uint64(0); i < runCount; i++ {
		n, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		l, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		if l > uint64(len(data)-pos) {
			return nil, errors.New("segment: RLE 值被截断")
		}
		v := make([]byte, l)
		copy(v, data[pos:pos+int(l)])
		pos += int(l)
		if uint64(len(out))+n > uint64(count) {
			return nil, errors.New("segment: RLE 游程超过行数")
		}
		for j := uint64(0); j < n; j++ {
			out = append(out, append([]byte(nil), v...))
		}
	}
	if len(out) != count {
		return nil, fmt.Errorf("segment: RLE 解压行数 %d,期望 %d", len(out), count)
	}
	if pos != len(data) {
		return nil, fmt.Errorf("segment: RLE 解压后有多余字节(%d)", len(data)-pos)
	}
	return out, nil
}

// ---------------------------------------------------------------- DICT(字典 + 位打包下标)

func encodeDict(values [][]byte) ([]byte, error) {
	if len(values) == 0 {
		return []byte{}, nil
	}
	index := make(map[string]uint32, len(values))
	dict := make([][]byte, 0, 16)
	codes := make([]uint32, len(values))
	for i, v := range values {
		key := string(v)
		code, ok := index[key]
		if !ok {
			if len(dict) >= 1<<24 {
				return nil, ErrEncodingNotApplicable
			}
			code = uint32(len(dict))
			index[key] = code
			dict = append(dict, v)
		}
		codes[i] = code
	}
	width := bits.Len32(uint32(len(dict) - 1))
	out := putUvarint(nil, uint64(len(dict)))
	for _, d := range dict {
		out = putUvarint(out, uint64(len(d)))
		out = append(out, d...)
	}
	out = putUvarint(out, uint64(width))
	out = append(out, packCodes(codes, width)...)
	return out, nil
}

func decodeDict(data []byte, count int) ([][]byte, error) {
	pos := 0
	dictLen, err := readUvarint(data, &pos)
	if err != nil {
		return nil, err
	}
	dict := make([][]byte, 0, dictLen)
	for i := uint64(0); i < dictLen; i++ {
		l, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		if l > uint64(len(data)-pos) {
			return nil, errors.New("segment: DICT 字典项被截断")
		}
		v := make([]byte, l)
		copy(v, data[pos:pos+int(l)])
		pos += int(l)
		dict = append(dict, v)
	}
	width, err := readUvarint(data, &pos)
	if err != nil {
		return nil, err
	}
	if width > 32 {
		return nil, fmt.Errorf("segment: DICT 码宽非法 %d", width)
	}
	codes, consumed := unpackCodes(data[pos:], count, int(width))
	if consumed != len(data)-pos {
		return nil, errors.New("segment: DICT 码流有多余字节")
	}
	out := make([][]byte, count)
	for i := 0; i < count; i++ {
		if uint64(codes[i]) >= dictLen {
			return nil, fmt.Errorf("segment: DICT 码 %d 越界(字典 %d)", codes[i], dictLen)
		}
		out[i] = append([]byte(nil), dict[codes[i]]...)
	}
	return out, nil
}

// packCodes 低位优先打包 width 位的无符号码。
func packCodes(codes []uint32, width int) []byte {
	if width == 0 {
		return nil
	}
	out := make([]byte, (len(codes)*width+7)/8)
	bitPos := 0
	for _, code := range codes {
		for b := 0; b < width; b++ {
			if code&(1<<b) != 0 {
				out[bitPos/8] |= 1 << (bitPos % 8)
			}
			bitPos++
		}
	}
	return out
}

func unpackCodes(data []byte, count, width int) ([]uint32, int) {
	out := make([]uint32, count)
	if width == 0 {
		return out, 0
	}
	bitPos := 0
	need := (count*width + 7) / 8
	if need > len(data) {
		need = len(data)
	}
	for i := 0; i < count; i++ {
		var code uint32
		for b := 0; b < width; b++ {
			bytePos := bitPos / 8
			if bytePos >= need {
				break
			}
			if data[bytePos]&(1<<(bitPos%8)) != 0 {
				code |= 1 << b
			}
			bitPos++
		}
		out[i] = code
	}
	return out, need
}

// ---------------------------------------------------------------- BITPACK(整数差分)

// encodeBitpack 支持定长 8 字节(INT64)或 4 字节(INT32)整数序列:
// 首值原样,其后为一阶差分的 zigzag 位打包。适用于版本段这类近似单调的整数列。
func encodeBitpack(values [][]byte) ([]byte, error) {
	if len(values) == 0 {
		return []byte{}, nil
	}
	width := len(values[0])
	if width != 4 && width != 8 {
		return nil, ErrEncodingNotApplicable
	}
	nums := make([]int64, len(values))
	for i, v := range values {
		if len(v) != width {
			return nil, ErrEncodingNotApplicable
		}
		if width == 4 {
			nums[i] = int64(int32(binary.BigEndian.Uint32(v)))
		} else {
			nums[i] = int64(binary.BigEndian.Uint64(v))
		}
	}
	deltas := make([]uint64, len(nums)-1)
	maxDelta := uint64(0)
	for i := 1; i < len(nums); i++ {
		deltas[i-1] = zigzag(nums[i] - nums[i-1])
		if deltas[i-1] > maxDelta {
			maxDelta = deltas[i-1]
		}
	}
	deltaBits := 0
	if len(deltas) > 0 {
		deltaBits = bits.Len64(maxDelta)
	}
	out := []byte{byte(width)}
	out = putUvarint(out, uint64(deltaBits))
	out = append(out, values[0]...)
	codes := make([]uint32, len(deltas))
	for i, d := range deltas {
		if deltaBits <= 32 {
			codes[i] = uint32(d)
		} else {
			// 超过 32 位的差值用 64 位打包:拆成两段 32 位。
			codes[i] = uint32(d & 0xFFFFFFFF)
		}
	}
	if deltaBits <= 32 {
		out = append(out, packCodes(codes, deltaBits)...)
	} else {
		lo := make([]uint32, len(deltas))
		hi := make([]uint32, len(deltas))
		for i, d := range deltas {
			lo[i] = uint32(d & 0xFFFFFFFF)
			hi[i] = uint32(d >> 32)
		}
		out = append(out, packCodes(lo, 32)...)
		out = append(out, packCodes(hi, deltaBits-32)...)
	}
	return out, nil
}

func decodeBitpack(data []byte, count int) ([][]byte, error) {
	if len(data) == 0 {
		return nil, ErrEncodingNotApplicable
	}
	width := int(data[0])
	if (width != 4 && width != 8) || count == 0 {
		return nil, fmt.Errorf("segment: BITPACK 宽度非法 %d", width)
	}
	pos := 1
	deltaBits, err := readUvarint(data, &pos)
	if err != nil {
		return nil, err
	}
	if pos+width > len(data) {
		return nil, errors.New("segment: BITPACK 首值被截断")
	}
	firstRaw := data[pos : pos+width]
	pos += width
	var cur int64
	if width == 4 {
		cur = int64(int32(binary.BigEndian.Uint32(firstRaw)))
	} else {
		cur = int64(binary.BigEndian.Uint64(firstRaw))
	}
	out := make([][]byte, 0, count)
	v := make([]byte, width)
	if width == 4 {
		binary.BigEndian.PutUint32(v, uint32(int32(cur)))
	} else {
		binary.BigEndian.PutUint64(v, uint64(cur))
	}
	out = append(out, v)

	n := count - 1
	if n == 0 {
		return out, nil
	}
	var deltas []uint64
	if deltaBits <= 32 {
		codes, consumed := unpackCodes(data[pos:], n, int(deltaBits))
		pos += consumed
		deltas = make([]uint64, n)
		for i := range codes {
			deltas[i] = uint64(codes[i])
		}
	} else {
		lo, consumedLo := unpackCodes(data[pos:], n, 32)
		pos += consumedLo
		hi, consumedHi := unpackCodes(data[pos:], n, int(deltaBits-32))
		pos += consumedHi
		deltas = make([]uint64, n)
		for i := range lo {
			deltas[i] = uint64(hi[i])<<32 | uint64(lo[i])
		}
	}
	if pos != len(data) {
		return nil, errors.New("segment: BITPACK 码流有多余字节")
	}
	for i := 0; i < n; i++ {
		cur += unzigzag(deltas[i])
		b := make([]byte, width)
		if width == 4 {
			binary.BigEndian.PutUint32(b, uint32(int32(cur)))
		} else {
			binary.BigEndian.PutUint64(b, uint64(cur))
		}
		out = append(out, b)
	}
	return out, nil
}

// ---------------------------------------------------------------- PREFIX(前缀截断)

func encodePrefix(values [][]byte) ([]byte, error) {
	out := putUvarint(nil, uint64(len(values)))
	prev := []byte(nil)
	for _, v := range values {
		common := 0
		max := len(prev)
		if len(v) < max {
			max = len(v)
		}
		for common < max && prev[common] == v[common] {
			common++
		}
		suffix := v[common:]
		out = putUvarint(out, uint64(common))
		out = putUvarint(out, uint64(len(suffix)))
		out = append(out, suffix...)
		prev = v
	}
	return out, nil
}

func decodePrefix(data []byte, count int) ([][]byte, error) {
	pos := 0
	n, err := readUvarint(data, &pos)
	if err != nil {
		return nil, err
	}
	if int(n) != count {
		return nil, fmt.Errorf("segment: PREFIX 行数 %d,期望 %d", n, count)
	}
	out := make([][]byte, 0, count)
	prev := []byte(nil)
	for i := 0; i < count; i++ {
		common, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		suffixLen, err := readUvarint(data, &pos)
		if err != nil {
			return nil, err
		}
		if common > uint64(len(prev)) {
			return nil, errors.New("segment: PREFIX 公共前缀超过前一个值")
		}
		if suffixLen > uint64(len(data)-pos) {
			return nil, errors.New("segment: PREFIX 后缀被截断")
		}
		v := make([]byte, 0, int(common)+int(suffixLen))
		v = append(v, prev[:common]...)
		v = append(v, data[pos:pos+int(suffixLen)]...)
		pos += int(suffixLen)
		out = append(out, v)
		prev = v
	}
	if pos != len(data) {
		return nil, fmt.Errorf("segment: PREFIX 解压后有多余字节(%d)", len(data)-pos)
	}
	return out, nil
}

// ---------------------------------------------------------------- 选择与分派

// EncodeWith 用指定编码压缩一组值;编码不适用时返回 ErrEncodingNotApplicable。
func EncodeWith(enc Encoding, values [][]byte) ([]byte, error) {
	switch enc {
	case EncRaw:
		return encodeRaw(values)
	case EncRLE:
		return encodeRLE(values)
	case EncDict:
		return encodeDict(values)
	case EncBitpack:
		return encodeBitpack(values)
	case EncPrefix:
		return encodePrefix(values)
	default:
		return nil, fmt.Errorf("segment: 未知编码 %d", enc)
	}
}

// DecodeWith 用指定编码解压 count 个值;结果与原值逐字节相等(INV-18)。
func DecodeWith(enc Encoding, data []byte, count int) ([][]byte, error) {
	switch enc {
	case EncRaw:
		return decodeRaw(data, count)
	case EncRLE:
		return decodeRLE(data, count)
	case EncDict:
		return decodeDict(data, count)
	case EncBitpack:
		return decodeBitpack(data, count)
	case EncPrefix:
		return decodePrefix(data, count)
	default:
		return nil, fmt.Errorf("segment: 未知编码 %d", enc)
	}
}

// EncodeBest 按"体积最小"为 chunk 自选编码(FR-564)。
// 不适用(如 BITPACK 遇到非定长值)的编码直接跳过;体积相同按 RAW→RLE→DICT→
// PREFIX→BITPACK 的固定顺序取前者,保证同一份数据的选择结果可回归。
func EncodeBest(values [][]byte) (Encoding, []byte) {
	order := []Encoding{EncRaw, EncRLE, EncDict, EncPrefix, EncBitpack}
	bestEnc := EncRaw
	var best []byte
	for _, enc := range order {
		data, err := EncodeWith(enc, values)
		if err != nil {
			continue
		}
		if best == nil || len(data) < len(best) {
			bestEnc = enc
			best = data
		}
	}
	if best == nil {
		// RAW 不会失败;这里只是防御性别名。
		best, _ = encodeRaw(values)
	}
	return bestEnc, best
}

func equalBytes(a, b []byte) bool {
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
