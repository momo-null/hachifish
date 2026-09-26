#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M1' DoD② 对拍测试脚手架：u2(XML) vs Hachimi Accessibility(JSON) 同屏语义一致性。

方法：同一前台屏幕上，两个后端各取一次 UI 树，提取「语义节点」（有文本/可点击/可编辑），
按三档口径比对：
  L1 严格：bounds + text + id 三元组完全一致
  L2 宽松：bounds 相同（几何完全对齐）
  L3 文本：非空文本集合的交集覆盖
一致性 = 2|A∩B| / (|A|+|B|)（Jaccard 同型）。

运行：.tools/python312/python.exe harness/parity/m1_parity.py
"""
import json
import re
import subprocess
import sys
import time

import uiautomator2 as u2
from lxml import etree

ADB = r"D:/code/hachimi/.tools/platform-tools/adb.exe"
SERIAL = "REDACTED_SERIAL"
REPORT = r"D:/code/hachimi/harness/checks/m1_parity_report.txt"


def adb(*args, timeout=60):
    r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "ignore")


def bridge(prim, extra=None, wait=1.5):
    """调用 Hachimi 调试桥原语，结果经 App 私有文件 + run-as 读取（绕开 logcat 4KB 截断）。"""
    cmd = (f"am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
           f"-n com.hachimi.app/.DebugBridgeReceiver --es prim {prim}")
    for k, v in (extra or {}).items():
        cmd += f" --es {k} '{v}'"
    adb("shell", cmd)
    time.sleep(wait)
    out = adb("exec-out", "run-as", "com.hachimi.app", "cat", "files/bridge_result.json")
    if not out.strip():
        raise RuntimeError("bridge no response")
    return json.loads(re.search(r"\{.*\}", out, re.S).group(0))


A11Y_HACHIMI = "com.hachimi.app/com.hachimi.app.HachimiAccessibilityService"


def force_rebind():
    """恢复配方（实测有效，按序）：
    1. force-stop u2 的 instrumentation（它会独占/挤掉 a11y 绑定）
    2. am kill 本应用进程
    3. settings 开关切换（disable→enable 触发系统重绑）"""
    adb("shell", "am force-stop com.github.uiautomator")
    adb("shell", "am kill com.hachimi.app")
    time.sleep(1.5)
    cur = adb("shell", "settings get secure enabled_accessibility_services").strip()
    without = ":".join(p for p in cur.split(":") if p and p != A11Y_HACHIMI)
    with_h = (without + ":" + A11Y_HACHIMI) if without else A11Y_HACHIMI
    adb("shell", f"settings put secure enabled_accessibility_services {without}")
    time.sleep(1.5)
    adb("shell", f"settings put secure enabled_accessibility_services {with_h}")
    time.sleep(2)


def wait_bound(timeout_s=75):
    """冷启动需要数秒（broadcast 拉起进程），未响应≠未绑定；强制恢复只做一次，
    恢复后给足重绑时间再轮询——否则会杀掉刚恢复的绑定。"""
    deadline = time.time() + timeout_s
    forced = False
    last_err = ""
    while time.time() < deadline:
        try:
            r = bridge("observe", wait=2.5)
            if r["result"].get("ok"):
                print("  a11y bound")
                return
            last_err = str(r["result"].get("error"))
        except Exception as e:
            last_err = type(e).__name__
        if not forced and time.time() > deadline - 45:
            print(f"  force rebind (last: {last_err}) ...")
            force_rebind()
            forced = True
        time.sleep(4)
    raise RuntimeError(f"accessibility service not bound within {timeout_s}s (last: {last_err})")


def semantic_u2(xml, app_pkg):
    """解析 u2 XML。按 package 过滤到应用窗口——u2 dump 含状态栏/导航栏/输入法等
    系统窗口节点（各自带不同 package），a11y rootInActiveWindow 只含应用窗口，
    不按包名限定范围会引入纯窗口差异（一致性口径必须定义在同一范围）。"""
    root = etree.fromstring(xml.encode("utf-8"))
    nodes = []
    for n in root.iter("node"):
        if n.get("package") != app_pkg:
            continue
        text = n.get("text") or ""
        rid = n.get("resource-id") or ""
        clickable = n.get("clickable") == "true"
        if not (text or clickable or rid):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        nodes.append({"bounds": tuple(map(int, m.groups())),
                      "text": text, "rid": rid.split("/")[-1] if rid else ""})
    return nodes


def semantic_a11y(tree):
    nodes = []
    for n in tree:
        text = n.get("text") or ""
        rid = n.get("view_id") or ""
        clickable = bool(n.get("clickable"))
        if not (text or clickable or rid):
            continue
        b = n.get("bounds")
        nodes.append({"bounds": tuple(b), "text": text,
                      "rid": rid.split("/")[-1] if rid else ""})
    return nodes


def compare(a, b, label):
    sa, sb = set(a), set(b)
    inter = sa & sb
    union = sa | sb
    j = len(inter) / len(union) if union else 1.0
    print(f"  {label}: |u2|={len(sa)} |a11y|={len(sb)} 交={len(inter)} 一致率={j:.1%}")
    return j


def main():
    # 顺序关键（实测：u2 的 instrumentation 连接会挤掉 a11y 绑定）：
    # 先恢复并抓取 a11y 数据，最后才连 u2
    print("== wait a11y bound ==")
    wait_bound()

    print("== launch notes ==")
    bridge("launch_app", {"package": "org.fossify.notes"})
    time.sleep(2.5)

    # 统一屏幕状态：收起输入法（u2 dump 含 IME 全窗口，a11y rootInActiveWindow 只含
    # 应用窗口；键盘不收起会引入纯窗口范围差异，污染一致性口径）
    subprocess.run([ADB, "shell", "input", "keyevent", "111"], capture_output=True, timeout=30)
    time.sleep(1.5)

    print("== dump Hachimi (a11y JSON) 先抓 ==")
    obj = bridge("observe")
    if not obj["result"].get("ok"):
        raise RuntimeError("observe failed: " + str(obj["result"]))
    n_a11y = semantic_a11y(obj["result"]["tree"])

    print("== connect u2 + dump (XML) 后抓 ==")
    d = u2.connect(SERIAL)
    # 应用窗口范围 = 本次实验启动的包（app_current 在过渡期可能返回桌面，不可靠）
    app_pkg = "org.fossify.notes"
    xml = d.dump_hierarchy()
    n_u2 = semantic_u2(xml, app_pkg)

    print("== compare ==")
    k_strict = lambda x: (x["bounds"], x["text"], x["rid"])
    k_bounds = lambda x: (x["bounds"], x["text"])
    j_strict = compare([k_strict(x) for x in n_u2], [k_strict(x) for x in n_a11y], "L1 严格(bounds+text+id)")
    j_bounds = compare([k_bounds(x) for x in n_u2], [k_bounds(x) for x in n_a11y], "L2 几何(bounds+text)")
    tu = {x["text"] for x in n_u2 if x["text"]}
    ta = {x["text"] for x in n_a11y if x["text"]}
    j_text = len(tu & ta) / len(tu | ta) if (tu or ta) else 1.0
    print(f"  L3 文本集合一致率: {j_text:.1%}  (u2 {len(tu)} 条 / a11y {len(ta)} 条)")

    lines = [
        "M1' 对拍脚手架报告 · u2(XML) vs Hachimi a11y(JSON) · 2026-09-26",
        f"前台屏幕: Fossify Notes 列表/编辑页",
        f"节点数: u2={len(n_u2)} a11y={len(n_a11y)}",
        f"L1 严格(bounds+text+id) 一致率: {j_strict:.1%}",
        f"L2 几何(bounds+text)   一致率: {j_bounds:.1%}",
        f"L3 文本集合            一致率: {j_text:.1%}",
        "",
        "注：本报告为树级对拍（同一屏幕两后端语义一致率）。",
        "DoD② 完整口径（同一任务全轨迹一致率≥90%）在任务级对拍用例中累计验证。",
    ]
    report = "\n".join(lines)
    print()
    print(report)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write(report + "\n")


if __name__ == "__main__":
    main()
