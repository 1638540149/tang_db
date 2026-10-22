package server

import (
	"testing"
	"time"

	"github.com/1638540149/tang_db/be/internal/store"
)

func TestBuildHeartbeatMapsAllFields(t *testing.T) {
	start := time.UnixMilli(1700000000000)
	st := store.Status{
		DataDir:            "./data/BE",
		MemTableBytes:      1024,
		ImmutableCount:     2,
		SegmentCount:       3,
		SegmentBytes:       4096,
		WALBytes:           512,
		PutCount:           10,
		GetCount:           20,
		ScanCount:          30,
		PutErrCount:        1,
		GetErrCount:        2,
		ScanErrCount:       3,
		LastFsyncUnixMilli: 1700000000123,
		DiskTotalBytes:     1000,
		DiskAvailableBytes: 600,
	}

	req := BuildHeartbeat("be-1", "127.0.0.1:9100", "P2-0.1.0", 3*time.Second, start, st)

	if req.BeId != "be-1" || req.Endpoint != "127.0.0.1:9100" || req.Version != "P2-0.1.0" {
		t.Fatalf("身份字段不对: %+v", req)
	}
	if req.StartTimeUnixMillis != 1700000000000 {
		t.Errorf("start_time = %d", req.StartTimeUnixMillis)
	}
	if req.HeartbeatIntervalSeconds != 3 {
		t.Errorf("interval = %d", req.HeartbeatIntervalSeconds)
	}
	if req.DataDir != "./data/BE" || req.DiskTotalBytes != 1000 || req.DiskAvailableBytes != 600 {
		t.Errorf("存储字段不对: %+v", req)
	}
	if req.MemtableBytes != 1024 || req.ImmutableMemtableCount != 2 {
		t.Errorf("内存表字段不对: %+v", req)
	}
	if req.SegmentCount != 3 || req.SegmentBytes != 4096 || req.WalBytes != 512 {
		t.Errorf("磁盘文件字段不对: %+v", req)
	}
	if req.PutCount != 10 || req.GetCount != 20 || req.ScanCount != 30 {
		t.Errorf("服务量字段不对: %+v", req)
	}
	if req.PutErrorCount != 1 || req.GetErrorCount != 2 || req.ScanErrorCount != 3 {
		t.Errorf("错误计数字段不对: %+v", req)
	}
	if req.LastFsyncUnixMillis != 1700000000123 {
		t.Errorf("last_fsync = %d", req.LastFsyncUnixMillis)
	}
}
