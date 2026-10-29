package exec

import (
	"bytes"
	"encoding/binary"
	"fmt"
)

// 谓词字节码(SPEC-P6.1 FR-703 / FR-704 / K146)。
//
// 设计原则:BE 只做**字节比较**,不感知类型。FE 负责把字面量按"保序编码"编译成
// 字节串,列值在比较时由 slot 的类型变换(INT32/INT64/FLOAT64)转成同一套字节序。
//
// 指令集:
//
//	0x01 PUSH_SLOT   : u16 列槽号
//	0x02 PUSH_LIT    : u32 长度 + 字节
//	0x03 PUSH_NULL
//	0x10 EQ 0x11 NE 0x12 LT 0x13 LE 0x14 GT 0x15 GE
//	0x16 IS_NULL 0x17 IS_NOT_NULL
//	0x18 BETWEEN     : 弹 hi, lo, v
//	0x19 IN          : u16 n + n 组(u32 长度 + 字节);弹 v
//	0x20 AND 0x21 OR 0x22 NOT
//	0x30 END         : 栈顶即结果
const (
	opPushSlot  byte = 0x01
	opPushLit   byte = 0x02
	opPushNull  byte = 0x03
	opEQ        byte = 0x10
	opNE        byte = 0x11
	opLT        byte = 0x12
	opLE        byte = 0x13
	opGT        byte = 0x14
	opGE        byte = 0x15
	opIsNull    byte = 0x16
	opIsNotNull byte = 0x17
	opBetween   byte = 0x18
	opIn        byte = 0x19
	opAnd       byte = 0x20
	opOr        byte = 0x21
	opNot       byte = 0x22
	opEnd       byte = 0x30

	maxStackSize = 64
)

// ErrBadPredicate 表示字节码非法(属于调用方参数错误)。
var ErrBadPredicate = fmt.Errorf("exec: invalid predicate")

// Predicate 是校验过的谓词字节码。
type Predicate struct {
	code []byte
}

// ParsePredicate 校验并包装字节码(只在开始扫描时做一次)。
func ParsePredicate(code []byte) (*Predicate, error) {
	if len(code) == 0 {
		return nil, nil
	}
	if err := validatePredicate(code); err != nil {
		return nil, err
	}
	return &Predicate{code: code}, nil
}

// validatePredicate 只做结构校验(操作码/长度/END),不执行。
func validatePredicate(code []byte) error {
	pos := 0
	sawEnd := false
	stack := 0
	need := func(n int, depth int) error {
		if pos+n > len(code) {
			return ErrBadPredicate
		}
		if stack < depth {
			return ErrBadPredicate
		}
		return nil
	}
	for pos < len(code) {
		op := code[pos]
		pos++
		switch op {
		case opPushNull:
			stack++
		case opPushLit:
			if err := need(4, 0); err != nil {
				return err
			}
			n := int(binary.LittleEndian.Uint32(code[pos : pos+4]))
			pos += 4
			if n < 0 || pos+n > len(code) {
				return ErrBadPredicate
			}
			pos += n
			stack++
		case opPushSlot:
			if err := need(2, 0); err != nil {
				return err
			}
			pos += 2
			stack++
		case opIsNull, opIsNotNull, opNot:
			if err := need(0, 1); err != nil {
				return err
			}
		case opEQ, opNE, opLT, opLE, opGT, opGE, opAnd, opOr:
			if err := need(0, 2); err != nil {
				return err
			}
			stack--
		case opBetween:
			if err := need(0, 3); err != nil {
				return err
			}
			stack -= 2
		case opIn:
			if err := need(2, 1); err != nil {
				return err
			}
			n := int(binary.LittleEndian.Uint16(code[pos : pos+2]))
			pos += 2
			for i := 0; i < n; i++ {
				if pos+4 > len(code) {
					return ErrBadPredicate
				}
				l := int(binary.LittleEndian.Uint32(code[pos : pos+4]))
				pos += 4
				if l < 0 || pos+l > len(code) {
					return ErrBadPredicate
				}
				pos += l
			}
		case opEnd:
			if stack != 1 {
				return ErrBadPredicate
			}
			sawEnd = true
		default:
			return fmt.Errorf("%w: unknown opcode 0x%02x", ErrBadPredicate, op)
		}
		if stack > maxStackSize {
			return ErrBadPredicate
		}
	}
	if !sawEnd {
		return fmt.Errorf("%w: missing END", ErrBadPredicate)
	}
	return nil
}

