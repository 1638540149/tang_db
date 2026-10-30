// tang_db BE 进程入口。
//
// 启动流程:
//  1. 解析命令行参数,并加载配置文件(优先级:命令行 > config/be.conf > 内置默认值)
//  2. 打开并恢复 LSM 存储引擎(加载 segment、重放 WAL)
//  3. 启动后台 Flush Worker
//  4. 启动 gRPC 服务
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/1638540149/tang_db/be/internal/beid"
	"github.com/1638540149/tang_db/be/internal/config"
	"github.com/1638540149/tang_db/be/internal/server"
	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
	"google.golang.org/grpc"
)

var (
	configPath = flag.String("config", "", "配置文件路径,默认 "+config.DefaultPath)
	dataDir    = flag.String("data-dir", "", "数据目录路径,覆盖配置文件里的 data_dir")
	port       = flag.Int("port", 0, "gRPC 监听端口,覆盖配置文件里的 port")
	memtable   = flag.String("memtable-size", "", "memtable 阈值,如 64MB/1MB/1024,覆盖配置文件里的 memtable_size")
)

func main() {
	flag.Parse()

	if err := run(); err != nil {
		slog.Error("BE 启动失败", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, configFrom, err := loadConfig(*configPath)
	if err != nil {
		return err
	}

	// 命令行显式给出的参数覆盖配置文件。
	explicit := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { explicit[f.Name] = true })

	if explicit["data-dir"] {
		cfg.DataDir = *dataDir
	}
	if explicit["port"] {
		cfg.Port = *port
	}
	if explicit["memtable-size"] {
		size, err := config.ParseSize(*memtable)
		if err != nil {
			return fmt.Errorf("解析 --memtable-size 失败: %w", err)
		}
		cfg.MemTableSize = size
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return errors.New("data_dir 不能为空")
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return fmt.Errorf("port 无效: %d(应为 1-65535)", cfg.Port)
	}

	slog.Info("正在启动 tang_db BE",
		"version", store.EngineVersion(),
		"config", configFrom,
		"data_dir", cfg.DataDir,
		"port", cfg.Port,
		"memtable_size", cfg.MemTableSize,
		"max_txn_write_bytes", cfg.MaxTxnWriteBytes,
		"query_batch_rows", cfg.QueryBatchRows,
		"compaction_l0_files", cfg.CompactionL0Files,
		"compaction_level_ratio", cfg.CompactionLevelRatio,
		"compaction_level_max_bytes", cfg.CompactionLevelMaxBytes,
		"compaction_bloom_enabled", cfg.CompactionBloomEnabled,
	)

	// FR-365:崩溃注入必须在启动日志里显眼告警(仅验收使用)。
	if cfg.TestCrashPoint != "" {
		slog.Warn("WARN 崩溃注入已启用:"+cfg.TestCrashPoint+"(仅验收使用)", "test_crash_point", cfg.TestCrashPoint)
	}

	engine, stats, err := store.OpenWithOptions(cfg.DataDir, store.Options{
		MemtableSize:            cfg.MemTableSize,
		MaxTxnWriteBytes:        cfg.MaxTxnWriteBytes,
		TestCrashPoint:          cfg.TestCrashPoint,
		CompactionL0Files:       cfg.CompactionL0Files,
		CompactionLevelRatio:    cfg.CompactionLevelRatio,
		CompactionLevelMaxBytes: cfg.CompactionLevelMaxBytes,
		BloomEnabled:            cfg.CompactionBloomEnabled,
		WALBufferBytes:          cfg.WALBufferBytes,
		GroupCommitMaxBatch:     cfg.GroupCommitMaxBatch,
		GroupCommitMaxWait:      cfg.GroupCommitMaxWait,
		VectorCacheBytes:        cfg.VectorCacheBytes,
		VectorSearchParallelism: cfg.VectorSearchParallelism,
	})
	if err != nil {
		return fmt.Errorf("打开存储引擎失败: %w", err)
	}
	defer func() { _ = engine.Close() }()

	slog.Info("恢复完成",
		"segments_loaded", stats.SegmentsLoaded,
		"wal_replayed", stats.WALReplayed,
	)

	// be_id:首次启动生成并持久化到 <data_dir>/be.id,重启不变。
	identity, err := beid.LoadOrCreate(cfg.DataDir)
	if err != nil {
		return fmt.Errorf("初始化 be_id 失败: %w", err)
	}

	grpcServer := grpc.NewServer()
	pb.RegisterTangDbServer(grpcServer, server.NewTangDbServer(engine))

	addr := fmt.Sprintf("127.0.0.1:%d", cfg.Port)
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		if errors.Is(err, syscall.EADDRINUSE) || strings.Contains(err.Error(), "bind") {
			return fmt.Errorf("端口 %d 已被占用", cfg.Port)
		}
		return fmt.Errorf("监听 %s 失败: %w", addr, err)
	}

	slog.Info("BE 已就绪", "listen", addr, "data_dir", engine.DataDir(),
		"memtable_threshold", cfg.MemTableSize, "be_id", identity)

	// 注册与心跳:独立 goroutine,失败只退避重试,不影响前台读写。
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	reporter, err := server.NewReporter(engine, server.ReporterConfig{
		FeAddr:   fmt.Sprintf("%s:%d", cfg.FeHost, cfg.FePort),
		Endpoint: addr,
		BeID:     identity,
		Version:  store.EngineVersion(),
		Interval: time.Duration(cfg.HeartbeatIntervalSeconds) * time.Second,
	})
	if err != nil {
		// 连 channel 都建不起来说明配置地址非法;心跳不是前台依赖,记日志继续跑。
		slog.Warn("心跳客户端初始化失败,前台读写继续", "error", err)
	} else {
		defer func() { _ = reporter.Close() }()
		reporter.Start(ctx)
	}

	// 优雅关闭
	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-done
		slog.Info("正在关闭 BE...")
		cancel()
		grpcServer.GracefulStop()
	}()

	if err := grpcServer.Serve(lis); err != nil {
		return fmt.Errorf("gRPC 服务异常: %w", err)
	}
	return nil
}

// loadConfig 解析配置文件。
//
//   - 显式指定了 --config:文件必须存在,读失败即启动失败;
//   - 未指定:尝试默认路径 config.DefaultPath,不存在则退回内置默认值(打一条警告)。
//
// 返回生效的配置与配置来源(用于启动日志,便于排查"到底读的哪份配置")。
func loadConfig(explicit string) (config.Config, string, error) {
	if explicit != "" {
		cfg, err := config.Load(explicit)
		if err != nil {
			return config.Config{}, "", fmt.Errorf("读取配置文件 %s 失败: %w", explicit, err)
		}
		return cfg, explicit, nil
	}

	cfg, err := config.Load(config.DefaultPath)
	if err == nil {
		return cfg, config.DefaultPath, nil
	}
	if errors.Is(err, os.ErrNotExist) {
		slog.Warn("未找到默认配置文件,使用内置默认值", "path", config.DefaultPath)
		return config.Default(), "(内置默认值)", nil
	}
	return config.Config{}, "", fmt.Errorf("读取配置文件 %s 失败: %w", config.DefaultPath, err)
}
