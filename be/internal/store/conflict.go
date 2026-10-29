package store

import (
	"bytes"
	"sort"
)

// prepareWrites 按用户键排序去重(SPEC-P6.1 FR-693):
// 同一 key 在一个批里出现多次时只保留**最后一次写**。
//
// 排序结果沿用到 WAL 批记录构建与 memtable 应用 —— 同一批的行按 key 有序,
// 既让冲突检测能一次扫完,也让跳表插入顺序更好。
func prepareWrites(writes []Write) []Write {
	out := make([]Write, len(writes))
	copy(out, writes)
	sort.SliceStable(out, func(i, j int) bool {
		return bytes.Compare(out[i].Key, out[j].Key) < 0
	})
	uniq := out[:0]
	for _, w := range out {
		if n := len(uniq); n > 0 && bytes.Equal(uniq[n-1].Key, w.Key) {
			// SliceStable + 顺序遍历 ⇒ 后出现的就是"最后一次写"。
			uniq[n-1] = w
			continue
		}
		uniq = append(uniq, w)
	}
	return uniq
}

// hasPrefixRelatedKeys 判断相邻的写集 key 是否互为前缀。
//
// 完整 key = 用户键 + 8 字节版本段,用户键互为前缀时完整 key 的字节序与用户键序
// 不一致(P3 已知的格式约束,见 README)。SQL 路径的主键编码保证不会出现这种情况,
// 这里只影响裸 KV 场景,但"冲突不漏检"是红线,必须显式处理。
func hasPrefixRelatedKeys(sorted []Write) bool {
	for i := 1; i < len(sorted); i++ {
		if bytes.HasPrefix(sorted[i].Key, sorted[i-1].Key) {
			return true
		}
	}
	return false
}

// detectConflicts 单次区间扫描做批量冲突检测(SPEC-P6.1 FR-694):
// 一个 scanner 覆盖写集首尾 key 并归并,禁止每 key 建 scanner。
//
// 语义与 P3 完全一致:某写集 key 的最新版本 commit_ts > snapshot 即冲突,
// 返回冲突 key。冲突检测不取号、不写任何数据。
//
// 注意:不能假设"扫描出的用户键顺序 == 写集顺序" —— 用户键互为前缀时
// (P3 已知的格式约束,crash-test 用 key-1/key-10)完整 key 的字节序会与用户键序
// 不一致。因此每条扫到的用户键都用二分查写集,而不是单向前进指针。
func (e *Engine) detectConflicts(sorted []Write, snapshot uint64) ([]byte, error) {
	if len(sorted) == 0 {
		return nil, nil
	}

	start := sorted[0].Key
	end := prefixUpperBound(sorted[len(sorted)-1].Key)
	if hasPrefixRelatedKeys(sorted) {
		// 写集内部互为前缀时(如 key-1 / key-10),较短 key 的版本段(0xFF…)
		// 会排在较长 key 的上界之后,必须放开上界,否则会漏掉它的最新版本。
		end = nil
	}

	sc := e.newScanner(start, end, 0)
	defer sc.Close()

	for sc.Next() {
		userKey, ts, err := SplitVersion(sc.Key())
		if err != nil {
			return nil, err
		}
		i := sort.Search(len(sorted), func(k int) bool {
			return bytes.Compare(sorted[k].Key, userKey) >= 0
		})
		if i >= len(sorted) || !bytes.Equal(sorted[i].Key, userKey) {
			continue
		}
		// 版本段按"新 → 旧"排列:第一条就是最新版本。
		if ts > snapshot {
			return append([]byte(nil), userKey...), nil
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return nil, nil
}
