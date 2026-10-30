#!/usr/bin/env python3
# tang_db P7 AI 假端点(只用于验收脚本,不进生产路径)。
#
# 协议(与 FE 的 AiService 一致):
#   POST /embed  {"model":"m","texts":["a","b"]}
#   ← 200 {"model_version":"p7-stub-v1","dim":N,"vectors":[[...],...]}
#
# 环境变量:
#   AI_STUB_PORT   监听端口(默认 9999)
#   AI_STUB_DIM    返回维度(默认读 /dim 文件,见下)
#   AI_STUB_MODE   ok | bad_dim | fail500 | slow
#
# 计数:GET /stats → {"calls":N,"texts":M} 便于脚本核对"调用次数 ≤ ceil(行数/1024)"。

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("AI_STUB_PORT", "9999"))
DIM = int(os.environ.get("AI_STUB_DIM", "0"))
MODE = os.environ.get("AI_STUB_MODE", "ok")
MODEL_VERSION = "p7-stub-v1"

_lock = threading.Lock()
calls = 0
texts_sent = 0


def vector_for(text, dim):
    base = abs(hash(text)) % 97
    return [round((base + i) / 100.0, 6) for i in range(dim)]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音
        pass

    def _send(self, code, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        global MODE
        if self.path.startswith("/mode"):
            from urllib.parse import urlparse, parse_qs
            query = parse_qs(urlparse(self.path).query)
            MODE = (query.get("m") or [MODE])[0]
            self._send(200, {"mode": MODE})
            return
        if self.path.startswith("/stats"):
            with _lock:
                self._send(200, {"calls": calls, "texts": texts_sent, "mode": MODE})
            return
        if self.path.startswith("/health"):
            self._send(200, {"ok": True})
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):
        global calls, texts_sent
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            request = json.loads(raw.decode("utf-8"))
        except Exception:
            self._send(400, {"error": "bad json"})
            return
        texts = request.get("texts") or []
        with _lock:
            calls += 1
            texts_sent += len(texts)
        if MODE == "fail500":
            self._send(500, {"error": "stub failure"})
            return
        if MODE == "slow":
            time.sleep(30)
        dim = DIM if DIM > 0 else int(os.environ.get("AI_STUB_DIM", "4") or 4)
        if MODE == "bad_dim":
            dim = max(1, dim - 1)
        vectors = [vector_for(str(t), dim) for t in texts]
        self._send(200, {"model_version": MODEL_VERSION, "dim": dim, "vectors": vectors})


def main():
    global DIM
    if len(sys.argv) > 1:
        DIM = int(sys.argv[1])
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write("ai-stub listening on %d dim=%d mode=%s\n" % (PORT, DIM, MODE))
    sys.stderr.flush()
    server.serve_forever()


if __name__ == "__main__":
    main()
