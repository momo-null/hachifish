# -*- coding: utf-8 -*-
"""M4' 状态重置自动化（preregistration §5.3：pm clear / 重装 / 种子重建）。

R1：adb 与 u2 只出现在 harness 通道。u2 种子重建后必须清 u2 instrumentation
并恢复无障碍绑定（M0' 配方：force-stop com.github.uiautomator → a11y 复检）。

用法：
  python reset.py --tasks T1            # 按 tasks.json 的 reset 规格重置
  python reset.py --packages org.fossify.notes   # 仅 pm clear
  python reset.py --gate-clear          # 仅清授权白名单
"""
import argparse
import json
import subprocess
import sys
import time
import os

ADB = os.environ.get("HACHIMI_ADB", r"D:/code/hachimi/.tools/platform-tools/adb.exe")
TASKS_FILE = os.path.join(os.path.dirname(__file__), "tasks.json")
PY = sys.executable
LOOPBACK = "http://127.0.0.1:18071"


def adb(*args, timeout=60):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def forward():
    out = adb("forward", "tcp:18071", "tcp:8071")
    if "error" in out.lower():
        raise RuntimeError("adb forward failed: %s" % out.strip())


def pm_clear(pkg):
    out = adb("shell", "pm clear", pkg).strip()
    print("  pm clear %s -> %s" % (pkg, out))
    return "Success" in out


def gate_clear():
    out = adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
                       "-p com.hachimi.app --es prim gate_clear")
    ok = "result=0" in out
    print("  gate_clear -> %s" % ("ok" if ok else out.strip()[-60:]))
    return ok


def gate_allow(pkg, op):
    """门控预授权（M4' 口径：门控为环境配置非实验变量，M3' 已证触发与白名单行为）。"""
    out = adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
                       "-p com.hachimi.app --es prim gate_allow "
                       "--es package %s --es op %s" % (pkg, op))
    ok = "result=0" in out
    print("  gate_allow %s|%s -> %s" % (pkg, op, "ok" if ok else "fail"))
    return ok


def gate_allow_task(task):
    """按任务 app 列表预授权 launch_app + type_text（架构 §4.4 默认敏感集）。"""
    apps = [a.strip() for a in task.get("app", "").split("+") if a.strip()]
    for pkg in apps:
        for op in ("launch_app", "type_text"):
            gate_allow(pkg, op)


def seed_files(pkg="org.fossify.filemanager"):
    """T4/T9/T15 种子：主存储放一个可重命名/可查看的文本文件。"""
    adb("shell", "sh", "-c",
        "'echo hachimi_seed > /sdcard/Download/hachimi_seed.txt'")
    print("  seed file /sdcard/Download/hachimi_seed.txt")


