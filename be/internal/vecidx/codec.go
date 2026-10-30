package vecidx

import (
	"encoding/binary"
	"fmt"
	"hash/crc64"
	"os"
	"path/filepath"
)

var crc64Table = crc64.MakeTable(crc64.ECMA)

// WriteFile 把图原子地写到 path:先写 path.tmp,fsync 后 rename(段文件不可变,FR-746)。
func WriteFile(path string, g *Graph) (int64, error) {
	if err := g.Check(); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0750); err != nil {
		return 0, fmt.Errorf("vecidx: mkdir %s: %w", filepath.Dir(path), err)
	}
	buf := encode(g)
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_RDWR|os.O_TRUNC, 0640)
	if err != nil {
		return 0, fmt.Errorf("vecidx: create %s: %w", tmp, err)
	}
	defer os.Remove(tmp)
	if _, err := f.Write(buf); err != nil {
		f.Close()
		return 0, fmt.Errorf("vecidx: write %s: %w", tmp, err)
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return 0, fmt.Errorf("vecidx: fsync %s: %w", tmp, err)
	}
	if err := f.Close(); err != nil {
		return 0, err
	}
	if err := os.Rename(tmp, path); err != nil {
		return 0, fmt.Errorf("vecidx: rename %s: %w", path, err)
	}
	if err := syncFile(path); err != nil {
		return 0, err
	}
	return int64(len(buf)), nil
}

