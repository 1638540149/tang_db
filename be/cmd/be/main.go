// tang_db BE 进程入口。
//
// 启动流程:
//  1. 解析命令行参数
//  2. 打开并恢复 LSM 存储引擎(加载 segment、重放 WAL)
//  3. 启动后台 Flush Worker
//  4. 启动 gRPC 服务
package main

import (
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"github.com/1638540149/tang_db/be/internal/server"
	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
	"google.golang.org/grpc"
)

var (
	dataDir       = flag.String("data-dir", "./data/BE", "数据目录路径")
	port          = flag.Int("port", 9100, "gRPC 监听端口")
	memtableSize  = flag.String("memtable-size", "64MB", "memtable 阈值,例如 64MB、1MB、1024")
)

func main() {
	flag.Parse()

	if err := run(); err != nil {
		slog.Error("BE 启动失败", "error", err)
		os.Exit(1)
	}
}

func run() error {
	threshold, err := parseSize(*memtableSize)
	if err != nil {
		return fmt.Errorf("解析 --memtable-size 失败: %w", err)
	}

	slog.Info("正在启动 tang_db BE", "version", store.EngineVersion(), "data_dir", *dataDir, "port", *port, "memtable_size", threshold)

	engine, stats, err := store.Open(*dataDir, threshold)
	if err != nil {
		return fmt.Errorf("打开存储引擎失败: %w", err)
	}
	defer engine.Close()

	slog.Info("恢复完成",
		"segments_loaded", stats.SegmentsLoaded,
		"wal_replayed", stats.WALReplayed,
	)

	grpcServer := grpc.NewServer()
	pb.RegisterTangDbServer(grpcServer, server.NewTangDbServer(engine))

	addr := fmt.Sprintf("127.0.0.1:%d", *port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		if errors.Is(err, syscall.EADDRINUSE) || strings.Contains(err.Error(), "bind") {
			return fmt.Errorf("端口 %d 已被占用", *port)
		}
		return fmt.Errorf("监听 %s 失败: %w", addr, err)
	}

	slog.Info("BE 已就绪", "listen", addr, "data_dir", engine.DataDir(), "memtable_threshold", threshold)

	// 优雅关闭
	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-done
		slog.Info("正在关闭 BE...")
		grpcServer.GracefulStop()
	}()

	if err := grpcServer.Serve(lis); err != nil {
		return fmt.Errorf("gRPC 服务异常: %w", err)
	}
	return nil
}

// parseSize 解析形如 "64MB"、"1MB"、"1024" 的字符串为字节数。
func parseSize(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, errors.New("不能为空")
	}
	s = strings.ToUpper(s)
	var unit int64 = 1
	if strings.HasSuffix(s, "MB") {
		unit = 1024 * 1024
		s = strings.TrimSuffix(s, "MB")
	} else if strings.HasSuffix(s, "KB") {
		unit = 1024
		s = strings.TrimSuffix(s, "KB")
	} else if strings.HasSuffix(s, "GB") {
		unit = 1024 * 1024 * 1024
		s = strings.TrimSuffix(s, "GB")
	} else if strings.HasSuffix(s, "B") {
		s = strings.TrimSuffix(s, "B")
	}
	s = strings.TrimSpace(s)
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("非法数字 %q", s)
	}
	if n <= 0 {
		return 0, errors.New("必须大于 0")
	}
	return n * unit, nil
}
