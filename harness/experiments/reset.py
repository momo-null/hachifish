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
import sys
import time
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _env import adb, LOOPBACK, current_profile, ROOT

TASKS_FILE = os.path.join(os.path.dirname(__file__), "tasks.json")
APK_DIR = os.path.join(ROOT, ".tools", "apks")
PY = sys.executable


def forward():
    out = adb("forward", "tcp:18071", "tcp:8071")
    if "error" in out.lower():
        raise RuntimeError("adb forward failed: %s" % out.strip())


def pm_clear(pkg):
    """清应用状态。ColorOS 实录（2026-09-28）：`pm clear` 被 ROM 拒（无
    CLEAR_APP_USER_DATA）；卸载重装方案因「USB 安装」无法开启被用户否决
    （每次安装都弹确认框，流水线无法自动化）-> 返回 False，由 reset_task
    走 App 专属 cleanup_*（prereg §5.3 的「种子重建+产物清理」腿）。"""
    out = adb("shell", "pm clear", pkg).strip()
    ok = "Success" in out
    print("  pm clear %s -> %s" % (pkg, "Success" if ok else "被拒（ColorOS），改走产物清理"))
    return ok


def pm_grant(pkg, perm):
    """pm clear 会吊销运行时授权 -> 重置后静默重授（环境配置非实验变量，
    免去首启权限弹窗烧预算；2026-09-27 实测 gallery 媒体权限/clock 通知权限）。"""
    out = adb("shell", "pm grant", pkg, perm).strip()
    ok = "Exception" not in out and "error" not in out.lower()
    print("  pm grant %s %s -> %s" % (pkg, perm.split(".")[-1], "ok" if ok else out[-60:]))
    return ok


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


def bridge_call(expect_prim, prim_extras="", timeout=15):
    """显式组件广播（-n；targetSdk 36 纯隐式不投递）+ run-as 轮询确认真实执行。"""
    adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
        "-n com.hachimi.app/.DebugBridgeReceiver --es prim " + expect_prim +
        (" " + prim_extras if prim_extras else ""))
    deadline = time.time() + timeout
    while time.time() < deadline:
        raw = adb("shell", "run-as com.hachimi.app cat files/bridge_result.json")
        try:
            obj = json.loads(raw)
        except Exception:
            obj = None
        if isinstance(obj, dict) and obj.get("prim") == expect_prim:
            return obj
        time.sleep(0.5)
    return None


def waive_first_run_dialogs(pkg):
    """ColorOS quirk3 代烧：pm clear 后首启的权限/通知系统弹窗对 a11y 不可见
    （com.oplus.notificationmanager 等 overlay 盖脸 -> observe 'no active window'），
    且 pm grant 被 ROM 锁死无法静默重授。手段：launch -> a11y 全盲则按实测坐标
    盲点「允许」（1080x2400 底部弹窗）-> observe 复验可见。弹窗可见的 ROM 直接跳过。"""
    prof = current_profile()
    if prof.get("permission_dialog_visible_to_a11y", True):
        return
    if not a11y_ok():
        print("  waive %s: skip (a11y down, 由 recover 链路处理)" % pkg)
        return
    bridge_call("launch_app", "--es package " + pkg)
    time.sleep(3)
    r = bridge_call("observe", timeout=8)
    if r and r.get("result", {}).get("ok"):
        print("  waive %s: 无遮蔽弹窗（a11y 可见）" % pkg)
        return
    adb("shell", "input tap 779 1357")   # ColorOS 底部弹窗「允许」@1080x2400
    time.sleep(1.5)
    r2 = bridge_call("observe", timeout=8)
    ok = bool(r2 and r2.get("result", {}).get("ok"))
    print("  waive %s: 盲点允许 -> a11y %s" % (pkg, "可见 ✓" if ok else "仍不可见（需人工看一眼）"))


def seed_files(pkg="org.fossify.filemanager"):
    """T4/T9/T15 种子：主存储放一个可重命名/可查看的文本文件。"""
    adb("shell", "sh", "-c",
        "'echo hachimi_seed > /sdcard/Download/hachimi_seed.txt'")
    print("  seed file /sdcard/Download/hachimi_seed.txt")


