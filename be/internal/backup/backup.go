// Package backup 实现在线备份与恢复(SPEC-P8 D 组 / FR-841 ~ FR-850)。
//
// 设计要点:
//   - 备份点 = 一次 checkpoint 之后 MANIFEST 里的 segment 集合;此后写入的新数据不进本次备份,
//     备份期间不锁写(K192 / FR-842)。
//   - BACKUP_META 最后写:目录里没有它就是"不完整备份",RESTORE 直接拒绝(FR-843 / 边界 177)。
//   - 备份产物是纯文件目录(manifest/ + sst/ + wal/ + BACKUP_META),可整体搬走(FR-849)。
//   - 增量备份只复制父备份没有的文件;RESTORE 沿 parent 链补齐线段文件(FR-845 / SC-455)。
//   - BE 不出现表名/列名/SQL:契约里只有目录与数字(边界铁律)。
package backup

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/1638540149/tang_db/be/internal/manifest"
	"github.com/1638540149/tang_db/be/internal/segment"
	"github.com/1638540149/tang_db/be/internal/store"
)

// 任务状态。
const (
	StatusRunning   = "RUNNING"
	StatusSuccess   = "SUCCESS"
	StatusFailed    = "FAILED"
	StatusCancelled = "CANCELLED"
)

// MetaName 是"备份已完成"的唯一标记文件。
const MetaName = "BACKUP_META"

// FileEntry 是 BACKUP_META 里的一条文件记录。
type FileEntry struct {
	Path   string `json:"path"` // 相对路径,如 sst/000001.seg
	Bytes  int64  `json:"bytes"`
	SHA256 string `json:"sha256"`
}

// Meta 是 BACKUP_META 的内容(K193:JSON,可人工检查)。
type Meta struct {
	Version       int         `json:"version"`        // BACKUP_META 自身版本
	EngineVersion string      `json:"engine_version"` // 库版本
	FormatVersion uint64      `json:"format_version"` // 数据目录格式版本(FR-848)
	CreatedAt     string      `json:"created_at"`
	SnapshotRows  uint64      `json:"snapshot_rows"`
	Incremental   bool        `json:"incremental"`
	Parent        string      `json:"parent,omitempty"` // 增量备份的父目录
	Segments      []uint64    `json:"segments"`
	Files         []FileEntry `json:"files"`     // 本目录实际持有的文件
	Inherited     []FileEntry `json:"inherited"` // 从父目录继承(本目录没有)的文件
	TotalBytes    int64       `json:"total_bytes"`
	CopiedBytes   int64       `json:"copied_bytes"`
}

// Job 是一次备份任务的内存状态(FR-846 / FR-847)。
type Job struct {
	ID           string `json:"job_id"`
	Dir          string `json:"backup_dir"`
	Status       string `json:"status"`
	CopiedBytes  int64  `json:"copied_bytes"`
	TotalBytes   int64  `json:"total_bytes"`
	Message      string `json:"message"`
	Incremental  bool   `json:"incremental"`
	StartedMilli int64  `json:"started_unix_millis"`
	FinishedMilli int64 `json:"finished_unix_millis"`
}

// Manager 持有备份任务表(进程内,重启后历史由 FE 的 sys_backup_jobs 保留)。
type Manager struct {
	engine *store.Engine
	seq    atomic.Uint64

	mu   sync.Mutex
	jobs map[string]*Job
}

// NewManager 创建备份任务管理器。
func NewManager(engine *store.Engine) *Manager {
	return &Manager{engine: engine, jobs: make(map[string]*Job)}
}

// Job 按 id 查任务;不存在返回 false(BE 重启后旧任务自然消失,FE 据此判 CANCELLED)。
func (m *Manager) Job(id string) (Job, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, ok := m.jobs[id]
	if !ok {
		return Job{}, false
	}
	return *job, true
}

// Jobs 返回全部任务(按开始时间升序)。
func (m *Manager) Jobs() []Job {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Job, 0, len(m.jobs))
	for _, job := range m.jobs {
		out = append(out, *job)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedMilli < out[j].StartedMilli })
	return out
}

