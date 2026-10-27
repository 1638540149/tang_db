// Package server 实现 tang_db BE 的 gRPC 服务。
package server

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"

	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const defaultBatchRows = 1024

// TangDbServer 实现 proto/tangdb.proto 中定义的 gRPC 服务。
type TangDbServer struct {
	pb.UnimplementedTangDbServer
	engine *store.Engine

	// 事务句柄:BE 只发号与释放,不保存写集(写集在 FE 内存,FR-319)。
	txnMu     sync.Mutex
	txns      map[uint64]uint64 // txn_id -> snapshot
	nextTxnID atomic.Uint64
}

// NewTangDbServer 创建 gRPC 服务实例。
func NewTangDbServer(engine *store.Engine) *TangDbServer {
	return &TangDbServer{
		engine: engine,
		txns:   make(map[uint64]uint64),
	}
}

// Ping 返回 BE 版本与数据目录,供客户端探活。
func (s *TangDbServer) Ping(ctx context.Context, _ *pb.PingRequest) (*pb.PingResponse, error) {
	return &pb.PingResponse{
		Version: s.engine.Version(),
		DataDir: s.engine.DataDir(),
	}, nil
}

// Put 写入键值。语义与 P1 保持不变:单 key 自动提交事务,返回 OK 即已 fsync。
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

