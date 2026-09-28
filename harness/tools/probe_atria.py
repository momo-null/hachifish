# -*- coding: utf-8 -*-
"""Atria 兼容性探测（换端前必跑的一次性检查）。

判定 bridge.py:135 恒发的 thinking={"type":"disabled"} 会不会被新端 400：
  A  GET  /v1/models                        鉴权 + model 名核对
  B  POST 纯 messages（无 tools / 无 thinking） 基线延迟
  C  POST + tools                           工具面是否被接受
  D  POST + tools + thinking + tool 历史      完全复刻 bridge 发出的 body
  E  BrainClient.chat() 真客户端跑一遍（含 _wire_messages 拼装，最贴近线上）

配置来源：环境变量 HACHIMI_BRAIN_* > harness/config.local.json（*.local.json 已 gitignore）。
密钥不写进代码。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "harness"))
sys.path.insert(0, os.path.join(ROOT, "kernel"))
from _env import brain_config  # noqa: E402

TIMEOUT = 180  # 探测阶段放宽，目的是量出真实延迟而非被 60s 截断

TOOLS = [
    {"type": "function", "function": {
        "name": "observe", "description": "观察当前屏幕 UI 树",
        "parameters": {"type": "object", "properties": {"compact": {"type": "boolean"}}}}},
    {"type": "function", "function": {
        "name": "launch_app", "description": "启动应用",
        "parameters": {"type": "object", "properties": {"package": {"type": "string"}},
                       "required": ["package"]}}},
    {"type": "function", "function": {
        "name": "task_done", "description": "完成任务",
        "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}}}},
]

SIMPLE = [
    {"role": "system", "content": "你通过调用工具完成任务。每轮调用一个工具。"},
    {"role": "user", "content": "目标: 启动 org.fossify.notes 然后 task_done"},
]

# 第二回合历史：assistant.tool_calls + role:tool（严格端会校验 tool_call_id 配对）
HISTORY = SIMPLE + [
    {"role": "assistant", "content": None, "tool_calls": [
        {"id": "call_1_0", "type": "function",
         "function": {"name": "launch_app",
                      "arguments": json.dumps({"package": "org.fossify.notes"})}}]},
    {"role": "tool", "tool_call_id": "call_1_0", "content": '{"ok": true}'},
]


def post(url, body, api_key):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key},
        method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8", "ignore")
            return getattr(resp, "status", 200), time.time() - t0, raw
    except urllib.error.HTTPError as e:
        return e.code, time.time() - t0, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return -1, time.time() - t0, "%s: %s" % (type(e).__name__, e)


def head(url, api_key):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + api_key})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return getattr(resp, "status", 200), time.time() - t0, resp.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, time.time() - t0, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return -1, time.time() - t0, "%s: %s" % (type(e).__name__, e)


def brief(msg):
    """把 assistant message 压成一行摘要：tool_calls 名 + content 前 60 字。"""
    try:
        calls = [t["function"]["name"] for t in msg.get("tool_calls") or []]
        return "tool_calls=%s content=%r" % (calls, (msg.get("content") or "")[:60])
    except Exception:
        return json.dumps(msg, ensure_ascii=False)[:160]


def report(tag, status, dt, raw):
    line = "%-28s HTTP %-4s %6.1fs" % (tag, status, dt)
    try:
        data = json.loads(raw)
        if "data" in data and isinstance(data["data"], list):  # /models
            ids = [d.get("id") for d in data["data"]]
            line += "  models=%s" % ids[:12]
        else:
            msg = data["choices"][0]["message"]
            line += "  " + brief(msg)
    except Exception:
        line += "  RAW " + raw[:200]
    print(line, flush=True)
    return status, dt


def main():
    cfg = brain_config()
    base = (cfg.get("base_url") or "").rstrip("/")
    key = cfg.get("api_key") or ""
    model = cfg.get("model") or ""
    if not (base and key and model):
        print("no brain config: %s" % cfg)
        return 2
    print("endpoint=%s model=%s" % (base, model), flush=True)

    results = {}
    # A: 鉴权 + 模型名
    s, dt, raw = head(base + "/models", key)
    results["A_models"] = report("A GET /models", s, dt, raw)
    if s == 200:
        try:
            ids = [d.get("id") for d in json.loads(raw).get("data", [])]
            if model not in ids:
                print("  !! model %r not in /models list" % model, flush=True)
        except Exception:
            pass

    # B: 纯 messages
    s, dt, raw = post(base + "/chat/completions",
                      {"model": model, "messages": SIMPLE}, key)
    results["B_plain"] = report("B plain messages", s, dt, raw)

    # C: + tools
    s, dt, raw = post(base + "/chat/completions",
                      {"model": model, "messages": SIMPLE, "tools": TOOLS}, key)
    results["C_tools"] = report("C +tools", s, dt, raw)

    # D: + tools + thinking + tool 历史（复刻 bridge body）
    s, dt, raw = post(base + "/chat/completions",
                      {"model": model, "messages": HISTORY, "tools": TOOLS,
                       "thinking": {"type": "disabled"}}, key)
    results["D_thinking"] = report("D +tools+thinking+history", s, dt, raw)

    # E: 真客户端（内核内部消息格式 → _wire_messages）
    try:
        from hachimi_kernel.brain_client import BrainClient, BrainError
        cli = BrainClient(base, key, model, timeout=TIMEOUT,
                          thinking={"type": "disabled"})
        inner = [
            {"role": "system", "content": "你通过调用工具完成任务。每轮调用一个工具。"},
            {"role": "user", "content": "目标: 启动 org.fossify.notes 然后 task_done"},
            {"role": "assistant", "content": None,
             "tool_calls": [{"name": "launch_app",
                             "args": {"package": "org.fossify.notes"}}]},
            {"role": "tool", "name": "launch_app", "content": '{"ok": true}'},
        ]
        kt = [{"name": "observe", "description": "观察当前屏幕 UI 树",
               "parameters": {"type": "object", "properties": {"compact": {"type": "boolean"}}}},
              {"name": "launch_app", "description": "启动应用",
               "parameters": {"type": "object", "properties": {"package": {"type": "string"}},
                              "required": ["package"]}},
              {"name": "task_done", "description": "完成任务",
               "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}}}]
        t0 = time.time()
        try:
            out = cli.chat(inner, kt)
            dt = time.time() - t0
            print("%-28s HTTP %-4s %6.1fs  tool_calls=%s content=%r"
                  % ("E BrainClient.chat", 200, dt,
                     [c["name"] for c in out["tool_calls"]],
                     (out.get("content") or "")[:60]), flush=True)
            results["E_client"] = (200, dt)
        except BrainError as e:
            dt = time.time() - t0
            print("%-28s FAIL    %6.1fs  %s" % ("E BrainClient.chat", dt, str(e)[:180]),
                  flush=True)
            results["E_client"] = (-1, dt)
    except Exception as e:
        print("E BrainClient import/use failed: %s: %s" % (type(e).__name__, e), flush=True)

    print("\n--- verdict ---", flush=True)
    b, c, d = results.get("B_plain", (-1, 0)), results.get("C_tools", (-1, 0)), \
        results.get("D_thinking", (-1, 0))
    if d[0] == 200 and c[0] == 200:
        print("thinking 字段被接受 → 可零改动切端（set_brain 直接切）", flush=True)
    elif d[0] != 200 and c[0] == 200:
        print("thinking 导致 %s → 必须改 bridge.py:135（thinking 默认 None）" % d[0], flush=True)
    elif c[0] != 200:
        print("tools 就被拒（HTTP %s）→ 换端不可行，需另找方案" % c[0], flush=True)
    lat = [v[1] for k, v in results.items() if v[0] == 200 and k != "A_models"]
    if lat:
        print("可用请求延迟 min=%.1fs max=%.1fs（timeout 现为 60s）" % (min(lat), max(lat)),
              flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
