package config

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDefault(t *testing.T) {
	cfg := Default()
	if cfg.DataDir != "./data/BE" || cfg.Port != 9100 || cfg.MemTableSize != 64*1024*1024 {
		t.Fatalf("内置默认值不对: %+v", cfg)
	}
	if cfg.FeHost != "127.0.0.1" || cfg.FePort != 9091 || cfg.HeartbeatIntervalSeconds != 3 {
		t.Fatalf("心跳默认值不对: %+v", cfg)
	}
	if cfg.TestCrashPoint != "" {
		t.Fatalf("test_crash_point 默认应为空(关闭),实际 %q", cfg.TestCrashPoint)
	}
	if cfg.MaxTxnWriteBytes != DefaultMaxTxnWriteBytes {
		t.Fatalf("max_txn_write_bytes 默认 = %d, want %d", cfg.MaxTxnWriteBytes, DefaultMaxTxnWriteBytes)
	}
}

// TestParseP3Fields test_crash_point / max_txn_write_bytes 的解析与校验(FR-364)。
func TestParseP3Fields(t *testing.T) {
	src := `test_crash_point = after_fsync
max_txn_write_bytes = 8MB
`
	cfg, err := Parse(strings.NewReader(src))
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if cfg.TestCrashPoint != "after_fsync" {
		t.Fatalf("test_crash_point = %q", cfg.TestCrashPoint)
	}
	if cfg.MaxTxnWriteBytes != 8*1024*1024 {
		t.Fatalf("max_txn_write_bytes = %d", cfg.MaxTxnWriteBytes)
	}

	// 显式空值 = 关闭,应被接受。
	cfg, err = Parse(strings.NewReader("test_crash_point =\n"))
	if err != nil {
		t.Fatalf("空 test_crash_point 应被接受: %v", err)
	}
	if cfg.TestCrashPoint != "" {
		t.Fatalf("test_crash_point = %q", cfg.TestCrashPoint)
	}

	for _, bad := range []string{
		"test_crash_point = somewhere\n",
		"max_txn_write_bytes = 0\n",
		"max_txn_write_bytes = 1XB\n",
	} {
		if _, err := Parse(strings.NewReader(bad)); err == nil {
			t.Fatalf("期望报错: %q", bad)
		}
	}
}

func TestParseFullFile(t *testing.T) {
	src := `# tang_db BE 配置
# 注释与空行应被忽略

data_dir = ./data/test
port = 9200
memtable_size = 32KB
fe_host = 10.0.0.2
fe_port = 9191
heartbeat_interval_seconds = 5
`
	cfg, err := Parse(strings.NewReader(src))
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if cfg.DataDir != "./data/test" {
		t.Errorf("data_dir = %q", cfg.DataDir)
	}
	if cfg.Port != 9200 {
		t.Errorf("port = %d", cfg.Port)
	}
	if cfg.MemTableSize != 32*1024 {
		t.Errorf("memtable_size = %d", cfg.MemTableSize)
	}
	if cfg.FeHost != "10.0.0.2" || cfg.FePort != 9191 || cfg.HeartbeatIntervalSeconds != 5 {
		t.Errorf("心跳配置 = %s:%d/%ds", cfg.FeHost, cfg.FePort, cfg.HeartbeatIntervalSeconds)
	}
}

func TestParseKeepsDefaultsForMissingKeys(t *testing.T) {
	cfg, err := Parse(strings.NewReader("port = 9300\n"))
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	def := Default()
	if cfg.DataDir != def.DataDir || cfg.MemTableSize != def.MemTableSize {
		t.Errorf("未出现的键应沿用默认值: %+v", cfg)
	}
	if cfg.Port != 9300 {
		t.Errorf("port = %d", cfg.Port)
	}
}

func TestParseRejects(t *testing.T) {
	cases := map[string]string{
		"格式错误":    "data_dir ./data/BE",
		"未知配置项":   "datadir = ./data/BE",
		"取值为空":    "port = ",
		"端口越界":    "port = 70000",
		"端口非数字":   "port = abc",
		"阈值非法":    "memtable_size = 12XB",
		"阈值为零":    "memtable_size = 0",
		"FE 端口越界": "fe_port = 0",
		"心跳间隔为零":  "heartbeat_interval_seconds = 0",
		"心跳间隔非数字": "heartbeat_interval_seconds = x",
	}
	for name, src := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := Parse(strings.NewReader(src)); err == nil {
				t.Fatalf("期望报错,实际通过了: %q", src)
			}
		})
	}
}

func TestParseSize(t *testing.T) {
	ok := map[string]int64{
		"64MB":  64 * 1024 * 1024,
		"1mb":   1024 * 1024,
		"32KB":  32 * 1024,
		"1GB":   1024 * 1024 * 1024,
		"1024":  1024,
		"2048B": 2048,
		" 8MB ": 8 * 1024 * 1024,
	}
	for in, want := range ok {
		got, err := ParseSize(in)
		if err != nil {
			t.Errorf("ParseSize(%q) 报错: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("ParseSize(%q) = %d,期望 %d", in, got, want)
		}
	}

	for _, in := range []string{"", "abc", "0", "-1MB", "MB"} {
		if _, err := ParseSize(in); err == nil {
			t.Errorf("ParseSize(%q) 期望报错", in)
		}
	}
}

func TestLoadMissingFile(t *testing.T) {
	_, err := Load(filepath.Join(t.TempDir(), "nope.conf"))
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("期望 os.ErrNotExist,实际: %v", err)
	}
}

func TestLoadFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "be.conf")
	if err := os.WriteFile(path, []byte("port = 9400\nmemtable_size = 1MB\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("加载失败: %v", err)
	}
	if cfg.Port != 9400 || cfg.MemTableSize != 1024*1024 {
		t.Fatalf("配置不对: %+v", cfg)
	}
}

func TestLoadErrorMentionsFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.conf")
	if err := os.WriteFile(path, []byte("port = abc\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := Load(path)
	if err == nil {
		t.Fatal("期望报错")
	}
	if !strings.Contains(err.Error(), "bad.conf") || !strings.Contains(err.Error(), "第 1 行") {
		t.Fatalf("错误信息应包含文件名与行号,实际: %v", err)
	}
}