def seed_alarms():
    """T11/T14 种子：经脚本大脑在 clock 里加一个 07:30 闹钟（跑之前先 pm clear）。"""
    body = {
        "objective": "Open the app org.fossify.clock, add a new alarm at 07:30, "
                     "enable it, confirm it appears in the alarm list, then call task_done.",
        "done_when": "07:30", "max_steps": 16,
        "script": [
            {"name": "launch_app", "args": {"package": "org.fossify.clock"}},
            {"name": "wait", "args": {"seconds": 1.5}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.clock:id/add_alarm"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "tap_by_id", "args": {"view_id": "android:id/button1"}},
            {"name": "task_done", "args": {"summary": "seed alarm"}},
        ],
    }
    return _run_scripted(body)


def seed_notes():
    """T1/T5/T6/T10/T11/T13/T15 种子：重建内容含 111111 的便签。
    经回环 + 剧本大脑走 Hachimi 自身无障碍通道（零 u2 依赖——u2 instrumentation
    与无障碍互斥，拆除后 Flyme 重绑退避可达分钟级，见 2026-09-27 实测）。
    双流程：A=编辑器已开直接输入；B=新建对话框流。done_when 强校验。"""
    flow_a = {
        "objective": "seed note", "done_when": "111111", "max_steps": 8,
        "script": [
            {"name": "launch_app", "args": {"package": "org.fossify.notes"}},
            {"name": "wait", "args": {"seconds": 2}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/text_note_view"}},
            {"name": "type_text", "args": {"text": "111111555555555555"}},
            {"name": "press", "args": {"key": "back"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "task_done", "args": {"summary": "seeded"}},
        ],
    }
    flow_b = {
        "objective": "seed note", "done_when": "111111", "max_steps": 12,
        "script": [
            {"name": "launch_app", "args": {"package": "org.fossify.notes"}},
            {"name": "wait", "args": {"seconds": 2}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/new_note"}},
            {"name": "wait", "args": {"seconds": 1.5}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/type_text_note"}},
            {"name": "wait", "args": {"seconds": 0.5}},
            {"name": "tap_by_id", "args": {"view_id": "android:id/button1"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/text_note_view"}},
            {"name": "type_text", "args": {"text": "111111555555555555"}},
            {"name": "press", "args": {"key": "back"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "task_done", "args": {"summary": "seeded"}},
        ],
    }
    for name, body in (("A(direct)", flow_a), ("B(dialog)", flow_b)):
        if _run_scripted(body):
            print("  seed note 111111 via scripted brain flow %s" % name)
            return
    raise RuntimeError("seed_notes: both scripted flows failed")


def cleanup_u2():
    """u2 instrumentation 挤占无障碍绑定 → 用完即清（M0' 配方）。"""
    adb("shell", "am force-stop com.github.uiautomator")
    adb("shell", "am force-stop com.github.uiautomator.test")
    print("  u2 force-stopped")


def a11y_ok():
    forward()
    import urllib.request
    try:
        with urllib.request.urlopen(LOOPBACK + "/health", timeout=5) as r:
            return json.loads(r.read().decode()).get("a11y") is True
    except Exception:
        return False


def a11y_recover():
    """无障碍掉线恢复（M0' 配方强化版，2026-09-27 实测定案）：
    先杀 u2 instrumentation（与 Hachimi 无障碍互斥，残留即永远绑不上）->
    写占位 none（真实变化=全关）-> 重开 -> accessibility_enabled=1 -> 轮询（~60s）。
    注意：空值传不进 Windows adb（引号被剥，settings put 缺值静默失败），必须用
    无引号 token（如 none）制造 OFF 态。"""
    cleanup_u2()
    svc = "com.hachimi.app/com.hachimi.app.HachimiAccessibilityService"
    adb("shell", "settings", "put", "secure",
        "enabled_accessibility_services", "none")
    time.sleep(2)
    adb("shell", "settings", "put", "secure",
        "enabled_accessibility_services", svc)
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    for i in range(12):   # 最长 ~60s 重绑窗口
        time.sleep(5)
        if a11y_ok():
            print("  a11y rebind confirmed (waited ~%ds)" % ((i + 1) * 5))
            return True
    return False


def _run_scripted(body):
    """经回环 /task 用剧本大脑执行种子动作（不耗 LLM token）。"""
    import urllib.request
    forward()
    req = urllib.request.Request(
        LOOPBACK + "/task", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        resp = r.read().decode()
    for _ in range(40):
        time.sleep(1)
        try:
            with urllib.request.urlopen(LOOPBACK + "/status", timeout=5) as r:
                s = r.read().decode()
        except Exception:
            continue
        if "'phase': 'done'" in s or "'phase': 'error'" in s:
            ok = "'phase': 'done'" in s
            print("  scripted seed -> %s" % ("done" if ok else "ERROR " + s[:160]))
            return ok
    print("  scripted seed timeout")
    return False


def reset_task(task):
    """按 tasks.json 的 reset 规格执行完整重置。"""
    print("reset %s (%s)" % (task["id"], task.get("title", "")))
    spec = task.get("reset") or {}
    for pkg in spec.get("pm_clear", []):
        pm_clear(pkg)
    gate_clear()
    gate_allow_task(task)
    if spec.get("seed_files"):
        seed_files()
    if spec.get("seed_alarms"):
        seed_alarms()
    if spec.get("seed_notes"):
        seed_notes()
    if not a11y_ok():
        print("  a11y down -> recovering")
        if not a11y_recover():
            raise RuntimeError("accessibility recovery failed")
    print("  reset complete (a11y ok)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", nargs="*", help="task ids in tasks.json")
    ap.add_argument("--packages", nargs="*", help="plain pm clear")
    ap.add_argument("--gate-clear", action="store_true")
    args = ap.parse_args()

    if args.gate_clear:
        gate_clear()
        return
    if args.packages:
        for p in args.packages:
            pm_clear(p)
        return
    if args.tasks:
        with open(TASKS_FILE, encoding="utf-8") as f:
            all_tasks = {t["id"]: t for t in json.load(f)["tasks"]}
        for tid in args.tasks:
            reset_task(all_tasks[tid])
        return
    ap.print_help()


if __name__ == "__main__":
    main()
