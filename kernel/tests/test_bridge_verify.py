# -*- coding: utf-8 -*-
"""bridge 完成校验测试（P0；2026-09-30 需求④修订）：done_when 文本探测保留 /
空判据 = 信任大脑（桌面语义，弱探测移除）/ objective 传递回归。

纯标准库离线跑（不触网）：`py -3 tests/test_bridge_verify.py`。
"""

import sys
import os
import json

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge  # noqa: E402
from hachimi_kernel.mini_loop import MiniLoop  # noqa: E402


class FakeBridge(object):
    """伪 Kotlin 原语派发器：observe 返回预置树，其余原语记名。"""

    def __init__(self, tree):
        self.tree = tree
        self.calls = []

    def call(self, name, args_json):
        self.calls.append((name, args_json))
        if name == "observe":
            return json.dumps({"ok": True, "tree": self.tree})
        return json.dumps({"ok": True})


def verify(objective, done_when, tree):
    fb = FakeBridge(tree)
    bridge._bridge = fb
    return bridge._verify_done({"done_when": done_when, "objective": objective})


EDITOR_TREE = [
    {"view_id": "org.fossify.notes:id/et_content", "text": "买牛奶 07:30", "desc": ""},
    {"view_id": "org.fossify.notes:id/btn_save", "text": "保存", "desc": ""},
]

LIST_TREE = [
    {"view_id": "org.fossify.notes:id/fab_new", "text": "", "desc": "新建笔记"},
]


# ---------------- done_when 显式判据（v1 语义不变） ----------------

def test_done_when_hit():
    r = verify("目标", "买牛奶", EDITOR_TREE)
    assert r["passed"] is True, r


def test_done_when_miss():
    r = verify("目标", "买牛奶", LIST_TREE)
    assert r["passed"] is False, r


# ---------------- 空判据 → 信任大脑（2026-09-30 对齐桌面，弱探测移除） ----------------

def test_empty_done_when_trusts_brain():
    # 判据留空不再做目标关键词弱探测（强制写目标的假拒通道已移除）：
    # 完成与否由大模型自己判断，task_done 直接通过
    r = verify(u"在便签里写一条「买牛奶」", "", LIST_TREE)
    assert r["passed"] is True, r
    assert u"信任大脑" in r["reason"], r


def test_task_done_spec_without_verify():
    # 无校验面时模型工具面的 task_done 提示口径 = 模型自判
    class DoneBrain(object):
        def chat(self, messages, tools):
            return {"content": None,
                    "tool_calls": [{"name": "task_done", "args": {"summary": "x"}}]}

    loop = MiniLoop(objective="x", done_when="", tools={}, brain=DoneBrain())
    specs = {s["name"]: s for s in loop._tool_specs()}
    assert u"由你判断" in specs["task_done"]["description"], specs["task_done"]


def test_task_done_spec_with_verify():
    # 有校验面（verify_fn 注入）时提示口径 = 强制校验
    class DoneBrain(object):
        def chat(self, messages, tools):
            return {"content": None,
                    "tool_calls": [{"name": "task_done", "args": {"summary": "x"}}]}

    loop = MiniLoop(objective="x", done_when="买牛奶", tools={}, brain=DoneBrain(),
                    verify_fn=lambda a: {"passed": False, "reason": "not yet"})
    specs = {s["name"]: s for s in loop._tool_specs()}
    assert u"强制校验" in specs["task_done"]["description"], specs["task_done"]


# ---------------- mini_loop → verify_fn 传参（objective 传递回归） ----------------

def test_loop_passes_objective_to_verify_fn():
    seen = {}

    def verify_fn(args):
        seen.update(args)
        return {"passed": True, "reason": "ok"}

    class DoneBrain(object):
        def chat(self, messages, tools):
            return {"content": None,
                    "tool_calls": [{"name": "task_done", "args": {"summary": "x"}}]}

    loop = MiniLoop(objective=u"写「买牛奶」", done_when="", tools={},
                    brain=DoneBrain(), verify_fn=verify_fn, config={"max_steps": 2})
    loop.run()
    assert seen.get("objective") == u"写「买牛奶」", seen
    assert seen.get("done_when") == "", seen


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for fn in fns:
        try:
            fn()
            print("[PASS]", fn.__name__)
        except AssertionError as e:
            failed += 1
            print("[FAIL]", fn.__name__, "--", e)
    print("== %d/%d passed ==" % (len(fns) - failed, len(fns)))
    sys.exit(1 if failed else 0)
