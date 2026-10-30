package store

import (
	"encoding/binary"
	"math"
	"testing"

	"github.com/1638540149/tang_db/be/internal/vecidx"
)

func feRow4(tableID uint32, id int32, title, category string, vec []float32) []byte {
	out := []byte{0x01}
	var tmp [8]byte
	binary.LittleEndian.PutUint32(tmp[:4], tableID)
	out = append(out, tmp[:4]...)
	binary.LittleEndian.PutUint16(tmp[:2], 4)
	out = append(out, tmp[:2]...)
	out = append(out, 0x00)
	binary.LittleEndian.PutUint32(tmp[:4], uint32(id))
	out = append(out, tmp[:4]...)
	for _, s := range []string{title, category} {
		b := []byte(s)
		binary.LittleEndian.PutUint32(tmp[:4], uint32(len(b)))
		out = append(out, tmp[:4]...)
		out = append(out, b...)
	}
	for _, v := range vec {
		binary.LittleEndian.PutUint32(tmp[:4], math.Float32bits(v))
		out = append(out, tmp[:4]...)
	}
	return out
}

func feSlots4(dim int) []byte {
	out := []byte{4, 0}
	put := func(ord uint16, kind, width byte) {
		var t [2]byte
		binary.LittleEndian.PutUint16(t[:], ord)
		out = append(out, t[:]...)
		out = append(out, kind, width)
	}
	put(0, 2, 4) // INT32
	put(1, 5, 4) // VARLEN_SIGNED
	put(2, 5, 4)
	put(3, 6, 0)
	out = append(out, 1, 0) // wide count
	var t [2]byte
	binary.LittleEndian.PutUint16(t[:], 3)
	out = append(out, t[:]...)
	var w [4]byte
	binary.LittleEndian.PutUint32(w[:], uint32(4*dim))
	out = append(out, w[:]...)
	return out
}

func TestExtractVectors4Cols(t *testing.T) {
	dir := t.TempDir()
	e, _, err := OpenWithOptions(dir, Options{MemtableSize: 1 << 20, CompactionL0Files: 100})
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	const tableID = 64
	def := VecDefinition{IndexID: 1, TableID: tableID, StartKey: prefixOf(tableID, 0),
		EndKey: prefixOf(tableID, 1), ColumnSlots: feSlots4(4), RowFrame: []byte{1, 4, 0, 7, 0},
		VectorOrdinal: 3, Dim: 4, Metric: vecidx.MetricL2, M: 8, EfConstruction: 32}
	if err := e.RegisterVectorIndex(def); err != nil {
		t.Fatal(err)
	}
	var writes []Write
	for i := 0; i < 10; i++ {
		key := append(prefixOf(tableID, 0), byte(i>>24), byte(i>>16), byte(i>>8), byte(i))
		writes = append(writes, Write{Key: key, Value: feRow4(tableID, int32(i), "t", "A",
			[]float32{float32(i), 0, 0, 1})})
	}
	if _, _, err := e.Commit(0, 0, writes); err != nil {
		t.Fatal(err)
	}
	if err := e.ForceFlushAll(); err != nil {
		t.Fatal(err)
	}
	waitReady(t, e, 1)
	st := e.VectorIndexStatus(1)
	t.Logf("status=%+v", st)
	if st.NodeCount != 10 {
		t.Fatalf("nodes=%d want 10", st.NodeCount)
	}
}
