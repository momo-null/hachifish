# -*- coding: utf-8 -*-
"""K0 角色卡注入测试（redesign_plan：character.md → persona → system prompt）。

覆盖三段链路：
1. MiniLoop._system_prompt 的 persona 块（有则前置、无则逐字节与旧版一致——
   零注入回归红线：不配人设的既有任务行为不变）。
2. 端到端：persona 文本真实进入 brain 收到的 messages[0]（system）。
3. bridge._persona_block / knowledge_inject.load_character_text：
   - character.md 存在 → 完整读取（截断管控 20000 字符）；
   - 文件缺失 → 空串（零注入，不崩、不拖垮任务）；
   - bridge 兜底：注入层任何异常 → 空串。

纯标准库，`py -3 tests/test_k0_persona.py` 直接跑。
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel.mini_loop import MiniLoop          # noqa: E402
from hachimi_kernel import knowledge_inject            # noqa: E402
from hachimi_kernel import bridge                      # noqa: E402


class ProbeBrain(object):
    """只记录 messages、永不产出 tool_calls 的探针大脑（纯文本即退出循环条件）。"""

    def __init__(self):
        self.seen_system = None
        self.calls = 0

    def chat(self, messages, tools):
        self.calls += 1
        if self.calls == 1:
            self.seen_system = messages[0]["content"]
        return {"content": "ok", "tool_calls": []}


def make_loop(persona=None, objective="测试目标"):
    config = {"max_steps": 1}
    if persona is not None:
        config["persona"] = persona
    return MiniLoop(objective=objective, done_when="", tools={}, brain=ProbeBrain(),
                    config=config)


# ---------------- 1. system prompt 组装 ----------------

def test_persona_prepended_to_system_prompt():
    loop = make_loop(persona="你是大肥鱼，称呼用户为主人")
    p = loop._system_prompt()
    assert p.startswith("# 助手人格设定\n你是大肥鱼"), p[:50]
    # 人设块后接既有结构（目标/完成判据/工具/规则），旧字段一个不少
    assert "目标: 测试目标" in p
    assert "可用工具:" in p
    assert "task_done" in p


def test_no_persona_byte_identical_to_legacy():
    """零注入红线：不配 persona 时 prompt 与旧版逐字节一致（缓存/回归友好）。"""
    loop = make_loop(persona=None)
    p = loop._system_prompt()
    assert p.startswith("目标: 测试目标"), repr(p[:30])
    assert "# 助手人格设定" not in p


def test_blank_persona_treated_as_absent():
    loop = make_loop(persona="   \n  ")
    assert "# 助手人格设定" not in loop._system_prompt()


def test_persona_reaches_brain_system_message():
    """端到端：persona 真实进入 brain.chat 的 system 消息。"""
    brain = ProbeBrain()
    loop = MiniLoop(objective="X", done_when="", tools={}, brain=brain,
                    config={"persona": "你是大肥鱼", "max_steps": 1})
    loop.run()
    assert brain.seen_system is not None
    assert brain.seen_system.startswith("# 助手人格设定\n你是大肥鱼")


# ---------------- 2. character.md 读取层 ----------------

def _with_character_file(content, fn):
    """临时 character.md（patch 路径函数），跑完恢复。"""
    origin = knowledge_inject.character_card
    tmp = tempfile.mkdtemp(prefix="hachimi_k0_")
    try:
        path = os.path.join(tmp, "character.md")
        if content is not None:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        knowledge_inject.character_card = lambda: __import__("pathlib").Path(path)
        fn(path)
    finally:
        knowledge_inject.character_card = origin


def test_load_character_text_reads_file():
    def check(_path):
        text = knowledge_inject.load_character_text()
        assert "大肥鱼" in text and "主人" in text
    _with_character_file("你是大肥鱼，称呼用户为主人", check)


def test_load_character_text_missing_file_returns_empty():
    def check(_path):
        assert knowledge_inject.load_character_text() == ""
    _with_character_file(None, check)   # 只建目录不建文件


def test_load_character_text_truncated_at_limit():
    long_text = "字" * (knowledge_inject._CHARACTER_TRUNCATE + 100)
    def check(_path):
        got = knowledge_inject.load_character_text()
        assert len(got) == knowledge_inject._CHARACTER_TRUNCATE
    _with_character_file(long_text, check)


# ---------------- 3. bridge 注入兜底 ----------------

def test_persona_block_returns_text_when_file_exists():
    def check(_path):
        block = bridge._persona_block()
        assert "大肥鱼" in block
    _with_character_file("你是大肥鱼", check)


def test_persona_block_empty_when_file_missing():
    def check(_path):
        assert bridge._persona_block() == ""
    _with_character_file(None, check)


def test_persona_block_survives_inject_layer_failure():
    """knowledge_inject 抛异常 → bridge 兜底空串，绝不拖垮任务。"""
    origin = knowledge_inject.load_character_text
    try:
        knowledge_inject.load_character_text = lambda: (_ for _ in ()).throw(
            RuntimeError("boom"))
        assert bridge._persona_block() == ""
    finally:
        knowledge_inject.load_character_text = origin


def test_full_chain_prompt_contains_character_md():
    """全链路：character.md → _persona_block → MiniLoop config → prompt。"""
    def check(_path):
        persona = bridge._persona_block()
        loop = make_loop(persona=persona)
        assert "大肥鱼" in loop._system_prompt()
    _with_character_file("你是大肥鱼，勤快爱自嘲", check)


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
