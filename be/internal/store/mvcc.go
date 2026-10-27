package store

import (
	"bytes"
	"encoding/binary"
	"fmt"

	"github.com/1638540149/tang_db/be/internal/exec"
)

// VersionBytes 是 key 尾部版本段的固定长度(SPEC-P3 §5.1 / FR-320)。
//
// 版本段 = ^commit_ts(按位取反、8 字节大端):
// 因此 commit_ts 越大,^commit_ts 的字节序越小,新版本排在前面。
const VersionBytes = 8

// AppendVersion 在用户键尾部追加 8 字节版本段 ^commit_ts。
//
// userKey 由 FE 编码(表 ID + 索引 ID + 索引列值 + 主键值),BE 不解释它的内容;
// BE 只维护"尾部 8 字节是版本"这一条结构约定(宪法边界铁律)。
func AppendVersion(userKey []byte, commitTS uint64) []byte {
	out := make([]byte, len(userKey)+VersionBytes)
	copy(out, userKey)
	binary.BigEndian.PutUint64(out[len(userKey):], ^commitTS)
	return out
}

// SplitVersion 把完整 key 拆成"用户键 + commit_ts"。
func SplitVersion(fullKey []byte) (userKey []byte, commitTS uint64, err error) {
	if len(fullKey) < VersionBytes+1 {
		return nil, 0, fmt.Errorf("store: key too short for version segment (%d bytes)", len(fullKey))
	}
	n := len(fullKey) - VersionBytes
	userKey = fullKey[:n]
	commitTS = ^binary.BigEndian.Uint64(fullKey[n:])
	return userKey, commitTS, nil
}

// MaxCommittedTS 返回当前已提交水位(最后分配出去的 commit_ts)。
//
// snapshot = 0 表示"最新已提交",读路径用它作为上界。
func (e *Engine) MaxCommittedTS() uint64 { return e.lastCommitTS.Load() }

// GetSnapshot 返回用户键在快照 s 下可见的版本值(SPEC-P3 FR-322 / FR-325)。
//
// 可见规则:满足 commit_ts <= s 的**最大** commit_ts 版本;墓碑照原样返回,
// 由 FE 解释(FR-327)。s = 0 表示最新已提交。
func (e *Engine) GetSnapshot(userKey []byte, snapshot uint64) ([]byte, bool, error) {
	e.getCount.Add(1)
	value, ok, err := e.getSnapshot(userKey, snapshot)
	if err != nil {
		e.getErrCount.Add(1)
	}
	return value, ok, err
}

func (e *Engine) getSnapshot(userKey []byte, snapshot uint64) ([]byte, bool, error) {
	if len(userKey) == 0 {
		return nil, false, fmt.Errorf("store: empty key")
	}
	if snapshot == 0 {
		snapshot = e.MaxCommittedTS()
	}
	// 注意:不能只扫到"第一个用户键不同"就停。
	// 完整 key = 用户键 + 8 字节版本段;当用户键 U 是另一个用户键 U+x 的前缀时,
	// U+x 的完整 key 可能排在 U 的版本之前(U 后面的版本首字节是 0xFF,通常大于 x[0])。
	// 因此点查要扫完"以 U 为前缀"的整个原始 key 区间,只挑用户键恰好等于 U 的版本。
	var (
		bestTS  uint64
		bestVal []byte
		found   bool
	)
	sc := e.newScanner(userKey, prefixUpperBound(userKey), 0)
	defer sc.Close()
	for sc.Next() {
		uk, ts, err := SplitVersion(sc.Key())
		if err != nil {
			return nil, false, err
		}
		if !bytes.Equal(uk, userKey) {
			continue
		}
		if ts <= snapshot && (!found || ts > bestTS) {
			bestTS = ts
			bestVal = sc.Value()
			found = true
		}
	}
	if err := sc.Err(); err != nil {
		return nil, false, fmt.Errorf("store: scan versions: %w", err)
	}
	return bestVal, found, nil
}

// latestVersionTS 返回该用户键最新已提交版本的 commit_ts(墓碑也算)。
//
// 冲突检测用它判断"快照之后有没有人改过这一行"(FR-329 / FR-331)。
func (e *Engine) latestVersionTS(userKey []byte) (uint64, bool, error) {
	var (
		latest uint64
		found  bool
	)
	sc := e.newScanner(userKey, prefixUpperBound(userKey), 0)
	defer sc.Close()
	for sc.Next() {
		uk, ts, err := SplitVersion(sc.Key())
		if err != nil {
			return 0, false, err
		}
		if !bytes.Equal(uk, userKey) {
			continue
		}
		if !found || ts > latest {
			latest = ts
			found = true
		}
	}
	if err := sc.Err(); err != nil {
		return 0, false, fmt.Errorf("store: scan versions: %w", err)
	}
	return latest, found, nil
}

