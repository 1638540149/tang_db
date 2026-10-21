// Package manifest 维护数据库版本状态:当前有效的 segment 集合与当前 WAL 编号。
//
// 更新策略:
//   1. 将新状态写入 MANIFEST-<new>.tmp
//   2. fsync 后 rename 为 MANIFEST-<new>
//   3. 将 MANIFEST-<new> 文件名写入 CURRENT.tmp
//   4. fsync 后 rename 为 CURRENT
//
// 这样 CURRENT 永远指向完整可用的 MANIFEST 文件。
package manifest

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
)

// Manifest 描述数据库某一时刻的版本状态。
type Manifest struct {
	Version      uint64   `json:"version"`
	Segments     []uint64 `json:"segments"`      // 有效 segment 编号(按创建顺序,旧到新)
	NextSegment  uint64   `json:"next_segment"`  // 下一个 segment 编号
	NextWAL      uint64   `json:"next_wal"`      // 下一个 WAL 编号
}

// Registry 管理 CURRENT 与 MANIFEST 文件的原子更新。
type Registry struct {
	mu       sync.Mutex
	dir      string
	current  uint64 // 当前 MANIFEST 编号
}

// Open 打开数据目录下的 manifest 系统。若 CURRENT 不存在则创建初始版本。
func Open(dir string) (*Registry, *Manifest, error) {
	if err := os.MkdirAll(dir, 0750); err != nil {
		return nil, nil, fmt.Errorf("manifest: create dir %s: %w", dir, err)
	}
	r := &Registry{dir: dir}
	m, err := r.Load()
	if err != nil {
		return nil, nil, err
	}
	if m == nil {
		m = &Manifest{Version: 1, NextSegment: 1, NextWAL: 1}
		if err := r.Save(m); err != nil {
			return nil, nil, err
		}
	}
	return r, m, nil
}

// Load 从 CURRENT 指针读取当前 MANIFEST。
func (r *Registry) Load() (*Manifest, error) {
	currentPath := filepath.Join(r.dir, "CURRENT")
	data, err := os.ReadFile(currentPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("manifest: read CURRENT: %w", err)
	}
	name := strings.TrimSpace(string(data))
	if name == "" {
		return nil, errors.New("manifest: CURRENT is empty")
	}
	m, err := r.readFile(name)
	if err != nil {
		return nil, err
	}
	num, err := parseManifestName(name)
	if err != nil {
		return nil, err
	}
	r.current = num
	return m, nil
}

// Save 原子地写入新的 MANIFEST 并更新 CURRENT。
func (r *Registry) Save(m *Manifest) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	newNum := r.current + 1
	if m.Version < newNum {
		m.Version = newNum
	}
	newName := manifestName(newNum)
	tmpName := newName + ".tmp"
	tmpPath := filepath.Join(r.dir, tmpName)
	finalPath := filepath.Join(r.dir, newName)

	data, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return fmt.Errorf("manifest: marshal: %w", err)
	}
	data = append(data, '\n')

	if err := os.WriteFile(tmpPath, data, 0640); err != nil {
		return fmt.Errorf("manifest: write tmp: %w", err)
	}
	if err := fsyncFile(tmpPath); err != nil {
		return fmt.Errorf("manifest: fsync tmp: %w", err)
	}
	if err := os.Rename(tmpPath, finalPath); err != nil {
		return fmt.Errorf("manifest: rename tmp: %w", err)
	}
	if err := fsyncFile(finalPath); err != nil {
		return err
	}

	currentTmp := filepath.Join(r.dir, "CURRENT.tmp")
	if err := os.WriteFile(currentTmp, []byte(newName+"\n"), 0640); err != nil {
		return fmt.Errorf("manifest: write CURRENT.tmp: %w", err)
	}
	if err := fsyncFile(currentTmp); err != nil {
		return fmt.Errorf("manifest: fsync CURRENT.tmp: %w", err)
	}
	currentPath := filepath.Join(r.dir, "CURRENT")
	if err := os.Rename(currentTmp, currentPath); err != nil {
		return fmt.Errorf("manifest: rename CURRENT: %w", err)
	}
	if err := fsyncFile(currentPath); err != nil {
		return fmt.Errorf("manifest: fsync CURRENT: %w", err)
	}
	if err := fsyncDir(r.dir); err != nil {
		return fmt.Errorf("manifest: fsync dir: %w", err)
	}

	r.current = newNum
	return nil
}

func (r *Registry) readFile(name string) (*Manifest, error) {
	path := filepath.Join(r.dir, name)
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("manifest: read %s: %w", name, err)
	}
	var m Manifest
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, fmt.Errorf("manifest: parse %s: %w", name, err)
	}
	return &m, nil
}

func manifestName(number uint64) string {
	return fmt.Sprintf("MANIFEST-%06d", number)
}

func parseManifestName(name string) (uint64, error) {
	if !strings.HasPrefix(name, "MANIFEST-") {
		return 0, fmt.Errorf("manifest: invalid name %s", name)
	}
	n, err := strconv.ParseUint(name[9:], 10, 64)
	if err != nil {
		return 0, fmt.Errorf("manifest: invalid name %s", name)
	}
	return n, nil
}

func fsyncFile(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func fsyncDir(dir string) error {
	// Windows 不支持对目录执行 fsync,忽略该错误以保证可移植性。
	if runtime.GOOS == "windows" {
		return nil
	}
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

// AppendSnapshot 以追加方式记录一次轻量级状态变更(本期未启用,保留接口)。
func AppendSnapshot(path string, m *Manifest) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0640)
	if err != nil {
		return err
	}
	defer f.Close()
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	w := bufio.NewWriter(f)
	if _, err := w.Write(data); err != nil {
		return err
	}
	if err := w.WriteByte('\n'); err != nil {
		return err
	}
	if err := w.Flush(); err != nil {
		return err
	}
	return f.Sync()
}
