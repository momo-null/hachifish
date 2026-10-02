# -*- coding: utf-8 -*-
"""bridge 最近任务数据面测试（P1.5）：list_tasks_json 的元数据组装 / run.json 指标
回填 / 空目录与坏文件容错。纯标准库离线跑：`py -3 tests/test_bridge_tasks.py`。
"""

import sys
import os
import json
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge  # noqa: E402
from hachimi_kernel import runtime_paths as P  # noqa: E402
from hachimi_kernel.task_store import TaskStore  # noqa: E402


def setup_tmp_root():
    """runtime_paths 全局根指到临时目录（隔离本机真实任务数据）。"""
    tmp = tempfile.mkdtemp(prefix="hachimi_tasks_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    return tmp


def write_run(task_id, run_id, success, steps, brain_calls=3, wall=(0.0, 37.5)):
    """仿 finish_run 落盘 <run_id>.run.json（bridge 读取的唯一指标源）。"""
    d = P.task_trajectory(task_id).parent
    d.mkdir(parents=True, exist_ok=True)
    rec = {
        "run_id": run_id,
        "success": success,
        "steps": steps,
        "brain_calls": brain_calls,
        "retry_count": 0,
        "start_ts": "2026-09-29T14:00:%06.3f+00:00" % wall[0],
        "end_ts": "2026-09-29T14:01:%05.3f+00:00" % wall[1],  # 秒字段必须 <60
    }
    (d / ("%s.run.json" % run_id)).write_text(
        json.dumps(rec, ensure_ascii=False), encoding="utf-8")


def main():
    tmp = setup_tmp_root()
    fails = []

    def check(name, cond):
        print(("PASS " if cond else "FAIL ") + name)
        if not cond:
            fails.append(name)

    # 1) 空目录：ok=true 空列表
    r = json.loads(bridge.list_tasks_json())
    check("empty root ok", r["ok"] and r["tasks"] == [])

    # 2) 有任务：meta 组装 + run.json 指标回填（success/steps/wall）
    t_ok = TaskStore.create("便签创建「买牛奶」", "")["task_id"]
    write_run(t_ok, "r1", True, 12)
    t_bad = TaskStore.create("时钟加闹钟 07:30", "07:30")["task_id"]
    write_run(t_bad, "r2", False, 24, brain_calls=24)
    t_raw = TaskStore.create("只有元数据没有run", "")["task_id"]  # 无 run.json

    r = json.loads(bridge.list_tasks_json())
    check("count", r["ok"] and len(r["tasks"]) == 3)
    by_obj = {t["objective"]: t for t in r["tasks"]}
    check("倒序", r["tasks"][0]["objective"] == "只有元数据没有run")
    ok_t = by_obj["便签创建「买牛奶」"]
    check("success 回填", ok_t["success"] is True and ok_t["state"] == "done")
    check("steps 回填", ok_t["steps"] == 12)
    check("wall_s=97.5", abs(ok_t["wall_s"] - 97.5) < 0.1)  # 14:00:00.000 → 14:01:37.500
    bad_t = by_obj["时钟加闹钟 07:30"]
    check("failed 状态", bad_t["state"] == "failed" and bad_t["success"] is False)
    raw_t = by_obj["只有元数据没有run"]
    check("无 run 保持 pending", raw_t["state"] == "pending" and raw_t["steps"] == -1)
    check("done_when 透传", bad_t["done_when"] == "07:30")

    # 3) 坏 run.json 容错（不炸、指标回落 -1）：文件名排最后（sorted 取尾）
    (P.task_trajectory(t_ok).parent / ("zz_corrupt.run.json")).write_text("{bad", encoding="utf-8")
    r = json.loads(bridge.list_tasks_json())
    t = [x for x in r["tasks"] if x["task_id"] == t_ok][0]
    check("坏 run.json 不炸", r["ok"] and t["steps"] == -1 and t["state"] == "pending")

    # 4) limit 生效
    r = json.loads(bridge.list_tasks_json(2))
    check("limit", r["ok"] and len(r["tasks"]) == 2)

    print("\n%d failed" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
