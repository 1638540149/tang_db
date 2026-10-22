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
}

func TestParseFullFile(t *testing.T) {
	src := `# tang_db BE 配置
# 注释与空行应被忽略

data_dir = ./data/test
port = 9200
memtable_size = 32KB
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
		"格式错误":      "data_dir ./data/BE",
		"未知配置项":     "datadir = ./data/BE",
		"取值为空":      "port = ",
		"端口越界":      "port = 70000",
		"端口非数字":     "port = abc",
		"阈值非法":      "memtable_size = 12XB",
		"阈值为零":      "memtable_size = 0",
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
		"64MB":    64 * 1024 * 1024,
		"1mb":     1024 * 1024,
		"32KB":    32 * 1024,
		"1GB":     1024 * 1024 * 1024,
		"1024":    1024,
		"2048B":   2048,
		" 8MB ":   8 * 1024 * 1024,
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
