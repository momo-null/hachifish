# -*- coding: utf-8 -*-
"""M4' DoD⑥ 示教录制器 PC 侧（对照组 C 输入）。

设备端（TeachRecorder，经调试桥控制）录制无障碍事件流；本工具负责：
  dump   拉取录制会话（run-as cat，UTF-8 直读）
  build  会话 -> ScriptedBrain 兼容 /task body（launch/tap/type/wait/observe + task_done）
  replay 回放 body 经回环 /task 走同一 MiniLoop -> 产出合法轨迹（与真跑同 schema）

用法：
  python teach.py start|stop|status
  python teach.py dump --out session.json
  python teach.py build --session session.json --task T2 --out replay_T2.json
  python teach.py replay --body replay_T2.json

转换规则（v1）：
  - 丢弃自家包 / launcher / systemui 事件；WINDOW_STATE_CHANGED 仅作对账不转步骤
  - 连续同字段 TEXT_CHANGED 取最新完整文本 -> type_text（替换语义）
  - CLICKED：有 view_id -> tap_by_id，否则 bounds 中心 -> tap_xy；跨包切换 -> launch_app
  - 步间真实间隔 >1.5s 插 wait（上限 3s）；launch_app 后固定 wait 1.5s
  - 每个实质动作前插 observe（--no-observe 关闭）；末尾 task_done（受 verify 门控）

已知边界：系统 back/home 无事件不录制——依赖 back 的任务 build 后手工补 press 步骤。
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from _env import adb, LOOPBACK, ADB

APP = "com.hachimi.app"
TASKS_FILE = os.path.join(HERE, "tasks.json")
DROP_PKGS = ("com.hachimi.app", "com.android.systemui")
LAUNCH_WAIT = 1.5
# 录制会话的真实节奏（人类点击间隔）回放时保留：目标 App 显示异步滞后于点击
# （2026-09-27 实测：零延迟连点后 verify 时结果尚未渲染 -> task_done 被门控拒绝）
GAP_WAIT_MIN = 0.4
GAP_WAIT_MAX = 3.0
TRAIL_WAIT = 1.0


def adb(*args, timeout=60, binary=False):
    r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    if binary:
        return r.stdout + r.stderr
    return (r.stdout + r.stderr).decode("utf-8", "replace")


def run_as(*args, **kw):
    """直接参数形式（snapshot.py 同款：不经 sh -c，引号不可靠）。"""
    return adb("shell", "run-as", APP, *args, **kw)


def broadcast(prim):
    out = adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
                       "-p com.hachimi.app --es prim %s" % prim)
    if "result=0" not in out and "Broadcast completed" not in out:
        raise RuntimeError("broadcast %s failed: %s" % (prim, out.strip()[:120]))
    raw = run_as("cat", "files/bridge_result.json")
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", "replace")
    try:
        rec = json.loads(raw)
    except Exception as e:
        raise RuntimeError("bridge_result unreadable: %s | raw=%r" % (e, raw[:120]))
    if rec.get("prim") != prim:
        raise RuntimeError("stale bridge_result (prim=%s, want %s)" % (rec.get("prim"), prim))
    return rec.get("result") or {}


def parse_repr(text):
    import ast
    return ast.literal_eval(text)


# ---------------- 命令：start / stop / status / dump ----------------

def cmd_start(_a):
    r = broadcast("teach_start")
    print("teach_start -> %s" % json.dumps(r, ensure_ascii=False))


def cmd_stop(_a):
    r = broadcast("teach_stop")
    print("teach_stop -> %s" % json.dumps(r, ensure_ascii=False))


def cmd_status(_a):
    r = broadcast("teach_status")
    print("teach_status -> %s" % json.dumps(r, ensure_ascii=False))


def cmd_dump(a):
    r = broadcast("teach_dump")
    if not r.get("ok"):
        raise RuntimeError("teach_dump failed: %s" % r.get("error"))
    raw = run_as("cat", r["path"], binary=True)
    data = raw.replace(b"\r\n", b"\n").decode("utf-8", "replace")
    events = json.loads(data)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump({"path": r["path"], "events": events}, f, ensure_ascii=False, indent=1)
    print("dump %d events -> %s" % (len(events), a.out))


# ---------------- 命令：build（会话 -> /task body） ----------------

def _is_dropped(pkg):
    return pkg in DROP_PKGS or "launcher" in pkg.lower()


def _tap_action(ev):
    view_id = ev.get("view_id") or ""
    if view_id:
        return {"name": "tap_by_id", "args": {"view_id": view_id}}
    b, s = ev.get("bounds"), ev.get("screen")
    if b and s and s[0] and s[1]:
        x = round((b[0] + b[2]) / 2.0 / s[0], 4)
        y = round((b[1] + b[3]) / 2.0 / s[1], 4)
        return {"name": "tap_xy", "args": {"x": min(max(x, 0.0), 1.0),
                                           "y": min(max(y, 0.0), 1.0)}}
    return None


def build_script(session, with_observe=True):
    """事件流 -> ScriptedBrain script（纯函数，便于测试）。"""
    steps = []
    last_pkg = None
    last_ts = None

    def gap_wait(ts):
        nonlocal last_ts
        if last_ts is not None and ts is not None:
            gap = (ts - last_ts) / 1000.0
            if gap > GAP_WAIT_MIN:
                steps.append({"name": "wait",
                              "args": {"seconds": min(round(gap, 1), GAP_WAIT_MAX)}})
        last_ts = ts

    def push(action, ts):
        gap_wait(ts)
        if with_observe:
            steps.append({"name": "observe", "args": {"compact": True}})
        steps.append(action)

    for ev in session.get("events", []):
        pkg = ev.get("pkg") or ""
        if _is_dropped(pkg):
            continue
        etype = ev.get("type")
        if etype == "WINDOW_STATE_CHANGED":
            continue
        if etype == "VIEW_TEXT_CHANGED":
            push({"name": "type_text", "args": {"text": ev.get("text") or ""}},
                 ev.get("ts"))
        elif etype == "VIEW_CLICKED":
            action = _tap_action(ev)
            if action is None:
                print("  [warn] click without view_id/bounds dropped: %s %s"
                      % (pkg, ev.get("cls")))
                continue
            if pkg != last_pkg:
                push({"name": "launch_app", "args": {"package": pkg}}, ev.get("ts"))
                steps.append({"name": "wait", "args": {"seconds": LAUNCH_WAIT}})
            push(action, ev.get("ts"))
        last_pkg = pkg if etype in ("VIEW_CLICKED", "VIEW_TEXT_CHANGED") else last_pkg
    return steps


def cmd_build(a):
    with open(a.session, encoding="utf-8") as f:
        session = json.load(f)
    with open(TASKS_FILE, encoding="utf-8") as f:
        task = {t["id"]: t for t in json.load(f)["tasks"]}[a.task]
    script = build_script(session, with_observe=not a.no_observe)
    if not script:
        raise RuntimeError("empty script: session has no convertible events")
    script.append({"name": "wait", "args": {"seconds": TRAIL_WAIT}})
    script.append({"name": "task_done",
                   "args": {"summary": "replay of human demo (%s)" % a.task}})
    body = {"objective": task["objective"], "done_when": task["done_when"],
            "max_steps": len(script) + 4, "script": script}
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(body, f, ensure_ascii=False, indent=1)
    print("build %s: %d script steps (max_steps=%d) -> %s"
          % (a.task, len(script), body["max_steps"], a.out))
    for s in script:
        print("  %s %s" % (s["name"], json.dumps(s["args"], ensure_ascii=False)[:80]))


# ---------------- 命令：replay（MiniLoop 回放 -> 合法轨迹） ----------------

def cmd_replay(a):
    sys.path.insert(0, HERE)
    import reset as reset_mod
    with open(a.body, encoding="utf-8") as f:
        body = json.load(f)
    task_id = a.task or "TEACH"
    if a.task:
        with open(TASKS_FILE, encoding="utf-8") as f:
            task = {t["id"]: t for t in json.load(f)["tasks"]}[a.task]
        reset_mod.reset_task(task)   # pm clear + 门控预授权 + a11y 检查
    else:
        reset_mod.forward()
        for pkg in ("org.fossify.math",):
            reset_mod.gate_allow(pkg, "launch_app")
            reset_mod.gate_allow(pkg, "type_text")
    reset_mod.forward()
    req = urllib.request.Request(
        LOOPBACK + "/task", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        resp = parse_repr(r.read().decode())
    if not resp.get("ok"):
        raise RuntimeError("task rejected: %r" % resp)
    run_id = resp["run_id"]
    print("replay run_id=%s (script %d steps)" % (run_id, len(body["script"])))
    status = {}
    for _ in range(120):
        time.sleep(2)
        try:
            with urllib.request.urlopen(LOOPBACK + "/status", timeout=10) as r:
                status = parse_repr(r.read().decode())
        except Exception:
            continue
        if status.get("phase") in ("done", "error"):
            break
    summary = status.get("summary") or {}
    print("phase=%s loop_status=%s steps=%s verified_summary=%r"
          % (status.get("phase"), summary.get("status"), summary.get("steps"),
             (summary.get("summary") or "")[:40]))
    # 导出轨迹（合法轨迹 = MiniLoop 产物，schema 与真跑一致）
    out_dir = os.path.join(HERE, "results", "teach")
    os.makedirs(out_dir, exist_ok=True)
    try:
        traj = parse_repr(urllib.request.urlopen(
            LOOPBACK + "/trajectory/%s" % run_id, timeout=30).read().decode())
        path = os.path.join(out_dir, "%s_replay_%s.jsonl" % (task_id, run_id))
        with open(path, "w", encoding="utf-8") as f:
            for line in traj.get("lines", []):
                f.write(json.dumps(line, ensure_ascii=False) + "\n")
        print("trajectory %d lines -> %s" % (len(traj.get("lines", [])), path))
    except Exception as e:
        print("trajectory export failed: %s: %s" % (type(e).__name__, e))
    ok = status.get("phase") == "done" and summary.get("status") == "done"
    print("REPLAY %s" % ("DONE (legal trajectory produced)" if ok else "FAILED"))
    if not ok:
        raise SystemExit(1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("start", "stop", "status"):
        p = sub.add_parser(name)
        p.set_defaults(fn={"start": cmd_start, "stop": cmd_stop,
                           "status": cmd_status}[name])
    p = sub.add_parser("dump")
    p.add_argument("--out", default=os.path.join(HERE, "teach_session.json"))
    p.set_defaults(fn=cmd_dump)
    p = sub.add_parser("build")
    p.add_argument("--session", required=True)
    p.add_argument("--task", required=True, help="task id in tasks.json")
    p.add_argument("--out", required=True)
    p.add_argument("--no-observe", action="store_true")
    p.set_defaults(fn=cmd_build)
    p = sub.add_parser("replay")
    p.add_argument("--body", required=True)
    p.add_argument("--task", help="tasks.json id -> full reset+gate prep; omit for math-only prep")
    p.set_defaults(fn=cmd_replay)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
