package segment

import (
	"container/list"
	"sync"
	"sync/atomic"
)

// SPEC-P7.1 A 组(K180 / SC-439):chunk 解码缓存。
//
// 背景(打点实测):10 万 × 128 维下,BE 的候选回表是 64 次点查;每次点查要跨 6 个
// segment,每个 segment 都要**整块解码**命中的 chunk(value chunk ≈ 2.2MB)。
// 384 次整块解码 ≈ 290ms,占了检索时延的 9 成。
//
// 这里做的只是"解码一次、复用结果":
//   - 缓存的是**解码后的行**(只读切片),不缓存原始字节 —— 原始字节命中后仍要整块解码;
//   - 进程级 LRU,按字节预算淘汰(默认 96MB),与磁盘格式无关,不改任何文件布局;
//   - 解码结果只读:迭代器只读它的切片,淘汰后旧切片仍由 GC 持有,不会看到半块数据。
//
// 与 P6.1 的 chunk 计数口径的关系:缓存命中不增加 ChunksRead(确实没有解码),
// 因此 SHOW SEGMENTS 的 skipped_chunks / pruned_chunks 语义不变。

const defaultChunkCacheBytes = 96 << 20 // 96MB:10 万 × 128 维全表 value chunk 约 55MB

type chunkCacheKey struct {
	reader uint64
	chunk  int
}

type cachedChunk struct {
	keys   [][]byte
	values [][]byte
	size   int64
}

type chunkCacheEntry struct {
	key   chunkCacheKey
	chunk *cachedChunk
}

type chunkCache struct {
	mu      sync.Mutex
	max     int64
	bytes   int64
	order   *list.List // front = 最近使用
	entries map[chunkCacheKey]*list.Element
}

var (
	readerSeq        atomic.Uint64
	globalChunkCache = newChunkCache(defaultChunkCacheBytes)
)

func newChunkCache(max int64) *chunkCache {
	return &chunkCache{
		max:     max,
		order:   list.New(),
		entries: make(map[chunkCacheKey]*list.Element),
	}
}

func (c *chunkCache) get(key chunkCacheKey) *cachedChunk {
	if c == nil || c.max <= 0 {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.entries[key]
	if !ok {
		return nil
	}
	c.order.MoveToFront(el)
	return el.Value.(*chunkCacheEntry).chunk
}

func (c *chunkCache) put(key chunkCacheKey, chunk *cachedChunk) {
	if c == nil || c.max <= 0 || chunk == nil || chunk.size > c.max {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.entries[key]; ok {
		c.order.MoveToFront(el)
		return
	}
	el := c.order.PushFront(&chunkCacheEntry{key: key, chunk: chunk})
	c.entries[key] = el
	c.bytes += chunk.size
	for c.bytes > c.max {
		back := c.order.Back()
		if back == nil {
			break
		}
		entry := back.Value.(*chunkCacheEntry)
		c.order.Remove(back)
		delete(c.entries, entry.key)
		c.bytes -= entry.chunk.size
	}
}

// drop 清掉某个 reader 的全部缓存(segment 关闭 / 回收时调用)。
func (c *chunkCache) drop(readerID uint64) {
	if c == nil || c.max <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for key, el := range c.entries {
		if key.reader != readerID {
			continue
		}
		entry := el.Value.(*chunkCacheEntry)
		c.order.Remove(el)
		delete(c.entries, key)
		c.bytes -= entry.chunk.size
	}
}

// cachedRows 返回某 chunk 已解码的行(key/value),命中时可直接复用。
func (r *Reader) cachedRows(idx int) *cachedChunk {
	return globalChunkCache.get(chunkCacheKey{reader: r.cacheID, chunk: idx})
}

func (r *Reader) cacheRows(idx int, keys, values [][]byte) {
	size := int64(0)
	for i := range keys {
		size += int64(len(keys[i]) + 24)
	}
	for i := range values {
		size += int64(len(values[i]) + 24)
	}
	globalChunkCache.put(chunkCacheKey{reader: r.cacheID, chunk: idx},
		&cachedChunk{keys: keys, values: values, size: size})
}
