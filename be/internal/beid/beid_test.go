package beid

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadOrCreatePersists(t *testing.T) {
	dir := t.TempDir()
	first, err := LoadOrCreate(dir)
	if err != nil {
		t.Fatalf("首次生成失败: %v", err)
	}
	if !IsValid(first) {
		t.Fatalf("生成的 id 非法: %q", first)
	}
	data, err := os.ReadFile(filepath.Join(dir, FileName))
	if err != nil {
		t.Fatalf("读取 be.id 失败: %v", err)
	}
	if strings.TrimSpace(string(data)) != first {
		t.Fatalf("落盘内容 %q != %q", data, first)
	}

	second, err := LoadOrCreate(dir)
	if err != nil {
		t.Fatalf("二次加载失败: %v", err)
	}
	if second != first {
		t.Fatalf("重启后 be_id 变了: %q -> %q", first, second)
	}
}

func TestLoadOrCreateRegeneratesCorruptFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, FileName), []byte("not-a-uuid"), 0o640); err != nil {
		t.Fatal(err)
	}
	id, err := LoadOrCreate(dir)
	if err != nil {
		t.Fatalf("损坏文件应重新生成: %v", err)
	}
	if !IsValid(id) {
		t.Fatalf("重新生成的 id 非法: %q", id)
	}
}

func TestNewUUIDv4Unique(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 100; i++ {
		id, err := NewUUIDv4()
		if err != nil {
			t.Fatal(err)
		}
		if len(id) != 36 || id[14] != '4' {
			t.Fatalf("不是 UUID v4: %s", id)
		}
		if seen[id] {
			t.Fatalf("重复的 uuid: %s", id)
		}
		seen[id] = true
	}
}

func TestIsValid(t *testing.T) {
	if !IsValid("3f2504e0-4f89-41d3-9a0c-0305e82c3301") {
		t.Fatal("合法 UUID 被判为非法")
	}
	for _, bad := range []string{"", "abc", "3f2504e0-4f89-41d3-9a0c-0305e82c330", "3f2504e0_4f89-41d3-9a0c-0305e82c3301", "zzzzzzzz-4f89-41d3-9a0c-0305e82c3301"} {
		if IsValid(bad) {
			t.Fatalf("非法 UUID 通过了校验: %q", bad)
		}
	}
}
