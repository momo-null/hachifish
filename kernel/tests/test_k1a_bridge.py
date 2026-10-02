# -*- coding: utf-8 -*-
"""K1a 统一入口 bridge 端到端测试（redesign_plan P2）。

覆盖：
- 首条消息（无 task_id + message）→ 自动建 task → chat 终态（闲聊不进工具循环）
- message 事件（K3）/ finish 事件带 objective / step 事件带 total（K2）
- 会话落盘（user/assistant）与第二轮历史恢复（ProjectStore session jsonl）
- chat 轮 finish_run 记 success=True（对话轮不算任务失败）

纯标准库离线跑：`py -3 tests/test_k1a_bridge.py`（FakeBrain，无 HTTP）。
"""

import json
import sys
import os
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge          # noqa: E402
from hachimi_kernel import runtime_paths as P  # noqa: E402
from hachimi_kernel.task_store import TaskStore, ProjectStore  # noqa: E402


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_k1a_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    return tmp


class NarrativeSink(object):
    def __init__(self):
        self.events = []

    def push(self, payload):
        self.events.append(json.loads(payload))


class RecordingBrain(object):
    """按脚本回纯文本；记录每次 chat 的完整消息视图。"""

    def __init__(self, replies):
        self.replies = list(replies)
        self.views = []

    def chat(self, messages, tools):
        self.views.append([(m.get("role"), m.get("content") or "")
                           for m in messages])
        return {"content": self.replies.pop(0), "tool_calls": []}


def run_sync(body):
    """直接调 _run（同步，绕后台线程）；补齐 _state 运行态。"""
    bridge._state = {"run_id": "r_test_%d" % int(time.time() * 1000),
                     "phase": "running", "objective": body.get("message", ""),
                     "started": time.time(), "summary": None,
                     "trajectory": [], "error": None}
    bridge._controls.update({"paused": False, "stopped": False})
    bridge._run(body, bridge._controls)


def main():
    setup_tmp_root()
    fails = []

    def check(name, cond):
        print(("PASS " if cond else "FAIL ") + name)
        if not cond:
            fails.append(name)

    sink = NarrativeSink()
    bridge.register_narrative(sink)
    brain = RecordingBrain(["主人你好呀～我是大肥鱼！", "主人是我的主人呀～"])
    bridge._make_brain = lambda body: brain

    # ---- 1) 首条消息：自动建 task + chat 终态 + 事件序列 ----
    run_sync({"message": "你好，你是谁？", "max_steps": 6})
    starts = [e for e in sink.events if e["event"] == "start"]
    check("start 事件带 task_id（前端续发依赖）",
          len(starts) == 1 and (starts[0].get("task_id") or "").startswith("t_"))
    msgs = [e for e in sink.events if e["event"] == "message"]
    check("闲聊产出 message 事件", len(msgs) == 1 and "大肥鱼" in msgs[0]["content"])
    finishes = [e for e in sink.events if e["event"] == "finish"]
    check("finish status=chat", len(finishes) == 1 and finishes[0]["status"] == "chat")
    check("finish 带 objective（K3）", finishes[0].get("objective") == "你好，你是谁？")
    steps_ev = [e for e in sink.events if e["event"] == "step"]
    check("闲聊零工具步", len(steps_ev) == 0)
    check("phase=done（chat 即对话成功轮）", bridge.status()["phase"] == "done")

    # task 自动建立 + 会话落盘
    r = json.loads(bridge.list_tasks_json())
    check("自动建 task（objective=首条消息）",
          r["ok"] and any(t["objective"] == "你好，你是谁？" for t in r["tasks"]))
    tid = [t["task_id"] for t in r["tasks"] if t["objective"] == "你好，你是谁？"][0]
    meta = TaskStore.get(tid)
    sess = ProjectStore.read_session(meta["project_id"], meta["session_id"])
    check("session 落盘 user+assistant",
          [m["role"] for m in sess] == ["user", "assistant"]
          and sess[0]["content"] == "你好，你是谁？"
          and "大肥鱼" in sess[1]["content"])
    # chat 轮记成功（K1c 语义：对话轮不算任务失败）——run 记录落 *.run.json
    import glob as _glob
    run_files = sorted(_glob.glob(str(P.task_trajectory(tid).parent / "*.run.json")))
    check("chat 轮 run 落盘", len(run_files) >= 1)
    last_run = json.loads(P.Path(run_files[-1]).read_text(encoding="utf-8"))
    check("chat 轮 run 记 success", last_run.get("success") is True)

    # ---- 2) 第二轮同 task：历史恢复（user/assistant 交替） ----
    run_sync({"task_id": tid, "message": "那我是谁？", "max_steps": 6})
    check("第二轮后消息视图数 = 2", len(brain.views) == 2)
    v2 = dict(brain.views[1])
    check("历史 user/assistant 交替进入上下文",
          ("user", "你好，你是谁？") in brain.views[1]
          and ("assistant", "主人你好呀～我是大肥鱼！") in brain.views[1]
          and brain.views[1][-1] == ("user", "那我是谁？"))
    check("统一入口 system 无「目标:」模板",
          not str(brain.views[1][0][1]).startswith("目标:"))
    sess2 = ProjectStore.read_session(meta["project_id"], meta["session_id"])
    check("第二轮 session 追加 2 条", len(sess2) == 4
          and sess2[2]["content"] == "那我是谁？"
          and "主人" in sess2[3]["content"])

    # ---- 3) 任务轮：step 事件带 total（K2）+ 工具循环正常 ----
    class TaskBrain(object):
        def __init__(self):
            self.n = 0

        def chat(self, messages, tools):
            self.n += 1
            if self.n == 1:
                return {"content": "好的，先看看", "tool_calls": [
                    {"name": "observe", "args": {}}]}
            return {"content": "做完了～", "tool_calls": []}

    bridge._make_brain = lambda body: TaskBrain()
    sink.events.clear()
    run_sync({"task_id": tid, "message": "帮我看看现在屏幕", "max_steps": 8})
    steps_ev = [e for e in sink.events if e["event"] == "step"]
    check("step 事件带 total（K2）",
          len(steps_ev) == 1 and steps_ev[0].get("total") == 8)
    check("任务轮穿插口播 message 事件",
          any(e["event"] == "message" and "看看" in e.get("content", "")
              for e in sink.events))
    finishes = [e for e in sink.events if e["event"] == "finish"]
    check("任务轮 chat 收尾", finishes and finishes[0]["status"] == "chat")

    # ---- 4) K6：会话消息回放（V13 切换/冷启动数据面） ----
    r = json.loads(bridge.session_messages_json(tid))
    check("session_messages ok", r["ok"])
    roles = [m["role"] for m in r["messages"]]
    check("回放 user/assistant 严格交替且以 user 开头",
          all(r0 == ("user" if i % 2 == 0 else "assistant") for i, r0 in enumerate(roles)))
    check("回放内容含三轮 user",
          sum(1 for m in r["messages"] if m["role"] == "user"
              and m["content"] in ("你好，你是谁？", "那我是谁？", "帮我看看现在屏幕")) == 3)
    check("回放带时间戳", all(m.get("ts") for m in r["messages"]))
    r_bad = json.loads(bridge.session_messages_json("t_nonexistent"))
    check("不存在 task 返回 ok:false", r_bad["ok"] is False)

    print("\n%d failed" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
