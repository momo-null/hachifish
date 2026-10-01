# -*- coding: utf-8 -*-
"""P2 DoD4 · 单任务 A/B 对照（验证「越用越顺手」）。

判据口径（doc/product_milestones.md P2 DoD4）：同一任务在「有知识」条件下的
步数应低于「无知识」基线；每次 run 的指标 = steps / wall_clock / brain_calls。

两组区别：
- **A（无知识基线）**：每个 run 前清空设备端知识（memory/ + skills/），
  kernel 注入块恒空 —— 对应冷启动。
- **B（有知识）**：连续跑，**不清空**；前一个 run 成功后 Curator 蒸馏出的
  facts / skill 会在下一次 run 之前注入（DoD4 机制要求「下一次 run 就能看到
  本次沉淀」）。

用法：
  python dod4_ab.py --task T1 --group A --runs 1   # 冒烟单跑
  python dod4_ab.py --task T1 --group A --runs 3   # A 组 3 次
  python dod4_ab.py --task T1 --group B --runs 3   # B 组 3 次（接续 A，知识累积）

产出：harness/experiments/results/dod4_<task>/<group>.jsonl（追加写，中断不丢）
"""
import argparse
import ast
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
from _env import adb, LOOPBACK          # noqa: E402

import reset as R                        # noqa: E402

TASKS_FILE = os.path.join(HERE, "tasks.json")
POLL_S = 2.0
# max_steps 已放大到 1000（任务集统一），但每步仍需一次 LLM 往返（实测 2-5s/步），
# 1000 步 ≈ 40+ 分钟；墙钟取内核默认 900s 兜底：既不再出现「26 步截断」，也避免
# 单个失败样本空转一小时。想要彻底不限，把这里调大即可。
WALL_CLOCK_S = 900
RUN_TIMEOUT_S = WALL_CLOCK_S + 300


def parse_repr(text):
    """兼容 python-repr 与 JSON（环回两端 historical 两种形态，见 runner.py）。"""
    try:
        return ast.literal_eval(text)
    except Exception:
        return json.loads(text)


def http(method, path, body=None, timeout=20):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(LOOPBACK + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return parse_repr(r.read().decode("utf-8"))


def clear_knowledge():
    """清空设备端知识（A 组每个 run 前）+ 记录当时确实为空。"""
    adb("shell", "run-as", "com.hachimi.app", "rm", "-rf",
        "files/memory", "files/skills")
    time.sleep(1)
    return True


def knowledge_snapshot():
    """知识概况（供每 run 前后对照：是否真的有东西可注入）。"""
    out = adb("shell", "run-as", "com.hachimi.app", "sh", "-c",
              "'ls files/memory files/memory/rollouts files/skills 2>/dev/null | tr \"\\n\" \" | \"'")
    return " ".join(out.split())


PERCEPTION = {"observe", "look", "screenshot"}


def _denoise(run_id):
    """拉轨迹算去噪口径（P2-R Phase 4 判据）：perception / effective。"""
    try:
        d = http("GET", "/trajectory/%s" % run_id, timeout=30) or {}
        lines = d.get("lines") or d.get("trajectory") or []
        perc = sum(1 for ln in lines
                   if ((ln.get("action") or {}).get("tool")) in PERCEPTION)
        return perc
    except Exception:
        return None


def run_task(task):
    """跑一次任务，返回结果 dict（含 steps / wall_clock / brain_calls / success）。"""
    body = {"objective": task["objective"], "done_when": task["done_when"],
            "max_steps": task["max_steps"], "wall_clock_s": WALL_CLOCK_S}
    R.forward()
    resp = http("POST", "/task", body)
    run_id = (resp or {}).get("run_id")
    t0 = time.time()
    phase, summary = "timeout", {}
    while time.time() - t0 < RUN_TIMEOUT_S:
        time.sleep(POLL_S)
        s = http("GET", "/status") or {}
        phase = s.get("phase") or phase
        if phase in ("done", "error"):
            summary = s.get("summary") or {}
            break
    steps = (summary or {}).get("steps")
    perc = _denoise(run_id) if run_id else None
    return {
        "task": task["id"], "run_id": run_id, "phase": phase,
        "success": phase == "done" and (summary or {}).get("status") == "done",
        "steps": steps,
        "perception": perc,
        "effective": (steps - perc) if (steps is not None and perc is not None) else None,
        "brain_calls": (summary or {}).get("brain_calls"),
        "wall_clock_s": (summary or {}).get("wall_clock_s"),
        "task_done_rejected": (summary or {}).get("task_done_rejected"),
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", default="T1")
    ap.add_argument("--group", choices=["A", "B"], required=True)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--no-clean", action="store_true",
                    help="B 组首跑也不清知识（继承上一任务的沉淀）")
    args = ap.parse_args()

    with open(TASKS_FILE, encoding="utf-8") as f:
        task = {t["id"]: t for t in json.load(f)["tasks"]}[args.task]

    out_dir = os.path.join(HERE, "results", "dod4_%s" % args.task.lower())
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "%s.jsonl" % args.group)

    print("DoD4 %s x%d [%s]" % (args.task, args.runs, args.group))
    for i in range(args.runs):
        print("\n== run %d/%d ==" % (i + 1, args.runs))
        if args.group == "A" or (i == 0 and not args.no_clean):
            # A 每次清；B 默认只在首跑前清（两组从同一「零知识」起点出发，
            # 差别纯粹来自跑过程中的沉淀，B2/B3 才带知识）。
            # --no-clean：B 组连首跑都不清 —— 用于「继承上一任务的沉淀」这一
            # P2 主场景（knowledge 是跨同 App 任务迁移的资产，不是同任务反复下棋）。
            clear_knowledge()
            print("  knowledge cleared -> %s" % knowledge_snapshot())
        else:
            print("  knowledge kept -> %s" % knowledge_snapshot())
        R.reset_task(task)
        result = run_task(task)
        result["run_index"] = i + 1
        print("  -> success=%s steps=%s (perc=%s eff=%s) brain_calls=%s wall=%ss" % (
            result["success"], result["steps"], result["perception"],
            result["effective"], result["brain_calls"], result["wall_clock_s"]))
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(result, ensure_ascii=False) + "\n")

    rows = [json.loads(l) for l in open(out_path, encoding="utf-8") if l.strip()]
    steps = [r.get("steps") for r in rows if r.get("success")]
    print("\n%s 组累计：%d run，成功 %d，steps=%s" % (args.group, len(rows), len(steps), steps))


if __name__ == "__main__":
    main()
