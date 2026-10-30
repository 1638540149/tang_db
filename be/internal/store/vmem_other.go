//go:build !windows

package store

import (
	"os"
	"strconv"
	"strings"
)

// totalPhysicalMemory 在非 Windows 上读 /proc/meminfo;读不到就返回 0(调用方用固定默认值)。
func totalPhysicalMemory() uint64 {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(data), "\n") {
		if !strings.HasPrefix(line, "MemTotal:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return 0
		}
		kb, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil {
			return 0
		}
		return kb * 1024
	}
	return 0
}
