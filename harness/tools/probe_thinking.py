# -*- coding: utf-8 -*-
"""Test 1: history WITHOUT reasoning_content -> expect 400 (confirms rule).
Test 2: thinking disabled param -> 200, no reasoning, deterministic."""
import json
import sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "kernel"))
from hachimi_kernel.brain_client import BrainClient, BrainError

cli = BrainClient("https://api.deepseek.com/v1",
                  "REDACTED_API_KEY", "deepseek-flash")

TOOLS = [{"type": "function", "function": {"name": "launch_app", "description": "launch app",
           "parameters": {"type": "object", "properties": {"package": {"type": "string"}}}}},
         {"type": "function", "function": {"name": "task_done", "description": "finish",
           "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}}}}]

msgs = [
    {"role": "system", "content": "你通过调用工具完成任务。每轮调用一个工具。"},
    {"role": "user", "content": "目标: launch org.fossify.notes then task_done"},
    {"role": "assistant", "content": None,
     "tool_calls": [{"name": "launch_app", "args": {"package": "org.fossify.notes"}}]},
    {"role": "tool", "name": "launch_app", "content": '{"ok": true}'},
]

# Test 1: no reasoning_content anywhere in history, thinking model decides
print("TEST1: assistant msgs WITHOUT reasoning_content")
INT_TOOLS = [{"name": "launch_app", "description": "launch app",
              "parameters": {"type": "object",
                             "properties": {"package": {"type": "string"}}}},
             {"name": "task_done", "description": "finish",
              "parameters": {"type": "object",
                             "properties": {"summary": {"type": "string"}}}}]
try:
    r = cli.chat(msgs, INT_TOOLS)
    print("TEST1: 200 ok, reasoning=%s, calls=%s" % (bool(r.get("reasoning_content")),
                                                     [c["name"] for c in r["tool_calls"]]))
except BrainError as e:
    print("TEST1: %s" % str(e)[:150])

# Test 2: same history, thinking disabled
print("TEST2: thinking disabled param")

import urllib.request
body = {
    "model": "deepseek-flash",
    "messages": cli._wire_messages(msgs),
    "tools": TOOLS,
    "thinking": {"type": "disabled"},
}
req = urllib.request.Request(
    "https://api.deepseek.com/v1/chat/completions",
    data=json.dumps(body).encode("utf-8"),
    headers={"Content-Type": "application/json",
             "Authorization": "Bearer REDACTED_API_KEY"},
    method="POST")
try:
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    m = data["choices"][0]["message"]
    print("TEST2: 200 ok, reasoning=%s, content=%r, tc=%s"
          % (bool(m.get("reasoning_content")), (m.get("content") or "")[:40],
             [t["function"]["name"] for t in m.get("tool_calls") or []]))
except urllib.error.HTTPError as e:
    print("TEST2: HTTP %d %s" % (e.code, e.read().decode("utf-8", "ignore")[:150]))