def seed_alarms():
    """T11/T14 种子：clock 加一个 08:00 闹钟（跑之前先 pm clear）。
    2026-09-27 实测修 bug：旧脚本 tap add_alarm（id 不存在）且从不设时间。
    真实流 = alarm_fab -> 编辑器（默认 08:00）-> button1 保存 -> button1 关首次
    提示框（pm clear 后必现）。注意：Hachimi 重装后 Flyme 切换警告会拦首次
    launch_app，开头的 button1 空点即警告的「打开」（无警告时 view not found
    空过，剧本大脑不看结果）。文本模式改时间不可靠（type_text 替换语义吞字段），
    默认 08:00 即种子值。"""
    body = {
        "objective": "seed alarm", "done_when": "08:00", "max_steps": 14,
        "script": [
            {"name": "launch_app", "args": {"package": "org.fossify.clock"}},
            {"name": "wait", "args": {"seconds": 1.5}},
            {"name": "tap_by_id", "args": {"view_id": "android:id/button1"}},
            {"name": "wait", "args": {"seconds": 2}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.clock:id/alarm_fab"}},
            {"name": "wait", "args": {"seconds": 1.2}},
            {"name": "tap_by_id", "args": {"view_id": "android:id/button1"}},
            {"name": "wait", "args": {"seconds": 1.2}},
            {"name": "tap_by_id", "args": {"view_id": "android:id/button1"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "task_done", "args": {"summary": "seed alarm"}},
        ],
    }
    return _run_scripted(body)


def apply_profile():
    """把设备 profile 注入到设备端（跨品牌兼容）。
    经调试桥 config 原语写入 tap_bottom_limit（底部钳制阈值）：默认 1.0 = 不钳制；
    仅手势区吞底部触摸的 ROM（如旧 Flyme）才设 0.93。其余 profile 字段供 harness 逻辑判断。
    注意：value 必须走 --es args JSON 形态——Kotlin 端独立 extras 白名单漏了 "value"
    （2026-09-28 实录：--es value 写成空串），下次重建 APK 时补。"""
    prof = current_profile()
    limit = prof.get("tap_bottom_limit", "1.0")
    forward()
    adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
        "-p com.hachimi.app --es prim config --es key tap_bottom_limit "
        "--es args '{\"value\":\"%s\"}'" % limit)
    print("  apply_profile: tap_bottom_limit=%s (brand-matched)" % limit)
    return prof


def prewarm():
    """环境预热（Hachimi (re)install 后跑一次）。
    通用部分（所有设备）：清授权白名单 + 预授权 launch_app/type_text。
    品牌专属部分：仅当 profile.app_switch_warning=true 时逐 App 拉起并点掉首次切换警告
    （旧 Flyme 行为；通用 ROM 无此警告，跳过以免误触）。"""
    prof = current_profile()
    apply_profile()   # 注入 tap_bottom_limit 等到设备端
    forward()
    gate_clear()
    for pkg in ("org.fossify.notes", "org.fossify.math", "org.fossify.clock",
                "org.fossify.gallery", "org.fossify.filemanager"):
        gate_allow(pkg, "launch_app")
        gate_allow(pkg, "type_text")
    if not prof.get("app_switch_warning"):
        print("  prewarm: app_switch_warning=false -> 跳过首次切换警告 Dismiss")
        return
    for pkg in ("org.fossify.notes", "org.fossify.math", "org.fossify.clock",
                "org.fossify.gallery", "org.fossify.filemanager"):
        adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
            "-p com.hachimi.app --es prim launch_app --es package %s" % pkg)
        time.sleep(3)
        # 警告对话框在则点「打开」(button1)；无则空过
        adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
            "-p com.hachimi.app --es prim tap_by_id --es view_id android:id/button1",
            timeout=90)
        time.sleep(2)
        adb("shell", "input keyevent 4")   # 回到桌面，下一个
        time.sleep(1)
        print("  prewarm %s done" % pkg)


def _observe_tree():
    """observe -> 失败/盲态返回 None（调用方自行处置）。"""
    r = bridge_call("observe", timeout=10)
    if r and r.get("result", {}).get("ok"):
        return r["result"].get("tree", [])
    return None


def _real_items(tree, view_id):
    """取真实宽度的控件（排除已删条目的零宽僵尸节点——长按删除后旧节点会以
    零宽形式滞留树中若干快照，其 text 仍会被 verify_done 全文探测命中）。"""
    return [n for n in tree
            if (n.get("view_id") or "") == view_id
            and (n.get("bounds") or [0, 0, 0, 0])[2] > (n.get("bounds") or [0, 0, 0, 0])[0]]


def cleanup_notes(max_rounds=12):
    """Notes 1.7.0 状态清理（pm clear 被锁 + 用户裁定不重装后的替代复位）：
    循环长按列表首个真实条目（长按=移入回收站+撤销 snackbar）直到无内容条目；
    完成后 force-stop 刷掉僵尸节点。空态占位符（请在此插入文字）非产物，可留。"""
    adb("shell", "am", "force-stop", "org.fossify.notes")
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.notes")
    time.sleep(3)
    placeholder = "请在此插入文字"
    for _ in range(max_rounds):
        tree = _observe_tree()
        if tree is None:
            print("  cleanup_notes: observe 失败（盲态），中止")
            return False
        items = _real_items(tree, "org.fossify.notes:id/text_note_view")
        content = [n for n in items if (n.get("text") or "") not in ("", placeholder)]
        if not content:
            break
        b = content[0]["bounds"]
        cy = round((b[1] + b[3]) / 2 / 2400.0, 3)
        bridge_call("gesture", "--es args '{\"points\":[[0.5,%s],[0.5,%s]],"
                    "\"duration_ms\":1000}'" % (cy, cy))
        time.sleep(2)
    else:
        print("  cleanup_notes: %d 轮后仍有内容条目" % max_rounds)
        return False
    adb("shell", "am", "force-stop", "org.fossify.notes")   # 刷僵尸节点
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.notes")
    time.sleep(3)
    tree = _observe_tree()
    ok = tree is not None and not [
        n for n in _real_items(tree, "org.fossify.notes:id/text_note_view")
        if (n.get("text") or "") not in ("", placeholder)]
    print("  cleanup_notes -> %s" % ("clean" if ok else "仍有残留"))
    return ok