// Empty 判断谓词是否为空。
func (p *Predicate) Empty() bool { return p == nil || len(p.code) == 0 }

// predValue 是字节码栈上的值:要么是字节串(列值/字面量),要么是布尔,要么是 NULL。
type predValue struct {
	null    bool
	isBool  bool
	boolVal bool
	b       []byte
}

func (v predValue) truth() bool { return v.isBool && !v.null && v.boolVal }

// Eval 在给定行上执行谓词。lookup 返回某列的比较态字节。
//
// 结果按 SQL 三值逻辑:只有 TRUE 才通过;FALSE 与 UNKNOWN 都不通过。
func (p *Predicate) Eval(lookup func(ordinal int) (null bool, b []byte)) (bool, error) {
	if p.Empty() {
		return true, nil
	}
	code := p.code
	stack := make([]predValue, 0, maxStackSize)
	pos := 0
	push := func(v predValue) error {
		if len(stack) >= maxStackSize {
			return ErrBadPredicate
		}
		stack = append(stack, v)
		return nil
	}
	pop := func() (predValue, error) {
		if len(stack) == 0 {
			return predValue{}, ErrBadPredicate
		}
		v := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		return v, nil
	}

	for pos < len(code) {
		op := code[pos]
		pos++
		switch op {
		case opPushNull:
			if err := push(predValue{null: true}); err != nil {
				return false, err
			}
		case opPushLit:
			if pos+4 > len(code) {
				return false, ErrBadPredicate
			}
			n := int(binary.LittleEndian.Uint32(code[pos : pos+4]))
			pos += 4
			if n < 0 || pos+n > len(code) {
				return false, ErrBadPredicate
			}
			if err := push(predValue{b: code[pos : pos+n]}); err != nil {
				return false, err
			}
			pos += n
		case opPushSlot:
			if pos+2 > len(code) {
				return false, ErrBadPredicate
			}
			ordinal := int(binary.LittleEndian.Uint16(code[pos : pos+2]))
			pos += 2
			if lookup == nil {
				// 只做结构校验时不会走到这里;真执行必须提供取值器。
				return false, ErrBadPredicate
			}
			isNull, b := lookup(ordinal)
			if err := push(predValue{null: isNull, b: b}); err != nil {
				return false, err
			}
		case opIsNull, opIsNotNull:
			v, err := pop()
			if err != nil {
				return false, err
			}
			truth := v.null
			if op == opIsNotNull {
				truth = !v.null
			}
			if err := push(predValue{isBool: true, boolVal: truth}); err != nil {
				return false, err
			}
		case opNot:
			v, err := pop()
			if err != nil {
				return false, err
			}
			if v.null || !v.isBool {
				if err := push(predValue{null: true}); err != nil {
					return false, err
				}
				continue
			}
			if err := push(predValue{isBool: true, boolVal: !v.boolVal}); err != nil {
				return false, err
			}
		case opAnd, opOr:
			rhs, err := pop()
			if err != nil {
				return false, err
			}
			lhs, err := pop()
			if err != nil {
				return false, err
			}
			if err := push(logical(lhs, rhs, op == opAnd)); err != nil {
				return false, err
			}
		case opEQ, opNE, opLT, opLE, opGT, opGE:
			rhs, err := pop()
			if err != nil {
				return false, err
			}
			lhs, err := pop()
			if err != nil {
				return false, err
			}
			if err := push(compareOp(lhs, rhs, op)); err != nil {
				return false, err
			}
		case opBetween:
			hi, err := pop()
			if err != nil {
				return false, err
			}
			lo, err := pop()
			if err != nil {
				return false, err
			}
			v, err := pop()
			if err != nil {
				return false, err
			}
			if err := push(logical(compareOp(v, lo, opGE), compareOp(v, hi, opLE), true)); err != nil {
				return false, err
			}
		case opIn:
			if pos+2 > len(code) {
				return false, ErrBadPredicate
			}
			n := int(binary.LittleEndian.Uint16(code[pos : pos+2]))
			pos += 2
			v, err := pop()
			if err != nil {
				return false, err
			}
			acc := predValue{isBool: true, boolVal: false}
			for i := 0; i < n; i++ {
				if pos+4 > len(code) {
					return false, ErrBadPredicate
				}
				l := int(binary.LittleEndian.Uint32(code[pos : pos+4]))
				pos += 4
				if l < 0 || pos+l > len(code) {
					return false, ErrBadPredicate
				}
				candidate := predValue{b: code[pos : pos+l]}
				pos += l
				acc = logical(acc, compareOp(v, candidate, opEQ), false)
			}
			if err := push(acc); err != nil {
				return false, err
			}
		case opEnd:
			if len(stack) != 1 {
				return false, ErrBadPredicate
			}
			return stack[0].truth(), nil
		default:
			return false, fmt.Errorf("%w: unknown opcode 0x%02x", ErrBadPredicate, op)
		}
	}
	return false, fmt.Errorf("%w: missing END", ErrBadPredicate)
}

