// Package server 实现 tang_db BE 的 gRPC 服务。
package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// TangDbServer 实现 proto/tangdb.proto 中定义的 gRPC 服务。
type TangDbServer struct {
	pb.UnimplementedTangDbServer
	engine *store.Engine
}

// NewTangDbServer 创建 gRPC 服务实例。
func NewTangDbServer(engine *store.Engine) *TangDbServer {
	return &TangDbServer{engine: engine}
}

// Ping 返回 BE 版本与数据目录,供客户端探活。
func (s *TangDbServer) Ping(ctx context.Context, _ *pb.PingRequest) (*pb.PingResponse, error) {
	return &pb.PingResponse{
		Version: s.engine.Version(),
		DataDir: s.engine.DataDir(),
	}, nil
}

// Put 写入键值。engine.Put 内部保证 WAL fsync 后才返回,因此这里返回 OK 即表示已落盘。
func (s *TangDbServer) Put(ctx context.Context, req *pb.PutRequest) (*pb.PutResponse, error) {
	if len(req.Key) == 0 {
		return &pb.PutResponse{
			Status:  pb.StatusCode_INVALID_ARGUMENT,
			Message: "key 不能为空",
		}, nil
	}
	if len(req.Value) > 1<<20 {
		return &pb.PutResponse{
			Status:  pb.StatusCode_INVALID_ARGUMENT,
			Message: fmt.Sprintf("value 超过 1MB 上限: %d", len(req.Value)),
		}, nil
	}

	if err := s.engine.Put(req.Key, req.Value); err != nil {
		slog.Error("put failed", "error", err)
		return &pb.PutResponse{
			Status:  pb.StatusCode_INTERNAL,
			Message: err.Error(),
		}, nil
	}
	return &pb.PutResponse{Status: pb.StatusCode_OK, Message: "OK"}, nil
}

// Get 读取键值;不存在时返回 NOT_FOUND。
func (s *TangDbServer) Get(ctx context.Context, req *pb.GetRequest) (*pb.GetResponse, error) {
	if len(req.Key) == 0 {
		return &pb.GetResponse{
			Status:  pb.StatusCode_INVALID_ARGUMENT,
			Message: "key 不能为空",
		}, nil
	}

	value, ok, err := s.engine.Get(req.Key)
	if err != nil {
		slog.Error("get failed", "error", err)
		return &pb.GetResponse{
			Status:  pb.StatusCode_INTERNAL,
			Message: err.Error(),
		}, nil
	}
	if !ok {
		return &pb.GetResponse{
			Status:  pb.StatusCode_NOT_FOUND,
			Message: "键不存在",
		}, nil
	}
	return &pb.GetResponse{
		Status: pb.StatusCode_OK,
		Value:  value,
	}, nil
}

// mustEmbedUnimplementedTangDbServer 由生成的代码提供,这里不需要额外实现。

// grpcError 将内部错误转换为 gRPC 状态错误(非业务错误时使用)。
func grpcError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.Canceled) {
		return status.Error(codes.Canceled, "请求已取消")
	}
	return status.Error(codes.Internal, err.Error())
}
