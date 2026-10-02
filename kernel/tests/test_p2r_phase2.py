# -*- coding: utf-8 -*-
"""P2-R Phase 2 测试：脱敏 / 相关度 top-k / 字符预算 / 中性措辞。

对应 doc/knowledge_redesign.md §2（注入只进一次 + 可被模型采用）：
1. 脱敏：type_text 以 <text> 注入，上次任务的字面值不出现。
2. 相关度：包名命中者优先；max_skills 裁剪生效。
3. 预算：max_chars 限制块大小（至少保留 1 条）。
4. 措辞：不再出现"可能过时，以实际观测为准"；要求优先复用。
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel import bridge                                 # noqa: E402
from hachimi_kernel import knowledge_inject as KI                 # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    Skill, SkillLibrary, SkillMetadata, SkillSubstep, compute_entry_id,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2r2_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def _mk_skill(name, package, pattern=""):
    sub = [SkillSubstep("launch_app", {"package": package}),
           SkillSubstep("tap_by_id", {"view_id": "new_note"}),
           SkillSubstep("type_text", {"text": "上次任务的字面内容"})]
    return Skill(name=name, objective_pattern=pattern or name,
                 entry_id=compute_entry_id(sub), substeps=sub,
                 metadata=SkillMetadata(success_count=1, total_uses=1,
                                        confidence=1.0, status="candidate",
                                        scope="global"))


def _payload(*skills):
    return [{"name": s.name, "entry_id": s.entry_id,
             "objective_pattern": s.objective_pattern,
             "ops": bridge._skill_ops(s)} for s in skills]


# ---------------- 1. 脱敏 ----------------

def test_ops_are_sanitized():
    setup_tmp_root()
    lib = SkillLibrary(task_id="t")
    lib.save(_mk_skill("notes-create", "org.fossify.notes"))
    block = bridge._knowledge_block()
    assert "type_text(<text>)" in block, block
    assert "上次任务的字面内容" not in block, block


# ---------------- 2. 选档 top-k（LLM 判定；无 brain 保持原序） ----------------

def test_selection_follows_llm_pick():
    setup_tmp_root()
    a = _mk_skill("clock-skill", "org.fossify.clock")
    b = _mk_skill("notes-skill", "org.fossify.notes")

    class _Pick:
        def chat(self, messages, tools):
            return {"content": '{"picked": ["notes-skill"]}'}

    import json as _json
    from hachimi_kernel.skill_library import set_retrieval_brain
    set_retrieval_brain(_Pick())
    try:
        block = KI.build_knowledge_block(
            "", _payload(a, b),
            objective="Open the app org.fossify.notes, create a note")
    finally:
        set_retrieval_brain(None)
    assert "notes-skill" in block and "clock-skill" not in block, block


def test_selection_without_brain_keeps_original_order():
    setup_tmp_root()
    a = _mk_skill("clock-skill", "org.fossify.clock")
    b = _mk_skill("notes-skill", "org.fossify.notes")
    from hachimi_kernel.skill_library import set_retrieval_brain
    set_retrieval_brain(None)
    block = KI.build_knowledge_block("", _payload(a, b),
                                     objective="任意任务描述")
    assert block.index("clock-skill") < block.index("notes-skill"), block


def test_max_skills_truncates():
    setup_tmp_root()
    skills = [_mk_skill("s%d" % i, "org.app%d" % i) for i in range(5)]
    block = KI.build_knowledge_block("", _payload(*skills),
                                     objective="unrelated", max_skills=2)
    assert block.count("- **s") == 2, block


# ---------------- 3. 字符预算 ----------------

def test_budget_caps_block_size():
    setup_tmp_root()
    skills = [_mk_skill("long-%d" % i, "org.app%d" % i,
                        pattern="x" * 300) for i in range(6)]
    block = KI.build_knowledge_block("", _payload(*skills),
                                     objective="", max_skills=6, max_chars=800)
    # 至少 1 条 + 预算约束下明显小于全量
    assert "- **long-" in block
    assert len(block) < 6 * 350, len(block)


# ---------------- 4. 措辞 ----------------

def test_hint_neutral_no_disclaimer():
    setup_tmp_root()
    SkillLibrary(task_id="t").save(_mk_skill("notes-create", "org.fossify.notes"))
    block = bridge._knowledge_block()
    assert "可直接沿用" in block
    assert "可能过时，以实际观测为准" not in block


def test_memory_section_absent_when_empty():
    setup_tmp_root()
    block = bridge._knowledge_block()
    assert "## 历史记忆" not in block, block
