// Package memtable 提供基于跳表的有序内存表。
//
// 跳表支持并发插入(通过细粒度锁)、按 key 查找以及有序遍历。
// P1 版本只存储最新值,不做 MVCC 版本链。
package memtable

import (
	"bytes"
	"errors"
	"math/rand"
	"sort"
	"sync"
)

const (
	maxLevel = 12
	p        = 0.25
)

// MemTable 是有序跳表实现的内存键值表。
type MemTable struct {
	mu      sync.RWMutex
	head    *node
	level   int
	size    int64  // 估算的内存占用字节数
	entries int64  // KV 条数
	writes  uint64 // 写计数水位(惰性游标用它判断是否失效)
}

type node struct {
	key     []byte
	value   []byte
	forward []*node
}

// New 创建空跳表。
func New() *MemTable {
	h := &node{forward: make([]*node, maxLevel)}
	return &MemTable{head: h, level: 1}
}

// Put 插入或覆盖一个键值。
func (m *MemTable) Put(key, value []byte) {
	if len(key) == 0 {
		panic("memtable: empty key")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes++

	update := make([]*node, maxLevel)
	cur := m.head
	for i := m.level - 1; i >= 0; i-- {
		for cur.forward[i] != nil && bytes.Compare(cur.forward[i].key, key) < 0 {
			cur = cur.forward[i]
		}
		update[i] = cur
	}
	cur = cur.forward[0]
	if cur != nil && bytes.Equal(cur.key, key) {
		oldSize := int64(len(cur.key)) + int64(len(cur.value))
		cur.value = append([]byte(nil), value...)
		m.size += int64(len(value)) - oldSize
		return
	}

	newLevel := randomLevel()
	if newLevel > m.level {
		for i := m.level; i < newLevel; i++ {
			update[i] = m.head
		}
		m.level = newLevel
	}

	n := &node{
		key:     append([]byte(nil), key...),
		value:   append([]byte(nil), value...),
		forward: make([]*node, newLevel),
	}
	for i := 0; i < newLevel; i++ {
		n.forward[i] = update[i].forward[i]
		update[i].forward[i] = n
	}
	m.size += int64(len(key)) + int64(len(value))
	m.entries++
}

// Get 按 key 查找;不存在时 ok=false。
func (m *MemTable) Get(key []byte) (value []byte, ok bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cur := m.head
	for i := m.level - 1; i >= 0; i-- {
		for cur.forward[i] != nil && bytes.Compare(cur.forward[i].key, key) < 0 {
			cur = cur.forward[i]
		}
	}
	cur = cur.forward[0]
	if cur != nil && bytes.Equal(cur.key, key) {
		return cur.value, true
	}
	return nil, false
}

// ApproximateSize 返回估算的内存占用(字节)。
func (m *MemTable) ApproximateSize() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.size
}

// Len 返回条目数。
func (m *MemTable) Len() int64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.entries
}

// ErrUnsafe 表示游标创建之后 memtable 又被写入过:继续用它会读到"过期的相对位置",
// 因此必须由上层重建游标(SPEC-P6.1 FR-697 / K151)。宁可重试,不许静默脏读。
var ErrUnsafe = errors.New("memtable: iterator invalidated by concurrent write (unsafe)")

// Iterator 提供有序遍历(SPEC-P6.1 FR-696 / FR-697)。
//
// 默认是**惰性游标**:Seek 走跳表下降 O(log n),遍历过程中不分配整表切片;
// 创建时记录写计数水位,一旦水位变化就返回 ErrUnsafe,由上层重建。
// 长生命周期调用方(需要"拷贝语义"的快照)用 IteratorSnapshot。
type Iterator struct {
	m  *MemTable
	wn uint64 // 创建时的写计数水位

	snapshot bool    // true = 拷贝语义
	nodes    []*node // snapshot 模式下的节点快照
	pos      int

	cur   *node // 惰性模式下的当前位置
	valid bool
	key   []byte
	value []byte
	err   error
}

// Iterator 创建惰性游标(不拷贝整表,只记水位)。
func (m *MemTable) Iterator() *Iterator {
	m.mu.RLock()
	wn := m.writes
	m.mu.RUnlock()
	return &Iterator{m: m, wn: wn, pos: -1}
}

