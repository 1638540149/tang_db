// Package config 加载 tang_db BE 的配置文件。
//
// 配置文件是简单的 key = value 文本(一行一项,# 开头的整行为注释),
// 解析只用标准库手写 —— BE 侧不允许引入任何第三方依赖(项目宪法第二条),
// 所以不用 yaml/toml 之类的库。
//
// 取值优先级固定为:命令行参数 > 配置文件 > 内置默认值。
package config

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

// DefaultPath 是未显式指定 --config 时查找的配置文件路径(相对进程工作目录)。
const DefaultPath = "./config/be.conf"

// DefaultMemTableSize 是 memtable 刷盘阈值的默认值(64MB)。
const DefaultMemTableSize int64 = 64 * 1024 * 1024

// DefaultMaxTxnWriteBytes 是单事务写入量的默认上限(64MB,SPEC-P3 FR-309)。
const DefaultMaxTxnWriteBytes int64 = 64 * 1024 * 1024

// 崩溃注入点取值(SPEC-P3 FR-364 / K55)。空字符串表示关闭。
var crashPoints = map[string]bool{
	"":               true,
	"before_wal":     true,
	"after_fsync":    true,
	"after_memtable": true,
	"before_ok":      true,
}

// 心跳相关默认值(见 P2 规约 FR-160)。
const (
	DefaultFeHost                   = "127.0.0.1"
	DefaultFePort                   = 9091
	DefaultHeartbeatIntervalSeconds = 3
)

// Config 是 BE 的启动配置。
type Config struct {
	DataDir      string // 数据目录:LSM 数据、WAL、segment、MANIFEST 都在这里
	Port         int    // gRPC 监听端口
	MemTableSize int64  // memtable 刷盘阈值(字节)

	FeHost                   string // FE 心跳服务地址
	FePort                   int    // FE 心跳服务端口
	HeartbeatIntervalSeconds int    // 心跳间隔(秒)

	// TestCrashPoint 崩溃注入点(仅验收使用;默认空 = 关闭)。
	TestCrashPoint string
	// MaxTxnWriteBytes 单事务写入量上限(默认 64MB)。
	MaxTxnWriteBytes int64
}

// Default 返回内置默认配置。
func Default() Config {
	return Config{
		DataDir:                  "./data/BE",
		Port:                     9100,
		MemTableSize:             DefaultMemTableSize,
		FeHost:                   DefaultFeHost,
		FePort:                   DefaultFePort,
		HeartbeatIntervalSeconds: DefaultHeartbeatIntervalSeconds,
		TestCrashPoint:           "",
		MaxTxnWriteBytes:         DefaultMaxTxnWriteBytes,
	}
}

// Load 读取并解析配置文件。文件不存在时返回的错误可用
// errors.Is(err, os.ErrNotExist) 判断。
func Load(path string) (Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return Config{}, err
	}
	defer func() { _ = f.Close() }()

	cfg, err := Parse(f)
	if err != nil {
		return Config{}, fmt.Errorf("%s: %w", path, err)
	}
	return cfg, nil
}

// Parse 从 r 解析配置。文件里没出现的键沿用内置默认值;
// 出现未知配置项、格式错误或取值非法时返回错误,不静默忽略。
func Parse(r io.Reader) (Config, error) {
	cfg := Default()
	scanner := bufio.NewScanner(r)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		key, value, found := strings.Cut(line, "=")
		if !found {
			return Config{}, fmt.Errorf("第 %d 行格式错误(应为 key = value):%q", lineNo, line)
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key == "" {
			return Config{}, fmt.Errorf("第 %d 行缺少配置项名:%q", lineNo, line)
		}
		// test_crash_point 的默认值就是空(关闭),允许显式写空值。
		if value == "" && key != "test_crash_point" {
			return Config{}, fmt.Errorf("第 %d 行 %s 的取值为空", lineNo, key)
		}

		switch key {
		case "data_dir":
			cfg.DataDir = value
		case "port":
			port, err := strconv.Atoi(value)
			if err != nil || port < 1 || port > 65535 {
				return Config{}, fmt.Errorf("第 %d 行 port 无效:%q(应为 1-65535)", lineNo, value)
			}
			cfg.Port = port
		case "memtable_size":
			size, err := ParseSize(value)
			if err != nil {
				return Config{}, fmt.Errorf("第 %d 行 memtable_size 无效:%v", lineNo, err)
			}
			cfg.MemTableSize = size
		case "fe_host":
			cfg.FeHost = value
		case "fe_port":
			port, err := strconv.Atoi(value)
			if err != nil || port < 1 || port > 65535 {
				return Config{}, fmt.Errorf("第 %d 行 fe_port 无效:%q(应为 1-65535)", lineNo, value)
			}
			cfg.FePort = port
		case "heartbeat_interval_seconds":
			sec, err := strconv.Atoi(value)
			if err != nil || sec < 1 || sec > 3600 {
				return Config{}, fmt.Errorf("第 %d 行 heartbeat_interval_seconds 无效:%q(应为 1-3600)", lineNo, value)
			}
			cfg.HeartbeatIntervalSeconds = sec
		case "test_crash_point":
			if !crashPoints[value] {
				return Config{}, fmt.Errorf("第 %d 行 test_crash_point 无效:%q"+
					"(应为空 / before_wal / after_fsync / after_memtable / before_ok)", lineNo, value)
			}
			cfg.TestCrashPoint = value
		case "max_txn_write_bytes":
			size, err := ParseSize(value)
			if err != nil {
				return Config{}, fmt.Errorf("第 %d 行 max_txn_write_bytes 无效:%v", lineNo, err)
			}
			cfg.MaxTxnWriteBytes = size
		default:
			return Config{}, fmt.Errorf("第 %d 行未知配置项:%q", lineNo, key)
		}
	}
	if err := scanner.Err(); err != nil {
		return Config{}, fmt.Errorf("读取配置失败: %w", err)
	}
	return cfg, nil
}

// ParseSize 解析形如 "64MB"、"1MB"、"32KB"、"1024" 的容量字符串为字节数。
func ParseSize(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, errors.New("不能为空")
	}
	s = strings.ToUpper(s)
	var unit int64 = 1
	switch {
	case strings.HasSuffix(s, "MB"):
		unit = 1024 * 1024
		s = strings.TrimSuffix(s, "MB")
	case strings.HasSuffix(s, "KB"):
		unit = 1024
		s = strings.TrimSuffix(s, "KB")
	case strings.HasSuffix(s, "GB"):
		unit = 1024 * 1024 * 1024
		s = strings.TrimSuffix(s, "GB")
	case strings.HasSuffix(s, "B"):
		s = strings.TrimSuffix(s, "B")
	}

	s = strings.TrimSpace(s)
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("非法数字 %q", s)
	}
	if n <= 0 {
		return 0, errors.New("必须大于 0")
	}
	return n * unit, nil
}
