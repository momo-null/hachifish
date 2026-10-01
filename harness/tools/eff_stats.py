# -*- coding: utf-8 -*-
"""去噪步数统计（P2-R Phase 0 测量工具；迁自本地 .tools/eff_stats.py）。

动机：这批轨迹里几乎每个动作后都跟一次 observe，总步数大半在计量
「模型重看了几次屏幕」，掩盖知识注入的真实效应。指标口径：

  steps_total      : 循环步数（产品当前口径）
  perception_steps : observe / look / screenshot（纯确认动作）
  effective_steps  : steps_total - perception_steps（真实编辑 / 导航）

用法：
  python eff_stats.py --task t1
  python eff_stats.py --task t5
"""
import ast
import json
import os
import subprocess
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys_path = os.path.dirname(HERE)
import sys                                          # noqa: E402
sys.path.insert(0, sys_path)
from _env import ROOT as R_ROOT                     # noqa: E402,F401

PERCEPTION = {"observe", "look", "screenshot"}


def traj(run_id):
    subprocess.run(["adb", "forward", "tcp:18071", "tcp:8071"], capture_output=True)
    raw = urllib.request.urlopen(
        "http://127.0.0.1:18071/trajectory/" + run_id, timeout=30).read().decode()
    try:
        d = ast.literal_eval(raw)
    except Exception:
        d = json.loads(raw)
    return d.get("lines") or []


def score(task):
    root = os.path.join(R_ROOT, "harness", "experiments", "results", "dod4_%s" % task)
    out = []
    for group in ("A", "B"):
        path = os.path.join(root, "%s.jsonl" % group)
        if not os.path.isfile(path):
            continue
        for l in open(path, encoding="utf-8"):
            if not l.strip():
                continue
            r = json.loads(l)
            if not r.get("success"):
                out.append({"group": group, "run_index": r.get("run_index"),
                            "steps_total": r.get("steps"), "perception": None,
                            "effective": None, "wall": r.get("wall_clock_s"),
                            "failed": True})
                continue
            lines = traj(r["run_id"])
            perc = sum(1 for ln in lines
                       if ((ln.get("action") or {}).get("tool")) in PERCEPTION)
            total = r.get("steps")
            out.append({"group": group, "run_index": r.get("run_index"),
                        "steps_total": total, "perception": perc,
                        "effective": total - perc,
                        "wall": r.get("wall_clock_s"), "failed": False})
    return out


def show(rows):
    for group in ("A", "B"):
        g = [r for r in rows if r["group"] == group]
        if not g:
            continue
        print("== %s ==" % group)
        for r in g:
            print("  run%-2s total=%-4s perception=%-4s effective=%-4s wall=%ss%s" % (
                r["run_index"], r["steps_total"], r["perception"], r["effective"],
                r["wall"], "  FAILED" if r.get("failed") else ""))
        ok = [r for r in g if not r.get("failed")]
        if ok:
            print("  mean  total=%.1f  effective=%.1f  perception=%.1f  wall=%.0fs" % (
                sum(r["steps_total"] for r in ok) / len(ok),
                sum(r["effective"] for r in ok) / len(ok),
                sum(r["perception"] for r in ok) / len(ok),
                sum(r["wall"] for r in ok) / len(ok)))


def main():
    task = "t1"
    if "--task" in sys.argv:
        task = sys.argv[sys.argv.index("--task") + 1]
    show(score(task))


if __name__ == "__main__":
    main()