// Get 读取用户键在快照下可见的版本值;不存在时返回 NOT_FOUND。
func (s *TangDbServer) Get(ctx context.Context, req *pb.GetRequest) (*pb.GetResponse, error) {
	if len(req.Key) == 0 {
		return &pb.GetResponse{
			Status:  pb.StatusCode_INVALID_ARGUMENT,
			Message: "key 不能为空",
		}, nil
	}

	value, ok, err := s.engine.GetSnapshot(req.Key, req.Snapshot)
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

// Scan 按**用户键**范围 [start_key, end_key) 有序流式返回;每个用户键只出现一次。
func (s *TangDbServer) Scan(req *pb.ScanRequest, stream pb.TangDb_ScanServer) error {
	batchRows := int(req.BatchRows)
	if batchRows <= 0 {
		batchRows = defaultBatchRows
	}
	limit := int(req.Limit)
	if len(req.EndKey) > 0 && len(req.StartKey) > 0 &&
		bytes.Compare(req.StartKey, req.EndKey) > 0 {
		return stream.Send(&pb.RowBatch{
			Status:  pb.StatusCode_INVALID_ARGUMENT,
			Message: "扫描起点大于终点",
		})
	}

	sc := s.engine.NewMVCCScanner(req.StartKey, req.EndKey, req.Snapshot, limit)
	defer sc.Close()
	batch := make([]*pb.Kv, 0, batchRows)

	send := func(status pb.StatusCode, message string) error {
		if err := stream.Send(&pb.RowBatch{Status: status, Message: message, Rows: batch}); err != nil {
			return err
		}
		return nil
	}

	for sc.Next() {
		if err := stream.Context().Err(); err != nil {
			return err
		}
		batch = append(batch, &pb.Kv{
			Key:   append([]byte(nil), sc.Key()...),
			Value: append([]byte(nil), sc.Value()...),
		})
		if len(batch) >= batchRows {
			if err := send(pb.StatusCode_OK, ""); err != nil {
				return err
			}
			batch = make([]*pb.Kv, 0, batchRows)
		}
	}

	if err := sc.Err(); err != nil {
		s.engine.RecordScanError()
		slog.Error("scan failed", "error", err)
		// 已经读到的行先发出去(它们本身是正确的),再明确报错,不让客户端误以为扫描正常结束。
		if len(batch) > 0 {
			if err := send(pb.StatusCode_OK, ""); err != nil {
				return err
			}
			batch = make([]*pb.Kv, 0, batchRows)
		}
		return send(pb.StatusCode_INTERNAL, "扫描失败: "+err.Error())
	}
	if len(batch) > 0 {
		return send(pb.StatusCode_OK, "")
	}
	return nil
}

// BeginTxn 发放事务句柄并返回当前已提交水位(SPEC-P3 FR-301)。
//
// 未提交的写集不在 BE:这里不做任何 I/O,只发号 + 读一次水位。
func (s *TangDbServer) BeginTxn(ctx context.Context, req *pb.BeginTxnRequest) (*pb.BeginTxnResponse, error) {
	// G-3 / INV-7:登记活跃快照,compaction 的 GC 水位不会越过它。
	snapshot := s.engine.BeginSnapshot()
	txnID := s.nextTxnID.Add(1)
	s.txnMu.Lock()
	s.txns[txnID] = snapshot
	s.txnMu.Unlock()
	// read_only 本期只是提示(K47),不影响发号或快照。
	return &pb.BeginTxnResponse{
		Status:   pb.StatusCode_OK,
		Message:  "OK",
		TxnId:    txnID,
		Snapshot: snapshot,
	}, nil
}

// CommitTxn 执行"冲突检测 → 原子批写 → fsync → memtable"(SPEC-P3 FR-302 / FR-312)。
func (s *TangDbServer) CommitTxn(ctx context.Context, req *pb.CommitTxnRequest) (*pb.CommitTxnResponse, error) {
	if req.TxnId != 0 {
		s.txnMu.Lock()
		_, known := s.txns[req.TxnId]
		s.txnMu.Unlock()
		if !known {
			return &pb.CommitTxnResponse{
				Status:  pb.StatusCode_INVALID_ARGUMENT,
				Message: "事务句柄无效或已结束,请重新 BEGIN",
			}, nil
		}
	}

	writes := make([]store.Write, 0, len(req.Writes))
	for _, kv := range req.Writes {
		if len(kv.Key) == 0 {
			return &pb.CommitTxnResponse{
				Status:  pb.StatusCode_INVALID_ARGUMENT,
				Message: "写集里有空的 key",
			}, nil
		}
		writes = append(writes, store.Write{
			Key:   kv.Key,
			Value: kv.Value,
		})
	}

	commitTS, conflictKey, err := s.engine.Commit(req.Snapshot, req.TxnId, writes)
	if err != nil {
		slog.Error("commit failed", "error", err)
		if errors.Is(err, store.ErrTxnTooLarge) || errors.Is(err, store.ErrTxnTooManyWrite) {
			return &pb.CommitTxnResponse{
				Status:  pb.StatusCode_INVALID_ARGUMENT,
				Message: err.Error(),
			}, nil
		}
		return &pb.CommitTxnResponse{
			Status:  pb.StatusCode_INTERNAL,
			Message: err.Error(),
		}, nil
	}
	if conflictKey != nil {
		// 只回 key 的原始字节;翻译成"表/主键"是 FE 的事(FR-307 / FR-333)。
		return &pb.CommitTxnResponse{
			Status:      pb.StatusCode_CONFLICT,
			ConflictKey: conflictKey,
			Message:     "写写冲突",
		}, nil
	}

	if req.TxnId != 0 {
		s.txnMu.Lock()
		snapshot, known := s.txns[req.TxnId]
		delete(s.txns, req.TxnId)
		s.txnMu.Unlock()
		if known {
			s.engine.ReleaseSnapshot(snapshot)
		}
	}
	return &pb.CommitTxnResponse{
		Status:   pb.StatusCode_OK,
		CommitTs: commitTS,
		Message:  "OK",
	}, nil
}

// AbortTxn 只释放 BE 侧句柄,不产生任何 I/O(SPEC-P3 FR-303)。
func (s *TangDbServer) AbortTxn(ctx context.Context, req *pb.AbortTxnRequest) (*pb.AbortTxnResponse, error) {
	if req.TxnId != 0 {
		s.txnMu.Lock()
		snapshot, known := s.txns[req.TxnId]
		delete(s.txns, req.TxnId)
		s.txnMu.Unlock()
		if known {
			s.engine.ReleaseSnapshot(snapshot)
		}
	}
	return &pb.AbortTxnResponse{Status: pb.StatusCode_OK, Message: "OK"}, nil
}

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
