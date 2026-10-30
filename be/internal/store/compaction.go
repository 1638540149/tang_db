package store

import (
	"bytes"
	"container/heap"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"

	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/segment"
)

// Leveled compaction(SPEC-P5 G 组 / FR-599 ~ FR-603 / K104 / K113)。
//
// 规则:
//   - L0 文件之间 key 可重叠,超过 compaction_l0_files 即合并到 L1;
//   - L1+ 层内 key 范围不重叠,单层超过 compaction_level_max_bytes × ratio^(L-1) 时
//     挑一个文件 + 下一层所有重叠文件合并到下一层;
//   - 合并时丢弃 commit_ts < GC 水位的旧版本(等于水位必须保留)与可回收墓碑;
//   - 新 segment 落盘 → fsync → 原子更新 MANIFEST → 再删旧文件;
//   - 合并期间查询读旧 MANIFEST 视图,不阻塞(K113);旧文件在最后一个读者退出后才删。

// tombstonePrefix 是 FE 行值编码里的墓碑标记(SPEC-P3 §5.3 / RowCodec.FORMAT_TOMBSTONE)。
//
// 这是**存储格式结构约定**,不是表名/列名/SQL 语义:compaction 必须能识别墓碑才能做
// 版本回收(G-4)。BE 仍然不解释 value 的其余内容。
const tombstonePrefix byte = 0x02

// IsTombstoneValue 判断一个 value 是否是墓碑。
func IsTombstoneValue(value []byte) bool {
	return len(value) >= 5 && value[0] == tombstonePrefix
}

// compactionPick 描述一次待执行的合并。
type compactionPick struct {
	inputs   []uint64
	outLevel int
}

// compactLoop 连续执行可用的合并,直到没有触发条件。
func (e *Engine) compactLoop() {
	e.compactionMu.Lock()
	defer e.compactionMu.Unlock()
	for i := 0; i < 32; i++ {
		inputs, outLevel, ok := e.pickCompaction()
		if !ok {
			return
		}
		if err := e.runCompaction(inputs, outLevel); err != nil {
			slog.Error("compaction failed", "inputs", inputs, "out_level", outLevel, "error", err)
			return
		}
	}
}

// pickCompaction 选择一次合并(不看统计信息,纯阈值规则)。
func (e *Engine) pickCompaction() ([]uint64, int, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if e.closed || e.disableCompaction {
		return nil, 0, false
	}
	levels := e.state.Levels
	if len(levels) == 0 {
		return nil, 0, false
	}
	// L0:文件数触发。
	if len(levels[0]) >= e.compactionL0Files {
		inputs := append([]uint64(nil), levels[0]...)
		var next []uint64
		if len(levels) > 1 {
			next = levels[1]
		}
		inputs = append(inputs, e.overlapping(e.handleRange(inputs), next)...)
		return inputs, 1, true
	}
	// L1+:字节数触发,往下一层合并。
	for level := 1; level < len(levels) && level < maxLevels; level++ {
		if e.levelBytesLocked(levels[level]) <= e.levelLimit(level) {
			continue
		}
		input := levels[level][0]
		first := e.handles[input].r.FirstKey()
		last := e.handles[input].r.LastKey()
		inputs := []uint64{input}
		if len(levels) > level+1 {
			inputs = append(inputs, e.overlapping([2][]byte{first, last}, levels[level+1])...)
		}
		return inputs, level + 1, true
	}
	return nil, 0, false
}

// handleRange 返回一组 segment 的 key 范围并集。
func (e *Engine) handleRange(nums []uint64) [2][]byte {
	var first, last []byte
	for _, num := range nums {
		h, ok := e.handles[num]
		if !ok {
			continue
		}
		f := h.r.FirstKey()
		l := h.r.LastKey()
		if first == nil || bytes.Compare(f, first) < 0 {
			first = f
		}
		if last == nil || bytes.Compare(l, last) > 0 {
			last = l
		}
	}
	return [2][]byte{first, last}
}

// overlapping 返回 nums 里与 [first,last] 重叠的 segment 编号。调用方须持有 e.mu。
func (e *Engine) overlapping(span [2][]byte, nums []uint64) []uint64 {
	if span[0] == nil || span[1] == nil {
		return nil
	}
	var out []uint64
	for _, num := range nums {
		h, ok := e.handles[num]
		if !ok {
			continue
		}
		if h.r.ContainsRange(span[0], span[1]) {
			out = append(out, num)
		}
	}
	return out
}

