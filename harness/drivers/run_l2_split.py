# -*- coding: utf-8 -*-
"""L2 split-mode run driver: notes multi-page editing task, console docked.
Captures a mid-run screenshot of the split view (console + target app)."""
import ast
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _env import adb, ADB, ROOT

BASE = "http://127.0.0.1:18071"
SHOT = os.path.join(ROOT, "harness", "checks", "m3_l2_split_console.png")

LINE1 = "L2 第一行：分屏模式任务"
LINE2 = "L2 第二行：追加成功"

BODY = {
    "objective": ("Open the app org.fossify.notes, create a new text note whose content "
                  "is \"%s\", and save it. Then reopen that note, append a second line "
                  "\"%s\", and save again. Confirm both lines are visible in the note, "
                  "then call task_done." % (LINE1, LINE2)),
    "done_when": LINE2,
    "max_steps": 30,
    "mode": "split",
}


def post(path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else b""
    req = urllib.request.Request(BASE + path, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read().decode("utf-8")


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return r.read().decode("utf-8")


def parse_repr(text):
    return ast.literal_eval(text)


def tap_gate():
    adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
                 "-p com.hachimi.app --es prim observe", timeout=60)
    time.sleep(1)
    raw = adb("shell", "run-as com.hachimi.app cat files/bridge_result.json",
              timeout=60)
    tree = json.loads(raw)["result"].get("tree", [])
    texts = [((n.get("text") or "") + " " + (n.get("desc") or "")) for n in tree]
    if not any("授权门控" in t for t in texts):
        return None
    for n in tree:
        t = ((n.get("text") or "") + " " + (n.get("desc") or ""))
        if "总是允许" in t:
            b = n["bounds"]
            cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            adb("shell", "input tap %d %d" % (cx, cy))
            time.sleep(2.5)
            return "tapped allow_always"
    return None


def screenshot():
    with open(SHOT, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"],
                               capture_output=True, timeout=60).stdout)
    print("screenshot saved:", SHOT)


def main():
    resp = parse_repr(post("/task", BODY))
    run_id = resp["run_id"]
    print("run:", run_id)
    last_step = -1
    shot_taken = False
    t0 = time.time()
    while time.time() - t0 < 420:
        time.sleep(2)
        try:
            s = parse_repr(get("/status"))
        except Exception as e:
            print("status err", e)
            continue
        step = s.get("steps_so_far", 0)
        if step != last_step:
            print("[%5.1fs] step=%d paused=%s" % (time.time() - t0, step,
                                                  s.get("paused")))
            last_step = step
        if step >= 5 and not shot_taken:
            screenshot()
            shot_taken = True
        if s.get("phase") in ("done", "error"):
            print("PHASE:", s["phase"])
            print("summary:", json.dumps(s.get("summary"), ensure_ascii=False))
            print("error:", s.get("error"))
            screenshot()
            break
        gate = tap_gate()
        if gate:
            print("[%5.1fs] GATE popped ->" % (time.time() - t0), gate)


if __name__ == "__main__":
    main()
