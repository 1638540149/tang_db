package segment

import (
	"bytes"
	"fmt"
	"sort"
)

// Iterator 是 segment 的有序迭代器。
//
// 它以 chunk 为单位按需读取(每次最多 chunkRows 行),不会把整个文件读进内存,
// 因此可以直接支撑流式 Scan。
type Iterator struct {
	r      *Reader
	chunk  int // 当前 chunk 下标,-1 表示尚未加载
	keys   [][]byte
	values [][]byte
	pos    int
	valid  bool
	err    error
}

// NewIterator 创建从空位置开始的迭代器;用 First 或 Seek 定位。
func (r *Reader) NewIterator() *Iterator {
	return &Iterator{r: r, chunk: -1}
}

// First 定位到第一条记录。
func (it *Iterator) First() {
	if err := it.loadChunk(0); err != nil {
		it.fail(err)
		return
	}
	it.pos = 0
	it.valid = true
}

// Seek 定位到第一个 key >= target 的位置。
func (it *Iterator) Seek(target []byte) {
	if it.err != nil {
		return
	}
	// 用 ZoneMap 的 keyMax 跳过整块不可能包含 target 的 chunk。
	idx := sort.Search(len(it.r.zoneMap), func(i int) bool {
		return bytes.Compare(it.r.zoneMap[i].keyMax, target) >= 0
	})
	if idx >= len(it.r.zoneMap) {
		it.valid = false
		return
	}
	if err := it.loadChunk(idx); err != nil {
		it.fail(err)
		return
	}
	pos := sort.Search(len(it.keys), func(i int) bool {
		return bytes.Compare(it.keys[i], target) >= 0
	})
	if pos >= len(it.keys) {
		// 理论上不会发生(keyMax >= target),保守地推进到下一块。
		it.pos = len(it.keys)
		it.advanceChunk()
		return
	}
	it.pos = pos
	it.valid = true
}

// Valid 判断当前位置是否有效。
func (it *Iterator) Valid() bool { return it.valid }

// Key 返回当前 key;无效时返回 nil。
func (it *Iterator) Key() []byte {
	if !it.valid {
		return nil
	}
	return it.keys[it.pos]
}

// Value 返回当前 value;无效时返回 nil。
func (it *Iterator) Value() []byte {
	if !it.valid {
		return nil
	}
	return it.values[it.pos]
}

// Next 前进一条记录。
func (it *Iterator) Next() {
	if !it.valid || it.err != nil {
		return
	}
	it.pos++
	if it.pos < len(it.keys) {
		return
	}
	it.advanceChunk()
}

// Err 返回迭代过程中遇到的错误。
func (it *Iterator) Err() error { return it.err }

func (it *Iterator) advanceChunk() {
	if it.chunk+1 >= len(it.r.zoneMap) {
		it.valid = false
		return
	}
	if err := it.loadChunk(it.chunk + 1); err != nil {
		it.fail(err)
		return
	}
	it.pos = 0
	it.valid = true
}

func (it *Iterator) loadChunk(idx int) error {
	zone := it.r.zoneMap[idx]
	entry := it.r.index[idx]

	keyChunk, err := it.r.readColumnBlock(
		it.r.footer.KeyColumnOffset+entry.keyOffset, it.r.keyChunkLen(idx))
	if err != nil {
		return err
	}
	valueChunk, err := it.r.readColumnBlock(
		it.r.footer.ValueColumnOffset+entry.valueOffset, it.r.valueChunkLen(idx))
	if err != nil {
		return err
	}

	keys := decodeBytesSeq(keyChunk)
	values := decodeBytesSeq(valueChunk)
	if len(keys) != int(zone.rowCount) || len(values) != int(zone.rowCount) {
		return fmt.Errorf("segment: chunk %d row count mismatch", idx)
	}
	it.chunk = idx
	it.keys = keys
	it.values = values
	return nil
}

func (it *Iterator) fail(err error) {
	it.err = err
	it.valid = false
}
