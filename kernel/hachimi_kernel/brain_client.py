# -*- coding: utf-8 -*-
"""brain_client —— OpenAI 兼容对话客户端（stdlib urllib 版）。

架构 §3.2：BYOK 直连规划/VLM，模型无关（BrainClient 零改动解析标准 tool_calls）。
桌面版用 httpx；内核 v1 用 urllib（红线 R7：仅标准库）——语义对齐：chat(messages, tools)
→ {"content": str|None, "tool_calls": [{"name", "args"(dict)}]}。
"""
import json
import urllib.error
import urllib.request


class BrainError(Exception):
    pass


class BrainClient:
    def __init__(self, base_url, api_key, model, timeout=60):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def chat(self, messages, tools):
        """messages: OpenAI 格式；tools: [{name, description, parameters}]。
        返回 {"content": str|None, "tool_calls": [{"name", "args"}]}。"""
        body = {
            "model": self.model,
            "messages": messages,
            "tools": [{"type": "function",
                       "function": {"name": t["name"],
                                    "description": t.get("description", ""),
                                    "parameters": t.get("parameters", {"type": "object"})}}
                      for t in tools],
        }
        url = self.base_url + "/chat/completions"
        print("[brain] POST %s (%d msgs, %d tools, timeout=%ds)"
              % (url, len(messages), len(tools), self.timeout), flush=True)
        req = urllib.request.Request(
            url,
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + self.api_key},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                print("[brain] response %s, %d bytes" % (getattr(resp, "status", "?"), len(raw)), flush=True)
                data = json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise BrainError("HTTP %d: %s" % (e.code, e.read().decode("utf-8", "ignore")[:200]))
        except Exception as e:
            raise BrainError("%s: %s" % (type(e).__name__, e))
        try:
            msg = data["choices"][0]["message"]
        except (KeyError, IndexError):
            raise BrainError("unexpected response shape: " + json.dumps(data)[:200])
        calls = []
        for tc in msg.get("tool_calls") or []:
            fn = tc.get("function", {})
            raw = fn.get("arguments") or "{}"
            try:
                args = json.loads(raw) if isinstance(raw, str) else dict(raw)
            except json.JSONDecodeError:
                args = {"_raw": raw}
            calls.append({"name": fn.get("name"), "args": args})
        return {"content": msg.get("content"), "tool_calls": calls}