// Start 启动一次后台备份,立即返回任务(FR-847:前台可继续查询)。
func (m *Manager) Start(dir, parent string, snapshotRows uint64, force bool) (Job, error) {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return Job{}, errors.New("备份目录不能为空")
	}
	absDir, err := filepath.Abs(dir)
	if err != nil {
		return Job{}, fmt.Errorf("备份目录非法: %w", err)
	}
	if err := checkTargetDir(absDir, m.engine.DataDir(), force); err != nil {
		return Job{}, err
	}
	id := fmt.Sprintf("bk_%d_%d", time.Now().UnixMilli(), m.seq.Add(1))
	job := &Job{
		ID:           id,
		Dir:          absDir,
		Status:       StatusRunning,
		Incremental:  strings.TrimSpace(parent) != "",
		StartedMilli: time.Now().UnixMilli(),
	}
	m.mu.Lock()
	m.jobs[id] = job
	m.mu.Unlock()

	go m.run(job, absDir, strings.TrimSpace(parent), snapshotRows, force)
	return *job, nil
}

// checkTargetDir 落实边界 176:目标目录必须不存在 / 为空,或显式 FORCE。
func checkTargetDir(absDir, dataDir string, force bool) error {
	if samePath(absDir, dataDir) {
		return errors.New("备份目录不能是 BE 正在使用的数据目录")
	}
	info, err := os.Stat(absDir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return fmt.Errorf("备份目录不可用: %w", err)
	}
	if !info.IsDir() {
		return fmt.Errorf("备份目录已存在且不是目录: %s", absDir)
	}
	entries, err := os.ReadDir(absDir)
	if err != nil {
		return fmt.Errorf("读取备份目录失败: %w", err)
	}
	if len(entries) > 0 && !force {
		return fmt.Errorf("备份目录已存在且非空: %s(换一个目录或加 FORCE)", absDir)
	}
	return nil
}

func samePath(a, b string) bool {
	aa, errA := filepath.Abs(a)
	bb, errB := filepath.Abs(b)
	if errA != nil || errB != nil {
		return false
	}
	return strings.EqualFold(filepath.Clean(aa), filepath.Clean(bb))
}

// run 执行备份:checkpoint → 计算文件清单 → 逐文件复制 + 校验和 → 最后写 BACKUP_META。
func (m *Manager) run(job *Job, dir, parent string, snapshotRows uint64, force bool) {
	err := m.copyAll(job, dir, parent, snapshotRows, force)
	m.mu.Lock()
	defer m.mu.Unlock()
	job.FinishedMilli = time.Now().UnixMilli()
	if err != nil {
		job.Status = StatusFailed
		job.Message = err.Error()
		// 边界 177:失败删半成品(不留 BACKUP_META,目录本身也尽量清掉)。
		_ = os.RemoveAll(dir)
		return
	}
	job.Status = StatusSuccess
	job.Message = "备份完成"
}

