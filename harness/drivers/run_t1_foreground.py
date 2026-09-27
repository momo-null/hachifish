# -*- coding: utf-8 -*-
"""T1 foreground-mode run driver: POST task (Chinese note text), poll, and
auto-tap the gate when it pops (allow_always), printing each transition.
Usage: python t1_run.py [tap|peek]"""
import ast
import json
import subprocess
import sys
import time
import urllib.request


def parse_repr(text):
    """回环返回 Python dict repr（单引号/True/None），ast 安全解析。"""
    return ast.literal_eval(text)

BASE = "http://127.0.0.1:18071"
ADB = r"D:/code/hachimi/.tools/platform-tools/adb.exe"
NOTE_TEXT = "提醒：明天上午备份实验数据"

BODY = {
    "objective": ("Open the app org.fossify.notes, create a new note containing the text "
                  "\"%s\", make sure the note is saved so the notes list shows it, "
                  "then call task_done." % NOTE_TEXT),
    "done_when": NOTE_TEXT,
    "max_steps": 18,
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


def adb(*args, timeout=30):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          timeout=timeout).stdout


def tap_gate():
    """Dump screen via debug bridge; tap allow-always ONLY when the gate dialog
    is genuinely the active window (title visible). Cooldown after a tap."""
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
            time.sleep(2.5)   # 等门控关闭，避免滞后 observe 误点底层 App
            return "tapped allow_always"
    return None


def main():
    resp = parse_repr(post("/task", BODY))
    run_id = resp["run_id"]
    print("run:", run_id)
    last_step = -1
    t0 = time.time()
    while time.time() - t0 < 300:
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
        if s.get("phase") in ("done", "error"):
            print("PHASE:", s["phase"])
            print("summary:", json.dumps(s.get("summary"), ensure_ascii=False))
            print("error:", s.get("error"))
            break
        gate = tap_gate()
        if gate:
            print("[%5.1fs] GATE popped ->" % (time.time() - t0), gate)


if __name__ == "__main__":
    main()
