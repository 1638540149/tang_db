// Package memtable 提供基于跳表的有序内存表。
//
// 跳表支持并发插入(通过细粒度锁)、按 key 查找以及有序遍历。
// P1 版本只存储最新值,不做 MVCC 版本链。
package memtable

import (
	"bytes"
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
	size    int64 // 估算的内存占用字节数
	entries int64 // KV 条数
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

// Iterator 提供有序遍历。遍历时持有读锁的快照。
func (m *MemTable) Iterator() *Iterator {
	m.mu.RLock()
	// 收集所有节点到快照切片,避免遍历期间锁长期占用。
	var nodes []*node
	for cur := m.head.forward[0]; cur != nil; cur = cur.forward[0] {
		nodes = append(nodes, cur)
	}
	m.mu.RUnlock()
	return &Iterator{nodes: nodes, pos: -1}
}

// Iterator 是跳表的有序迭代器。
type Iterator struct {
	nodes []*node
	pos   int
}

// Valid 判断当前位置是否有效。
func (it *Iterator) Valid() bool {
	return it.pos >= 0 && it.pos < len(it.nodes)
}

// Next 移动到下一个位置。
func (it *Iterator) Next() {
	it.pos++
}

// Key 返回当前 key。调用前须确保 Valid()==true。
func (it *Iterator) Key() []byte {
	return it.nodes[it.pos].key
}

// Value 返回当前 value。调用前须确保 Valid()==true。
func (it *Iterator) Value() []byte {
	return it.nodes[it.pos].value
}

// First 移动到首元素。
func (it *Iterator) First() {
	it.pos = 0
}

// Seek 定位到第一个 key >= target 的位置。target 为空表示首元素。
// 越界后 Valid() 返回 false。
func (it *Iterator) Seek(target []byte) {
	it.pos = sort.Search(len(it.nodes), func(i int) bool {
		return bytes.Compare(it.nodes[i].key, target) >= 0
	})
}

func randomLevel() int {
	level := 1
	for level < maxLevel && rand.Float64() < p {
		level++
	}
	return level
}