func (m *Manager) copyAll(job *Job, dir, parent string, snapshotRows uint64, force bool) error {
	// ① checkpoint:封 memtable + 刷完 immutable(FR-841)。
	if err := m.engine.ForceFlushAll(); err != nil {
		return fmt.Errorf("备份 checkpoint 失败: %w", err)
	}
	mstate, err := m.engine.SnapshotManifest()
	if err != nil {
		return fmt.Errorf("读取 MANIFEST 快照失败: %w", err)
	}
	// 持有快照里 segment 的引用:复制期间即使 compaction 换掉 MANIFEST,旧文件也不会被删。
	release, err := m.engine.PinSegments(mstate.Segments)
	if err != nil {
		return fmt.Errorf("备份快照失效: %w", err)
	}
	defer release()

	// ② 增量:父目录存在才走增量;父目录没了退化为全量(边界 181)。
	parentMeta := (*Meta)(nil)
	if parent != "" {
		loaded, err := ReadMeta(parent)
		if err != nil {
			// 退化全量,并在任务消息里说明
			job.Message = "父备份不可用(" + err.Error() + "),已退化为全量备份"
			parent = ""
		} else {
			parentMeta = loaded
		}
	}
	inherited := map[string]FileEntry{}
	if parentMeta != nil {
		for _, entry := range parentMeta.Files {
			inherited[entry.Path] = entry
		}
		for _, entry := range parentMeta.Inherited {
			inherited[entry.Path] = entry
		}
	}

	needed, err := m.neededFiles(mstate)
	if err != nil {
		return err
	}

	// ③ 目标目录:非空 + force 时先清空。
	if info, err := os.Stat(dir); err == nil && info.IsDir() {
		entries, _ := os.ReadDir(dir)
		if len(entries) > 0 {
			if !force {
				return fmt.Errorf("备份目录已存在且非空: %s(换一个目录或加 FORCE)", dir)
			}
			if err := os.RemoveAll(dir); err != nil {
				return fmt.Errorf("清空备份目录失败: %w", err)
			}
		}
	}
	for _, sub := range []string{"manifest", "sst", "wal"} {
		if err := os.MkdirAll(filepath.Join(dir, sub), 0o750); err != nil {
			return fmt.Errorf("创建备份目录失败: %w", err)
		}
	}

	var total int64
	var toCopy []srcFile
	var skipped []FileEntry
	for _, file := range needed {
		if entry, ok := inherited[file.rel]; ok && !strings.HasPrefix(file.rel, "manifest/") {
			// segment 不可变(硬约束 #4):同名即同内容,增量备份不必再复制。
			skipped = append(skipped, entry)
			total += entry.Bytes
			continue
		}
		info, err := os.Stat(file.src)
		if err != nil {
			// WAL 尾部可能在 checkpoint 后被回收:它不是本次备份的必需文件。
			if os.IsNotExist(err) && strings.HasPrefix(file.rel, "wal/") {
				continue
			}
			return fmt.Errorf("备份源文件缺失 %s: %w", file.rel, err)
		}
		total += info.Size()
		toCopy = append(toCopy, file)
	}
	m.mu.Lock()
	job.TotalBytes = total
	m.mu.Unlock()

	files := make([]FileEntry, 0, len(toCopy))
	var copied int64
	for _, file := range toCopy {
		entry, err := copyFile(file.src, filepath.Join(dir, file.rel))
		if err != nil {
			return fmt.Errorf("复制 %s 失败: %w", file.rel, err)
		}
		entry.Path = file.rel
		files = append(files, entry)
		copied += entry.Bytes
		m.mu.Lock()
		job.CopiedBytes = copied
		m.mu.Unlock()
	}

	meta := Meta{
		Version:       1,
		EngineVersion: m.engine.Version(),
		FormatVersion: mstate.FormatVersion,
		CreatedAt:     time.Now().Format("2006-01-02 15:04:05"),
		SnapshotRows:  snapshotRows,
		Incremental:   parentMeta != nil,
		Parent:        parent,
		Segments:      append([]uint64(nil), mstate.Segments...),
		Files:         files,
		Inherited:     skipped,
		TotalBytes:    total,
		CopiedBytes:   copied,
	}
	// ④ BACKUP_META 最后写(原子 rename):有它就是完整备份(FR-843)。
	return WriteMeta(dir, &meta)
}

// stateDir 是 BE 的数据目录(MANIFEST/sst/wal 的共同父目录)。
func (m *Manager) stateDir() string {
	return m.engine.DataDir()
}

// srcFile 是一条"备份目录相对路径 → 数据目录绝对路径"的映射。
type srcFile struct {
	rel string
	src string
}

// neededFiles 列出本次备份需要的文件。
//
// 数据目录布局是 <dataDir>/{CURRENT,MANIFEST-*,sst/,wal/},而备份目录按规约
// 固定为 manifest/ + sst/ + wal/(FR-849):MANIFEST 文件在备份里进 manifest/。
func (m *Manager) neededFiles(mstate *manifest.Manifest) ([]srcFile, error) {
	base := m.stateDir()
	var out []srcFile
	currentPath := filepath.Join(base, "CURRENT")
	data, err := os.ReadFile(currentPath)
	if err != nil {
		return nil, fmt.Errorf("读取 CURRENT 失败: %w", err)
	}
	name := strings.TrimSpace(string(data))
	if name == "" {
		return nil, errors.New("CURRENT 为空")
	}
	out = append(out,
		srcFile{rel: "manifest/CURRENT", src: currentPath},
		srcFile{rel: filepath.ToSlash(filepath.Join("manifest", name)),
			src: filepath.Join(base, name)})

	segments := map[uint64]bool{}
	for _, num := range mstate.Segments {
		segments[num] = true
		out = append(out, srcFile{
			rel: filepath.ToSlash(filepath.Join("sst", segment.Name(num))),
			src: filepath.Join(base, "sst", segment.Name(num)),
		})
	}
	for _, entry := range mstate.VecIndexes {
		if !segments[entry.Segment] {
			continue
		}
		out = append(out, srcFile{
			rel: filepath.ToSlash(filepath.Join("sst", entry.File)),
			src: filepath.Join(base, "sst", entry.File),
		})
	}
	// WAL:**不复制**。checkpoint 已经 ForceFlushAll,快照里的数据全部在 segment 里;
	// 备份期间仍在写入,WAL 尾部含"备份点之后"的写,带上它恢复后就会多出这些行
	// (实测 SC-453 多 2 行、checksum 不符)。恢复后从 MANIFEST 起步即可,WAL 缺失不影响启动。
	return out, nil
}

