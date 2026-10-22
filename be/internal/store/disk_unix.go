//go:build !windows

package store

import "syscall"

// diskUsage 返回路径所在文件系统的总容量与可用空间(字节)。
func diskUsage(path string) (total, available uint64) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0
	}
	return st.Blocks * uint64(st.Bsize), st.Bavail * uint64(st.Bsize)
}
