# -*- coding: utf-8 -*-
"""P2-R 测试：技能检索（**LLM 选档**）+ load/search 工具。

2026-10-02 用户红线：不要脚本化的语义判断。旧实现 score_skill（包名交集 ×10 >
拉丁词元 > CJK bigram）整套删除 —— 把"这个技能和当前任务相关吗"写成正则权重。
本文件改测：LLM 选档接线、无 brain 时不猜（保持原序）、命中计数、工具契约。
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel import bridge                                 # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    Skill, SkillLibrary, SkillSubstep, SkillMetadata, compute_entry_id,
    search_skills, select_skills, set_retrieval_brain,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rs_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


class _PickBrain:
    """假 brain：按预设顺序返回选中项（只测接线）。"""

    def __init__(self, picked):
        self.picked = picked
        self.calls = 0

    def chat(self, messages, tools):
        self.calls += 1
        return {"content": json.dumps({"picked": self.picked}, ensure_ascii=False)}


def _mk(name, package, pattern):
    sub = [SkillSubstep("launch_app", {"package": package}),
           SkillSubstep("tap_by_id", {"view_id": "fab"}),
           SkillSubstep("type_text", {"text": "内容"})]
    lib = SkillLibrary(task_id="t_s")
    lib.save(Skill(name=name, objective_pattern=pattern, substeps=sub,
                   entry_id=compute_entry_id(sub), metadata=SkillMetadata(scope="global")))
    return lib


# ---------------- 1. 选档：LLM 判定 / 无 brain 不猜 ----------------

def test_select_uses_llm_pick():
    skills = [
        {"name": "notes-skill", "routine": "建便签", "pkg": "org.fossify.notes"},
        {"name": "clock-skill", "routine": "加闹钟", "pkg": "org.fossify.clock"},
    ]
    set_retrieval_brain(_PickBrain(["clock-skill"]))
    try:
        got = select_skills(skills, "给时钟加个闹钟", limit=3)
        assert [s["name"] for s in got] == ["clock-skill"]
    finally:
        set_retrieval_brain(None)


def test_select_without_brain_keeps_order_and_does_not_guess():
    skills = [{"name": "a"}, {"name": "b"}, {"name": "c"}]
    set_retrieval_brain(None)
    got = select_skills(skills, "任意查询", limit=2)
    assert [s["name"] for s in got] == ["a", "b"]      # 原序截断，不做语义判断


def test_search_skills_returns_structured_hits():
    skills = [
        {"name": "notes-skill", "objective_pattern": "notes app",
         "entry_id": "e1", "routine": "建便签"},
        {"name": "clock-skill", "objective_pattern": "clock app",
         "entry_id": "e2", "routine": "加闹钟"},
    ]
    set_retrieval_brain(_PickBrain(["clock-skill"]))
    try:
        hits = search_skills(skills, "加闹钟", limit=5)
        assert len(hits) == 1
        assert hits[0]["name"] == "clock-skill"
        assert hits[0]["rank"] == 1 and hits[0]["entry_id"] == "e2"
        assert "applies" in hits[0]
    finally:
        set_retrieval_brain(None)


def test_search_skills_empty_query_keeps_order_without_guessing():
    """无任务描述时不做判断：保持原序（此前是按词面打分，现在没有打分器了）。"""
    set_retrieval_brain(_PickBrain(["x"]))
    try:
        hits = search_skills([{"name": "a"}, {"name": "b"}], "", limit=5)
        assert [h["name"] for h in hits] == ["a", "b"]
    finally:
        set_retrieval_brain(None)


# ---------------- 2. search_skill / load_skill 工具 ----------------

def test_tool_search_hit_and_miss():
    setup_tmp_root()
    set_retrieval_brain(_PickBrain(["notes-skill"]))
    try:
        _mk("notes-skill", "org.fossify.notes", "create a note in notes app")
        _mk("clock-skill", "org.fossify.clock", "add alarm in clock app")
        r = bridge._search_skill({"query": "建个便签"})
        assert r["ok"] is True and r["results"][0]["name"] == "notes-skill"
        assert "load_skill" in r["note"]
        # 命中计数（簿记）：search 命中即记一次使用
        s = SkillLibrary(task_id="t_s").load("notes-skill")
        assert s.metadata.total_uses >= 1
        set_retrieval_brain(_PickBrain([]))
        miss = bridge._search_skill({"query": "建个便签"})
        assert miss["ok"] is False and "available" in miss
        assert bridge._search_skill({})["ok"] is False
    finally:
        set_retrieval_brain(None)


def test_tool_search_accepts_skill_objects():
    """Omni 侧可能直接传 Skill 对象列表 —— 同一接口必须兼容。"""
    setup_tmp_root()
    lib = _mk("clock-skill", "org.fossify.clock", "add alarm in clock app")
    skills = lib.list_global()
    set_retrieval_brain(_PickBrain(["clock-skill"]))
    try:
        hits = search_skills(skills, "加闹钟", limit=3)
        assert hits and hits[0]["name"] == "clock-skill"
    finally:
        set_retrieval_brain(None)
