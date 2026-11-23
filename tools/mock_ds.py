#!/usr/bin/env python3
"""
DSHS 端到端验证用的假 DeepSeek 服务。

目的：在没有 API key 的情况下验证「流式解析 → tool_calls 拼装 → 工具执行 → 结果回灌 → 第二轮 → 账本」整条链路。

行为：
  - 第 1 轮（请求里没有 role=tool 的消息）→ 返回一个 fs_list 的 tool_call
  - 第 2 轮（请求里有 role=tool）→ 返回最终文本，不再调用工具
  - 每次都带 usage，且第二轮的 prompt_cache_hit_tokens 设得比较高，用来验证账本里的命中率显示

跑：
  python tools/mock_ds.py 8899
模拟器里把 base URL 指到 http://10.0.2.2:8899
"""

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8899

ROUNDS = {"n": 0}
LOCK = threading.Lock()


def sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode("utf-8")


def chunk(delta, finish=None, usage=None):
    obj = {
        "id": "mock-1",
        "object": "chat.completion.chunk",
        "model": "mock",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    if usage is not None:
        obj["usage"] = usage
    return obj


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + (fmt % args) + "\n")

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._json({"ok": True, "rounds": ROUNDS["n"]})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            self._json({"error": "not found"}, 404)
            return
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(raw)
        except Exception:
            self._json({"error": "bad json"}, 400)
            return

        msgs = req.get("messages", [])
        has_tool_result = any(m.get("role") == "tool" for m in msgs)
        tools = [t.get("function", {}).get("name") for t in req.get("tools", [])]

        with LOCK:
            ROUNDS["n"] += 1
            rnd = ROUNDS["n"]

        sys.stderr.write("[mock] round=%d tool_result=%s tools=%s\n"
                         % (rnd, has_tool_result, tools))

        # 带 tools 时历史 assistant 的 reasoning_content 必须原样回传，否则 400。
        # 检查必须在发 200 之前 —— 发完状态行就改不了了。
        if has_tool_result and not any(
                m.get("role") == "assistant" and m.get("reasoning_content")
                for m in msgs):
            body = json.dumps({"error": {"message":
                "reasoning_content must be passed back when tools are present"}}).encode()
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        # 必须 close：没有 Content-Length 也没有 chunked 时，客户端靠连接关闭判断结束
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        if not has_tool_result:
            # 第一轮：先流式吐一段 reasoning_content，再吐正文，然后发工具调用。
            # 按用户这句话选工具：提到屏幕/界面就走无障碍读界面，否则列目录。
            last_user = ""
            for m in msgs:
                if m.get("role") == "user":
                    last_user = m.get("content") or ""
            wants_ui = any(k in last_user for k in ("屏幕", "界面", "screen", "ui"))
            tool_name = "ui_dump" if wants_ui else "fs_list"
            tool_args = "{}" if wants_ui else "{\"path\":\".\"}"

            self.wfile.write(sse(chunk({"reasoning_content": "选一个工具"})))
            for piece in ["我先", "看一眼", "当前目录。" if not wants_ui else "当前界面。"]:
                self.wfile.write(sse(chunk({"content": piece})))
            self.wfile.flush()
            self.wfile.write(sse(chunk({
                "tool_calls": [{
                    "index": 0,
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {"name": tool_name, "arguments": tool_args},
                }]
            })))
            self.wfile.write(sse(chunk({}, finish="tool_calls", usage={
                "prompt_tokens": 1024,
                "prompt_cache_hit_tokens": 896,
                "prompt_cache_miss_tokens": 128,
                "completion_tokens": 42,
            })))
        else:
            for piece in ["目录", "读到了。", "（这是 mock 服务的回答）"]:
                self.wfile.write(sse(chunk({"content": piece})))
            self.wfile.flush()
            self.wfile.write(sse(chunk({}, finish="stop", usage={
                "prompt_tokens": 1280,
                "prompt_cache_hit_tokens": 1216,
                "prompt_cache_miss_tokens": 64,
                "completion_tokens": 31,
            })))

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        # 不显式关连接：让客户端读到 [DONE] 自己结束


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write("[mock] listening on http://127.0.0.1:%d\n" % PORT)
    srv.serve_forever()
