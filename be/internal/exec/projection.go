package exec

import (
	"encoding/binary"
	"errors"
	"fmt"
)

// 行框架协议(SPEC-P6.1 FR-701 / FR-702 / K145)。
//
// 磁盘上的行值刻意保持不变(BE 侧不解码类型),所以 FE 必须把"如何跳过每一列"
// 作为参数下发;BE 只按这些参数做字节切片,不知道列名/类型。
//
// column_slots 编码:
//
//	[u16 slotCount] 然后 slotCount 组 [u16 ordinal][u8 kind][u8 width]
//
// kind:
//
//	0 FIXED   : 定长 width 字节(BOOLEAN 等本身保序的列)
//	1 VARLEN  : 4 字节小端长度 + 载荷(JSON)
//	5 VARLEN_SIGNED : 同 VARLEN,但比较时按有符号字节序(VARCHAR,与 FE 完全一致)
//	2 INT32   : 4 字节小端有符号整数(比较时翻转符号位后按大端)
//	3 INT64   : 8 字节小端有符号整数(同上)
//	4 FLOAT64 : 8 字节小端 IEEE754(比较时做保序变换)
//	6 VECTOR_FIXED : 定长 4n 字节(SPEC-P7 §5.1)。宽度超过 u8,主槽表 width 记 0,
//	                 真实宽度在槽表末尾的宽槽段:[u16 宽槽数] 然后 [u16 ordinal][u32 width]×
//
// row_frame 编码:
//
//	[u8 nullmapBytes][u16 columnCount][u16 prefixBytes]
//
// prefixBytes = 行值中 NULL 位图之前的字节数(版本 1 + 表 ID 4 + 列数 2 = 7)。
const (
	SlotFixed   byte = 0
	SlotVarLen  byte = 1
	SlotInt32   byte = 2
	SlotInt64   byte = 3
	SlotFloat64 byte = 4
	// SlotVarLenSigned 是"按有符号字节序比较"的变长列。FE 侧字符串比较用的是
	// Java 的 signed byte(见 ValueOps.compare),所以 BE 必须对每个字节翻最高位,
	// 让无符号的字节比较得到与 FE 完全一致的结果。
	SlotVarLenSigned byte = 5
	// SlotVectorFixed 是 SPEC-P7 的定长向量槽:BE 只按偏移切片,不知道这是向量。
	SlotVectorFixed byte = 6
)

// ErrBadProjection 表示 FE 下发的列投影参数不自洽(属于调用方参数错误)。
var ErrBadProjection = errors.New("exec: invalid column projection")

// Slot 描述一列在行值里的编码方式。
type Slot struct {
	Ordinal int
	Kind    byte
	Width   int
}

// RowFrame 是行框架参数。
type RowFrame struct {
	NullmapBytes int
	ColumnCount  int
	PrefixBytes  int
}

// ParseSlots 解析 FE 下发的列槽表。
func ParseSlots(b []byte) ([]Slot, error) {
	if len(b) < 2 {
		return nil, fmt.Errorf("%w: column_slots too short", ErrBadProjection)
	}
	n := int(binary.LittleEndian.Uint16(b[0:2]))
	if len(b) < 2+n*4 {
		return nil, fmt.Errorf("%w: column_slots truncated (want %d slots)", ErrBadProjection, n)
	}
	slots := make([]Slot, n)
	for i := 0; i < n; i++ {
		base := 2 + i*4
		slots[i] = Slot{
			Ordinal: int(binary.LittleEndian.Uint16(b[base : base+2])),
			Kind:    b[base+2],
			Width:   int(b[base+3]),
		}
	}
	// 宽槽段(SPEC-P7 §5.1):仅当存在定长 >255 字节的列(向量)时出现。
	pos := 2 + n*4
	if pos+2 <= len(b) {
		wide := int(binary.LittleEndian.Uint16(b[pos : pos+2]))
		pos += 2
		if pos+wide*6 > len(b) {
			return nil, fmt.Errorf("%w: column_slots wide section truncated (want %d)", ErrBadProjection, wide)
		}
		for i := 0; i < wide; i++ {
			ordinal := int(binary.LittleEndian.Uint16(b[pos : pos+2]))
			width := int(binary.LittleEndian.Uint32(b[pos+2 : pos+6]))
			pos += 6
			found := false
			for j := range slots {
				if slots[j].Ordinal == ordinal {
					slots[j].Width = width
					found = true
					break
				}
			}
			if !found {
				return nil, fmt.Errorf("%w: wide slot ordinal %d not present", ErrBadProjection, ordinal)
			}
		}
	}
	return slots, nil
}

