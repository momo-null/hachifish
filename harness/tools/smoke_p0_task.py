# -*- coding: utf-8 -*-
"""P0 工程冒烟（PC 驱动，非 DoD 验收）：等无障碍上线 → 下一个空判据任务 →
验证 objective 弱探测链路真机可用 → 报告结果。

用法：python smoke_p0_task.py   （需先 adb forward tcp:18071 tcp:8071）
"""

import ast
import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:18071"
OBJECTIVE = u"在便签里创建一条内容为「买牛奶」的笔记"   # 引号短语 → 弱探测判据


def req(path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data,
                               headers={"Content-Type": "application/json"},
                               method="POST" if body is not None else "GET")
    with urllib.request.urlopen(r, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def main():
    deadline = time.time() + 600
    while True:
        try:
            h = json.loads(req("/health"))
            if h.get("a11y"):
                print("[smoke] a11y bound, launching smoke task")
                break
        except Exception:
            pass
        if time.time() > deadline:
            print("[smoke] TIMEOUT: 10 分钟内无障碍未开启，退出")
            return 2
        time.sleep(3)

    r = ast.literal_eval(req("/task", {"objective": OBJECTIVE, "done_when": "",
                                       "max_steps": 24}))
    print("[smoke] /task ->", r)
    if not r.get("ok"):
        print("[smoke] task rejected:", r.get("error"))
        return 3
    run_id = r["run_id"]

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
    print("phase:", s.get("phase"), "| summary:", s.get("summary"))
    try:
        t = ast.literal_eval(req("/trajectory/" + run_id))
        print("trajectory:", t.get("path"), "lines:", len(t.get("lines", [])))
    except Exception as e:
        print("trajectory fetch failed:", e)

    ok = s.get("phase") == "done" and s.get("summary", {}).get("status") == "done"
    print("[smoke] RESULT:", "PASS（弱探测链路真机可用）" if ok else "NOT-PASSED（看上方 summary/trajectory）")
    return 0 if ok else 4


if __name__ == "__main__":
    sys.exit(main())
