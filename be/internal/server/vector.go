package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/1638540149/tang_db/be/internal/server/pb"
	"github.com/1638540149/tang_db/be/internal/store"
)

// SPEC-P7 B/C 组:向量索引的 gRPC 入口。
//
// 边界:请求里只有"行键范围 / 列槽表 / 行框架 / 维度 / 度量操作码 / float32 字节",
// 服务端不解释表名、列名、类型名(INV-16 / K161)。

// RegisterVectorIndex 注册定义并让后台为缺失的 segment 建图(FR-748)。
func (s *TangDbServer) RegisterVectorIndex(ctx context.Context,
	req *pb.RegisterVectorIndexRequest) (*pb.RegisterVectorIndexResponse, error) {
	def := req.GetDefinition()
	if def == nil {
		return &pb.RegisterVectorIndexResponse{
			Status: pb.StatusCode_INVALID_ARGUMENT, Message: "缺少 definition",
		}, nil
	}
	definition := store.VecDefinition{
		IndexID:        def.GetIndexId(),
		TableID:        def.GetTableId(),
		StartKey:       def.GetStartKey(),
		EndKey:         def.GetEndKey(),
		ColumnSlots:    def.GetColumnSlots(),
		RowFrame:       def.GetRowFrame(),
		VectorOrdinal:  int(def.GetVectorOrdinal()),
		Dim:            int(def.GetDim()),
		Metric:         uint8(def.GetMetric()),
		M:              int(def.GetM()),
		EfConstruction: int(def.GetEfConstruction()),
	}
	if err := s.engine.RegisterVectorIndex(definition); err != nil {
		slog.Error("register vector index failed", "index", def.GetIndexId(), "error", err)
		return &pb.RegisterVectorIndexResponse{
			Status: pb.StatusCode_INTERNAL, Message: err.Error(),
		}, nil
	}
	status := s.engine.VectorIndexStatus(def.GetIndexId())
	return &pb.RegisterVectorIndexResponse{
		Status:          pb.StatusCode_OK,
		Message:         "OK",
		PendingSegments: uint32(status.PendingSegments),
	}, nil
}

// DropVectorIndex 删除该索引的全部图文件与 MANIFEST 记录(FR-737)。
func (s *TangDbServer) DropVectorIndex(ctx context.Context,
	req *pb.DropVectorIndexRequest) (*pb.DropVectorIndexResponse, error) {
	if err := s.engine.DropVectorIndex(req.GetIndexId()); err != nil {
		return &pb.DropVectorIndexResponse{
			Status: pb.StatusCode_INTERNAL, Message: err.Error(),
		}, nil
	}
	return &pb.DropVectorIndexResponse{Status: pb.StatusCode_OK, Message: "OK"}, nil
}

// SearchVectorIndex 每段 top-ef → 归并 → MVCC 过滤(FR-754 / FR-757)。
//
// SPEC-P7.1:请求带 profile 时返回五段耗时(默认关闭);带 allow/allow_bloom 时
// 把允许集下推到图检索的收集端(K182 / FR-805)。
func (s *TangDbServer) SearchVectorIndex(ctx context.Context,
	req *pb.SearchVectorIndexRequest) (*pb.SearchVectorIndexResponse, error) {
	hits, examined, timings, err := s.engine.SearchVectorIndex(req.GetIndexId(),
		req.GetQuery(), int(req.GetEf()), int(req.GetK()), req.GetSnapshot(),
		req.GetProfile(), req.GetAllow(), req.GetAllowBloom())
	if err != nil {
		if errors.Is(err, store.ErrNoVectorIndex) {
			return &pb.SearchVectorIndexResponse{
				Status: pb.StatusCode_NOT_FOUND, Message: "向量索引未注册",
			}, nil
		}
		if errors.Is(err, store.ErrNoVectorIndexDefinition) {
			return &pb.SearchVectorIndexResponse{
				Status: pb.StatusCode_NOT_FOUND, Message: "向量索引定义缺失",
			}, nil
		}
		slog.Error("search vector index failed", "index", req.GetIndexId(), "error", err)
		return &pb.SearchVectorIndexResponse{
			Status: pb.StatusCode_INTERNAL, Message: err.Error(),
		}, nil
	}
	out := make([]*pb.VectorCandidate, 0, len(hits))
	for _, hit := range hits {
		out = append(out, &pb.VectorCandidate{
			Key:        hit.Key,
			Distance:   hit.Distance,
			RowOrdinal: hit.Ordinal,
		})
	}
	status := s.engine.VectorIndexStatus(req.GetIndexId())
	profile := ""
	if timings != nil {
		profile = timings.String()
		// 边界 161:打点日志限流(1 秒最多一条),避免并发 8 线程时日志成为瓶颈。
		s.logVecProfile(req.GetIndexId(), profile)
	}
	return &pb.SearchVectorIndexResponse{
		Status:             pb.StatusCode_OK,
		Candidates:         out,
		CandidatesExamined: uint32(examined),
		PendingSegments:    uint32(status.PendingSegments),
		Profile:            profile,
	}, nil
}