// prefixUpperBound 返回"以 key 为前缀"的全部字节串的字典序上界(不含)。
//
// 标准做法:从末尾找第一个不是 0xFF 的字节,把它 +1 并截断;全 0xFF 时返回 nil
// (表示没有上界)。
func prefixUpperBound(key []byte) []byte {
	for i := len(key) - 1; i >= 0; i-- {
		if key[i] < 0xFF {
			out := make([]byte, i+1)
			copy(out, key[:i+1])
			out[i]++
			return out
		}
	}
	return nil
}

// MVCCScanner 是按用户键分组、按快照消解版本的流式扫描器(SPEC-P3 FR-323 / FR-324)。
//
// 它包一层底层有序扫描器:底层吐完整 key(用户键 + 8 字节版本),这里把同一个
// 用户键的多个版本合并成一行(取 ts <= snapshot 的最大版本),每个用户键只输出一次。
// 内存占用只与数据源数量和一个"待处理 key"有关,不随范围大小增长。
type MVCCScanner struct {
	sc       *exec.Scanner
	snapshot uint64
	end      []byte // 用户键级终点(不含),nil/空 = 到末尾
	limit    int
	emitted  int

	pendingKey   []byte
	pendingValue []byte
	hasPending   bool

	key   []byte
	value []byte
	done  bool
	err   error
}

func newMVCCScanner(sc *exec.Scanner, end []byte, snapshot uint64, limit int) *MVCCScanner {
	return &MVCCScanner{
		sc:       sc,
		snapshot: snapshot,
		end:      end,
		limit:    limit,
	}
}

// NewMVCCScanner 创建覆盖用户键范围 [start, end) 的流式 MVCC 扫描器。
// snapshot = 0 表示最新已提交;limit <= 0 表示不限,限制作用在"分组之后"。
func (e *Engine) NewMVCCScanner(start, end []byte, snapshot uint64, limit int) *MVCCScanner {
	e.scanCount.Add(1)
	if snapshot == 0 {
		snapshot = e.MaxCommittedTS()
	}
	// 终点由本层按用户键判断:不能把用户键终点直接交给底层按完整 key 过滤,
	// 否则当 end 是某个用户键的前缀时,该键的版本可能被提前截掉。
	return newMVCCScanner(e.newScanner(start, nil, 0), end, snapshot, limit)
}

// Close 释放底层扫描器持有的 segment 引用(可重复调用)。
func (s *MVCCScanner) Close() {
	if s.sc != nil {
		s.sc.Close()
	}
}

// Next 前进到下一个对快照可见的用户键。返回 false 表示结束或出错。
func (s *MVCCScanner) Next() bool {
	if s.done {
		return false
	}
	if s.limit > 0 && s.emitted >= s.limit {
		s.done = true
		s.Close()
		return false
	}

	var groupKey []byte
	var chosen []byte
	visible := false

	for {
		var fullKey, value []byte
		if s.hasPending {
			fullKey, value = s.pendingKey, s.pendingValue
			s.hasPending = false
		} else {
			if !s.sc.Next() {
				if err := s.sc.Err(); err != nil {
					s.err = fmt.Errorf("store: mvcc scan: %w", err)
				}
				if groupKey != nil && visible {
					s.emit(groupKey, chosen)
					return true
				}
				s.done = true
				s.Close()
				return false
			}
			fullKey, value = s.sc.Key(), s.sc.Value()
		}

		userKey, ts, err := SplitVersion(fullKey)
		if err != nil {
			s.err = err
			s.done = true
			return false
		}

		if len(s.end) > 0 && bytes.Compare(userKey, s.end) >= 0 {
			if groupKey != nil && visible {
				s.emit(groupKey, chosen)
				return true
			}
			s.done = true
			s.Close()
			return false
		}

		if groupKey == nil {
			groupKey = userKey
		} else if !bytes.Equal(userKey, groupKey) {
			// 换组:把当前这条留到下一轮,先输出上一组(如果有可见版本)。
			s.hasPending = true
			s.pendingKey = fullKey
			s.pendingValue = value
			if visible {
				s.emit(groupKey, chosen)
				return true
			}
			groupKey = userKey
			visible = false
			chosen = nil
			continue
		}

		// 版本按新到旧排列;第一条 ts <= snapshot 的版本就是该快照下的可见版本。
		if !visible && ts <= s.snapshot {
			visible = true
			chosen = value
		}
	}
}

func (s *MVCCScanner) emit(userKey, value []byte) {
	s.key = userKey
	s.value = value
	s.emitted++
}

// Key 返回当前用户键(不含版本段)。
func (s *MVCCScanner) Key() []byte { return s.key }

// Value 返回当前可见版本的 value(墓碑由 FE 解释)。
func (s *MVCCScanner) Value() []byte { return s.value }

// Err 返回扫描过程中的错误;正常结束返回 nil。
func (s *MVCCScanner) Err() error { return s.err }