// ParseRowFrame 解析行框架参数。
func ParseRowFrame(b []byte) (RowFrame, error) {
	if len(b) < 5 {
		return RowFrame{}, fmt.Errorf("%w: row_frame too short", ErrBadProjection)
	}
	f := RowFrame{
		NullmapBytes: int(b[0]),
		ColumnCount:  int(binary.LittleEndian.Uint16(b[1:3])),
		PrefixBytes:  int(binary.LittleEndian.Uint16(b[3:5])),
	}
	if f.NullmapBytes <= 0 || f.ColumnCount <= 0 || f.PrefixBytes < 0 {
		return RowFrame{}, fmt.Errorf("%w: row_frame values out of range", ErrBadProjection)
	}
	if want := (f.ColumnCount + 7) / 8; f.NullmapBytes < want {
		return RowFrame{}, fmt.Errorf("%w: nullmap_bytes=%d < ceil(%d/8)",
			ErrBadProjection, f.NullmapBytes, f.ColumnCount)
	}
	return f, nil
}

// RowLayout 是"行值 → 每列字节区间"的解析器。
type RowLayout struct {
	frame   RowFrame
	slots   []Slot // 下发顺序
	ordered []Slot // 按 ordinal 索引
}

// NewRowLayout 校验槽表与行框架是否自洽。
func NewRowLayout(frame RowFrame, slots []Slot) (*RowLayout, error) {
	byOrdinal := make([]*Slot, frame.ColumnCount)
	for i := range slots {
		s := slots[i]
		if s.Ordinal < 0 || s.Ordinal >= frame.ColumnCount {
			return nil, fmt.Errorf("%w: slot ordinal %d out of range", ErrBadProjection, s.Ordinal)
		}
		if byOrdinal[s.Ordinal] != nil {
			return nil, fmt.Errorf("%w: duplicate slot ordinal %d", ErrBadProjection, s.Ordinal)
		}
		copy := s
		byOrdinal[s.Ordinal] = &copy
	}
	for i, s := range byOrdinal {
		if s == nil {
			return nil, fmt.Errorf("%w: missing slot for ordinal %d", ErrBadProjection, i)
		}
		if s.Width <= 0 {
			return nil, fmt.Errorf("%w: slot %d has width %d", ErrBadProjection, i, s.Width)
		}
	}
	ordered := make([]Slot, frame.ColumnCount)
	for i := range slots {
		ordered[slots[i].Ordinal] = slots[i]
	}
	return &RowLayout{frame: frame, slots: slots, ordered: ordered}, nil
}

// Column 是一列在行值中的字节区间;Null 为 true 时 Off/Len 无意义。
type Column struct {
	Off  int
	Len  int
	Null bool
}

// Locate 解析行值,返回每列(UIT 顺序)的字节区间。
//
// 返回 ok=false 表示这行不是可投影的行格式(墓碑 / 格式不符),调用方必须原样处理。
func (l *RowLayout) Locate(row []byte) (cols []Column, ok bool) {
	if len(row) == 0 || row[0] != 0x01 { // 0x01 = FE 的行格式版本;其他是墓碑/未知
		return nil, false
	}
	if len(row) < l.frame.PrefixBytes+l.frame.NullmapBytes {
		return nil, false
	}
	if got := int(binary.LittleEndian.Uint16(row[5:7])); got != l.frame.ColumnCount {
		return nil, false
	}
	pos := l.frame.PrefixBytes + l.frame.NullmapBytes
	cols = make([]Column, l.frame.ColumnCount)
	for i := 0; i < l.frame.ColumnCount; i++ {
		null := row[l.frame.PrefixBytes+i/8]&(1<<(i%8)) != 0
		if null {
			cols[i] = Column{Null: true}
			continue
		}
		slot := l.ordered[i]
		size := slot.Width
		if slot.Kind == SlotVarLen || slot.Kind == SlotVarLenSigned {
			if pos+4 > len(row) {
				return nil, false
			}
			n := int(binary.LittleEndian.Uint32(row[pos : pos+4]))
			if n < 0 || pos+4+n > len(row) {
				return nil, false
			}
			size = 4 + n
		}
		if pos+size > len(row) {
			return nil, false
		}
		cols[i] = Column{Off: pos, Len: size}
		pos += size
	}
	return cols, true
}

