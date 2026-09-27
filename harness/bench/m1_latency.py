#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M1' 时延基准：八原语真机执行时延 P50/P95（设备侧计时，不含 adb 往返）。

口径对应预注册 §5.3「每次 action 50-300ms 异步」与 M1' DoD③。
前提：Hachimi 已开启无障碍、Fossify Notes 已安装。
运行：py -3 harness/bench/m1_latency.py
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _env import adb, ADB, ROOT, resolve_serial
BC = "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE -n com.hachimi.app/.DebugBridgeReceiver"
N = 12          # 每原语采样次数
SLEEP = 1.2     # 迭代间隔（秒）

# 单行：经 adb shell 传参时裸 \n 会把一行拆成多条 shell 命令（旧值因此只输入了首段）
NOTE_TEXT = "111111 14447 Hachimi-M1-evidence-0927"

# 编辑器内幂等原语；tap_by_id 不幂等（点一次目标即消失）单独 phase；screenshot 最后。
# Notes 1.7.0 实采：text_note_view = 列表页笔记条目（老版语义=编辑器文本框，已变）；
# tap_xy/gesture 路径须避开 IME 区（y>0.55），否则滑动会被输入法识别为滑行输入往
# 焦点框灌字符（2026-09-27 实录：对话框标题被灌入 12 个 '6'）。
BENCH = [
    ("observe",   [], ""),
    ("tap_xy",    ["--es", "x", "0.5", "--es", "y", "0.35"], "点击文本区"),
    ("type_text", ["--es", "args", '{"text":"%s"}' % NOTE_TEXT], "整体替换为同内容"),
    ("gesture",   ["--es", "points", "[[0.5,0.45],[0.5,0.2]]"], "文本区内上滑"),
]
TAP_BY_ID_VID = "org.fossify.notes:id/text_note_view"
BENCH_LAST = [("screenshot", [], "全屏截图")]
# 展示顺序（与预注册口径一致）
DISPLAY_ORDER = ["observe", "tap_by_id", "tap_xy", "type_text", "gesture", "screenshot"]


def sh(remote_cmd):
    subprocess.run([ADB, "shell", remote_cmd], stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE, timeout=30)


