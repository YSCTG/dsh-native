#!/usr/bin/env python3
"""最小 MCP server（JSON-RPC over HTTP），用来验 DSHS 的 MCP 客户端。

只实现三件事：initialize / tools/list / tools/call。
工具：add（两数相加）、echo（原样返回）。

  python tools/mock_mcp.py 8901
模拟器里填 http://10.0.2.2:8901/mcp
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8901

TOOLS = [
    {
        "name": "add",
        "description": "把两个数字相加",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
            "required": ["a", "b"],
        },
    },
    {
        "name": "echo",
        "description": "原样返回传入的文本",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mcp] " + (fmt % args) + "\n")

    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._send({"ok": True, "server": "mock-mcp"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(raw)
        except Exception:
            return self._send({"jsonrpc": "2.0", "id": None,
                               "error": {"code": -32700, "message": "parse error"}})

        method = req.get("method")
        rid = req.get("id")

        if method == "initialize":
            return self._send({
                "jsonrpc": "2.0", "id": rid,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "mock-mcp", "version": "0.1"},
                },
            })

        if method == "notifications/initialized":
            return self._send({}, 202)

        if method == "tools/list":
            return self._send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})

        if method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "add":
                total = float(args.get("a", 0)) + float(args.get("b", 0))
                return self._send({"jsonrpc": "2.0", "id": rid, "result": {
                    "content": [{"type": "text", "text": str(total)}], "isError": False}})
            if name == "echo":
                return self._send({"jsonrpc": "2.0", "id": rid, "result": {
                    "content": [{"type": "text", "text": str(args.get("text", ""))}],
                    "isError": False}})
            return self._send({"jsonrpc": "2.0", "id": rid,
                               "error": {"code": -32602, "message": "unknown tool: " + str(name)}})

        self._send({"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32601, "message": "unknown method: " + str(method)}})


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    sys.stderr.write("[mcp] listening on http://127.0.0.1:%d/mcp\n" % PORT)
    srv.serve_forever()
