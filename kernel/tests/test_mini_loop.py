# -*- coding: utf-8 -*-
"""mini_loop v1 的 mock 驱动测试。

场景对应桌面已验证语义：task_done 强制校验 / 校验拒绝重规划 / 卡住检测 / 预算终止 /
轨迹扁平 schema。纯标准库，`py -3 tests/test_mini_loop.py` 直接跑（无 pytest 依赖）。
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel.mini_loop import MiniLoop  # noqa: E402


# ---------------- fake 设备：一个最小「便签」App ----------------

class FakeNotesApp(object):
    """内存中的假设备状态机：FAB → 编辑页 → 输入 → 保存。"""

    def __init__(self):
        self.screen = "list"          # list / editor
        self.saved = False
        self.typed = ""
        self.verify_calls = 0

    def observe(self, args):
        if self.screen == "list":
            tree = [{"view_id": "org.fossify.notes:id/fab_new", "cls": "Button",
                     "text": "", "clickable": True}]
        else:
            tree = [{"view_id": "org.fossify.notes:id/et_content", "cls": "EditText",
                     "text": self.typed, "clickable": False, "editable": True},
                    {"view_id": "org.fossify.notes:id/btn_save", "cls": "Button",
                     "text": "保存", "clickable": True}]
        return {"ok": True, "screen": self.screen, "tree": tree}

    def tap_by_id(self, args):
        vid = args.get("view_id")
        if self.screen == "list" and vid.endswith("fab_new"):
            self.screen = "editor"
            return {"ok": True}
        if self.screen == "editor" and vid.endswith("btn_save"):
            if not self.typed:
                return {"ok": False, "error": "内容为空"}
            self.saved = True
            return {"ok": True}
        return {"ok": False, "error": "tap miss: %s on %s" % (vid, self.screen)}

    def type_text(self, args):
        if self.screen != "editor":
            return {"ok": False, "error": "not in editor"}
        self.typed = args.get("text", "")
        return {"ok": True, "length": len(self.typed)}

    def verify(self, args):
        self.verify_calls += 1
        # 故障注入：typed 为空时判未完成（驱动"拒绝重规划"场景）
        passed = self.saved and bool(self.typed)
        return {"passed": passed,
                "reason": "saved=%s typed=%r" % (self.saved, self.typed[:20])}


def make_tools(app):
    return {
        "observe": {"description": "观察 UI 树", "call": app.observe},
        "tap_by_id": {"description": "按控件 id 点击", "call": app.tap_by_id},
        "type_text": {"description": "输入文本", "call": app.type_text},
        "verify_done": {"description": "完成校验", "call": app.verify},
    }


class ScriptedBrain(object):
    """按剧本吐 tool_calls 的假大脑。"""

    def __init__(self, script):
        self.script = list(script)
        self.messages_seen = 0

    def chat(self, messages, tools):
        self.messages_seen = len(messages)
        return {"content": None, "tool_calls": [self.script.pop(0)]}


def run(objective, script, app=None, config=None):
    app = app or FakeNotesApp()
    steps = []
    finishes = []
    loop = MiniLoop(objective=objective, done_when="便签已创建且内容非空",
                    tools=make_tools(app), brain=ScriptedBrain(script),
                    config=config,
                    on_step=lambda s: steps.append(s),
                    on_finish=lambda out, ss: finishes.append(out))
    out = loop.run()
    return out, steps, app, finishes


# ---------------- 用例 ----------------

def test_happy_path_task_done_verified():
    app = FakeNotesApp()
    script = [
        {"name": "observe", "args": {}},
        {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/fab_new"}},
        {"name": "type_text", "args": {"text": "提醒: 明天 9 点"}},
        {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/btn_save"}},
        {"name": "task_done", "args": {"summary": "便签已创建"}},
    ]
    out, steps, app, finishes = run("在便签里创建提醒", script, app)
    assert out["status"] == "done", out
    assert out["task_done_rejected"] == 0
    assert app.saved is True
    assert steps[-1]["action"]["tool"] == "task_done" and steps[-1]["verified"] is True
    assert len(finishes) == 1 and finishes[0]["steps"] == len(steps)
    # 扁平 schema 质量信号
    assert all("verified" in s and "retry" in s for s in steps)


def test_verify_rejection_then_replan():
    # 大脑抢跑 task_done（未保存）→ 校验拒绝 → 继续完成 → 二次 task_done 通过
    app = FakeNotesApp()
    script = [
        {"name": "observe", "args": {}},
        {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/fab_new"}},
        {"name": "task_done", "args": {"summary": "还没保存就报完成"}},   # 应被拒绝
        {"name": "type_text", "args": {"text": "提醒: 明天 9 点"}},
        {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/btn_save"}},
        {"name": "task_done", "args": {"summary": "便签已创建"}},
    ]
    out, steps, app, _ = run("在便签里创建提醒", script, app)
    assert out["status"] == "done", out
    assert out["task_done_rejected"] == 1
    assert steps[2]["action"]["tool"] == "task_done" and steps[2]["verified"] is False
    assert steps[2]["retry"] == 1
    assert steps[5]["verified"] is True
    assert app.saved is True


def test_stuck_detection_terminates():
    app = FakeNotesApp()
    same = {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/wrong_btn"}}
    script = [dict(same) for _ in range(6)]
    out, steps, _, _ = run("点一个不存在的按钮", script, app, config={"max_steps": 10})
    assert out["status"] == "incomplete", out
    # 第 3 次相同动作收到 stuck_warning，第 4 次判 stuck 终止
    warned = [s for s in steps if s["result"].get("stuck_warning")]
    assert len(warned) >= 1
    stucked = [s for s in steps if s["result"].get("error", "").startswith("stuck:")]
    assert len(stucked) == 1


def test_budget_incomplete_not_fake_done():
    app = FakeNotesApp()
    script = [
        {"name": "observe", "args": {}},
        {"name": "observe", "args": {}},
        {"name": "tap_by_id", "args": {"view_id": "org.fossify.notes:id/fab_new"}},
    ]
    out, steps, _, _ = run("永远做不完的任务", script, app, config={"max_steps": 2})
    assert out["status"] == "incomplete", out
    assert len(steps) == 2


def test_unknown_tool_structured_error():
    out, steps, _, _ = run("X", [{"name": "no_such_tool", "args": {}}], config={"max_steps": 1})
    assert steps[0]["result"]["ok"] is False
    assert "unknown tool" in steps[0]["result"]["error"]


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
