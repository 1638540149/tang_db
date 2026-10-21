package memtable

import (
	"bytes"
	"fmt"
	"sort"
	"sync"
	"testing"
)

func TestBasicPutGet(t *testing.T) {
	m := New()
	m.Put([]byte("b"), []byte("2"))
	m.Put([]byte("a"), []byte("1"))
	m.Put([]byte("c"), []byte("3"))

	v, ok := m.Get([]byte("a"))
	if !ok || !bytes.Equal(v, []byte("1")) {
		t.Fatalf("get a failed: %v %v", v, ok)
	}
	v, ok = m.Get([]byte("c"))
	if !ok || !bytes.Equal(v, []byte("3")) {
		t.Fatalf("get c failed")
	}
	_, ok = m.Get([]byte("z"))
	if ok {
		t.Fatal("expected missing z")
	}
}

func TestOverwrite(t *testing.T) {
	m := New()
	m.Put([]byte("k"), []byte("old"))
	m.Put([]byte("k"), []byte("new"))
	v, ok := m.Get([]byte("k"))
	if !ok || !bytes.Equal(v, []byte("new")) {
		t.Fatalf("overwrite failed: %v", v)
	}
}

func TestOrderedIteration(t *testing.T) {
	m := New()
	keys := []string{"cherry", "apple", "banana", "date"}
	for _, k := range keys {
		m.Put([]byte(k), []byte(k+"-v"))
	}
	sort.Strings(keys)

	it := m.Iterator()
	var got []string
	for it.First(); it.Valid(); it.Next() {
		got = append(got, string(it.Key()))
	}
	if len(got) != len(keys) {
		t.Fatalf("iterator len %d, want %d", len(got), len(keys))
	}
	for i := range keys {
		if got[i] != keys[i] {
			t.Fatalf("iterator order mismatch at %d: got %s, want %s", i, got[i], keys[i])
		}
	}
}

func TestBinaryKeyValue(t *testing.T) {
	m := New()
	key := []byte{0x00, 0x01, 0x00, 0xFF}
	value := []byte{0xAB, 0xCD}
	m.Put(key, value)
	got, ok := m.Get(key)
	if !ok || !bytes.Equal(got, value) {
		t.Fatalf("binary key/value failed")
	}
}

func TestConcurrentPut(t *testing.T) {
	m := New()
	const n = 1000
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			key := []byte(fmt.Sprintf("key-%04d", i))
			value := []byte(fmt.Sprintf("value-%d", i))
			m.Put(key, value)
		}(i)
	}
	wg.Wait()

	if m.Len() != int64(n) {
		t.Fatalf("len %d, want %d", m.Len(), n)
	}
	for i := 0; i < n; i++ {
		key := []byte(fmt.Sprintf("key-%04d", i))
		expected := []byte(fmt.Sprintf("value-%d", i))
		got, ok := m.Get(key)
		if !ok || !bytes.Equal(got, expected) {
			t.Fatalf("concurrent get failed for %s: %v %v", key, got, ok)
		}
	}
}