func syncFile(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

// encode 序列化整张图(含尾部 CRC64)。
func encode(g *Graph) []byte {
	size := 8 + 4 + 4 + 1 + 4 + 8 + 4 + 4 + 8 + 8
	size += len(g.RowOrdinals) * 8
	for _, level := range g.Levels {
		size += 8 + len(level.Nodes)*4
		for _, adj := range level.Adj {
			size += 4 + len(adj)*4
		}
	}
	size += 8 // checksum

	buf := make([]byte, 0, size)
	buf = append(buf, Magic[:]...)
	buf = be32(buf, Version)
	buf = be32(buf, uint32(g.Dim))
	buf = append(buf, g.Metric)
	buf = be32(buf, uint32(g.LevelCount))
	buf = be64(buf, g.EntryPoint)
	buf = be32(buf, uint32(g.M))
	buf = be32(buf, uint32(g.EfConstruction))
	buf = be64(buf, uint64(len(g.RowOrdinals)))
	buf = be64(buf, g.BuildSeq)
	for _, ord := range g.RowOrdinals {
		buf = be64(buf, ord)
	}
	for _, level := range g.Levels {
		buf = be64(buf, uint64(len(level.Nodes)))
		for _, id := range level.Nodes {
			buf = be32(buf, id)
		}
		for _, adj := range level.Adj {
			buf = be32(buf, uint32(len(adj)))
			for _, nb := range adj {
				buf = be32(buf, nb)
			}
		}
	}
	sum := crc64.Checksum(buf, crc64Table)
	buf = be64(buf, sum)
	return buf
}

// ReadFile 读取并校验图段文件;截断 / 校验和不符 / 布局非法都返回 ErrCorrupt。
func ReadFile(path string) (*Graph, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return Decode(data)
}

// Decode 解析图段字节。
func Decode(data []byte) (*Graph, error) {
	const headerLen = 8 + 4 + 4 + 1 + 4 + 8 + 4 + 4 + 8 + 8
	if len(data) < headerLen+8 {
		return nil, fmt.Errorf("%w: file too short (%d bytes)", ErrCorrupt, len(data))
	}
	if string(data[:8]) != string(Magic[:]) {
		return nil, fmt.Errorf("%w: bad magic", ErrCorrupt)
	}
	body := data[:len(data)-8]
	want := binary.BigEndian.Uint64(data[len(data)-8:])
	if got := crc64.Checksum(body, crc64Table); got != want {
		return nil, fmt.Errorf("%w: checksum mismatch (want %x got %x)", ErrCorrupt, want, got)
	}
	pos := 8
	version := binary.BigEndian.Uint32(body[pos:])
	pos += 4
	if version != Version {
		return nil, fmt.Errorf("%w: unsupported version %d (want %d)", ErrCorrupt, version, Version)
	}
	g := &Graph{}
	g.Dim = int(binary.BigEndian.Uint32(body[pos:]))
	pos += 4
	g.Metric = body[pos]
	pos++
	g.LevelCount = int(binary.BigEndian.Uint32(body[pos:]))
	pos += 4
	g.EntryPoint = binary.BigEndian.Uint64(body[pos:])
	pos += 8
	g.M = int(binary.BigEndian.Uint32(body[pos:]))
	pos += 4
	g.EfConstruction = int(binary.BigEndian.Uint32(body[pos:]))
	pos += 4
	nodeCount := binary.BigEndian.Uint64(body[pos:])
	pos += 8
	g.BuildSeq = binary.BigEndian.Uint64(body[pos:])
	pos += 8
	if nodeCount == 0 || nodeCount > uint64(len(body)) {
		return nil, fmt.Errorf("%w: nodeCount=%d", ErrCorrupt, nodeCount)
	}
	if pos+int(nodeCount)*8 > len(body) {
		return nil, fmt.Errorf("%w: truncated node table", ErrCorrupt)
	}
	g.RowOrdinals = make([]uint64, nodeCount)
	for i := range g.RowOrdinals {
		g.RowOrdinals[i] = binary.BigEndian.Uint64(body[pos:])
		pos += 8
	}
	if g.LevelCount <= 0 || g.LevelCount > 64 {
		return nil, fmt.Errorf("%w: levelCount=%d", ErrCorrupt, g.LevelCount)
	}
	g.Levels = make([]Level, g.LevelCount)
	for li := 0; li < g.LevelCount; li++ {
		if pos+8 > len(body) {
			return nil, fmt.Errorf("%w: truncated level %d header", ErrCorrupt, li)
		}
		n := int(binary.BigEndian.Uint64(body[pos:]))
		pos += 8
		if n < 0 || n > int(nodeCount) || pos+n*4 > len(body) {
			return nil, fmt.Errorf("%w: level %d node count %d", ErrCorrupt, li, n)
		}
		level := Level{Nodes: make([]uint32, n), Adj: make([][]uint32, n)}
		for i := 0; i < n; i++ {
			level.Nodes[i] = binary.BigEndian.Uint32(body[pos:])
			pos += 4
		}
		for i := 0; i < n; i++ {
			if pos+4 > len(body) {
				return nil, fmt.Errorf("%w: truncated adjacency at level %d", ErrCorrupt, li)
			}
			deg := int(binary.BigEndian.Uint32(body[pos:]))
			pos += 4
			if deg < 0 || pos+deg*4 > len(body) {
				return nil, fmt.Errorf("%w: bad degree %d at level %d", ErrCorrupt, deg, li)
			}
			adj := make([]uint32, deg)
			for j := 0; j < deg; j++ {
				adj[j] = binary.BigEndian.Uint32(body[pos:])
				pos += 4
			}
			level.Adj[i] = adj
		}
		g.Levels[li] = level
	}
	if pos != len(body) {
		return nil, fmt.Errorf("%w: trailing %d bytes", ErrCorrupt, len(body)-pos)
	}
	if err := g.Check(); err != nil {
		return nil, err
	}
	return g, nil
}

func be32(buf []byte, v uint32) []byte {
	var tmp [4]byte
	binary.BigEndian.PutUint32(tmp[:], v)
	return append(buf, tmp[:]...)
}

func be64(buf []byte, v uint64) []byte {
	var tmp [8]byte
	binary.BigEndian.PutUint64(tmp[:], v)
	return append(buf, tmp[:]...)
}
