#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
本地 OpenAI 兼容 /embeddings 桩服务(开发/QA 用,不参与生产)。

用途:在没有 embedding 密钥(或不想消耗额度/不想出网)时,让 RAG 索引与检索
整条链路可以真实跑通。向量由文本字符与二元组确定性生成 —— 共享用词的文本
余弦相似度更高,所以召回排序是有意义的,不是随机噪声。

用法:
    python qa/embedding_fixture.py --port 19379 --dim 64

然后启动后端时指向它:
    AICAP_EMBEDDING_BASE_URL=http://127.0.0.1:19379
    AICAP_EMBEDDING_MODEL=local-fixture
    AICAP_EMBEDDING_API_KEY=local
    AICAP_EMBEDDING_DIMENSION=64

注意:它与 Java 侧测试替身 qa/../EmbeddingFixture.java 生成完全相同的向量
(同样的 ord 哈希与 floorMod),因此两边的检索结果可互相印证。
"""

import argparse
import json
import math
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def vector_of(text, dim):
    """字符 + 二元组哈希装桶后 L2 归一化;与 Java 测试替身算法一致。"""
    v = [0.0] * dim
    for i, ch in enumerate(text):
        v[(ord(ch) * 31) % dim] += 1.0
        if i + 1 < len(text):
            v[(ord(ch) * 31 + ord(text[i + 1])) % dim] += 1.0
    norm = math.sqrt(sum(x * x for x in v))
    if norm > 0:
        v = [x / norm for x in v]
    return v


class Handler(BaseHTTPRequestHandler):
    dim = 64
    embedded = 0

    def do_POST(self):  # noqa: N802 (BaseHTTPRequestHandler 的命名约定)
        if not self.path.rstrip("/").endswith("/embeddings"):
            self._send(404, {"error": {"message": "not found"}})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            texts = body.get("input")
            if not isinstance(texts, list):
                self._send(400, {"error": {"message": "input must be an array"}})
                return
            data = [
                {"object": "embedding", "index": i, "embedding": vector_of(str(t), self.dim)}
                for i, t in enumerate(texts)
            ]
            Handler.embedded += len(data)
            self._send(200, {
                "object": "list",
                "data": data,
                "model": body.get("model", "local-fixture"),
                "usage": {"prompt_tokens": 0, "total_tokens": 0},
            })
        except Exception as exc:  # noqa: BLE001 — 桩服务,任何异常都回 400 足够
            self._send(400, {"error": {"message": str(exc)}})

    def _send(self, status, payload):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt, *args):
        # 默认日志每个请求两行,索引时太吵;只保留错误
        if not str(args[1] if len(args) > 1 else "").startswith("2"):
            print("[embedding-fixture] " + fmt % args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19379)
    parser.add_argument("--dim", type=int, default=64)
    args = parser.parse_args()
    Handler.dim = args.dim

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[embedding-fixture] http://127.0.0.1:{args.port}/embeddings  dim={args.dim}")
    print("[embedding-fixture] Ctrl+C 停止")
    server.serve_forever()


if __name__ == "__main__":
    main()
