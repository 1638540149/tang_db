package store

import "time"

// timeNowNanos 是 LRU 时间戳的唯一出口(便于单测替换;默认单调时钟)。
func timeNowNanos() int64 { return time.Now().UnixNano() }
