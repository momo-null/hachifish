# -*- coding: utf-8 -*-
"""bridge 任务详情/续话数据面测试（2026-09-30 需求③④）：
task_trace_json 组装 / status() 轨迹回传 / start_task body.task_id 复用同任务。
纯标准库离线跑：`py -3 tests/test_task_trace.py`。
"""

import sys
import os
import json
import time
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge  # noqa: E402
from hachimi_kernel import runtime_paths as P  # noqa: E402
from hachimi_kernel.task_store import TaskStore  # noqa: E402
from hachimi_kernel.trajectory import TrajectoryStore  # noqa: E402


def setup_tmp_root():
    """runtime_paths 全局根指到临时目录（隔离本机真实任务数据）。"""
    tmp = tempfile.mkdtemp(prefix="hachimi_trace_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    return tmp


def main():
    setup_tmp_root()
    fails = []

    def check(name, cond):
        print(("PASS " if cond else "FAIL ") + name)
        if not cond:
            fails.append(name)

    # 1) 不存在的 task：诚实 ok=false
    r = json.loads(bridge.task_trace_json("t_nope"))
    check("task 不存在 ok=false", r["ok"] is False and r["runs"] == [])

    # 2) 有轨迹的 task：TrajectoryStore 落两步 + run.json → trace 组装
    meta = TaskStore.create("便签创建「买牛奶」", "")
    tid = meta["task_id"]
    st = TrajectoryStore(tid, "111")
    st.log_step(state="device", observation={},
                action={"tool": "observe", "args": {"compact": True}},
                result={"ok": True}, metrics={}, verified=False)
    st.log_step(state="device", observation={},
                action={"tool": "task_done", "args": {"summary": "x"}},
                result={"ok": True, "note": "verified"}, metrics={}, verified=True)
    st.finish_run(objective="便签创建「买牛奶」", done_when="", expected=None,
                  backend="accessibility", brain_model="m", exec_model="accessibility",
                  success=True, steps=2, brain_calls=2, decision_steps=2,
                  action_count=2, retry_count=0, failures=0, recoveries=0)
    r = json.loads(bridge.task_trace_json(tid))
    check("trace ok + 1 run", r["ok"] and len(r["runs"]) == 1)
    run0 = r["runs"][0]
    check("run_id 解析", run0["run_id"] == "111")
    check("run 指标挂接", (run0["run"] or {}).get("success") is True)
    check("轨迹行", len(run0["lines"]) == 2 and
          run0["lines"][0]["action"]["tool"] == "observe")

    # 3) 续话：start_task body.task_id → 复用同 task，新 run 追加进同一目录
    r = json.loads(bridge.start_task_json(json.dumps({
        "task_id": tid, "objective": "把笔记标题改一下", "max_steps": 5,
        "script": [
            {"name": "observe", "args": {"compact": True}},
            {"name": "task_done", "args": {"summary": "续话完成"}},
        ]})))
    check("续话受理", r.get("ok") is True)
    s = {}
    for _ in range(200):
        s = bridge.status()
        if s.get("phase") != "running":
            break
        time.sleep(0.05)
    check("续话跑完（留空判据=信任大脑）", s.get("phase") == "done")
    check("status 带轨迹（R3 修复）",
          isinstance(s.get("trajectory"), list) and len(s["trajectory"]) >= 2)
    jsonls = list(P.task_trajectory(tid).parent.glob("*.jsonl"))
    check("同 task 追加 run", len(jsonls) == 2)
    r2 = json.loads(bridge.task_trace_json(tid))
    check("trace 2 runs 按时间序",
          len(r2["runs"]) == 2 and r2["runs"][0]["run_id"] == "111")

    # 4) 不存在的 task_id 续话：回退新建任务（不 500、不阻塞）
    r = json.loads(bridge.start_task_json(json.dumps({
        "task_id": "t_missing", "objective": "x",
        "script": [{"name": "task_done", "args": {"summary": "s"}}]})))
    check("坏 task_id 受理（回退新建）", r.get("ok") is True)
    for _ in range(200):
        s = bridge.status()
        if s.get("phase") != "running":
            break
        time.sleep(0.05)
    check("坏 task_id 仍正常收尾", s.get("phase") == "done")

    # 5) R10：终态语义与结果文本随 run 落盘（会话回放还原终态的唯一真源）
    meta2 = TaskStore.create("R10 落盘", "")
    tid2 = meta2["task_id"]
    st_new = TrajectoryStore(tid2, "aaa")
    st_new.finish_run(objective="R10 落盘", done_when="", expected=None,
                      backend="accessibility", brain_model="m", exec_model="accessibility",
                      success=True, steps=0, brain_calls=0, decision_steps=0,
                      action_count=0, retry_count=0, failures=0, recoveries=0,
                      status="chat", summary="主人你好呀")
    rec = json.loads((P.task_trajectory(tid2).parent / "aaa.run.json").read_text(encoding="utf-8"))
    check("run.json 落盘 status（R10）", rec.get("status") == "chat")
    check("run.json 落盘 summary（R10）", rec.get("summary") == "主人你好呀")
    # 旧调用不传 → 空串（回放端按 success 推导 status，不崩）
    old = json.loads((P.task_trajectory(tid).parent / "111.run.json").read_text(encoding="utf-8"))
    check("旧 run 无 status/summary 为空串",
          old.get("status", "") == "" and old.get("summary", "") == "")

    print("\n%d failed" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