func (e *Engine) levelBytesLocked(nums []uint64) int64 {
	var total int64
	for _, num := range nums {
		if h, ok := e.handles[num]; ok {
			total += segmentFileSize(h.path)
		}
	}
	return total
}

func (e *Engine) levelLimit(level int) int64 {
	limit := e.compactionLevelMax
	for i := 1; i < level; i++ {
		limit *= int64(e.compactionRatio)
	}
	return limit
}

func segmentFileSize(path string) int64 {
	info, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return info.Size()
}

// runCompaction 执行一次合并。写入与 merge 在锁外进行;MANIFEST 原子切换在锁内。
func (e *Engine) runCompaction(inputs []uint64, outLevel int) error {
	e.mu.RLock()
	if e.closed {
		e.mu.RUnlock()
		return nil
	}
	handles := make([]*segHandle, 0, len(inputs))
	for _, num := range inputs {
		h, ok := e.handles[num]
		if !ok {
			e.mu.RUnlock()
			return fmt.Errorf("compaction: segment %d 已不在当前视图", num)
		}
		h.refs.Add(1) // 合并期间持有引用,防止被别的合并删掉
		handles = append(handles, h)
	}
	segNum := e.state.NextSegment
	e.state.NextSegment++
	e.mu.RUnlock()

	release := func() {
		for _, h := range handles {
			e.releaseHandle(h, true)
		}
	}
	defer release()

	watermark := e.oldestActiveSnapshot()
	path := filepath.Join(e.sstDir, segment.Name(segNum))
	written, stats, err := e.mergeSegments(handles, path, watermark)
	if err != nil {
		_ = os.Remove(path)
		return err
	}
	if !written {
		// 所有版本都被回收:写一个空结果,只做 MANIFEST 切换。
		_ = os.Remove(path)
	}
	e.injectCrash(CrashCompactionAfterWrite)

	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return nil
	}
	// 再次确认输入仍在当前视图(compactionMu 已串行化,这里只是防御)。
	for _, num := range inputs {
		if _, ok := e.handles[num]; !ok {
			e.mu.Unlock()
			_ = os.Remove(path)
			return fmt.Errorf("compaction: segment %d 已被其他合并移除", num)
		}
	}

	newState := *e.state
	newState.FormatVersion = manifest.FormatVersion
	newState.Levels = cloneLevels(e.state.Levels)
	ensureLevel(&newState.Levels, outLevel)
	removeFromLevels(newState.Levels, inputs)
	if written {
		newState.Levels[outLevel] = append(newState.Levels[outLevel], segNum)
		sort.Slice(newState.Levels[outLevel], func(i, j int) bool {
			return newState.Levels[outLevel][i] < newState.Levels[outLevel][j]
		})
	}
	normalizeLevels(&newState)
	syncSegmentsField(&newState)

	var newHandle *segHandle
	if written {
		r, err := segment.Open(path)
		if err != nil {
			e.mu.Unlock()
			_ = os.Remove(path)
			return fmt.Errorf("compaction: open new segment: %w", err)
		}
		newHandle = &segHandle{num: segNum, path: path, r: r}
		newHandle.refs.Store(1)
	}
	if err := e.manifest.Save(&newState); err != nil {
		e.mu.Unlock()
		if newHandle != nil {
			_ = newHandle.r.Close()
		}
		_ = os.Remove(path)
		return fmt.Errorf("compaction: save manifest: %w", err)
	}
	if newHandle != nil {
		e.handles[segNum] = newHandle
	}
	// MANIFEST 已切换:旧 segment 从视图移除;最后一个读者退出后才删文件。
	for _, num := range inputs {
		if h, ok := e.handles[num]; ok {
			e.retireHandleLocked(h)
		}
	}
	e.state = &newState
	e.rebuildViewLocked()
	e.flushCond.Broadcast()
	e.mu.Unlock()

	e.injectCrash(CrashCompactionAfterManifest)
	// SPEC-P7 FR-749:旧图随旧 segment 删除,受影响的新段重建
	outputs := []uint64{}
	if written {
		outputs = append(outputs, segNum)
	}
	e.onCompactionDone(inputs, outputs)
	slog.Info("compaction 完成", "level", outLevel, "inputs", inputs,
		"out_segment", segNum, "rows", stats.rows, "dropped_versions", stats.dropped,
		"watermark", watermark)
	return nil
}

