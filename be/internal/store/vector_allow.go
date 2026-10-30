package store

import (
	"encoding/binary"
	"fmt"
)

// SPEC-P7.1 D 组(FR-805 / K182):路径 A 的允许集下推。
//
// FE 有两种编码(纯字节,BE 不认主键语义,只看"是不是同一串字节"):
//
//	键集合   [u32 len][key] ...
//	布隆     [u32 mbits][u32 hashCount][ceil(mbits/8) 字节位图]
//
// 选择哪种由 FE 决定(允许集 ≤ 1 万用键集合,否则布隆),BE 只按 allow_bloom 解释。
// 空 allow 表示不过滤(路径 B)。

// vecAllow 是已解析的允许集;nil 表示"不限"。
type vecAllow struct {
	keys  map[string]struct{}
	bloom *vecBloom
}

func (a *vecAllow) contains(key []byte) bool {
	if a == nil {
		return true
	}
	if a.bloom != nil {
		return a.bloom.contains(key)
	}
	_, ok := a.keys[string(key)]
	return ok
}

type vecBloom struct {
	bits []byte
	m    uint64
	k    uint32
}

// parseVecAllow 解析 FE 下发的允许集;格式非法时返回 nil(= 不过滤,不让坏数据
// 把检索结果错误地清空;结果正确性由 FE 的谓词再过滤兜底)。
func parseVecAllow(raw []byte, bloom bool) *vecAllow {
	if len(raw) == 0 {
		return nil
	}
	if bloom {
		b, err := parseVecBloom(raw)
		if err != nil {
			return nil
		}
		return &vecAllow{bloom: b}
	}
	keys := make(map[string]struct{}, len(raw)/16)
	for pos := 0; pos+4 <= len(raw); {
		n := int(binary.LittleEndian.Uint32(raw[pos:]))
		pos += 4
		if n < 0 || pos+n > len(raw) {
			return nil
		}
		keys[string(raw[pos:pos+n])] = struct{}{}
		pos += n
	}
	if len(keys) == 0 {
		return nil
	}
	return &vecAllow{keys: keys}
}

func parseVecBloom(raw []byte) (*vecBloom, error) {
	if len(raw) < 8 {
		return nil, fmt.Errorf("store: bloom allow set too short (%d bytes)", len(raw))
	}
	m := uint64(binary.LittleEndian.Uint32(raw[0:]))
	k := binary.LittleEndian.Uint32(raw[4:])
	if m == 0 || k == 0 || k > 32 {
		return nil, fmt.Errorf("store: invalid bloom parameters (m=%d k=%d)", m, k)
	}
	need := int((m + 7) / 8)
	if len(raw) < 8+need {
		return nil, fmt.Errorf("store: bloom bitmap truncated (need %d got %d)", need, len(raw)-8)
	}
	return &vecBloom{bits: raw[8 : 8+need], m: m, k: k}, nil
}

func (b *vecBloom) contains(key []byte) bool {
	h1 := fnv1a64(key)
	h2 := (h1 >> 32) | 1
	for i := uint32(0); i < b.k; i++ {
		bit := (h1 + uint64(i)*h2) % b.m
		if b.bits[bit>>3]&(1<<(bit&7)) == 0 {
			return false
		}
	}
	return true
}

// fnv1a64 与 FE 侧 VectorValues.fnv1a64 必须逐位一致(布隆过滤器两端共用一套哈希)。
func fnv1a64(data []byte) uint64 {
	h := uint64(0xcbf29ce484222325)
	for _, c := range data {
		h ^= uint64(c)
		h *= 0x100000001b3
	}
	return h
}
