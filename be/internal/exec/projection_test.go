package exec

import (
	"encoding/binary"
	"math"
	"testing"
)

// buildRow 按 FE 的 RowCodec 布局造一行:
// [版本 1B][表 ID 4B LE][列数 2B LE][nullmap][列值...]
func buildRow(tableID uint32, cols []struct {
	null bool
	kind byte
	raw  []byte
}) []byte {
	n := len(cols)
	nullmap := (n + 7) / 8
	out := make([]byte, 7+nullmap)
	out[0] = 0x01
	binary.LittleEndian.PutUint32(out[1:5], tableID)
	binary.LittleEndian.PutUint16(out[5:7], uint16(n))
	for i, c := range cols {
		if c.null {
			out[7+i/8] |= 1 << (i % 8)
		}
	}
	for _, c := range cols {
		if c.null {
			continue
		}
		out = append(out, c.raw...)
	}
	return out
}

type testCol = struct {
	null bool
	kind byte
	raw  []byte
}

func int32Raw(v int32) []byte {
	b := make([]byte, 4)
	binary.LittleEndian.PutUint32(b, uint32(v))
	return b
}

func int64Raw(v int64) []byte {
	b := make([]byte, 8)
	binary.LittleEndian.PutUint64(b, uint64(v))
	return b
}

func varRaw(s string) []byte {
	b := make([]byte, 4, 4+len(s))
	binary.LittleEndian.PutUint32(b, uint32(len(s)))
	return append(b, s...)
}

func encodeSlots(slots []Slot) []byte {
	out := make([]byte, 2, 2+4*len(slots))
	binary.LittleEndian.PutUint16(out, uint16(len(slots)))
	for _, s := range slots {
		var b [4]byte
		binary.LittleEndian.PutUint16(b[0:2], uint16(s.Ordinal))
		b[2] = s.Kind
		b[3] = byte(s.Width)
		out = append(out, b[:]...)
	}
	return out
}

func encodeFrame(f RowFrame) []byte {
	out := make([]byte, 5)
	out[0] = byte(f.NullmapBytes)
	binary.LittleEndian.PutUint16(out[1:3], uint16(f.ColumnCount))
	binary.LittleEndian.PutUint16(out[3:5], uint16(f.PrefixBytes))
	return out
}

func sampleSlots() []Slot {
	return []Slot{
		{Ordinal: 0, Kind: SlotInt32, Width: 4},
		{Ordinal: 1, Kind: SlotVarLen, Width: 4},
		{Ordinal: 2, Kind: SlotInt64, Width: 8},
		{Ordinal: 3, Kind: SlotFloat64, Width: 8},
		{Ordinal: 4, Kind: SlotInt32, Width: 4},
	}
}

func newSampleLayout(t *testing.T) *RowLayout {
	t.Helper()
	frame, err := ParseRowFrame(encodeFrame(RowFrame{NullmapBytes: 1, ColumnCount: 5, PrefixBytes: 7}))
	if err != nil {
		t.Fatalf("frame: %v", err)
	}
	slots, err := ParseSlots(encodeSlots(sampleSlots()))
	if err != nil {
		t.Fatalf("slots: %v", err)
	}
	layout, err := NewRowLayout(frame, slots)
	if err != nil {
		t.Fatalf("layout: %v", err)
	}
	return layout
}

func sampleRow() []byte {
	return buildRow(7, []testCol{
		{raw: int32Raw(42)},
		{raw: varRaw("abc")},
		{raw: int64Raw(-9)},
		{raw: int64Raw(0x3FF8000000000000)}, // 1.5
		{null: true},
	})
}

func TestProjectRowSelectsColumns(t *testing.T) {
	layout := newSampleLayout(t)
	row := sampleRow()

	// 只要第 1 列(VARCHAR)与第 4 列(INT,是 NULL)。
	frame, selected, ok := layout.ProjectRow(row, 1<<1|1<<4)
	if !ok {
		t.Fatal("project failed")
	}
	if selected != 2 {
		t.Fatalf("selected = %d, want 2", selected)
	}
	// [nullmap 1B][len 4B][abc][第 2 列是 NULL → 只有 nullmap 位]
	if len(frame) != 1+4+3 {
		t.Fatalf("frame len = %d, want %d (frame=%x)", len(frame), 1+4+3, frame)
	}
	if frame[0] != 0x02 {
		t.Fatalf("nullmap = %02x, want 0x02", frame[0])
	}
	if got := binary.LittleEndian.Uint32(frame[1:5]); got != 3 {
		t.Fatalf("len = %d, want 3", got)
	}
	if string(frame[5:8]) != "abc" {
		t.Fatalf("value = %q", frame[5:8])
	}
}

