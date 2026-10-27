// Package exec 提供存储层之上的有序扫描迭代器。
//
// 职责很窄:把 memtable、不可变 memtable、多层 segment 这些各自有序的数据源
// 做多路归并,按 key 升序、流式地吐出一条条 KV。它不解释 value 的内容
// (墓碑由 FE 解释),也不做任何 SQL 语义 —— 那是 FE 的事。
//
// 归并规则:
//   - 数据源按"新 → 旧"排列(memtable 最新,其次是不可变表,再是 segment 新到旧);
//   - 同一 key 只输出一次,取最新数据源的值;
//   - 每个数据源同一时刻只保留一条当前记录,内存占用与源数量成正比,与范围大小无关。
package exec

import (
	"bytes"
	"container/heap"
	"sync"

	"github.com/1638540149/tang_db/be/internal/memtable"
	"github.com/1638540149/tang_db/be/internal/segment"
)

// Cursor 是一个数据源上的有序游标。所有方法都只读取当前一条记录。
type Cursor interface {
	// Seek 定位到第一个 key >= target 的位置;target 为空表示从头开始。
	Seek(target []byte)
	Valid() bool
	Key() []byte
	Value() []byte
	Next()
	Err() error
}

// Scanner 是归并后的流式扫描器。用法:
//
//	sc := store.NewScanner(start, end, limit)
//	for sc.Next() {
//	    use(sc.Key(), sc.Value())
//	}
//	if err := sc.Err(); err != nil { ... }
type Scanner struct {
	start []byte
	end   []byte // nil/空 表示到末尾
	limit int    // <= 0 表示不限

	h       cursorHeap
	cursors []Cursor
	started bool
	done    bool
	emitted int
	key     []byte
	value   []byte
	err     error

	releaseOnce sync.Once
	release     func()
}

// NewScanner 创建扫描器。cursors 必须按"新 → 旧"排列,顺序决定同 key 的取舍。
func NewScanner(cursors []Cursor, start, end []byte, limit int) *Scanner {
	return &Scanner{
		start:   start,
		end:     end,
		limit:   limit,
		cursors: cursors,
	}
}

// NewScannerWithRelease 创建一个扫描器:结束时(用完 / Close)调用一次 release。
// store 用它实现 segment 引用计数,保证正在读的旧文件不会被 compaction 删除。
func NewScannerWithRelease(cursors []Cursor, start, end []byte, limit int, release func()) *Scanner {
	s := NewScanner(cursors, start, end, limit)
	s.release = release
	return s
}

// Close 释放扫描器持有的资源(可重复调用)。
func (s *Scanner) Close() {
	s.releaseOnce.Do(func() {
		if s.release != nil {
			s.release()
		}
	})
	if !s.done {
		s.done = true
	}
}

func (s *Scanner) finish() {
	s.done = true
	s.Close()
}

// Next 前进到满足条件的下一条记录。返回 false 表示扫描结束或出错。
func (s *Scanner) Next() bool {
	if s.done {
		return false
	}
	if !s.started {
		s.started = true
		if err := s.init(); err != nil {
			s.err = err
			s.finish()
			return false
		}
	}
	if s.limit > 0 && s.emitted >= s.limit {
		s.finish()
		return false
	}

	for s.h.Len() > 0 {
		top := s.h[0]
		key := top.c.Key()
		if len(s.end) > 0 && bytes.Compare(key, s.end) >= 0 {
			s.finish()
			return false
		}
		s.key = key
		s.value = top.c.Value()

		// 把所有等于该 key 的源一并推进:同一 key 只出一次,且 heap 顶部是"最新"的源。
		for s.h.Len() > 0 && bytes.Equal(s.h[0].c.Key(), key) {
			item := heap.Pop(&s.h).(*cursorItem)
			item.c.Next()
			if err := item.c.Err(); err != nil {
				s.err = err
				s.finish()
				return false
			}
			if item.c.Valid() {
				heap.Push(&s.h, item)
			}
		}

		s.emitted++
		if err := s.firstErr(); err != nil {
			s.err = err
			s.finish()
			return false
		}
		if s.limit > 0 && s.emitted >= s.limit {
			s.finish()
		}
		return true
	}
	s.finish()
	return false
}

// Key 返回当前记录的 key。
func (s *Scanner) Key() []byte { return s.key }

// Value 返回当前记录的 value(可能是墓碑,由调用方解释)。
func (s *Scanner) Value() []byte { return s.value }

// Err 返回扫描过程中的错误。正常结束返回 nil。
func (s *Scanner) Err() error { return s.err }

func (s *Scanner) init() error {
	if len(s.end) > 0 && len(s.start) > 0 && bytes.Compare(s.start, s.end) >= 0 {
		s.done = true
		return nil
	}
	for _, c := range s.cursors {
		c.Seek(s.start)
		if err := c.Err(); err != nil {
			return err
		}
		if c.Valid() {
			s.h = append(s.h, &cursorItem{c: c, idx: len(s.h)})
		}
	}
	heap.Init(&s.h)
	return nil
}

func (s *Scanner) firstErr() error {
	for _, c := range s.cursors {
		if err := c.Err(); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------- 适配器

// NewMemCursor 把 memtable 包装成 Cursor。
func NewMemCursor(m *memtable.MemTable) Cursor {
	return &memCursor{it: m.Iterator()}
}

type memCursor struct {
	it *memtable.Iterator
}

func (c *memCursor) Seek(target []byte) { c.it.Seek(target) }
func (c *memCursor) Valid() bool        { return c.it.Valid() }
func (c *memCursor) Key() []byte        { return c.it.Key() }
func (c *memCursor) Value() []byte      { return c.it.Value() }
func (c *memCursor) Next()              { c.it.Next() }
func (c *memCursor) Err() error         { return nil }

// NewSegmentCursor 把 segment 包装成 Cursor。
func NewSegmentCursor(r *segment.Reader) Cursor {
	return &segmentCursor{it: r.NewIterator()}
}

type segmentCursor struct {
	it *segment.Iterator
}

func (c *segmentCursor) Seek(target []byte) { c.it.Seek(target) }
func (c *segmentCursor) Valid() bool        { return c.it.Valid() }
func (c *segmentCursor) Key() []byte        { return c.it.Key() }
func (c *segmentCursor) Value() []byte      { return c.it.Value() }
func (c *segmentCursor) Next()              { c.it.Next() }
func (c *segmentCursor) Err() error         { return c.it.Err() }

// ---------------------------------------------------------------- 堆

type cursorItem struct {
	c   Cursor
	idx int // 源顺序,越小越新
}

type cursorHeap []*cursorItem

func (h cursorHeap) Len() int { return len(h) }

func (h cursorHeap) Less(i, j int) bool {
	cmp := bytes.Compare(h[i].c.Key(), h[j].c.Key())
	if cmp != 0 {
		return cmp < 0
	}
	// key 相同时,更新的源排在前面(先被弹出,其值胜出)。
	return h[i].idx < h[j].idx
}

func (h cursorHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }

func (h *cursorHeap) Push(x any) { *h = append(*h, x.(*cursorItem)) }

func (h *cursorHeap) Pop() any {
	old := *h
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	*h = old[:n-1]
	return item
}
