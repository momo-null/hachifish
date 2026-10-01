# -*- coding: utf-8 -*-
"""低方差判据（P2-R）：对知识敏感、对模型抖动不敏感。

动机：步数是高方差指标（同任务 13~103 步），知识带来的边际被完全淹没
（n=10/组收口实验三个口径全部不显著）。改用二值/低方差判据：

  first_action_hit : 首个操作动作是否命中注入技能序列的首步（通常是 launch_app 包名）
  path_prefix_hit  : 模型动作序列（脱敏）与注入序列的**前缀**匹配率（只看开头几步）
  used_skill_tool  : 是否调用过 load_skill / search_skill（主动用知识的证据）
  inject_hit       : 注入块里是否含与当前任务同 App 的技能（注入命中率）

用法：
  python lowvar_judge.py --task t5 --snapshot seed_t1x3_active
  （B 组注入内容由冻结快照重建；A 组无注入 => 各项为 0/False）
"""
import ast
import json
import os
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "kernel"))
sys.path.insert(0, os.path.join(ROOT, "harness"))

PERCEPTION = {"observe", "look", "screenshot"}
SKILL_TOOLS = {"load_skill", "search_skill"}


def traj(run_id):
    subprocess.run(["adb", "forward", "tcp:18071", "tcp:8071"], capture_output=True)
    raw = urllib.request.urlopen(
        "http://127.0.0.1:18071/trajectory/" + run_id, timeout=30).read().decode()
    try:
        d = ast.literal_eval(raw)
    except Exception:
        d = json.loads(raw)
    return d.get("lines") or d.get("trajectory") or []


def inject_ops(snapshot):
    """冻结快照 → 注入技能的操作序列（脱敏），无快照返回空表。"""
    if not snapshot:
        return []
    tmp = tempfile.mkdtemp(prefix="kb_snap_")
    tar = os.path.join(ROOT, "harness", "experiments", "kb_snapshots",
                       snapshot + ".tar")
    subprocess.run(["tar", "-xf", tar, "-C", tmp], capture_output=True)
    os.environ["HACHIMI_DATA_DIR"] = tmp
    from hachimi_kernel.skill_library import SkillLibrary
    from hachimi_kernel.bridge import _skill_ops
    ops = []
    for f in sorted(SkillLibrary._global_dir().glob("*.md")):
        s = SkillLibrary._parse(f)
        if s is not None:
            ops.append(_skill_ops(s))
    return ops


def _canon(line):
    a = line.get("action") or {}
    tool = a.get("tool")
    if not tool or tool in PERCEPTION or tool in ("task_done", "verify_done", "wait"):
        return None
    return (tool, json.dumps(a.get("args") or {}, ensure_ascii=False, sort_keys=True))


def judge(lines, ops, objective=""):
    acts = [c for c in (_canon(l) for l in lines) if c]
    if not ops or not acts:
        return {"first_action_hit": 0, "path_prefix_hit": 0.0,
                "used_skill_tool": 0, "inject_hit": 0, "n_actions": len(acts)}
    # 注入序列：按 " → " 切分后的动作名 + 参数
    inj = []
    for o in ops:
        for part in str(o).split("→"):
            part = part.strip()
            if part:
                inj.append(part)
    # 1) 首个动作是否命中注入序列首步
    first = acts[0]
    first_hit = 0
    if inj:
        head = inj[0]
        if first[0] in head or head.split("(")[0] == first[0]:
            first_hit = 1
    # 2) 前缀匹配率：模型前 k 个动作里，有多少能在注入序列（同序）里找到同名动作
    k = min(5, len(acts))
    names_inj = [p.split("(")[0].strip() for p in inj]
    matched, want = 0, 0
    for tool, _ in acts[:k]:
        want += 1
        if tool in names_inj:
            matched += 1
    prefix = matched / max(1, want)
    # 3) 主动用知识工具
    used = 1 if any((l.get("action") or {}).get("tool") in SKILL_TOOLS
                    for l in lines) else 0
    # 4) 注入命中：注入块是否含与 objective 同 App 的技能
    hit = 0
    if objective:
        import re
        pkgs = set(re.findall(r"[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+", objective))
        if pkgs and any(p in " ".join(ops) for p in pkgs):
            hit = 1
    return {"first_action_hit": first_hit, "path_prefix_hit": round(prefix, 2),
            "used_skill_tool": used, "inject_hit": hit, "n_actions": len(acts)}


def main():
    task, snapshot = "t5", None
    if "--task" in sys.argv:
        task = sys.argv[sys.argv.index("--task") + 1]
    if "--snapshot" in sys.argv:
        snapshot = sys.argv[sys.argv.index("--snapshot") + 1]

    base = os.path.join(ROOT, "harness", "experiments", "results", "dod4_%s" % task)
    ops = inject_ops(snapshot)
    # objective 不在结果行里（只存了 task id），从任务集取
    tasks_file = os.path.join(ROOT, "harness", "experiments", "tasks.json")
    objectives = {t["id"]: t["objective"]
                  for t in json.load(open(tasks_file, encoding="utf-8"))["tasks"]}
    print("injected skills: %d" % len(ops))
    for group in ("A", "B"):
        p = os.path.join(base, "%s.jsonl" % group)
        if not os.path.isfile(p):
            continue
        rows = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
        rows = [r for r in rows if r.get("success")]
        g_ops = ops if group == "B" else []      # A 组无注入
        agg = {"first_action_hit": [], "path_prefix_hit": [],
               "used_skill_tool": [], "inject_hit": []}
        for r in rows:
            j = judge(traj(r["run_id"]), g_ops,
                      objective=objectives.get(r.get("task", ""), ""))
            for k in agg:
                agg[k].append(j[k])
        n = len(rows)
        print("\n== %s (n=%d) ==" % (group, n))
        if not n:
            continue
        for k, v in agg.items():
            print("  %-16s %.2f   %s" % (k, sum(v) / len(v), v))


if __name__ == "__main__":
    main()
