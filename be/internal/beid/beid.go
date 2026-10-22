// Package beid 负责 BE 实例身份(be_id)的生成与持久化。
//
// be_id 是一个 UUID v4,首次启动时生成并写入数据目录下的 be.id,
// 之后每次启动都读回同一个值 —— FE 才能把两次心跳认成同一个 BE(规约 FR-157 / K25)。
package beid

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
)

// FileName 是身份文件在数据目录下的文件名。
const FileName = "be.id"

// LoadOrCreate 读取数据目录下的 be_id;不存在或内容非法时生成新的并落盘。
//
// 文件损坏/丢失时按规约 §8 处理:重新生成并在日志里警告(旧 ID 会在 FE 侧自然过期)。
func LoadOrCreate(dataDir string) (string, error) {
	if err := os.MkdirAll(dataDir, 0o750); err != nil {
		return "", fmt.Errorf("beid: 创建数据目录失败: %w", err)
	}
	path := filepath.Join(dataDir, FileName)

	data, err := os.ReadFile(path)
	if err == nil {
		id := strings.TrimSpace(string(data))
		if IsValid(id) {
			return id, nil
		}
		slog.Warn("be.id 内容非法,将重新生成", "path", path)
	} else if !os.IsNotExist(err) {
		return "", fmt.Errorf("beid: 读取 %s 失败: %w", path, err)
	} else {
		slog.Info("首次启动,生成新的 be_id", "path", path)
	}

	id, err := NewUUIDv4()
	if err != nil {
		return "", err
	}
	if err := writeFileSync(path, id); err != nil {
		return "", err
	}
	return id, nil
}

// NewUUIDv4 生成一个 RFC 4122 UUID v4 字符串。
func NewUUIDv4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("beid: 生成随机数失败: %w", err)
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	dst := make([]byte, 36)
	hex.Encode(dst[0:8], b[0:4])
	dst[8] = '-'
	hex.Encode(dst[9:13], b[4:6])
	dst[13] = '-'
	hex.Encode(dst[14:18], b[6:8])
	dst[18] = '-'
	hex.Encode(dst[19:23], b[8:10])
	dst[23] = '-'
	hex.Encode(dst[24:36], b[10:16])
	return string(dst), nil
}

// IsValid 判断字符串是否是形如 8-4-4-4-12 的十六进制 UUID。
func IsValid(id string) bool {
	if len(id) != 36 {
		return false
	}
	for i, c := range id {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			isHex := (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
			if !isHex {
				return false
			}
		}
	}
	return true
}

func writeFileSync(path, content string) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(content+"\n"), 0o640); err != nil {
		return fmt.Errorf("beid: 写入临时文件失败: %w", err)
	}
	f, err := os.OpenFile(tmp, os.O_RDWR, 0)
	if err != nil {
		return fmt.Errorf("beid: 打开临时文件失败: %w", err)
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return fmt.Errorf("beid: fsync 失败: %w", err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("beid: 关闭临时文件失败: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("beid: 重命名 %s 失败: %w", path, err)
	}
	return nil
}
