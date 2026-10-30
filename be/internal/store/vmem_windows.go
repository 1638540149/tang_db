//go:build windows

package store

import (
	"syscall"
	"unsafe"
)

// totalPhysicalMemory 返回物理内存字节数(用于 K178 的"默认预算 = 内存的 10%")。
// 只用标准库 syscall,不引入任何第三方依赖。
func totalPhysicalMemory() uint64 {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GlobalMemoryStatusEx")
	type memoryStatusEx struct {
		length               uint32
		memoryLoad           uint32
		totalPhys            uint64
		availPhys            uint64
		totalPageFile        uint64
		availPageFile        uint64
		totalVirtual         uint64
		availVirtual         uint64
		availExtendedVirtual uint64
	}
	var status memoryStatusEx
	status.length = uint32(unsafe.Sizeof(status))
	if ret, _, _ := proc.Call(uintptr(unsafe.Pointer(&status))); ret == 0 {
		return 0
	}
	return status.totalPhys
}