func compareOp(lhs, rhs predValue, op byte) predValue {
	if lhs.null || rhs.null || lhs.isBool || rhs.isBool {
		return predValue{null: true}
	}
	cmp := bytes.Compare(lhs.b, rhs.b)
	var res bool
	switch op {
	case opEQ:
		res = cmp == 0
	case opNE:
		res = cmp != 0
	case opLT:
		res = cmp < 0
	case opLE:
		res = cmp <= 0
	case opGT:
		res = cmp > 0
	case opGE:
		res = cmp >= 0
	}
	return predValue{isBool: true, boolVal: res}
}

// logical 实现 AND / OR 的 SQL 三值逻辑。
func logical(lhs, rhs predValue, isAnd bool) predValue {
	lNull := lhs.null || !lhs.isBool
	rNull := rhs.null || !rhs.isBool
	if isAnd {
		if (!lNull && !lhs.boolVal) || (!rNull && !rhs.boolVal) {
			return predValue{isBool: true, boolVal: false}
		}
		if lNull || rNull {
			return predValue{null: true}
		}
		return predValue{isBool: true, boolVal: true}
	}
	if (!lNull && lhs.boolVal) || (!rNull && rhs.boolVal) {
		return predValue{isBool: true, boolVal: true}
	}
	if lNull || rNull {
		return predValue{null: true}
	}
	return predValue{isBool: true, boolVal: false}
}

// CompareBytes 把行值中某一列的字节转成比较态(SPEC-P6.1 FR-704 的保序编码)。
//
// 返回值可能指向 scratch 复用缓冲,调用方必须在同一次比较用完后再复用。
func CompareBytes(raw []byte, kind byte, scratch []byte) []byte {
	switch kind {
	case SlotInt32:
		if len(raw) != 4 {
			return nil
		}
		var out [4]byte
		v := binary.LittleEndian.Uint32(raw) ^ 0x80000000
		binary.BigEndian.PutUint32(out[:], v)
		return append(scratch[:0], out[:]...)
	case SlotInt64:
		if len(raw) != 8 {
			return nil
		}
		var out [8]byte
		v := binary.LittleEndian.Uint64(raw) ^ 0x8000000000000000
		binary.BigEndian.PutUint64(out[:], v)
		return append(scratch[:0], out[:]...)
	case SlotFloat64:
		if len(raw) != 8 {
			return nil
		}
		var out [8]byte
		v := binary.LittleEndian.Uint64(raw)
		if v&0x8000000000000000 != 0 {
			v = ^v // 负数:整体取反
		} else {
			v ^= 0x8000000000000000 // 正数:翻符号位
		}
		binary.BigEndian.PutUint64(out[:], v)
		return append(scratch[:0], out[:]...)
	case SlotVarLen:
		if len(raw) < 4 {
			return nil
		}
		n := int(binary.LittleEndian.Uint32(raw[:4]))
		if n < 0 || 4+n > len(raw) {
			return nil
		}
		return raw[4 : 4+n]
	case SlotVarLenSigned:
		if len(raw) < 4 {
			return nil
		}
		n := int(binary.LittleEndian.Uint32(raw[:4]))
		if n < 0 || 4+n > len(raw) {
			return nil
		}
		// Java 的 Arrays.compare(byte[], byte[]) 是有符号比较;无符号比较前把每个字节
		// 的最高位翻一下,两种口径完全等价。
		out := append(scratch[:0], raw[4:4+n]...)
		for i := range out {
			out[i] ^= 0x80
		}
		return out
	default:
		return raw
	}
}