// removeFromLevels 从所有层里移除给定 segment 编号。
func removeFromLevels(levels [][]uint64, remove []uint64) {
	drop := make(map[uint64]bool, len(remove))
	for _, num := range remove {
		drop[num] = true
	}
	for i := range levels {
		kept := levels[i][:0]
		for _, num := range levels[i] {
			if !drop[num] {
				kept = append(kept, num)
			}
		}
		levels[i] = kept
	}
}

type mergeStats struct {
	rows    int
	dropped int
}

// mergeSegments 多路归并 + GC 水位过滤,写出新 segment。
func (e *Engine) mergeSegments(handles []*segHandle, path string, watermark uint64) (bool, mergeStats, error) {
	stats := mergeStats{}
	h := &iterHeap{}
	for i, handle := range handles {
		it := handle.r.NewIterator()
		it.First()
		if it.Err() != nil {
			return false, stats, it.Err()
		}
		if it.Valid() {
			heapPush(h, &iterItem{it: it, idx: i})
		}
	}

	builder := segment.NewBloomAwareBuilder(path, e.bloomEnabled)
	var groupUserKey []byte
	var group []versionEntry
	flushGroup := func() {
		retained, dropped := keepVersions(group, watermark)
		stats.dropped += dropped
		for _, v := range retained {
			builder.Add(AppendVersion(groupUserKey, v.ts), v.value)
			stats.rows++
		}
		group = group[:0]
	}

	for h.Len() > 0 {
		top := heapPop(h)
		fullKey := top.it.Key()
		value := append([]byte(nil), top.it.Value()...)
		userKey, ts, err := SplitVersion(fullKey)
		if err != nil {
			return false, stats, err
		}
		if groupUserKey == nil {
			groupUserKey = append([]byte(nil), userKey...)
		} else if !bytes.Equal(userKey, groupUserKey) {
			flushGroup()
			groupUserKey = append(groupUserKey[:0], userKey...)
		}
		group = append(group, versionEntry{ts: ts, value: value})
		top.it.Next()
		if top.it.Err() != nil {
			return false, stats, top.it.Err()
		}
		if top.it.Valid() {
			heapPush(h, top)
		}
	}
	if groupUserKey != nil {
		flushGroup()
	}
	if builder.Len() == 0 {
		return false, stats, nil
	}
	if _, err := builder.Finish(); err != nil {
		return false, stats, err
	}
	return true, stats, nil
}

type versionEntry struct {
	ts    uint64
	value []byte
}

// keepVersions 按 GC 水位保留版本(versions 已按 ts 从新到旧)。
//
//   - 保留所有 ts >= watermark 的版本(等于水位必须保留,边界 46);
//   - 再保留 base = 最新的 ts <= watermark 的版本(INV-7 基版本);
//   - 若最终只剩一个 ts < watermark 的墓碑 → 整组回收(G-4)。
func keepVersions(versions []versionEntry, watermark uint64) ([]versionEntry, int) {
	if len(versions) == 0 {
		return nil, 0
	}
	retained := make([]versionEntry, 0, len(versions))
	base := false
	for _, v := range versions {
		if v.ts >= watermark {
			retained = append(retained, v)
			continue
		}
		if !base {
			retained = append(retained, v)
			base = true
		}
	}
	if len(retained) == 1 && IsTombstoneValue(retained[0].value) && retained[0].ts < watermark {
		return nil, len(versions)
	}
	return retained, len(versions) - len(retained)
}

// ---------------------------------------------------------------- 迭代器小顶堆

type iterItem struct {
	it  *segment.Iterator
	idx int
}

type iterHeap []*iterItem

func (h iterHeap) Len() int { return len(h) }
func (h iterHeap) Less(i, j int) bool {
	cmp := bytes.Compare(h[i].it.Key(), h[j].it.Key())
	if cmp != 0 {
		return cmp < 0
	}
	return h[i].idx < h[j].idx
}
func (h iterHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *iterHeap) Push(x any)         { *h = append(*h, x.(*iterItem)) }
func (h *iterHeap) Pop() any           { old := *h; n := len(old); x := old[n-1]; *h = old[:n-1]; return x }
func heapPush(h *iterHeap, item *iterItem) { heap.Push(h, item) }
func heapPop(h *iterHeap) *iterItem    { return heap.Pop(h).(*iterItem) }
