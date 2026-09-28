# -*- coding: utf-8 -*-
"""bridge 完成校验测试（P0）：done_when 文本探测 / 空判据目标弱探测 / objective 传递。

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


# ---------------- 空判据 → 目标弱探测 ----------------

def test_weak_probe_quoted_text_hit():
    r = verify(u"在便签里写一条「买牛奶」", "", EDITOR_TREE)
    assert r["passed"] is True, r


def test_weak_probe_quoted_text_miss():
    r = verify(u"在便签里写一条「买牛奶」", "", LIST_TREE)
    assert r["passed"] is False, r


def test_weak_probe_number_hit():
    r = verify(u"加一个 07:30 的闹钟", "", EDITOR_TREE)
    assert r["passed"] is True, r


def test_weak_probe_number_miss():
    r = verify(u"加一个 21:45 的闹钟", "", EDITOR_TREE)
    assert r["passed"] is False, r


def test_weak_probe_all_terms_required():
    # 多个引号段：全部命中才过（AND 语义，防单泛词假绿）
    r = verify(u"在便签写「咖啡豆」和「滤纸」", "", EDITOR_TREE)
    assert r["passed"] is False, r


def test_weak_probe_no_terms_rejected():
    # 目标无引号无数字：宁可拒判（非假绿），提示用户填判据
    r = verify(u"打开便签", "", LIST_TREE)
    assert r["passed"] is False, r
    assert u"空判据" in r["reason"], r


def test_probe_terms_extraction():
    assert bridge._probe_terms(u"写「买 牛奶」提醒") == [u"买 牛奶"]
    assert bridge._probe_terms(u"设 07:30 闹钟") == ["07:30"]
    assert bridge._probe_terms(u"重命名为 notes2") == ["2"]
    assert bridge._probe_terms(u"打开图库") == []
    assert bridge._probe_terms("") == []


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
