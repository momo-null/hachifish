#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M1' 时延基准：八原语真机执行时延 P50/P95（设备侧计时，不含 adb 往返）。

口径对应预注册 §5.3「每次 action 50-300ms 异步」与 M1' DoD③。
前提：Hachimi 已开启无障碍、Fossify Notes 已安装。
运行：py -3 harness/bench/m1_latency.py
"""
import json
import re
import subprocess
import sys
import time

ADB = r"D:/code/hachimi/.tools/platform-tools/adb.exe"
BC = "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE -n com.hachimi.app/.DebugBridgeReceiver"
N = 12          # 每原语采样次数
SLEEP = 1.2     # 迭代间隔（秒）

NOTE_TEXT = "111111\n14447\nHachimi-M1-evidence-0926"  # 保留用户原内容

# (prim, extras, 备注) —— press/launch_app 有导航副作用，只单测不进分布统计
BENCH = [
    ("observe",   [], ""),
    ("tap_by_id", ["--es", "view_id", "org.fossify.notes:id/text_note_view"], "聚焦输入框"),
    ("tap_xy",    ["--es", "x", "0.5", "--es", "y", "0.5"], "点击文本区"),
    ("type_text", ["--es", "text", NOTE_TEXT], "整体替换为同内容"),
    ("gesture",   ["--es", "points", "[[0.5,0.8],[0.5,0.4]]"], "文本区内上滑"),
    ("screenshot", [], "全屏截图"),
]


def sh(remote_cmd):
    subprocess.run([ADB, "shell", remote_cmd], stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE, timeout=30)


def main():
    collect_only = "--collect-only" in sys.argv
    if not collect_only:
        # 前置：激活 App（避免 stopped 状态吞广播）→ 清 logcat → 打开便签
        subprocess.run([ADB, "shell", "am", "start", "-n", "com.hachimi.app/.MainActivity"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        time.sleep(3)
        sh(BC + " --es prim launch_app --es package org.fossify.notes")
        time.sleep(3)
        subprocess.run([ADB, "logcat", "-c"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        for prim, extras, _ in BENCH:
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

    rows = []
    for prim, _, note in BENCH:
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
        "M1' 八原语时延基准（设备侧计时，ms）· 2026-09-26 · 设备 REDACTED_SERIAL · N=%d/原语" % N,
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
    with open(r"D:/code/hachimi/harness/checks/m1_latency_benchmark.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
