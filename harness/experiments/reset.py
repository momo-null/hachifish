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
    注意（2026-09-28 tap_xy 全灭事故）：设备上现有 APK 的 config 原语用 putString 落盘，
    而 tapXY 用 getFloat 读——写入即投毒（ClassCastException 令 tap_xy 全灭）。默认值
    1.0 根本无需写入（读取方默认就是 1.0），只有品牌专属值才写，且必须等 Kotlin 端
    类型修复随 APK 重建生效后再写非默认值。value 走 --es args JSON 形态
    （Kotlin 端独立 extras 白名单曾漏 "value"，已随类型修复一并补）。"""
    prof = current_profile()
    limit = str(prof.get("tap_bottom_limit", "1.0"))
    forward()
    if limit == "1.0":
        # 不写设备即可（读取方默认就是 1.0）。
        # 历史坑（2026-09-28）：这里曾为了擦「旧 APK 写下的字符串毒 pref」而
        #   am force-stop com.hachimi.app + run-as rm pref + 重启 MainActivity
        # ——force-stop 会当场解绑本 App 的无障碍服务，而 ColorOS/Android 12 上
        # adb 无 WRITE_SECURE_SETTINGS 且 am 无 unstop，**无法自动重绑，只能人工重开**——
        # 等于每跑一个任务就自断一次无障碍。Kotlin 端类型化写入 + tapXY 防御读取已随 APK
        # 重建落地，毒 pref 不会再产生，这段补丁连同自伤一起删除。
        print("  apply_profile: tap_bottom_limit=1.0 (default, 不写设备)")
        return prof
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


NOTES_PLACEHOLDER = "请在此插入文字"


def _strip_tabs(tree):
    """主屏分类 tab 条节点（y≈305-373，1080x2400）：无 view_id、有文本、真实宽度。
    当前 tab 不可点（其余可点）；条带可横滑，屏外 tab 以零宽节点存在。"""
    out = []
    for n in tree:
        b = n.get("bounds") or [0, 0, 0, 0]
        txt = (n.get("text") or "").strip()
        if (n.get("view_id") or "") == "" and txt and b[2] > b[0] and 290 <= b[1] <= 395:
            out.append(n)
    return out


def _notes_content(tree):
    """当前 tab 里的真实内容条目（零宽僵尸过滤 + 空态占位符排除）。"""
    return [n for n in _real_items(tree, "org.fossify.notes:id/text_note_view")
            if (n.get("text") or "") not in ("", NOTES_PLACEHOLDER)]


def _notes_delete_current():
    """删掉当前 tab 里的一条笔记：溢出菜单 -> 「删除便签」-> 确认框 button1。

    2026-09-28 实测：1.7.0 的「长按=移入回收站」早已失效（长按 1000/1500ms 树
    无任何变化），真实入口是右上角溢出菜单里的删除项，点击后弹确认框
    （delete_note_description + android:id/button1=删除）。
    返回 True=走完流程（不含删除是否生效，交给调用方的计数守卫判断）。"""
    _tap_norm(0.9, 0.085)      # 右上角溢出菜单
    time.sleep(2)
    tree = _observe_tree() or []
    target = None
    for n in tree:
        bb = n.get("bounds") or [0, 0, 0, 0]
        if bb[2] > bb[0] and "删" in (n.get("text") or ""):
            target = n
            break
    if target is None:
        return False
    bb = target["bounds"]
    _tap_norm(round((bb[0] + bb[2]) / 2 / 1080.0, 3),
              round((bb[1] + bb[3]) / 2 / 2400.0, 3))
    time.sleep(1.5)
    r = bridge_call("tap_by_id", "--es view_id android:id/button1")
    time.sleep(2)
    return bool((r or {}).get("result", {}).get("ok"))


def cleanup_notes(max_rounds=40):
    """Notes 1.7.0 状态清理 v3（pm clear 被 CLEAR_APP_USER_DATA 拒绝后的替代复位）。
    v1 只清当前 tab（历代产物散落各 tab 永远清不到）；v2 补了 tab 遍历但删除动作
    依赖长按 —— 实测已失效。v3: tab 遍历 + 溢出菜单删除路径 + 每步计数守卫。
    空 tab 本身保留（tab 名不含任何 done_when 串，无有效性威胁）。"""
    def _home():
        adb("shell", "am", "force-stop", "org.fossify.notes")
        time.sleep(1)
        bridge_call("launch_app", "--es package org.fossify.notes")
        time.sleep(3)

    def _cur_tab(tree):
        for t in _strip_tabs(tree):
            if not t.get("clickable"):
                return t.get("text")
        return None

    _home()
    visited = set()
    prev_tab = prev_count = None
    for i in range(max_rounds):
        tree = _observe_tree()
        if tree is None:
            print("  cleanup_notes: observe 失败（盲态），中止")
            return False
        content = _notes_content(tree)
        if content:
            tab, n = _cur_tab(tree), len(content)
            # 守卫只对「同一个 tab」生效：删除后冷启/切换会落到别的 tab，那里本来
            # 就还有笔记，跨 tab 比计数会误判（2026-09-28 实录：每 tab 各 1 条）。
            if prev_tab is not None and prev_tab == tab and prev_count is not None and n >= prev_count:
                print("  cleanup_notes: tab %r 第 %d 轮条目数未降（%d -> %d），中止"
                      % (tab, i + 1, prev_count, n))
                return False
            prev_tab, prev_count = tab, n
            if not _notes_delete_current():
                print("  cleanup_notes: 未命中删除菜单项，中止")
                return False
            continue
        prev_tab = prev_count = None      # 本 tab 已空，换 tab 后重新计数
        tabs = _strip_tabs(tree)
        for t in tabs:   # 当前 tab 不可点，视为已访问
            if not t.get("clickable"):
                visited.add(t["text"])
        nxt = next((t for t in tabs
                    if t.get("clickable") and t["text"] not in visited), None)
        if nxt is not None:
            visited.add(nxt["text"])
            b = nxt["bounds"]
            _tap_norm(round((b[0] + b[2]) / 2 / 1080.0, 3),
                      round((b[1] + b[3]) / 2 / 2400.0, 3))
            time.sleep(2)
            continue
        # 无未访问 tab：条带还有屏外迹象（零宽文本节点）则横滑暴露后再找一轮
        offscreen = [n for n in tree
                     if (n.get("text") or "").strip() and not n.get("view_id")
                     and (n.get("bounds") or [0, 0, 0, 0])[2] == (n.get("bounds") or [0, 0, 0, 0])[0]
                     and 290 <= n["bounds"][1] <= 395]
        if offscreen:
            bridge_call("gesture", "--es args '{\"points\":[[0.85,0.34],[0.15,0.34]],"
                        "\"duration_ms\":400}'")
            time.sleep(1.5)
            continue
        break
    else:
        print("  cleanup_notes: %d 轮后仍有内容条目" % max_rounds)
        return False
    adb("shell", "am", "force-stop", "org.fossify.notes")   # 刷僵尸节点
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.notes")
    time.sleep(3)
    tree = _observe_tree()
    ok = tree is not None and not _notes_content(tree)
    print("  cleanup_notes -> %s" % ("clean(当前 tab)" if ok else "仍有残留"))
    return ok


def _tap_norm(x, y):
    """坐标点按（gesture 双同点 100ms）。

    历史：tap_xy 原语曾因 config 用 putString 落盘而 getFloat 抛 ClassCastException
    全灭，当时只能走 gesture；2026-09-28 已随 APK 重建修好（类型化写入 + 防御读取，
    且 screenSize 改真实度量）。继续用 gesture 是为了少依赖一个原语、且与长按同一约定。"""
    return bridge_call("gesture", "--es args '{\"points\":[[%s,%s],[%s,%s]],"
                       "\"duration_ms\":100}'" % (x, y, x, y))


def _alarm_rows(tree):
    """闹钟列表的真实行（从 alarm_holder 节点里剥掉 ListView 容器）。

    2026-09-28 实录：容器自身的 view_id 也是 `alarm_holder`，bounds 覆盖整个列表
    （如 [0,299,1080,2161]），且**列表被清空后它依然存在**。直接用 holders 判空会带来两个 bug：
      1) 终止条件永不满足 -> 明明删完了却报「15 轮后仍有闹钟」；
      2) 长按坐标按 holders[0]（容器中心）算 -> 压根没落在任何一行上，行变少后按到空白处，
         CAB 不重进，后续每轮都在点「空选区的删除键」空转。
    判据：容器严格包住（contains）至少一个别的 alarm_holder，按「包住别人」剔除，剩下的就是叶子行。
    """
    # 判据换成 alarm_time：每条闹钟必然有且只有一个 alarm_time 节点（显示 08:00 等），
    # 实测「列表为空」时树里只剩容器 alarm_holder + alarms_placeholder('未找到闹钟')，
    # 没有任何 alarm_time —— 用它当计数基准不会被容器骗，也不会把空列表当成还剩 1 条。
    times = _real_items(tree, "org.fossify.clock:id/alarm_time")
    if not times:
        return []

    def _bounds(n):
        return n.get("bounds") or [0, 0, 0, 0]

    def _area(b):
        return max(0, b[2] - b[0]) * max(0, b[3] - b[1])

    def _contains(a, b):
        return (a[0] <= b[0] and a[1] <= b[1] and a[2] >= b[2] and a[3] >= b[3])

    holders = _real_items(tree, "org.fossify.clock:id/alarm_holder")
    rows = []
    for t in times:
        cand = [n for n in holders if _contains(_bounds(n), _bounds(t))]
        if cand:
            # 最内层 = 真正的行（外层 ListView 容器同样包得住它，面积更大）
            row = min(cand, key=lambda n: _area(_bounds(n)))
            if not any(row is r for r in rows):
                rows.append(row)
    rows.sort(key=lambda n: _bounds(n)[1])
    return rows


def cleanup_alarms(max_rounds=15):
    """Clock 闹钟清理（ColorOS pm clear 被锁后的替代复位）。闹钟存 App DB，
    历次 run 的种子/产物累积（2026-09-28 实录：列表堆 6+ 条 08:00 早会与上次
    07:30；T14 假阳性即 20:00 残留撞 done_when）。删除流（AlarmsAdapter）：
    长按闹钟行进 CAB 选取模式 -> 点 cab_delete（直接删，无确认框）。
    tab 导航：底部「闹钟」tab 的可点容器无 view_id，tap_by_id 打不中标签
    （TextView 不可点），只能坐标 tap (0.375, 0.940)（1080x2400 实测；tab 条 y≈2161-2352）。"""
    adb("shell", "am", "force-stop", "org.fossify.clock")
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.clock")
    time.sleep(2.5)
    _tap_norm(0.375, 0.940)   # 切闹钟 tab（App 恢复上次 tab，不可依赖；重复点无害）
    time.sleep(1.5)
    prev = None
    for i in range(max_rounds):
        tree = _observe_tree()
        if tree is None:
            print("  cleanup_alarms: observe 失败（盲态），中止")
            return False
        rows = _alarm_rows(tree)
        if not rows:
            break
        if prev is not None and len(rows) >= prev:
            # 有效的一刀必须让行数下降；不下降说明长按没进 CAB / 删到了空选区，
            # 继续下去只是空转（旧实现因此白跑 15 轮还报错）
            adb("shell", "input", "keyevent", "4")   # 兜底收 CAB
            print("  cleanup_alarms: 第 %d 轮行数未降（%d -> %d），中止"
                  % (i + 1, prev, len(rows)))
            return False
        prev = len(rows)
        b = min(rows, key=lambda n: (n.get("bounds") or [0, 0, 0, 0])[1])["bounds"]
        cy = round((b[1] + b[3]) / 2 / 2400.0, 3)
        bridge_call("gesture", "--es args '{\"points\":[[0.5,%s],[0.5,%s]],"
                    "\"duration_ms\":1000}'" % (cy, cy))   # 长按 -> CAB
        time.sleep(1.5)
        tree = _observe_tree()
        node = None
        for n in tree or []:
            vid = n.get("view_id") or ""
            bb = n.get("bounds") or [0, 0, 0, 0]
            txt = (n.get("text") or "") + (n.get("desc") or "")
            if vid.endswith("cab_delete") or ("删" in txt and bb[2] > bb[0]):
                node = n
                break
        if node is None:
            adb("shell", "input", "keyevent", "4")   # 兜底退出 CAB
            print("  cleanup_alarms: 未找到删除按钮（CAB 未进/树异常），失败")
            return False
        if node.get("view_id") and node.get("clickable"):
            bridge_call("tap_by_id", "--es view_id %s" % node["view_id"])
        else:
            bb = node["bounds"]
            _tap_norm(round((bb[0] + bb[2]) / 2 / 1080.0, 3),
                      round((bb[1] + bb[3]) / 2 / 2400.0, 3))
        time.sleep(1.5)
    else:
        print("  cleanup_alarms: %d 轮后仍有闹钟" % max_rounds)
        return False
    # 复验：force-stop 刷僵尸节点后重进闹钟列表
    adb("shell", "am", "force-stop", "org.fossify.clock")
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.clock")
    time.sleep(2.5)
    _tap_norm(0.375, 0.940)
    time.sleep(1.5)
    tree = _observe_tree()
    ok = tree is not None and not _alarm_rows(tree)
    print("  cleanup_alarms -> %s" % ("clean" if ok else "仍有残留"))
    return ok


def cleanup_files():
    """filemanager/gallery 产物清理：T4 产物 hachimi_renamed.txt、T9 Hachimi归档/、
    T7 Hachimi整理/、T12 Hachimi精选/ 均落共享存储根目录，残留即下轮 done_when
    免费命中（T9 首轮跑后 Hachimi归档 一直躺在根目录，2026-09-28 T15 轨迹实录）。
    共享存储 adb shell 可直删（rm 中文路径经 subprocess list 传递，不经 shell 引号）。"""
    for p in ("/sdcard/hachimi_renamed.txt", "/sdcard/Hachimi归档",
              "/sdcard/Hachimi整理", "/sdcard/Hachimi精选"):
        adb("shell", "rm", "-rf", p)
    print("  cleanup_files: 共享存储产物已清（hachimi_renamed/Hachimi归档/整理/精选）")
    return True


NOTES_LABEL_FIELD = "org.fossify.notes:id/locked_note_title"
NOTE_ROW_ID = "org.fossify.notes:id/text_note_view"


def _focus_note_row():
    """把焦点落到一条可编辑的便签行，返回 True=成功。

    两条腿（2026-10-01）：
    1) 列表里已有可点行（含占位符行）→ 直接 tap_by_id 进入内联编辑器；
    2) 清理后连占位行都不在树里（旧脚本因此 `view not found` 卡死，seed 报废）
       → 走工具栏 new_note 新建；若弹出 1.7.0 的新便签弹框（必须先填标签，
       否则 OK 静默无效）先填标签再确认。
    """
    if _real_items(_observe_tree() or [], NOTE_ROW_ID):
        bridge_call("tap_by_id", "--es view_id " + NOTE_ROW_ID)
        time.sleep(1.5)
        return True
    print("  seed_notes: 无占位行 -> 走工具栏 new_note 新建")
    bridge_call("tap_by_id", "--es view_id org.fossify.notes:id/new_note")
    time.sleep(2.5)
    tree = _observe_tree() or []
    # 1.7.0 的「新建便签」弹框带类型选择（文本便签 / 核对清单），默认类型会跟着
    # 上次选择走 —— 停在清单类型时新 tab 根本没有 text_note_view，seed 必然失败
    # （2026-10-01：实验跑到一半 Notes 变成 checklist tab，连续 3 次 seed 全灭）。
    # 显式点「文本便签」，把环境拉回确定态。
    if _real_items(tree, "org.fossify.notes:id/type_text_note"):
        bridge_call("tap_by_id", "--es view_id org.fossify.notes:id/type_text_note")
        time.sleep(1.5)
        tree = _observe_tree() or []
    if _real_items(tree, NOTES_LABEL_FIELD):
        bridge_call("tap_by_id", "--es view_id " + NOTES_LABEL_FIELD)
        time.sleep(1)
        # 标签必须**唯一**：与已有分类 tab 重名时 OK 静默无效、弹框不关
        # （2026-10-01：标签写死 "seed" 撞上上次留下的同名 tab，连续 3 次 seed 全灭）
        bridge_call("type_text", "--es text seed_%s"
                    % time.strftime("%H%M%S", time.localtime()))
        time.sleep(1)
        bridge_call("tap_by_id", "--es view_id android:id/button1")
        time.sleep(2.5)
        tree = _observe_tree() or []
    if not _real_items(tree, NOTE_ROW_ID):
        return False
    bridge_call("tap_by_id", "--es view_id " + NOTE_ROW_ID)
    time.sleep(1.5)
    return True


def seed_notes(retries=2):
    """T1/T5/T6/T10/T11/T13/T15 种子：重建内容含 111111 的便签。

    走 DebugBridge 原语（不经回环剧本大脑）：无 LLM token 消耗，且能按树状态做
    条件分支（2026-10-01：改为棋盘式脚本后不再被「空列表无占位行」卡死）。
    1.7.0 适配：列表条目 = 内联编辑器，点入直接 type_text 即写入并自动保存。
    前置：cleanup_notes 已跑（防旧产物让 done_when 躺赢）。
    """
    forward()
    adb("shell", "am", "force-stop", "org.fossify.notes")
    time.sleep(1)
    bridge_call("launch_app", "--es package org.fossify.notes")
    time.sleep(3)
    for attempt in range(retries + 1):
        if _focus_note_row():
            bridge_call("type_text", "--es text 111111555555555555")
            time.sleep(2)
            tree = _observe_tree() or []
            if any("111111" in (n.get("text") or "") for n in tree):
                print("  seed note 111111 via notes editor")
                return True
        print("  seed_notes: 第 %d 次没写进便签，重试" % (attempt + 1))
        adb("shell", "am", "force-stop", "org.fossify.notes")
        time.sleep(1)
        bridge_call("launch_app", "--es package org.fossify.notes")
        time.sleep(3)
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
    # 防上次 run 的内容残留在树里让 done_when 文本探测提前满足）。
    # 先统一 force-stop：launch_app 恢复的是上次 Activity 栈（T3 实录：重启直落
    # 残留的闹钟编辑器；T7 实录：launch 直落残留的选目录弹窗），必须掐掉重进。
    for pkg in spec.get("pm_clear", []):
        adb("shell", "am", "force-stop", pkg)
        time.sleep(0.5)
    for pkg in spec.get("pm_clear", []):
        if cleared.get(pkg):
            continue
        if pkg == "org.fossify.notes":
            cleanup_notes()
        elif pkg == "org.fossify.clock":
            cleanup_alarms()
        elif pkg in ("org.fossify.filemanager", "org.fossify.gallery"):
            cleanup_files()
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
