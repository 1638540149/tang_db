package server

import (
	"context"
	"log/slog"
	"time"

	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// ReporterConfig 是 BE → FE 心跳客户端的配置。
type ReporterConfig struct {
	FeAddr   string // FE 心跳服务地址,形如 127.0.0.1:9091
	Endpoint string // 本 BE 对外地址,形如 127.0.0.1:9100
	BeID     string
	Version  string
	Interval time.Duration
}

// Reporter 负责向 FE 注册并周期性上报心跳。
//
// 它跑在独立 goroutine 里:任何失败都只记日志并按退避重试,
// 绝不影响前台读写,也绝不让 BE 进程退出(规约 FR-153)。
type Reporter struct {
	engine    *store.Engine
	cfg       ReporterConfig
	startTime time.Time
	conn      *grpc.ClientConn
	client    pb.TangDbFrontendClient
	log       *slog.Logger
}

// NewReporter 创建心跳客户端。它只建立本地 channel 对象,不会阻塞拨号。
func NewReporter(engine *store.Engine, cfg ReporterConfig) (*Reporter, error) {
	if cfg.Interval <= 0 {
		cfg.Interval = 3 * time.Second
	}
	conn, err := grpc.NewClient(cfg.FeAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, err
	}
	return &Reporter{
		engine:    engine,
		cfg:       cfg,
		startTime: time.Now(),
		conn:      conn,
		client:    pb.NewTangDbFrontendClient(conn),
		log:       slog.Default().With("component", "heartbeat"),
	}, nil
}

// Start 在后台启动注册与心跳循环,ctx 取消时退出。
func (r *Reporter) Start(ctx context.Context) {
	go r.loop(ctx)
}

// Close 释放到 FE 的连接。
func (r *Reporter) Close() error {
	if r.conn == nil {
		return nil
	}
	return r.conn.Close()
}

// BuildHeartbeat 把本地状态序列化为心跳请求。抽成纯函数便于测试。
func BuildHeartbeat(beID, endpoint, version string, interval time.Duration, startTime time.Time, st store.Status) *pb.HeartbeatRequest {
	return &pb.HeartbeatRequest{
		BeId:                     beID,
		Endpoint:                 endpoint,
		Version:                  version,
		StartTimeUnixMillis:      startTime.UnixMilli(),
		HeartbeatIntervalSeconds: uint32(interval / time.Second),
		DataDir:                  st.DataDir,
		DiskTotalBytes:           st.DiskTotalBytes,
		DiskAvailableBytes:       st.DiskAvailableBytes,
		MemtableBytes:            uint64(st.MemTableBytes),
		ImmutableMemtableCount:   uint32(st.ImmutableCount),
		SegmentCount:             uint32(st.SegmentCount),
		SegmentBytes:             st.SegmentBytes,
		WalBytes:                 st.WALBytes,
		PutCount:                 st.PutCount,
		GetCount:                 st.GetCount,
		ScanCount:                st.ScanCount,
		PutErrorCount:            st.PutErrCount,
		GetErrorCount:            st.GetErrCount,
		ScanErrorCount:           st.ScanErrCount,
		LastFsyncUnixMillis:      st.LastFsyncUnixMilli,
	}
}

func (r *Reporter) loop(ctx context.Context) {
	backoff := 500 * time.Millisecond
	for {
		if ctx.Err() != nil {
			return
		}
		if err := r.register(ctx); err != nil {
			r.log.Warn("向 FE 注册失败,将退避重试(不影响前台读写)",
				"fe", r.cfg.FeAddr, "error", err, "retry_in", backoff)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff *= 2
			if backoff > 10*time.Second {
				backoff = 10 * time.Second
			}
			continue
		}
		break
	}

	r.log.Info("已向 FE 注册", "fe", r.cfg.FeAddr, "be_id", r.cfg.BeID, "endpoint", r.cfg.Endpoint)

	ticker := time.NewTicker(r.cfg.Interval)
	defer ticker.Stop()
	failBackoff := time.Second

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}

		if err := r.heartbeat(ctx); err != nil {
			r.log.Warn("心跳上报失败(前台读写不受影响)",
				"fe", r.cfg.FeAddr, "error", err, "retry_in", failBackoff)
			if !sleepCtx(ctx, failBackoff) {
				return
			}
			failBackoff *= 2
			if failBackoff > 10*time.Second {
				failBackoff = 10 * time.Second
			}
			continue
		}
		failBackoff = time.Second
	}
}

func (r *Reporter) register(ctx context.Context) error {
	callCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	resp, err := r.client.RegisterBe(callCtx, &pb.RegisterBeRequest{
		BeId:                     r.cfg.BeID,
		Endpoint:                 r.cfg.Endpoint,
		Version:                  r.cfg.Version,
		StartTimeUnixMillis:      r.startTime.UnixMilli(),
		HeartbeatIntervalSeconds: uint32(r.cfg.Interval / time.Second),
	})
	if err != nil {
		return err
	}
	if resp.GetStatus() != pb.StatusCode_OK {
		return &statusError{msg: resp.GetMessage()}
	}
	return nil
}

func (r *Reporter) heartbeat(ctx context.Context) error {
	callCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	req := BuildHeartbeat(r.cfg.BeID, r.cfg.Endpoint, r.cfg.Version,
		r.cfg.Interval, r.startTime, r.engine.Status())
	resp, err := r.client.ReportHeartbeat(callCtx, req)
	if err != nil {
		return err
	}
	if resp.GetStatus() != pb.StatusCode_OK {
		return &statusError{msg: resp.GetMessage()}
	}
	return nil
}

type statusError struct{ msg string }

func (e *statusError) Error() string {
	if e.msg == "" {
		return "FE 返回非 OK 状态"
	}
	return e.msg
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
