# -*- coding: utf-8 -*-
"""brain_client —— OpenAI 兼容对话客户端（stdlib urllib 版）。

架构 §3.2：BYOK 直连规划/VLM，模型无关（BrainClient 零改动解析标准 tool_calls）。
桌面版用 httpx；内核 v1 用 urllib（红线 R7：仅标准库）——语义对齐：chat(messages, tools)
→ {"content": str|None, "tool_calls": [{"name", "args"(dict)}]}。
"""
import json
import time
import urllib.error
import urllib.request


class BrainError(Exception):
    pass


# 网关抗抖动（2026-10-01 实测 discovery-api）：短请求 0.5-0.8s，但任务中段的长
# 上下文（23-33 msgs + 11 tools）请求出现 58s 长尾，一次超过 120s 未返回 —— 此时
# 整轮任务以 BrainError(TimeoutError) 报废，跑一次便签任务 15 步 / 247s 白烧。
# 对策：单次兜更长（DEFAULT_TIMEOUT）+ 对瞬时失败退避重试。仅重试传输层抖动与
# 服务端临时错误；4xx（参数/鉴权）是确定性失败，重试只会把错误拖成三倍耗时。
DEFAULT_TIMEOUT = 240
MAX_ATTEMPTS = 3
BACKOFF_S = (3, 8)
RETRYABLE_STATUS = frozenset({408, 409, 429, 500, 502, 503, 504})
RETRYABLE_ERRORS = frozenset({"TimeoutError", "timeout", "URLError", "socket.timeout",
                              "RemoteDisconnected", "ConnectionResetError",
                              "ConnectionAbortedError", "IncompleteRead",
                              "JSONDecodeError"})   # 截断/半个 JSON 同样值得再来一次


class BrainClient:
    def __init__(self, base_url, api_key, model, timeout=DEFAULT_TIMEOUT,
                 thinking=None, max_attempts=MAX_ATTEMPTS):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.max_attempts = max(1, int(max_attempts))
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
        }
        if tools:
            body["tools"] = [{"type": "function",
                              "function": {"name": t["name"],
                                           "description": t.get("description", ""),
                                           "parameters": t.get("parameters", {"type": "object"})}}
                             for t in tools]
        if self.thinking is not None:
            body["thinking"] = self.thinking
        req = urllib.request.Request(
            self.base_url + "/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + self.api_key},
            method="POST",
        )
        data = self._post_with_retry(req, len(messages), len(tools))
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

    def _post_with_retry(self, req, n_msgs, n_tools):
        """POST 一次并在瞬时失败时退避重试，返回已解析的响应 dict。

        可重试：传输层超时/断连与 408·409·429·5xx（服务端临时态）。
        不重试：其它 4xx（参数/鉴权错的确定性失败）与未知异常类型。
        每次 attempt 单独计时，做不到「总时长封顶」——网关持续不响应时，最坏
        ``max_attempts × timeout``；挑这两个值时按此乘法估算。
        """
        last_err = "unknown"
        for attempt in range(1, self.max_attempts + 1):
            print("[brain] POST %s (%d msgs, %d tools, timeout=%ds, attempt %d/%d)"
                  % (req.full_url, n_msgs, n_tools, self.timeout, attempt,
                     self.max_attempts), flush=True)
            retryable = False
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    raw = resp.read()
                print("[brain] response %s, %d bytes"
                      % (getattr(resp, "status", "?"), len(raw)), flush=True)
                return json.loads(raw.decode("utf-8"))
            except urllib.error.HTTPError as e:
                last_err = "HTTP %d: %s" % (
                    e.code, e.read().decode("utf-8", "ignore")[:200])
                retryable = e.code in RETRYABLE_STATUS
            except Exception as e:
                last_err = "%s: %s" % (type(e).__name__, e)
                retryable = type(e).__name__ in RETRYABLE_ERRORS
            if not retryable or attempt >= self.max_attempts:
                raise BrainError(last_err)
            delay = BACKOFF_S[attempt - 1] if attempt - 1 < len(BACKOFF_S) else BACKOFF_S[-1]
            print("[brain] attempt %d failed (%s) -> retry in %ds"
                  % (attempt, last_err[:120], delay), flush=True)
            time.sleep(delay)
        raise BrainError(last_err)   # 不可达：循环内已 raise 或用尽 attempts

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