// 打点日志限流:距上次打印不足 1 秒就丢弃(返回值里仍带完整耗时,EXPLAIN 看得到)。
var vecProfileLogAt atomic.Int64

func (s *TangDbServer) logVecProfile(indexID uint64, profile string) {
	now := time.Now().UnixMilli()
	last := vecProfileLogAt.Load()
	if now-last < 1000 || !vecProfileLogAt.CompareAndSwap(last, now) {
		return
	}
	slog.Info("向量检索分段耗时", "index", indexID, "profile", profile)
}

// VectorIndexStatus 段级状态(SHOW VECTOR INDEXES / information_schema)。
func (s *TangDbServer) VectorIndexStatus(ctx context.Context,
	req *pb.VectorIndexStatusRequest) (*pb.VectorIndexStatusResponse, error) {
	status := s.engine.VectorIndexStatus(req.GetIndexId())
	return &pb.VectorIndexStatusResponse{
		Status:          pb.StatusCode_OK,
		SegmentCount:    uint32(status.SegmentCount),
		ReadySegments:   uint32(status.ReadySegments),
		PendingSegments: uint32(status.PendingSegments),
		NodeCount:       status.NodeCount,
		GraphBytes:      status.GraphBytes,
		Metric:          uint32(status.Metric),
		Dim:             uint32(status.Dim),
	}, nil
}

// BatchGet 批量点查(ANN 候选回表):不存在的键直接跳过,不做任何语义解释。
//
// SPEC-P7.1 K180:一次 RPC 取全部候选;profile 打开时返回 BE 侧的候选回表分段耗时。
func (s *TangDbServer) BatchGet(ctx context.Context,
	req *pb.BatchGetRequest) (*pb.BatchGetResponse, error) {
	rows := make([]*pb.Kv, 0, len(req.GetKeys()))
	started := time.Now()
	for _, key := range req.GetKeys() {
		if len(key) == 0 {
			continue
		}
		value, ok, err := s.engine.GetSnapshot(key, req.GetSnapshot())
		if err != nil {
			slog.Error("batch get failed", "error", err)
			return &pb.BatchGetResponse{
				Status: pb.StatusCode_INTERNAL, Message: err.Error(),
			}, nil
		}
		if !ok {
			continue
		}
		rows = append(rows, &pb.Kv{Key: key, Value: value})
	}
	profile := ""
	if req.GetProfile() {
		profile = fmt.Sprintf("fetchback=%.2fms keys=%d hits=%d",
			float64(time.Since(started).Microseconds())/1000.0, len(req.GetKeys()), len(rows))
		s.logVecProfile(0, profile+" (batch_get)")
	}
	return &pb.BatchGetResponse{Status: pb.StatusCode_OK, Rows: rows, Profile: profile}, nil
}