// targetRel 把备份目录里的相对路径映射成恢复后数据目录里的相对路径:
// manifest/* 回到数据目录根(CURRENT 与 MANIFEST-* 就在根上),其余保持原样。
func targetRel(rel string) string {
	const prefix = "manifest/"
	if strings.HasPrefix(rel, prefix) {
		return strings.TrimPrefix(rel, prefix)
	}
	return rel
}

func copyFile(src, dst string) (FileEntry, error) {
	if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
		return FileEntry{}, err
	}
	in, err := os.Open(src)
	if err != nil {
		return FileEntry{}, err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o640)
	if err != nil {
		return FileEntry{}, err
	}
	hash := sha256.New()
	written, err := io.Copy(io.MultiWriter(out, hash), in)
	if err != nil {
		out.Close()
		return FileEntry{}, err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return FileEntry{}, err
	}
	if err := out.Close(); err != nil {
		return FileEntry{}, err
	}
	return FileEntry{Bytes: written, SHA256: hex.EncodeToString(hash.Sum(nil))}, nil
}

// WriteMeta 原子地写出 BACKUP_META(.tmp + fsync + rename)。
func WriteMeta(dir string, meta *Meta) error {
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	tmp := filepath.Join(dir, MetaName+".tmp")
	if err := os.WriteFile(tmp, data, 0o640); err != nil {
		return err
	}
	// Windows 上对只读句柄 fsync 会 Access is denied,必须用可写句柄。
	file, err := os.OpenFile(tmp, os.O_RDWR, 0o640)
	if err != nil {
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	file.Close()
	return os.Rename(tmp, filepath.Join(dir, MetaName))
}

// ReadMeta 读取一个备份目录的 BACKUP_META。
func ReadMeta(dir string) (*Meta, error) {
	path := filepath.Join(dir, MetaName)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, fmt.Errorf("备份不完整:%s 缺少 %s", dir, MetaName)
		}
		return nil, err
	}
	var meta Meta
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, fmt.Errorf("BACKUP_META 解析失败: %w", err)
	}
	if meta.FormatVersion != manifest.FormatVersion {
		return nil, fmt.Errorf("备份的 format_version=%d 与本 BE(%d)不一致",
			meta.FormatVersion, manifest.FormatVersion)
	}
	return &meta, nil
}

// RestoreResult 是恢复结果。
type RestoreResult struct {
	Files uint64
	Bytes uint64
}

