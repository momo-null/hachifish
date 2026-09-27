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
    def __init__(self, base_url, api_key, model, timeout=60, thinking=None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        # DeepSeek V4 系 thinking 模式的自适应开启不可预测：历史无 reasoning_content
        # 的 assistant 消息会在思考轮被 API 拒绝（400 must be passed back）。
        # GUI 任务循环默认 thinking disabled（确定性 + 低时延）；需思考时显式配置，
        # 并依赖 chat() 的 reasoning_content 回传（_wire_messages）。
        self.thinking = thinking

    def chat(self, messages, tools):
        """messages: 内核内部格式（OpenAI 兼容的子集：assistant.tool_calls = [{name, args}]，
        tool 消息带 name）；tools: [{name, description, parameters}]。
        返回 {"content": str|None, "tool_calls": [{"name", "args"}]}。"""
        body = {
            "model": self.model,
            "messages": self._wire_messages(messages),
            "tools": [{"type": "function",
                       "function": {"name": t["name"],
                                    "description": t.get("description", ""),
                                    "parameters": t.get("parameters", {"type": "object"})}}
                      for t in tools],
        }
        if self.thinking is not None:
            body["thinking"] = self.thinking
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
        return {"content": msg.get("content"), "tool_calls": calls,
                "reasoning_content": msg.get("reasoning_content")}

    @staticmethod
    def _wire_messages(messages):
        """内核内部消息 → OpenAI wire 格式。assistant.tool_calls 补 id/type/function 包装，
        tool 消息按名字配对补 tool_call_id（DeepSeek/严格 OpenAI 兼容端会校验，
        缺失报 422 missing field `id`）。"""
        out = []
        pending = []  # (tool_call_id, name) 等待对应 tool 结果
        for i, m in enumerate(messages):
            role = m.get("role")
            if role == "assistant" and m.get("tool_calls"):
                calls = []
                for c in m["tool_calls"]:
                    cid = "call_%d_%d" % (i, len(calls))
                    pending.append((cid, c.get("name", "")))
                    calls.append({"id": cid, "type": "function",
                                  "function": {"name": c.get("name", ""),
                                               "arguments": json.dumps(c.get("args") or {},
                                                                       ensure_ascii=False)}})
                out.append({"role": "assistant", "content": m.get("content"),
                            "tool_calls": calls})
                if m.get("reasoning_content"):
                    out[-1]["reasoning_content"] = m["reasoning_content"]
            elif role == "tool":
                cid = ""
                for j, (pid, pname) in enumerate(pending):
                    if pname == m.get("name"):
                        cid = pending.pop(j)[0]
                        break
                if not cid and pending:
                    cid = pending.pop(0)[0]
                out.append({"role": "tool", "tool_call_id": cid,
                            "content": m.get("content", "")})
            else:
                out.append(dict(m))
        return out
