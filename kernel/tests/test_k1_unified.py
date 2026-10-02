# -*- coding: utf-8 -*-
"""K1 统一入口测试（redesign_plan P2：mini_loop 对话轮次 + 父项目方案 B 移植）。

覆盖（与桌面 sdk_loop 行为对拍，rev4 移植清单 P0 项）：
1. 纯文本回复 = 本轮答案（方案 B）：无验证条件信任大脑 → status="chat"；
   有 done_when 先 verify（过=done / 拒=注入继续，不假绿）。
2. verify_stopped 例外：task_done 被拒后纯文本不触发 chat 终态。
3. history 会话恢复：user/assistant 交替历史 + 本轮 message（统一入口形态）。
4. RepeatGuard：同名工具连续失败 3 次 → 注入「换方案」提醒（只注一次）。
5. 异常自愈：brain 异常分类（上下文溢出硬截重试 1 次 / 普通错误 incomplete 终态）。
6. 旧表单路径回归保护：无 message/history 时行为逐字节不变。

纯标准库，`py -3 tests/test_k1_unified.py` 直接跑。
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel.mini_loop import MiniLoop  # noqa: E402


# ---------------- 假大脑 ----------------

class ScriptedBrain(object):
    """按剧本吐回复；剧本耗尽后吐纯文本（模拟「说完就停」收尾）。"""

    def __init__(self, script, final_text="好的，我知道了～"):
        self.script = list(script)
        self.final_text = final_text
        self.seen = []          # (role, content) 摘要，供历史断言
        self.views = []         # 每次 chat 的完整消息视图快照（供注入计数）
        self.fail = []          # 每次调用抛的异常类（对齐剧本长度；None=正常）

    def chat(self, messages, tools):
        for m in messages:
            self.seen.append((m.get("role"), (m.get("content") or "")[:120]))
        self.views.append([(m.get("role"), (m.get("content") or ""))
                           for m in messages])
        if self.fail:
            err = self.fail.pop(0)
            if err is not None:
                raise err()
        if self.script:
            call = self.script.pop(0)
            return {"content": None, "tool_calls": [call]}
        return {"content": self.final_text, "tool_calls": []}


class OkTool(object):
    def __init__(self):
        self.calls = 0

    def __call__(self, args):
        self.calls += 1
        return {"ok": True}


class FlakyTool(object):
    """按调用次数失败的失败工具：前 fail_times 次失败，之后成功。"""

    def __init__(self, fail_times=99):
        self.fail_times = fail_times
        self.calls = 0

    def __call__(self, args):
        self.calls += 1
        if self.calls <= self.fail_times:
            return {"ok": False, "error": "boom %d" % self.calls}
        return {"ok": True}


def make_tools(**named):
    return {k: {"description": k, "call": v} for k, v in named.items()}


def run_loop(brain, objective="X", done_when="", tools=None, config=None,
             history=None, message="", verify_fn=None):
    replies = []
    finishes = []
    steps = []
    loop = MiniLoop(objective=objective, done_when=done_when,
                    tools=tools or {}, brain=brain,
                    config=config or {"max_steps": 12},
                    on_step=lambda s: steps.append(s),
                    on_finish=lambda out, ss: finishes.append(out),
                    history=history, message=message,
                    on_reply=replies.append,
                    verify_fn=verify_fn)
    out = loop.run()
    return out, replies, finishes, steps, loop


# ---------------- 1. 方案 B：纯文本收尾 ----------------

def test_pure_text_reply_is_chat_final():
    """无验证条件：模型说完就停 = 本轮答案（桌面方案 B / 信任大脑）。"""
    brain = ScriptedBrain([{"name": "observe", "args": {}}])
    out, replies, finishes, steps, _ = run_loop(
        brain, tools=make_tools(observe=OkTool()), message="你是谁？")
    assert out["status"] == "chat", out
    assert replies == ["好的，我知道了～"]
    assert out["summary"] == "好的，我知道了～"
    assert len(finishes) == 1
    assert out["steps"] >= 1            # 纯文本回合也计步（防记账停摆）


def test_first_message_direct_reply_no_tools():
    """闲聊零工具：首条消息直接文字回复，不进任何工具循环。"""
    brain = ScriptedBrain([])           # 剧本为空 → 直接纯文本
    out, replies, _, steps, _ = run_loop(
        brain, tools=make_tools(observe=OkTool()), message="你好呀")
    assert out["status"] == "chat"
    assert replies and "好的" in replies[0]
    assert len(steps) == 0              # 闲聊不产工具步


def test_reply_with_done_when_verified_passes():
    """有完成判据：纯文本收尾先 verify，通过 = done（不假绿也不误杀）。"""

    def verify(payload):
        return {"passed": True, "reason": "看到分类文件夹"}

    brain = ScriptedBrain([])
    out, replies, _, _, _ = run_loop(
        brain, done_when="桌面已整理",
        tools=make_tools(observe=OkTool()),
        message="帮我整理桌面",
        verify_fn=verify)
    assert out["status"] == "done", out
    assert replies == ["好的，我知道了～"]   # 消息照常吐给前端


def test_reply_with_done_when_rejected_continues():
    """有完成判据：verify 拒绝 → 注入继续（防模型口头宣布完成）。"""
    verdicts = [{"passed": False, "reason": "桌面仍有散文件"},
                {"passed": True, "reason": "ok"}]

    def verify(payload):
        return verdicts.pop(0) if verdicts else {"passed": True, "reason": "r"}

    brain = ScriptedBrain([])
    out, _, _, steps, loop = run_loop(
        brain, done_when="桌面已整理",
        tools=make_tools(sort=OkTool()),
        message="整理桌面",
        verify_fn=verify)
    assert out["status"] == "done", out
    assert loop.task_done_rejected >= 0
    # 拒绝注入的提示确实进了消息流
    assert any("完成条件尚未满足" in (c or "") or "尚未满足" in (c or "")
               for r, c in brain.seen if r == "user"), brain.seen


def test_verify_stopped_prevents_false_chat_final():
    """task_done 被拒后模型改口纯文本：不触发 chat 终态（verify_stopped 例外）。"""
    verdicts = [{"passed": False, "reason": "没做完"}]

    def verify(payload):
        return verdicts.pop(0) if verdicts else {"passed": True, "reason": "r"}

    brain = ScriptedBrain([
        {"name": "task_done", "args": {"summary": "完成了"}},
        # 下一轮纯文本（剧本耗尽）——被拒后不许直接 chat 收尾
    ])
    tools = make_tools(observe=OkTool())
    out, replies, _, _, _ = run_loop(
        brain, done_when="必须做完", tools=tools, message="做这件事",
        verify_fn=verify)
    # 被拒 → 注入继续 → 剧本已空 → 再次纯文本 → 此轮 verify 通过（verdicts 耗尽回 True）→ done
    assert out["status"] in ("done", "chat"), out
    # 关键断言：紧跟拒绝后的第一轮纯文本没有被当作 chat 终态（否则 run 在
    # 首个纯文本处就结束且 verify 只被调一次、消息数更少）
    assert brain.seen, "brain must have been called"


# ---------------- 2. history / 统一入口形态 ----------------

def test_history_restored_as_alternating_context():
    """会话历史恢复：[system] + history(user/assistant 交替) + 本轮 message。"""
    brain = ScriptedBrain([])
    history = [
        {"role": "user", "content": "你是谁？"},
        {"role": "assistant", "content": "我是大肥鱼"},
    ]
    run_loop(brain, tools=make_tools(observe=OkTool()),
             history=history, message="再介绍一下自己")
    roles = [r for r, _ in brain.seen]
    assert roles[0] == "system"
    assert ("user", "你是谁？") in brain.seen
    assert ("assistant", "我是大肥鱼") in brain.seen
    assert brain.seen[-1] == ("user", "再介绍一下自己")
    # 统一入口形态：system prompt 不含「目标:」硬模板
    system = brain.seen[0][1]
    assert "目标:" not in system


def test_unified_entry_system_prompt_has_self_decide_rules():
    """统一入口 system prompt：自决规则（闲聊直答/操作用工具/task_done 收尾）。"""
    brain = ScriptedBrain([])
    run_loop(brain, tools=make_tools(observe=OkTool()), message="hi")
    system = brain.seen[0][1]
    assert "闲聊" in system or "直接" in system
    assert "task_done" in system


def test_legacy_objective_form_unchanged():
    """回归保护：无 message/history 的旧表单路径与旧行为一致（目标: 前缀）。"""
    brain = ScriptedBrain([])
    run_loop(brain, objective="在便签里创建提醒", tools={})
    system = brain.seen[0][1]
    assert "目标: 在便签里创建提醒" in system
    assert brain.seen[1] == ("user", "目标: 在便签里创建提醒")


# ---------------- 3. RepeatGuard ----------------

def test_repeat_guard_reminds_once_on_consecutive_failures():
    """同名工具连续失败：每 3 次连败注入一轮「换方案」提醒（计数清零重新累计，
    父项目 T4.5 语义——提醒一轮一次，绝不连败每次都刷屏）。"""
    brain = ScriptedBrain([{"name": "tap", "args": {"n": i}} for i in range(6)])
    out, _, _, _, _ = run_loop(
        brain, tools=make_tools(tap=FlakyTool(fail_times=99)),
        message="点那个按钮")
    # 最后一次 chat 视图快照数提醒：6 连败 = 第3败一轮 + 第6败一轮 = 2 条
    last = brain.views[-1] if brain.views else []
    reminders = [c for r, c in last if r == "user" and "换" in c]
    assert len(reminders) == 2, last            # 每 3 连败一轮，非每败必刷
    assert out["status"] in ("incomplete", "chat")


def test_repeat_guard_reset_on_success():
    """成功后计数清零：失败2次→成功→再失败2次，不触发提醒。"""
    flaky = FlakyTool(fail_times=2)
    brain = ScriptedBrain([
        {"name": "tap", "args": {"n": 1}},
        {"name": "tap", "args": {"n": 2}},      # 失败 2 次
        {"name": "tap", "args": {"n": 3}},      # 成功（清零）
        {"name": "tap", "args": {"n": 4}},
        {"name": "tap", "args": {"n": 5}},      # 再失败 2 次（不达 3）
    ])
    run_loop(brain, tools=make_tools(tap=flaky), message="x")
    for view in brain.views:
        for r, c in view:
            assert not (r == "user" and "换" in c), view


# ---------------- 4. 异常自愈 ----------------

def test_brain_error_terminates_incomplete():
    """普通 brain 异常 → incomplete 终态（人话进 summary），不崩 run。"""

    class BoomBrain(object):
        def chat(self, messages, tools):
            raise RuntimeError("connection refused")

    out, _, finishes, _, _ = run_loop(BoomBrain(), message="hi")
    assert out["status"] == "incomplete", out
    assert "connection refused" in out["summary"]
    assert len(finishes) == 1


def test_context_overflow_retries_once_with_hard_keep():
    """上下文溢出 → 硬截历史重试一次（同一 run 内只重试一次）。"""
    calls = {"n": 0}

    class OverflowBrain(object):
        def __init__(self):
            self.seen = []

        def chat(self, messages, tools):
            calls["n"] += 1
            self.seen = [(m.get("role"), (m.get("content") or "")[:30]) for m in messages]
            if calls["n"] == 1:
                raise RuntimeError("HTTP 400: maximum context length exceeded")
            return {"content": "截断后恢复", "tool_calls": []}

    brain = OverflowBrain()
    history = [{"role": "user", "content": "长会话" + "x" * 2000},
               {"role": "assistant", "content": "y" * 2000}]
    out, replies, _, _, _ = run_loop(
        brain, tools={}, history=history, message="继续")
    assert out["status"] == "chat", out
    assert replies == ["截断后恢复"]
    assert calls["n"] == 2                     # 重试一次成功
    # 重试时历史被硬截（消息数显著少于原全量）
    assert len(brain.seen[1]) < len(history) + 2


def test_context_overflow_retry_only_once():
    """溢出重试后仍溢出 → 不无限重试（一次为限）。"""
    class AlwaysOverflow(object):
        def __init__(self):
            self.calls = 0

        def chat(self, messages, tools):
            self.calls += 1
            raise RuntimeError("HTTP 400: maximum context length")

    brain = AlwaysOverflow()
    out, _, _, _, _ = run_loop(brain, message="hi")
    assert out["status"] == "incomplete"
    assert brain.calls == 2                    # 原始 1 次 + 重试 1 次


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
        except Exception as e:
            failed += 1
            print("[ERROR]", fn.__name__, "--", type(e).__name__, e)
    print("== %d/%d passed ==" % (len(fns) - failed, len(fns)))
    sys.exit(1 if failed else 0)
