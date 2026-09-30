# -*- coding: utf-8 -*-
"""统一入口真机冒烟（大肥鱼改造后，真任务，烧一次 LLM）：
发一条消息 → /task（统一入口）→ 验证首条消息自动建 task + 人设注入 + 任务闭环。

用法：python smoke_unified_entry.py
前置：adb forward tcp:18071 tcp:8071（脚本自动建）
"""
import ast
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _env import adb, LOOPBACK  # noqa: E402

# 任务目标：明确指向项目验证过的 Fossify Notes（避免模型选魅族自带 WebView 便签）
OBJECTIVE = u"在 Fossify 便签（org.fossify.notes）里创建一条内容为「你好，大肥鱼」的笔记"
TARGET_PKG = "org.fossify.notes"


def req(path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(LOOPBACK + path, data=data,
                               headers={"Content-Type": "application/json"},
                               method="POST" if body is not None else "GET")
    with urllib.request.urlopen(r, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def main():
    adb("forward", "tcp:18071", "tcp:8071")
    # 预授权目标 App 的敏感操作（launch_app + type_text）——无人值守任务必须预授权，
    # 否则 GateActivity 10s 超时自动拒绝（deny_timeout），type_text 全部失败预算耗尽。
    for op in ("launch_app", "type_text"):
        r = adb("shell", "am", "broadcast", "-a", "com.hachimi.app.DEBUG_PRIMITIVE",
                "-n", "com.hachimi.app/.DebugBridgeReceiver",
                "--es", "prim", "gate_allow",
                "--es", "package", TARGET_PKG, "--es", "op", op)
        print("[smoke] gate_allow %s|%s -> %s" % (TARGET_PKG, op, r.strip()[:120]))
    # 等无障碍上线
    deadline = time.time() + 120
    while True:
        try:
            h = json.loads(req("/health"))
            if h.get("a11y"):
                print("[smoke] a11y bound:", h)
                break
        except Exception:
            pass
        if time.time() > deadline:
            print("[smoke] TIMEOUT: a11y 未上线")
            return 2
        time.sleep(3)

    # 统一入口：message 优先（会话式）；首条消息无 task_id 自动建 task
    r = ast.literal_eval(req("/task", {"message": OBJECTIVE,
                                       "done_when": "",
                                       "max_steps": 32,
                                       "mode": "foreground"}))
    print("[smoke] /task ->", json.dumps(r, ensure_ascii=False))
    if not r.get("ok"):
        print("[smoke] task rejected:", r.get("error"))
        return 3
    run_id = r["run_id"]
    task_id = r.get("task_id", "")

    t0 = time.time()
    last = None
    while time.time() - t0 < 300:
        try:
            s = ast.literal_eval(req("/status"))
            if s.get("phase") != "running" or s.get("steps_so_far") != last:
                last = s.get("steps_so_far")
                print("[smoke] phase=%s steps=%s last_action=%s"
                      % (s.get("phase"), last, s.get("last_action")))
            if s.get("phase") != "running" and time.time() - t0 > 5:
                break
        except Exception as e:
            print("[smoke] status poll error:", e)
        time.sleep(2)

    s = ast.literal_eval(req("/status"))
    print("[smoke] == final ==")
    print("phase:", s.get("phase"), "| summary:", json.dumps(s.get("summary"), ensure_ascii=False))
    # 轨迹剖析：定位失败原语的真实 error（type_text 等）
    try:
        t = ast.literal_eval(req("/trajectory/" + run_id))
        for ln in t.get("lines", []):
            act = ln.get("action", {})
            res = ln.get("result", {})
            if act.get("tool") in ("type_text", "launch_app") or res.get("ok") is False:
                print("[trace] %s step=%s ok=%s args=%s error=%r"
                      % (act.get("tool"), ln.get("step"), res.get("ok"),
                         json.dumps(act.get("args"), ensure_ascii=False), res.get("error")))
    except Exception as e:
        print("trajectory analyze failed:", e)
    ok = s.get("phase") == "done" and s.get("summary", {}).get("status") == "done"
    print("[smoke] RESULT:", "PASS（统一入口+人设+闭环真机可用）" if ok else "NOT-PASSED（看上方）")
    return 0 if ok else 4


if __name__ == "__main__":
    sys.exit(main())
