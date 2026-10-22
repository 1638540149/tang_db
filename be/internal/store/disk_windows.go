//go:build windows

package store

import (
	"path/filepath"
	"syscall"
	"unsafe"
)

var (
	kernel32                = syscall.NewLazyDLL("kernel32.dll")
	getDiskFreeSpaceExWProc = kernel32.NewProc("GetDiskFreeSpaceExW")
)

// diskUsage 返回路径所在卷的总容量与调用者可用空间(字节)。
func diskUsage(path string) (total, available uint64) {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = path
	}
	p, err := syscall.UTF16PtrFromString(abs)
	if err != nil {
		return 0, 0
	}
	var freeToCaller, totalBytes, totalFree uint64
	r, _, _ := getDiskFreeSpaceExWProc.Call(
		uintptr(unsafe.Pointer(p)),
		uintptr(unsafe.Pointer(&freeToCaller)),
		uintptr(unsafe.Pointer(&totalBytes)),
		uintptr(unsafe.Pointer(&totalFree)),
	)
	if r == 0 {
		return 0, 0
	}
	return totalBytes, freeToCaller
}
