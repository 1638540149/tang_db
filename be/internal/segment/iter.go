package segment

import (
	"bytes"
	"fmt"
	"sort"
)

// Iterator 是 segment 的有序迭代器。
//
// 以 chunk 为单位按需读取(每次最多 chunkRows 行),不会把整个文件读进内存,
// 因此可以直接支撑流式 Scan。
type Iterator struct {
	r      *Reader
	chunk  int // 当前 chunk 下标,-1 表示尚未加载
	keys   [][]byte
	values [][]byte
	pos    int
	valid  bool
	err    error

	// SPEC-P6.1 FR-709:顺序扫描预读下一个 chunk(只读,不改磁盘格式)。
	// 预读失败不影响结果:消费时发现 err != nil 就退回同步读。
	pf *prefetch
}

// prefetch 是一个正在后台读取的 chunk。
type prefetch struct {
	idx        int
	keyChunk   []byte
	valueChunk []byte
	err        error
	done       chan struct{}
}

// startPrefetch 在后台把 idx 号 chunk 的原始字节读进内存。
// os.File.ReadAt 本身并发安全;预读只是把下一次 loadChunk 的两次 ReadAt 提前。
func (it *Iterator) startPrefetch(idx int) {
	if idx < 0 || idx >= len(it.r.index) {
		return
	}
	entry := it.r.index[idx]
	pf := &prefetch{idx: idx, done: make(chan struct{})}
	it.pf = pf
	go func() {
		defer close(pf.done)
		keyChunk, err := it.r.readAt(it.r.footer.ChunkAreaOffset+entry.keyOffset,
			entry.keyLen, false)
		if err != nil {
			pf.err = err
			return
		}
		valueChunk, err := it.r.readAt(it.r.footer.ValueColumnOffset+entry.valueOffset,
			entry.valueLen, false)
		if err != nil {
			pf.err = err
			return
		}
		pf.keyChunk, pf.valueChunk = keyChunk, valueChunk
	}()
}

// takePrefetch 取走"正好是 idx"的预读结果;没有或失败时返回 nil。
func (it *Iterator) takePrefetch(idx int) (*prefetch, bool) {
	pf := it.pf
	if pf == nil || pf.idx != idx {
		return nil, false
	}
	<-pf.done
	it.pf = nil
	if pf.err != nil {
		return nil, false
	}
	return pf, true
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
//
// v2:对 Index Block 的 keyMax 二分(v1:对 ZoneMap 二分),块内再用 sort.Search 二分。
func (it *Iterator) Seek(target []byte) {
	if it.err != nil {
		return
	}
	keyMax := func(i int) []byte {
		if it.r.format == formatV1 {
			return it.r.zoneMap[i].keyMax
		}
		return it.r.index[i].keyMax
	}
	n := len(it.r.index)
	idx := sort.Search(n, func(i int) bool {
		return bytes.Compare(keyMax(i), target) >= 0
	})
	if idx >= n {
		it.valid = false
		return
	}
	// SPEC-P6.1 FR-706:二分命中的 chunk 之前的全部 chunk 都被 key 范围剪掉了。
	it.r.AddSkippedChunks(idx)
	if err := it.loadChunk(idx); err != nil {
		it.fail(err)
		return
	}
	pos := sort.Search(len(it.keys), func(i int) bool {
		return bytes.Compare(it.keys[i], target) >= 0
	})
	if pos >= len(it.keys) {
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
	if it.chunk+1 >= len(it.r.index) {
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
	entry := it.r.index[idx]
	// SPEC-P7.1:已解码的 chunk 直接复用(点查回表的热点就是重复解码同一批 chunk)。
	if cached := it.r.cachedRows(idx); cached != nil {
		it.pf = nil
		it.chunk = idx
		it.keys = cached.keys
		it.values = cached.values
		return nil
	}
	var keyChunk, valueChunk []byte
	if pf, ok := it.takePrefetch(idx); ok {
		keyChunk, valueChunk = pf.keyChunk, pf.valueChunk
	} else {
		var err error
		keyChunk, err = it.r.readAt(it.r.footer.ChunkAreaOffset+entry.keyOffset, entry.keyLen, false)
		if err != nil {
			return err
		}
		valueChunk, err = it.r.readAt(it.r.footer.ValueColumnOffset+entry.valueOffset, entry.valueLen, false)
		if err != nil {
			return err
		}
	}

	var keys, values [][]byte
	if it.r.format == formatV2 {
		keyInfo, decodedKeys, err := decodeColumnChunk(keyChunk, columnIDKey)
		if err != nil {
			return err
		}
		_, decodedValues, err := decodeColumnChunk(valueChunk, columnIDValue)
		if err != nil {
			return err
		}
		if keyInfo.Rows != entry.rowCount || len(decodedValues) != entry.rowCount {
			return fmt.Errorf("segment: chunk %d 行数不一致(index %d)",
				idx, entry.rowCount)
		}
		keys, values = decodedKeys, decodedValues
	} else {
		keys = decodeBytesSeq(keyChunk)
		values = decodeBytesSeq(valueChunk)
		if len(keys) != entry.rowCount || len(values) != entry.rowCount {
			return fmt.Errorf("segment: chunk %d row count mismatch", idx)
		}
	}
	it.r.AddReadChunk()
	it.r.cacheRows(idx, keys, values)
	it.chunk = idx
	it.keys = keys
	it.values = values
	// 预读下一个 chunk:失败只影响性能,不影响结果(消费时退回同步读)。
	if it.pf == nil {
		it.startPrefetch(idx + 1)
	}
	return nil
}

func (it *Iterator) fail(err error) {
	it.err = err
	it.valid = false
}
