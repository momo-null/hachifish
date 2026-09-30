# -*- coding: utf-8 -*-
"""bridge delete_task_json 数据面测试（2026-09-30 需求⑤-①）：
删除任务 = TaskStore.remove(meta) + 清理 tasks/<tid>/ 全部资产；
运行中拒绝；不存在 ok:false。纯标准库离线跑，GLOBAL 指临时目录隔离。
"""

import sys
import os
import json
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge  # noqa: E402
from hachimi_kernel import runtime_paths as P  # noqa: E402
from hachimi_kernel.task_store import TaskStore  # noqa: E402
from hachimi_kernel.trajectory import TrajectoryStore  # noqa: E402


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_delete_test_")
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

    # 1) 建任务 + 落轨迹资产（trajectory jsonl + run.json + task_dir 目录）
    meta = TaskStore.create("便签创建「买牛奶」", "")
    tid = meta["task_id"]
    st = TrajectoryStore(tid, "r1")
    st.log_step(state="device", observation={},
                action={"tool": "observe", "args": {"compact": True}},
                result={"ok": True}, metrics={}, verified=False)
    st.finish_run(objective="便签创建「买牛奶」", done_when="", expected=None,
                  backend="accessibility", brain_model="m", exec_model="accessibility",
                  success=True, steps=1, brain_calls=1, decision_steps=1,
                  action_count=1, retry_count=0, failures=0, recoveries=0)
    task_dir = P.task_dir(tid)
    check("删除前 task_dir 存在且有资产",
          task_dir.exists() and len(list(task_dir.glob("*.jsonl"))) >= 1)

    # 2) 删除：meta + task_dir 全清，list 不再含它
    r = json.loads(bridge.delete_task_json(tid))
    check("删除返回 ok", r.get("ok") is True and r.get("task_id") == tid)
    check("删除后 task_dir 消失", not task_dir.exists())
    check("删除后 meta 消失", TaskStore.get(tid) is None)
    check("list 不再含该任务",
          all(m.get("task_id") != tid for m in TaskStore.list()))

    # 3) 不存在 id：诚实 ok=false（不误报成功）
    r = json.loads(bridge.delete_task_json("t_nope"))
    check("不存在 id ok=false", r.get("ok") is False)

    print("\n%d failed" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