def notes_to_editor():
    """冷启动 Notes 并新建文本笔记进编辑器（确定性状态，排除上次遗留对话框/焦点）。"""
    subprocess.run([ADB, "shell", "am", "force-stop", "org.fossify.notes"],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    time.sleep(1)
    sh(BC + " --es prim launch_app --es package org.fossify.notes")
    time.sleep(3)
    for vid, wait in (("org.fossify.notes:id/new_note", 1.2),
                      ("org.fossify.notes:id/type_text_note", 0.8),
                      ("android:id/button1", 1.5)):
        r = bridge_result(" --es prim tap_by_id --es view_id " + vid, "tap_by_id")
        if not (r and r.get("result", {}).get("ok")):
            sys.exit("播种 tap %s 失败: %s" % (vid, (r or {}).get("result")))
        time.sleep(wait)


def notes_to_list():
    subprocess.run([ADB, "shell", "am", "force-stop", "org.fossify.notes"],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    time.sleep(1)
    sh(BC + " --es prim launch_app --es package org.fossify.notes")
    time.sleep(3)


def bridge_result(cmd_suffix, expect_prim, timeout=20):
    """广播 + 轮询 run-as 读回执行结果（logcat 行被截 200 字符且会被 -c 清掉，
    设置步骤必须确认真实成功，否则门控白名单/播种会静默失败，见 2026-09-27 实录）。"""
    sh(BC + cmd_suffix)
    return poll_result(expect_prim, timeout=timeout)


def poll_result(expect_prim, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = subprocess.run([ADB, "shell", "run-as com.hachimi.app cat files/bridge_result.json"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        try:
            obj = json.loads(r.stdout.decode("utf-8", "ignore"))
        except Exception:
            obj = None
        if isinstance(obj, dict) and obj.get("prim") == expect_prim:
            return obj
        time.sleep(0.5)
    return None


def wait_bridge(max_wait=40):
    """无障碍重绑完成前广播会 'accessibility not bound'；注意 observe 在部分前台窗口
    （如 ColorOS 设置页）会报 'no active window'，那是窗口不可见而非掉线——判活只看
    是否仍是 not bound。"""
    deadline = time.time() + max_wait
    while time.time() < deadline:
        r = bridge_result(" --es prim observe", "observe", timeout=8)
        if isinstance(r, dict):
            res = r.get("result", {})
            if res.get("ok") or "not bound" not in str(res.get("error", "")):
                return True
    return False


def main():
    collect_only = "--collect-only" in sys.argv
    extra = {}  # prim -> [(latency, ok)]：run-as 文件通道样本
    if not collect_only:
        # 前置①：激活 App（避免 stopped 状态吞广播），然后等桥可用（无障碍重绑需数秒，
        # 绑定完成前的设置广播会静默失败）
        subprocess.run([ADB, "shell", "am", "start", "-n", "com.hachimi.app/.MainActivity"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if not wait_bridge():
            sys.exit("bridge 不可用：无障碍服务未绑定（先在手机上开启 Hachimi 无障碍）")
        # 前置②：门控预授权。M4' 门控拦截 type_text/launch_app，未授权会弹 GateActivity
        # 在主线程 latch 阻塞 90s → ColorOS Theia 看门狗介入 → 连锁崩溃 → "process is
        # bad" 拒收后续广播。gate_allow 走白名单瞬时放行。
        for op in ("type_text", "launch_app"):
            r = bridge_result(" --es prim gate_allow --es package org.fossify.notes --es op " + op,
                              "gate_allow")
            if not (r and r.get("result", {}).get("ok")):
                sys.exit("gate_allow %s 失败: %s" % (op, r))
        sh(BC + " --es prim launch_app --es package org.fossify.notes")
        time.sleep(3)
        # 前置③：播种编辑器状态（确定性：冷启动→新建文本笔记→编辑器）
        notes_to_editor()
        subprocess.run([ADB, "logcat", "-c"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        extra = {}  # logcat 截断丢失的样本在此重收；上面已初始化，此处沿用
        for prim, extras, _ in BENCH:
            for _ in range(N):
                sh(BC + " --es prim " + prim + " " + " ".join(extras))
                r = poll_result(prim, timeout=5)
                if r:
                    extra.setdefault(prim, []).append(
                        (r.get("latency_ms", 0), r.get("result", {}).get("ok")))
                time.sleep(SLEEP)
        # tap_by_id phase：列表态测量。冷启动→列表→tap 条目(开编辑器)，逐次冷启动恢复
        # （back 方案不可靠：编辑器+IME 态第一下 back 只关键盘，状态机漂移）。
        notes_to_list()
        for _ in range(N):
            sh(BC + " --es prim tap_by_id --es view_id " + TAP_BY_ID_VID)
            r = poll_result("tap_by_id", timeout=8)
            if r:
                extra.setdefault("tap_by_id", []).append(
                    (r.get("latency_ms", 0), r.get("result", {}).get("ok")))
            time.sleep(SLEEP)
            notes_to_list()
        for prim, extras, _ in BENCH_LAST:
            for _ in range(N):
                sh(BC + " --es prim " + prim + " " + " ".join(extras))
                time.sleep(SLEEP)

    # 结果回收（logcat 输出是 UTF-8，Windows 默认 GBK 解码会崩，必须手动 bytes 解码）
    out = subprocess.run([ADB, "logcat", "-d", "-s", "HachimiDebug:*"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode("utf-8", "ignore")

    data = {}
    for line in out.splitlines():
        if "HachimiDebug" not in line:
            continue
        try:
            obj = json.loads(re.search(r"\{.*\}", line.split("HachimiDebug: ", 1)[1]).group(0))
        except Exception:
            continue
        if "latency_ms" not in obj:
            continue
        data.setdefault(obj["prim"], []).append((obj["latency_ms"], obj["result"].get("ok")))
    for k, v in extra.items():          # logcat 截断丢失的样本从文件通道并入
        if not data.get(k):
            data[k] = v

    note_by_prim = {p: n for p, _, n in
                    BENCH + [("tap_by_id", [], "聚焦输入框（列表条目，逐次恢复）")] + BENCH_LAST}
    rows = []
    for prim in DISPLAY_ORDER:
        note = note_by_prim.get(prim, "")
        key = "screenshot_result" if prim == "screenshot" else prim
        lats = [x[0] for x in data.get(key, []) if x[1]]
        n_total = len(data.get(key, []))
        if not lats:
            rows.append((prim, n_total, "-", "-", "-", "-", note))
            continue
        lats.sort()
        p50 = lats[len(lats) // 2]
        p95 = lats[min(int(0.95 * len(lats)), len(lats) - 1)]
        rows.append((prim, n_total, lats[0], p50, p95, lats[-1], note))

    lines = [
        "M1' 八原语时延基准（设备侧计时，ms）· %s · 设备 %s · N=%d/原语"
        % (time.strftime("%Y-%m-%d"), resolve_serial(), N),
        "口径：预注册 §5.3 每次 action 50-300ms；M1' DoD③",
        "",
        "%-14s %6s %8s %8s %8s %8s  %s" % ("primitive", "n", "min", "p50", "p95", "max", "备注"),
    ]
    ok_all = True
    for prim, n, mn, p50, p95, mx, note in rows:
        lines.append("%-14s %6s %8s %8s %8s %8s  %s" % (prim, n, mn, p50, p95, mx, note))
        if isinstance(p50, int) and p50 > 300:
            ok_all = False
    in_range = all(isinstance(r[3], int) and r[3] <= 300 for r in rows if r[3] != "-")
    lines.append("")
    lines.append("p50 全部 <= 300ms 口径内: %s" % ("PASS" if in_range else "FAIL"))
    with open(os.path.join(ROOT, "harness", "checks", "m1_latency_benchmark.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