func TestProjectRowMaskZeroKeepsPrimaryKey(t *testing.T) {
	layout := newSampleLayout(t)
	frame, selected, ok := layout.ProjectRow(sampleRow(), 0)
	if !ok || selected != 1 {
		t.Fatalf("mask=0 → selected=%d ok=%v, want 只保留主键列", selected, ok)
	}
	if len(frame) != 1+4+4 {
		t.Fatalf("frame = %x (len %d)", frame, len(frame))
	}
	if binary.LittleEndian.Uint32(frame[1:5]) != 4 {
		t.Fatalf("len = %d, want 4", binary.LittleEndian.Uint32(frame[1:5]))
	}
	if binary.LittleEndian.Uint32(frame[5:9]) != 42 {
		t.Fatalf("primary key = %d", binary.LittleEndian.Uint32(frame[5:9]))
	}
}

func TestProjectRowRejectsTombstone(t *testing.T) {
	layout := newSampleLayout(t)
	if _, _, ok := layout.ProjectRow([]byte{0x02, 0, 0, 0, 7}, 1); ok {
		t.Fatal("墓碑不应被投影(应由 FE 丢弃)")
	}
}

func TestParseProjectionRejectsBadInput(t *testing.T) {
	if _, err := ParseSlots([]byte{1, 0}); err == nil {
		t.Fatal("槽表截断应报错")
	}
	if _, err := ParseRowFrame([]byte{1, 17, 0, 7, 0}); err == nil {
		t.Fatal("nullmap 不足应报错")
	}
	frame, _ := ParseRowFrame(encodeFrame(RowFrame{NullmapBytes: 1, ColumnCount: 5, PrefixBytes: 7}))
	slots, _ := ParseSlots(encodeSlots(sampleSlots()[:4]))
	if _, err := NewRowLayout(frame, slots); err == nil {
		t.Fatal("缺少列槽位应报错")
	}
}

// ---------------------------------------------------------------- 谓词字节码

type codeBuilder struct{ b []byte }

func (c *codeBuilder) slot(n int) *codeBuilder {
	c.b = append(c.b, opPushSlot)
	var x [2]byte
	binary.LittleEndian.PutUint16(x[:], uint16(n))
	c.b = append(c.b, x[:]...)
	return c
}

func (c *codeBuilder) lit(b []byte) *codeBuilder {
	c.b = append(c.b, opPushLit)
	var x [4]byte
	binary.LittleEndian.PutUint32(x[:], uint32(len(b)))
	c.b = append(c.b, x[:]...)
	c.b = append(c.b, b...)
	return c
}

func (c *codeBuilder) op(op byte) *codeBuilder {
	c.b = append(c.b, op)
	return c
}

func (c *codeBuilder) end() []byte {
	c.b = append(c.b, opEnd)
	return c.b
}

func evalOnRow(t *testing.T, layout *RowLayout, row []byte, code []byte) bool {
	t.Helper()
	pred, err := ParsePredicate(code)
	if err != nil {
		t.Fatalf("parse predicate: %v", err)
	}
	cols, ok := layout.Locate(row)
	if !ok {
		t.Fatal("locate failed")
	}
	var scratch [][]byte = make([][]byte, len(cols))
	pass, err := pred.Eval(func(ordinal int) (bool, []byte) {
		c := cols[ordinal]
		if c.Null {
			return true, nil
		}
		slot := layout.ordered[ordinal]
		scratch[ordinal] = CompareBytes(row[c.Off:c.Off+c.Len], slot.Kind, scratch[ordinal])
		return false, scratch[ordinal]
	})
	if err != nil {
		t.Fatalf("eval: %v", err)
	}
	return pass
}

func TestPredicateByteCompare(t *testing.T) {
	layout := newSampleLayout(t)
	row := sampleRow() // 列0 = 42, 列2 = -9, 列4 = NULL

	cases := []struct {
		name string
		code []byte
		want bool
	}{
		{"EQ 命中", new(codeBuilder).slot(0).lit(int32Lit(42)).op(opEQ).end(), true},
		{"EQ 不命中", new(codeBuilder).slot(0).lit(int32Lit(41)).op(opEQ).end(), false},
		{"GT", new(codeBuilder).slot(0).lit(int32Lit(41)).op(opGT).end(), true},
		{"LE", new(codeBuilder).slot(0).lit(int32Lit(42)).op(opLE).end(), true},
		{"负数比较 -9 < 0", new(codeBuilder).slot(2).lit(int64Lit(0)).op(opLT).end(), true},
		{"NULL EQ → unknown", new(codeBuilder).slot(4).lit(int32Lit(1)).op(opEQ).end(), false},
		{"NULL IS NULL", new(codeBuilder).slot(4).op(opIsNull).end(), true},
		{"NULL IS NOT NULL", new(codeBuilder).slot(4).op(opIsNotNull).end(), false},
		{"NOT(unknown) → unknown", new(codeBuilder).slot(4).lit(int32Lit(1)).op(opEQ).op(opNot).end(), false},
		{"AND unknown/false", new(codeBuilder).slot(0).lit(int32Lit(42)).op(opEQ).
			slot(4).lit(int32Lit(1)).op(opEQ).op(opAnd).end(), false},
		{"OR true/unknown", new(codeBuilder).slot(0).lit(int32Lit(42)).op(opEQ).
			slot(4).lit(int32Lit(1)).op(opEQ).op(opOr).end(), true},
		{"BETWEEN", new(codeBuilder).slot(0).lit(int32Lit(40)).lit(int32Lit(50)).op(opBetween).end(), true},
		{"BETWEEN 越界", new(codeBuilder).slot(0).lit(int32Lit(43)).lit(int32Lit(50)).op(opBetween).end(), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := evalOnRow(t, layout, row, tc.code); got != tc.want {
				t.Fatalf("got %v, want %v", got, tc.want)
			}
		})
	}
}

