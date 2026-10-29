package memtable

import (
	"bytes"
	"errors"
	"fmt"
	"runtime"
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

// TestLazyIteratorSeekBoundaries Seek 到首/尾/中间与越界的边界(边界 #112)。
func TestLazyIteratorSeekBoundaries(t *testing.T) {
	m := New()
	for _, k := range []string{"b", "d", "f"} {
		m.Put([]byte(k), []byte(k))
	}
	it := m.Iterator()

	it.Seek([]byte("a"))
	if !it.Valid() || string(it.Key()) != "b" {
		t.Fatalf("seek=a → %q valid=%v, want b", it.Key(), it.Valid())
	}
	it.Seek([]byte("c"))
	if !it.Valid() || string(it.Key()) != "d" {
		t.Fatalf("seek=c → %q, want d", it.Key())
	}
	it.Seek([]byte("f"))
	if !it.Valid() || string(it.Key()) != "f" {
		t.Fatalf("seek=f → %q, want f", it.Key())
	}
	it.Seek([]byte("z"))
	if it.Valid() {
		t.Fatalf("seek=z 应越界,实际 %q", it.Key())
	}
	if err := it.Err(); err != nil {
		t.Fatalf("err = %v", err)
	}
}

// TestLazyIteratorUnsafeAfterWrite 写计数水位越过之后必须显式报 unsafe,
// 且 IteratorSnapshot 不受影响(FR-697)。
func TestLazyIteratorUnsafeAfterWrite(t *testing.T) {
	m := New()
	m.Put([]byte("a"), []byte("1"))
	m.Put([]byte("b"), []byte("2"))

	it := m.Iterator()
	it.First()
	if !it.Valid() || string(it.Key()) != "a" {
		t.Fatalf("first = %q valid=%v", it.Key(), it.Valid())
	}

	m.Put([]byte("c"), []byte("3"))

	it.Next()
	if !errors.Is(it.Err(), ErrUnsafe) {
		t.Fatalf("err = %v, want ErrUnsafe", it.Err())
	}
	if it.Valid() {
		t.Fatal("unsafe 之后不得再返回行(禁止静默返回过期数据)")
	}

	// 游标(重建后)能看到全部数据。
	fresh := m.Iterator()
	var keys []string
	for fresh.First(); fresh.Valid(); fresh.Next() {
		keys = append(keys, string(fresh.Key()))
	}
	if len(keys) != 3 {
		t.Fatalf("keys = %v", keys)
	}

	snap := m.IteratorSnapshot()
	m.Put([]byte("d"), []byte("4"))
	var snapKeys []string
	for snap.First(); snap.Valid(); snap.Next() {
		snapKeys = append(snapKeys, string(snap.Key()))
	}
	if len(snapKeys) != 3 {
		t.Fatalf("snapshot 应固定在建表时刻的 3 行,实际 %v", snapKeys)
	}
	if err := snap.Err(); err != nil {
		t.Fatalf("snapshot err = %v", err)
	}
}

// TestLazyIteratorConcurrentWrite 遍历中并发写:要么走完,要么明确 unsafe,-race 下无数据竞争。
func TestLazyIteratorConcurrentWrite(t *testing.T) {
	m := New()
	const n = 512
	for i := 0; i < n; i++ {
		m.Put([]byte(fmt.Sprintf("k-%04d", i)), []byte("v"))
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; i < n; i++ {
			m.Put([]byte(fmt.Sprintf("w-%04d", i)), []byte("v"))
		}
	}()

	it := m.Iterator()
	seen := 0
	for it.First(); it.Valid(); it.Next() {
		seen++
		if it.Key() == nil {
			t.Fatal("nil key")
		}
	}
	if err := it.Err(); err != nil && !errors.Is(err, ErrUnsafe) {
		t.Fatalf("err = %v", err)
	}
	<-done
	if seen == 0 {
		t.Fatal("至少应读完第一行")
	}
}

// TestIteratorStressUnsafeTriggered 明确断言 unsafe 至少被触发一次(并发写场景)。
func TestIteratorStressUnsafeTriggered(t *testing.T) {
	m := New()
	for i := 0; i < 2000; i++ {
		m.Put([]byte(fmt.Sprintf("k-%05d", i)), []byte("v"))
	}
	stop := make(chan struct{})
	started := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		i := 0
		first := true
		for {
			select {
			case <-stop:
				return
			default:
			}
			m.Put([]byte(fmt.Sprintf("z-%05d", i)), []byte("v"))
			if first {
				close(started)
				first = false
			}
			i++
			runtime.Gosched()
		}
	}()
	<-started

	unsafeSeen := false
	for round := 0; round < 200 && !unsafeSeen; round++ {
		// 创建游标与第一次前进之间给写者一个机会推进水位。
		it := m.Iterator()
		runtime.Gosched()
		for it.First(); it.Valid(); it.Next() {
			// 只需要真的读到行;unsafe 会在 Next 里置 Err,循环条件随即退出。
		}
		if errors.Is(it.Err(), ErrUnsafe) {
			unsafeSeen = true
		}
	}
	close(stop)
	wg.Wait()
	if !unsafeSeen {
		t.Fatal("并发写期间未触发 unsafe(水位校验失效)")
	}
}
