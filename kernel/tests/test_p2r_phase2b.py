# -*- coding: utf-8 -*-
"""P2-R Phase 2b 测试：上下文卸载（observe 整树 → 可操作骨架 + ref 下钻）。

对应 doc/knowledge_redesign.md §3.4：
1. observe 的完整树照旧进轨迹 step.result（证据不丢）；
2. 发给 LLM 的 tool 消息只含可操作骨架（view_id / 文本 / click / 中心点），
   零宽僵尸节点与预算截断生效；
3. 非 observe 工具结果原样透传。
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                 # noqa: E402
from hachimi_kernel.context_offload import (                  # noqa: E402
    compress_observe, offload_tool_result,
)
from hachimi_kernel.mini_loop import MiniLoop                 # noqa: E402


def _node(vid="", text="", clickable=False, bounds=None):
    return {"view_id": vid, "text": text, "clickable": clickable,
            "bounds": bounds or [0, 0, 0, 0]}


def _observe_result():
    return {"ok": True, "tree": [
        _node("android:id/content", clickable=False, bounds=[0, 0, 1080, 2400]),
        _node("org.fossify.notes:id/new_note", "新建笔记", True, [816, 131, 960, 275]),
        _node("", "已删除条目僵尸", False, [0, 305, 0, 373]),   # 零宽：应被剔除
        _node("org.fossify.notes:id/text_note_view", "请在此插入文字", True,
              [0, 299, 1080, 1545]),
    ]}


def test_compress_keeps_operable_and_drops_zombies():
    out = compress_observe(_observe_result(), ref="step #3")
    sk = "\n".join(out["nodes"])
    assert "new_note" in sk and "text_note_view" in sk
    assert "已删除条目僵尸" not in sk            # 零宽僵尸被剔除
    assert "step #3" in out["screen"]            # ref 可下钻
    assert out["ok"] is True


def test_budget_truncates():
    big = {"ok": True, "tree": [
        _node("id_%03d" % i, "t%d" % i, True, [0, i * 10, 100, i * 10 + 8])
        for i in range(100)]}
    out = compress_observe(big, ref="step #1")
    assert len(out["nodes"]) <= 41               # 40 行上限 + 可能有 budget 行
    assert "truncated" in out


def test_non_observe_passthrough():
    r = {"ok": False, "error": "boom"}
    assert offload_tool_result("tap_by_id", r) is r


def test_loop_message_offloaded_but_trajectory_full():
    captured = {}

    class Brain(object):
        calls = 0

        def chat(self, messages, tools):
            Brain.calls += 1
            if Brain.calls == 1:
                captured["msgs"] = [dict(m) for m in messages]
                return {"content": None, "tool_calls": [
                    {"name": "observe", "args": {}}]}
            # 第二轮：检查 tool 消息已是骨架
            tool_msgs = [m for m in messages if m.get("role") == "tool"]
            captured["tool_content"] = json.loads(tool_msgs[-1]["content"])
            return {"content": "done", "tool_calls": []}

    seen_steps = []

    def fake_observe(args):
        return _observe_result()

    loop = MiniLoop(objective="look around", done_when="",
                    tools={"observe": {"description": "", "parameters": {},
                                       "call": fake_observe}},
                    brain=Brain(), config={"max_steps": 2}, message="go")
    orig_record = loop._record

    def rec(step_no, name, args, result, verified, retry):
        step = orig_record(step_no, name, args, result, verified, retry)
        seen_steps.append(step)
        return step

    loop._record = rec
    out = loop.run()
    assert out["status"] == "chat"
    # 消息侧：骨架 + ref
    tc = captured["tool_content"]
    assert "screen skeleton" in tc.get("screen", "")
    assert "step #1" in tc["screen"]
    # 轨迹侧：完整树原样保留（证据不丢）
    assert seen_steps[0]["result"]["tree"] == _observe_result()["tree"]
