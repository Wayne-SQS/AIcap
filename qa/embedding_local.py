#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
本地 OpenAI 兼容 /embeddings 服务(**真模型版**)。

与 `qa/embedding_fixture.py` 同协议、可互相替换,区别只在向量怎么算:

| 文件 | 向量来源 | 依赖 | 用途 |
|---|---|---|---|
| `qa/embedding_fixture.py` | 字符/二元组哈希(dim 64) | 零 | 只验链路:召回排序有意义,语义不可用 |
| 本文件 | 本地 ONNX 模型 | fastembed | 演示与真实检索,不出网、不耗额度 |

为什么需要它(而不是直接配一个外部供应商):RAG 的 embedding 在本项目是**硬依赖**
—— `KnowledgeIndexer#rebuild` 与 `RetrievalService#retrieve` 各自在
`EmbeddingProperties#settingsReady()` 为 false 时直接短路(前者一行都不写,
后者三路一条不跑)。外部供应商要 key、要额度、要网络;本机跑模型则这些全不需要,
而 Java 侧**一行代码都不用改** —— 它本来就是按 OpenAI 兼容 HTTP 协议调的。

用法:
    pip install fastembed
    python qa/embedding_local.py --port 19380 --model BAAI/bge-small-zh-v1.5

端口别用 19378/19379:那两个是 Java 契约测试夹具的口
(`EmbeddingFixture`=19378、`RerankModelFixture`=19379),占着它们会让 `mvn test` 红。

然后启动后端时指向它(Java 侧零改动):
    AICAP_EMBEDDING_BASE_URL=http://127.0.0.1:19380/v1
    AICAP_EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5
    AICAP_EMBEDDING_API_KEY=local
    AICAP_EMBEDDING_DIMENSION=512          # bge-small-zh-v1.5 的输出维度

踩坑记录:
- 模型文件由 fastembed 在**首次调用**时下载并缓存到本机,不是 pip 装完就有;
  国内若下载不动,设 `HF_ENDPOINT=https://hf-mirror.com` 再试,或换 `--model`。
- 换模型时 `AICAP_EMBEDDING_DIMENSION` 必须跟着改:写入向量库前有维度校验,
  维度不符会直接失败(块上记了 `embedding_model`,换模型后旧块会自动判为过期重算,
  但维度是建集合时定的,不会自动跟)。
"""

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def load_model(model_name):
    """加载本地模型。故意放在请求之外:首个请求才下载会把首次索引拖到超时。"""
    from fastembed import TextEmbedding
    return TextEmbedding(model_name=model_name)


class Handler(BaseHTTPRequestHandler):
    model = None
    model_name = ""
    lock = threading.Lock()

    def do_POST(self):  # noqa: N802 (BaseHTTPRequestHandler 的命名约定)
        if not self.path.rstrip("/").endswith("/embeddings"):
            self._send(404, {"error": {"message": "not found"}})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, TypeError):
            self._send(400, {"error": {"message": "invalid JSON body"}})
            return
        raw = payload.get("input")
        texts = [str(t) for t in raw] if isinstance(raw, list) else [str(raw)]
        if not texts or any(not t.strip() for t in texts):
            self._send(400, {"error": {"message": "input must be a non-empty string or list"}})
            return
        try:
            # fastembed 的 embed() 是生成器且非线程安全:批量请求并发打进来会交错
            with self.lock:
                vectors = [list(map(float, v)) for v in self.model.embed(texts)]
        except Exception as exc:
            self._send(500, {"error": {"message": "embedding failed: " + type(exc).__name__}})
            return
        self._send(200, {
            "object": "list",
            "data": [{"object": "embedding", "index": i, "embedding": v}
                     for i, v in enumerate(vectors)],
            "model": self.model_name,
            "usage": {"prompt_tokens": sum(len(t) for t in texts), "total_tokens": sum(len(t) for t in texts)},
        })

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/").endswith("/health"):
            self._send(200, {"status": "ok", "model": self.model_name})
        else:
            self._send(404, {"error": {"message": "not found"}})

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # 不打请求体:会议与需求原文属于业务数据,不该落进本地日志
        sys.stderr.write("embedding-local %s - %s\n" % (self.address_string(), fmt % args[:1]))


def main():
    parser = argparse.ArgumentParser()
    # 19378/19379 被 Java 契约测试夹具占用,默认避开;见文件头注释
    parser.add_argument("--port", type=int, default=19380)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--model", default="BAAI/bge-small-zh-v1.5")
    args = parser.parse_args()

    print("loading %s (first run downloads the model)..." % args.model, flush=True)
    Handler.model = load_model(args.model)
    Handler.model_name = args.model
    print("ready on http://%s:%d/v1/embeddings" % (args.host, args.port), flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