// ProjectRow 按掩码把行投影成 `[nullmap][len][bytes]×n` 帧(只含被选中的列,按 ordinal 升序)。
//
// 返回 ok=false 表示该行不可投影(墓碑等),调用方应跳过它 —— 墓碑在 MVCC 层已经
// 表示"这行被删了",FE 侧原本也是丢弃。
func (l *RowLayout) ProjectRow(row []byte, mask uint32) (frame []byte, selected int, ok bool) {
	cols, ok := l.Locate(row)
	if !ok {
		return nil, 0, false
	}
	// 主键列(ordinal 0)总是保留(边界 #113:列掩码全 0 时只回主键列)。
	if mask == 0 {
		mask = 1
	}
	selected = 0
	for i := 0; i < l.frame.ColumnCount; i++ {
		if mask&(1<<uint(i)) != 0 {
			selected++
		}
	}
	if selected == 0 {
		mask = 1
		selected = 1
	}
	nullmapLen := (selected + 7) / 8
	total := nullmapLen
	for i := 0; i < l.frame.ColumnCount; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		if cols[i].Null {
			continue
		}
		total += 4 + payloadLen(l.ordered[i], cols[i])
	}
	frame = make([]byte, 0, total)
	frame = append(frame, make([]byte, nullmapLen)...)
	out := 0
	var sizeBuf [4]byte
	for i := 0; i < l.frame.ColumnCount; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		if cols[i].Null {
			frame[out/8] |= 1 << (out % 8)
			out++
			continue
		}
		// 帧里的 len 是**载荷**长度:VARCHAR/JSON 的 4 字节长度前缀由 BE 剥掉,
		// FE 拿到的是纯净的列字节(ColumnarBlock 因此更小)。
		off, size := columnPayload(l.ordered[i], cols[i])
		binary.LittleEndian.PutUint32(sizeBuf[:], uint32(size))
		frame = append(frame, sizeBuf[:]...)
		frame = append(frame, row[off:off+size]...)
		out++
	}
	return frame, selected, true
}

// ColumnPayload 直接返回某一列在行值里的载荷字节(不建帧、不复制)。
//
// SPEC-P7.1:向量检索要按行读向量列,`ProjectRow` 每行都分配一个帧 —— 未落盘 1 万行时
// 每查询 5MB 额外分配,是 memtable 暴力段的主要成本。这里只做切片,调用方自己解释字节。
//
// 返回值:payload(非 NULL 时的载荷)、null(该列为 NULL)、ok(行/列可解析)。
func (l *RowLayout) ColumnPayload(row []byte, ordinal int) ([]byte, bool, bool) {
	if l == nil || ordinal < 0 || ordinal >= len(l.ordered) {
		return nil, false, false
	}
	cols, ok := l.Locate(row)
	if !ok {
		return nil, false, false
	}
	c := cols[ordinal]
	if c.Null {
		return nil, true, true
	}
	off, size := columnPayload(l.ordered[ordinal], c)
	if off < 0 || size < 0 || off+size > len(row) {
		return nil, false, false
	}
	return row[off : off+size], false, true
}

// payloadLen 返回一列在帧里携带的载荷长度。
func payloadLen(slot Slot, c Column) int {
	if slot.Kind == SlotVarLen || slot.Kind == SlotVarLenSigned {
		return c.Len - 4
	}
	return c.Len
}

// columnPayload 返回列载荷在行值里的起点与长度。
func columnPayload(slot Slot, c Column) (int, int) {
	if slot.Kind == SlotVarLen || slot.Kind == SlotVarLenSigned {
		return c.Off + 4, c.Len - 4
	}
	return c.Off, c.Len
}

// SelectedOrdinals 返回掩码对应的列序号(升序);mask 为 0 时只保留主键列。
func (l *RowLayout) SelectedOrdinals(mask uint32) []int {
	if mask == 0 {
		mask = 1
	}
	out := make([]int, 0, l.frame.ColumnCount)
	for i := 0; i < l.frame.ColumnCount; i++ {
		if mask&(1<<uint(i)) != 0 {
			out = append(out, i)
		}
	}
	if len(out) == 0 {
		out = append(out, 0)
	}
	return out
}