func TestPredicateInOpcode(t *testing.T) {
	layout := newSampleLayout(t)
	row := sampleRow()
	var code []byte
	code = append(code, opPushSlot, 1, 0) // 列 1(VARCHAR)
	code = append(code, opIn)
	var n [2]byte
	binary.LittleEndian.PutUint16(n[:], 2)
	code = append(code, n[:]...)
	for _, lit := range [][]byte{varLit("abc"), varLit("def")} {
		var l [4]byte
		binary.LittleEndian.PutUint32(l[:], uint32(len(lit)))
		code = append(code, l[:]...)
		code = append(code, lit...)
	}
	code = append(code, opEnd)
	if !evalOnRow(t, layout, row, code) {
		t.Fatal("IN ('abc','def') 应命中")
	}
}

func TestPredicateRejectsMalformed(t *testing.T) {
	for _, bad := range [][]byte{
		{0xFF},
		{opEQ, opEnd},
		{opPushSlot, 0, 0},
		{opPushLit, 5, 0, 0, 0, opEnd},
	} {
		if _, err := ParsePredicate(bad); err == nil {
			t.Fatalf("期望报错: %x", bad)
		}
	}
}

// TestCompareBytesOrderPreserving 保序编码必须让字节序 == 数值序。
func TestCompareBytesOrderPreserving(t *testing.T) {
	values := []int32{-2147483648, -1000, -1, 0, 1, 42, 2147483647}
	for i := 1; i < len(values); i++ {
		a := CompareBytes(int32Raw(values[i-1]), SlotInt32, nil)
		b := CompareBytes(int32Raw(values[i]), SlotInt32, nil)
		if bytesCompare(a, b) >= 0 {
			t.Fatalf("int32 保序失败: %d → %x, %d → %x", values[i-1], a, values[i], b)
		}
	}
	longs := []int64{-9223372036854775808, -1, 0, 1, 9223372036854775807}
	for i := 1; i < len(longs); i++ {
		a := CompareBytes(int64Raw(longs[i-1]), SlotInt64, nil)
		b := CompareBytes(int64Raw(longs[i]), SlotInt64, nil)
		if bytesCompare(a, b) >= 0 {
			t.Fatalf("int64 保序失败: %d → %x, %d → %x", longs[i-1], a, longs[i], b)
		}
	}
	doubles := []float64{-1.5, -0.5, 0, 0.5, 1.5, 1e300}
	for i := 1; i < len(doubles); i++ {
		a := CompareBytes(int64Raw(int64bits(doubles[i-1])), SlotFloat64, nil)
		b := CompareBytes(int64Raw(int64bits(doubles[i])), SlotFloat64, nil)
		if bytesCompare(a, b) >= 0 {
			t.Fatalf("float64 保序失败: %v → %x, %v → %x", doubles[i-1], a, doubles[i], b)
		}
	}
}

func int64bits(f float64) int64 { return int64(math.Float64bits(f)) }

// 字面量必须用"比较态"编码(与 CompareBytes 的输出同一套字节序)。
func int32Lit(v int32) []byte {
	b := make([]byte, 4)
	binary.BigEndian.PutUint32(b, uint32(v)^0x80000000)
	return b
}

func int64Lit(v int64) []byte {
	b := make([]byte, 8)
	binary.BigEndian.PutUint64(b, uint64(v)^0x8000000000000000)
	return b
}

func varLit(s string) []byte { return []byte(s) }

func bytesCompare(a, b []byte) int {
	if len(a) != len(b) {
		if len(a) < len(b) {
			return -1
		}
		return 1
	}
	for i := range a {
		if a[i] != b[i] {
			if a[i] < b[i] {
				return -1
			}
			return 1
		}
	}
	return 0
}