def seed_notes():
    """T1/T5/T6/T10/T11/T13/T15 种子：重建内容含 111111 的便签。
    经回环 + 剧本大脑走 Hachimi 自身无障碍通道（零 u2 依赖——u2 instrumentation
    与无障碍互斥，拆除后 Flyme 重绑退避可达分钟级，见 2026-09-27 实测）。
    1.7.0 适配（2026-09-28 实测）：主屏列表条目=内联编辑器，点入直接 type_text
    即写入并自动保存；done_when 由内核 verify_done 全文探测强校验。
    前置：cleanup_notes 已跑（无内容条目，防旧产物让 done_when 躺赢）。"""
    body = {
        "objective": "seed note", "done_when": "111111", "max_steps": 8,
        "script": [
            {"name": "launch_app", "args": {"package": "org.fossify.notes"}},
            {"name": "wait", "args": {"seconds": 2}},
            {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/text_note_view"}},
            {"name": "type_text", "args": {"text": "111111555555555555"}},
            {"name": "wait", "args": {"seconds": 1}},
            {"name": "task_done", "args": {"summary": "seeded"}},
        ],
    }
    if _run_scripted(body):
        print("  seed note 111111 via in-place editor")
        return True
    raise RuntimeError("seed_notes: in-place seed failed")


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
    """无障碍掉线恢复（无重启版，2026-09-28 定案；用户要求：手机不自动重启）：
    第一步 判别：accessibility_enabled=0 = 框架已禁用服务（服务崩溃后框架翻开关，
    该状态跨重启保留，reboot 救不了）-> 立即失败，需人工在手机上重开开关。
    第二步（Flyme 配方，ColorOS 上无害失败）：杀 u2 instrumentation（与 Hachimi
    无障碍互斥，残留即永远绑不上）-> settings put 写占位 none（真实变化=全关）->
    重开 -> accessibility_enabled=1 -> 轮询。空值传不进 Windows adb（引号被剥，
    settings put 缺值静默失败），必须用无引号 token（如 none）制造 OFF 态。
    第三步仍失败（ColorOS 窗口管线卡死，enabled=1 但系统级全盲）-> 报告人工：
    手机上把 Hachimi 无障碍关-开一次（重绑服务连接），仍不行再考虑手动重启。"""
    cleanup_u2()
    if adb("shell", "settings", "get", "secure", "accessibility_enabled").strip() == "0":
        print("  accessibility_enabled=0（框架已禁用）-> 需人工在手机上重开 Hachimi 无障碍")
        return False
    svc = "com.hachimi.app/com.hachimi.app.HachimiAccessibilityService"
    adb("shell", "settings", "put", "secure",
        "enabled_accessibility_services", "none")
    time.sleep(2)
    adb("shell", "settings", "put", "secure",
        "enabled_accessibility_services", svc)
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    # KernelHostService（回环/内核）只在 MainActivity 打开时启动——重启/进程死后
    # 无障碍即使重绑成功，/health 也一直拒连（2026-09-28 实录），先拉一次再轮询
    adb("shell", "am", "start", "-n", "com.hachimi.app/.MainActivity")
    time.sleep(3)
    for i in range(12):   # ~60s：Flyme 走这条路；ColorOS 上 put 无效但轮询兜住偶发重绑
        time.sleep(5)
        if a11y_ok():
            print("  a11y rebind confirmed (~%ds)" % ((i + 1) * 5))
            return True
    print("  60s 未恢复（疑 ColorOS 窗口管线卡死）-> 需人工：无障碍开关关-开一次")
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
    cleared = {pkg: pm_clear(pkg) for pkg in spec.get("pm_clear", [])}
    for pkg, perms in (spec.get("pm_grant") or {}).items():
        for p in (perms if isinstance(perms, list) else [perms]):
            pm_grant(pkg, p)   # ColorOS 上被锁，失败仅打印（-g 重装也无门，见 waive）
    gate_clear()
    gate_allow_task(task)
    # ColorOS：pm clear 被锁 + 不重装 -> App 专属产物清理（prereg §5.3「种子重建」腿；
    # 防上次 run 的内容残留在树里让 done_when 文本探测提前满足）
    for pkg in spec.get("pm_clear", []):
        if cleared.get(pkg):
            continue
        if pkg == "org.fossify.notes":
            cleanup_notes()
        else:
            print("  %s: 无专属清理器（非产物型 App，沿用现有状态）" % pkg)
    for pkg in spec.get("pm_clear", []):
        # ColorOS：首启通知授权/权限弹窗对 a11y 不可见（quirk3），统一在 reset 阶段
        # 代烧掉，不烧任务预算（无弹窗时空过）
        waive_first_run_dialogs(pkg)
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
    ap.add_argument("--prewarm", action="store_true",
                    help="one-shot: apply device profile + dismiss brand-specific first-launch warnings")
    args = ap.parse_args()

    if args.prewarm:
        prewarm()
        return
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
