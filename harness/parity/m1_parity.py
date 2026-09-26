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


def bridge(prim, extra=None):
    """调用 Hachimi 调试桥原语并从 logcat 取回结果 JSON。"""
    cmd = (f"am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
           f"-n com.hachimi.app/.DebugBridgeReceiver --es prim {prim}")
    for k, v in (extra or {}).items():
        cmd += f" --es {k} '{v}'"
    adb("shell", cmd)
    time.sleep(1.0)
    out = adb("logcat", "-d", "-s", "HachimiDebug:*", "-t", "5")
    lines = [l for l in out.splitlines() if "HachimiDebug" in l]
    if not lines:
        raise RuntimeError("bridge no response")
    obj = json.loads(re.search(r"\{.*\}", lines[-1].split("HachimiDebug: ", 1)[1]).group(0))
    return obj


def semantic_u2(xml):
    nodes = []
    for n in etree.fromstring(xml.encode("utf-8")).iter("node"):
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
    print("== connect u2 ==")
    d = u2.connect(SERIAL)
    info = d.info  # 触发 agent 就绪
    print("  u2 device ready:", info.get("displayRotation") is not None)

    # 统一前台：便签列表
    bridge("launch_app", {"package": "org.fossify.notes"})
    time.sleep(2.5)

    print("== dump u2 (XML) ==")
    xml = d.dump_hierarchy()
    n_u2 = semantic_u2(xml)

    print("== dump Hachimi (a11y JSON) ==")
    obj = bridge("observe")
    if not obj["result"].get("ok"):
        raise RuntimeError("observe failed: " + str(obj["result"]))
    n_a11y = semantic_a11y(obj["result"]["tree"])

    print("== compare ==")
    j_strict = compare([x for x in n_u2], [x for x in n_a11y], "L1 严格(bounds+text+id)")
    j_bounds = compare([(x["bounds"], x["text"]) for x in n_u2],
                       [(x["bounds"], x["text"]) for x in n_a11y], "L2 几何(bounds+text)")
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