// IteratorAt 在同一次读锁内"记水位 + Seek",消除"建游标"与"第一次定位"之间的
// 竞争窗口(并发写下这一步高频失败,SPEC-P6.1 FR-697 的失败面因此被放大)。
func (m *MemTable) IteratorAt(target []byte) *Iterator {
	it := &Iterator{m: m, pos: -1}
	m.mu.RLock()
	defer m.mu.RUnlock()
	it.wn = m.writes
	cur := m.head
	for i := m.level - 1; i >= 0; i-- {
		for cur.forward[i] != nil && bytes.Compare(cur.forward[i].key, target) < 0 {
			cur = cur.forward[i]
		}
	}
	it.cur = cur.forward[0]
	it.capture()
	return it
}

// IteratorFirstAtStart 与 IteratorAt 相同,但定位到首元素(目标为空时也走它)。
func (m *MemTable) IteratorFirstAtStart() *Iterator {
	return m.IteratorAt(nil)
}

// IteratorSnapshot 创建拷贝语义的快照游标:遍历期间不再受并发写影响。
// 给长生命周期调用方使用(内存占用与表大小成正比)。
func (m *MemTable) IteratorSnapshot() *Iterator {
	m.mu.RLock()
	nodes := make([]*node, 0, len(m.head.forward))
	nodes = nodes[:0]
	for cur := m.head.forward[0]; cur != nil; cur = cur.forward[0] {
		nodes = append(nodes, cur)
	}
	m.mu.RUnlock()
	return &Iterator{snapshot: true, nodes: nodes, pos: -1}
}

// Valid 判断当前位置是否有效。
func (it *Iterator) Valid() bool {
	if it.snapshot {
		return it.pos >= 0 && it.pos < len(it.nodes)
	}
	return it.valid
}

// Next 移动到下一个位置。
func (it *Iterator) Next() {
	if it.snapshot {
		it.pos++
		return
	}
	if it.err != nil {
		return
	}
	m := it.m
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.writes != it.wn {
		it.fail()
		return
	}
	if it.cur == nil {
		it.valid = false
		return
	}
	it.cur = it.cur.forward[0]
	it.capture()
}

// Key 返回当前 key。调用前须确保 Valid()==true。
func (it *Iterator) Key() []byte {
	if it.snapshot {
		return it.nodes[it.pos].key
	}
	return it.key
}

// Value 返回当前 value。调用前须确保 Valid()==true。
func (it *Iterator) Value() []byte {
	if it.snapshot {
		return it.nodes[it.pos].value
	}
	return it.value
}

// First 移动到首元素。
func (it *Iterator) First() {
	if it.snapshot {
		it.pos = 0
		return
	}
	if it.err != nil {
		return
	}
	m := it.m
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.writes != it.wn {
		it.fail()
		return
	}
	it.cur = m.head.forward[0]
	it.capture()
}

// Seek 定位到第一个 key >= target 的位置。target 为空表示首元素。
// 越界后 Valid() 返回 false。跳表下降,不拷贝整表。
func (it *Iterator) Seek(target []byte) {
	if it.snapshot {
		it.pos = sort.Search(len(it.nodes), func(i int) bool {
			return bytes.Compare(it.nodes[i].key, target) >= 0
		})
		return
	}
	if it.err != nil {
		return
	}
	m := it.m
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.writes != it.wn {
		it.fail()
		return
	}
	cur := m.head
	for i := m.level - 1; i >= 0; i-- {
		for cur.forward[i] != nil && bytes.Compare(cur.forward[i].key, target) < 0 {
			cur = cur.forward[i]
		}
	}
	it.cur = cur.forward[0]
	it.capture()
}

// Err 返回游标错误;水位失效时是 ErrUnsafe。
func (it *Iterator) Err() error {
	if it.snapshot {
		return nil
	}
	return it.err
}

// capture 在持锁状态下把当前节点的 key/value 记到游标上。
// 节点 value 只被整体替换(不原地改写),所以捕获的旧切片始终安全。
func (it *Iterator) capture() {
	if it.cur == nil {
		it.valid = false
		it.key = nil
		it.value = nil
		return
	}
	it.valid = true
	it.key = it.cur.key
	it.value = it.cur.value
}

func (it *Iterator) fail() {
	it.err = ErrUnsafe
	it.valid = false
}

func randomLevel() int {
	level := 1
	for level < maxLevel && rand.Float64() < p {
		level++
	}
	return level
}