// Restore 把一个备份目录恢复成"可直接启动的数据目录"(FR-844 / K194)。
//
// 目标必须为空、不存在,或显式 force;恢复先写临时目录,成功后 rename,
// 中途失败不会留下半恢复目录(边界 188)。
func (m *Manager) Restore(source, target string, force bool) (RestoreResult, error) {
	source = strings.TrimSpace(source)
	target = strings.TrimSpace(target)
	if source == "" || target == "" {
		return RestoreResult{}, errors.New("RESTORE 需要源目录与目标目录")
	}
	absSource, err := filepath.Abs(source)
	if err != nil {
		return RestoreResult{}, err
	}
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return RestoreResult{}, err
	}
	if samePath(absTarget, m.engine.DataDir()) {
		return RestoreResult{}, errors.New("不能恢复到 BE 正在使用的数据目录(请停库或换目标目录)")
	}

	// ① 读清单 + 沿 parent 链补齐文件
	chain, err := resolveChain(absSource)
	if err != nil {
		return RestoreResult{}, err
	}
	meta := chain[0].meta

	if info, err := os.Stat(absTarget); err == nil {
		if !info.IsDir() {
			return RestoreResult{}, fmt.Errorf("目标不是目录: %s", absTarget)
		}
		entries, _ := os.ReadDir(absTarget)
		if len(entries) > 0 && !force {
			return RestoreResult{}, fmt.Errorf("目标目录非空: %s(换一个目录或加 FORCE)", absTarget)
		}
	}

	// ② 先写临时目录,成功后整体 rename(边界 188)
	tmp := absTarget + fmt.Sprintf(".restoring-%d", time.Now().UnixNano())
	if err := os.MkdirAll(tmp, 0o750); err != nil {
		return RestoreResult{}, err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(tmp)
		}
	}()

	var files, bytes uint64
	for _, entry := range meta.Files {
		loc, found := chain.lookup(entry.Path)
		if !found {
			return RestoreResult{}, fmt.Errorf("备份链里缺少文件: %s", entry.Path)
		}
		size, err := restoreFile(filepath.Join(loc.dir, loc.path),
			filepath.Join(tmp, targetRel(entry.Path)), entry.SHA256)
		if err != nil {
			return RestoreResult{}, err
		}
		files++
		bytes += uint64(size)
	}
	for _, entry := range meta.Inherited {
		loc, found := chain.lookup(entry.Path)
		if !found {
			return RestoreResult{}, fmt.Errorf("增量备份的父目录缺少文件: %s", entry.Path)
		}
		size, err := restoreFile(filepath.Join(loc.dir, loc.path),
			filepath.Join(tmp, targetRel(entry.Path)), entry.SHA256)
		if err != nil {
			return RestoreResult{}, err
		}
		files++
		bytes += uint64(size)
	}
	for _, sub := range []string{"manifest", "sst", "wal"} {
		if err := os.MkdirAll(filepath.Join(tmp, sub), 0o750); err != nil {
			return RestoreResult{}, err
		}
	}

	if dirExists(absTarget) {
		if err := os.RemoveAll(absTarget); err != nil {
			return RestoreResult{}, err
		}
	}
	if err := os.Rename(tmp, absTarget); err != nil {
		return RestoreResult{}, fmt.Errorf("恢复目录改名失败: %w", err)
	}
	ok = true
	return RestoreResult{Files: files, Bytes: bytes}, nil
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func restoreFile(src, dst, wantSHA string) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
		return 0, err
	}
	in, err := os.Open(src)
	if err != nil {
		return 0, err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o640)
	if err != nil {
		return 0, err
	}
	hash := sha256.New()
	written, err := io.Copy(io.MultiWriter(out, hash), in)
	if err != nil {
		out.Close()
		return 0, err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return 0, err
	}
	if err := out.Close(); err != nil {
		return 0, err
	}
	got := hex.EncodeToString(hash.Sum(nil))
	if wantSHA != "" && !strings.EqualFold(got, wantSHA) {
		// FR-844:逐个文件校验,不一致直接失败(不留下半恢复目录)。
		return 0, fmt.Errorf("校验和不一致: %s(期望 %s,实际 %s)", src, wantSHA, got)
	}
	return written, nil
}

// backupChain 是一条备份链:index 0 = 目标备份,后面依次是祖先。
type backupChain []chainEntry

type chainEntry struct {
	dir  string
	meta *Meta
}

func resolveChain(source string) (backupChain, error) {
	var out backupChain
	dir := source
	seen := map[string]bool{}
	for depth := 0; depth < 16; depth++ {
		meta, err := ReadMeta(dir)
		if err != nil {
			return nil, err
		}
		out = append(out, chainEntry{dir: dir, meta: meta})
		if meta.Parent == "" {
			return out, nil
		}
		parent := strings.TrimSpace(meta.Parent)
		abs, err := filepath.Abs(parent)
		if err != nil {
			return nil, fmt.Errorf("父备份路径非法: %s", parent)
		}
		if seen[abs] {
			return nil, fmt.Errorf("备份链出现环: %s", abs)
		}
		seen[abs] = true
		dir = abs
	}
	return nil, errors.New("备份链超过 16 层,拒绝恢复")
}

type fileLoc struct {
	dir  string
	path string
}

// lookup 在整条链上找某个相对路径的文件。
func (c backupChain) lookup(rel string) (fileLoc, bool) {
	for _, entry := range c {
		for _, file := range entry.meta.Files {
			if file.Path == rel {
				return fileLoc{dir: entry.dir, path: rel}, true
			}
		}
	}
	return fileLoc{}, false
}
