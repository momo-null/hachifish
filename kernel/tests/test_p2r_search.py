# -*- coding: utf-8 -*-
"""P2-R 测试：统一检索接口 + search_skill 工具（Omni 可复用视角）。

1. `score_skill` / `search_skills` 是模块级公共接口（宿主无关），
   Skill 对象与 payload dict 两种输入都支持；
2. 打分优先级：包名交集 > 词元 > CJK bigram；零分条目被过滤；
3. search_skill 工具：命中返回结果（含 load_skill 提示）、无命中给 available、
   query 缺失报错。
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel import bridge                                 # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    SkillLibrary, SkillSubstep, score_skill, search_skills,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rs_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def _mk(name, package, pattern):
    sub = [SkillSubstep("launch_app", {"package": package}),
           SkillSubstep("tap_by_id", {"view_id": "fab"}),
           SkillSubstep("type_text", {"text": "内容"})]
    lib = SkillLibrary(task_id="t_s")
    lib.save(SkillLibrary.from_run_record({
        "success": True, "steps": 3, "objective": pattern,
        "steps_data": [{"tool": s.tool, "args": s.args} for s in sub]},
        task_id="t_s"))
    return lib


# ---------------- 1. 统一打分 / 检索接口 ----------------

def test_score_prefers_package_over_tokens():
    a = {"name": "notes-skill", "objective_pattern": "open notes app",
         "ops": "launch_app(org.fossify.notes)"}
    b = {"name": "clock-skill", "objective_pattern": "open clock app",
         "ops": "launch_app(org.fossify.clock)"}
    q = "Open the app org.fossify.notes and create a note"
    assert score_skill(a, q) > score_skill(b, q)


def test_search_skills_filters_zero_and_limits():
    skills = [
        {"name": "notes-skill", "objective_pattern": "notes",
         "ops": "launch_app(org.fossify.notes)"},
        {"name": "clock-skill", "objective_pattern": "clock",
         "ops": "launch_app(org.fossify.clock)"},
    ]
    hits = search_skills(skills, "org.fossify.notes 建便签", limit=5)
    assert hits and hits[0]["name"] == "notes-skill"
    assert all(h["score"] > 0 for h in hits)
    assert len(search_skills(skills, "org.fossify.notes", limit=1)) == 1


# ---------------- 2. search_skill 工具 ----------------

def test_tool_search_hit_and_miss():
    setup_tmp_root()
    _mk("notes-skill", "org.fossify.notes", "create a note in notes app")
    r = bridge._search_skill({"query": "org.fossify.notes 便签"})
    assert r["ok"] is True and "notes" in r["results"][0]["name"]
    assert "load_skill" in r["note"]

    miss = bridge._search_skill({"query": "zzz-unrelated"})
    assert miss["ok"] is False and "available" in miss
    assert bridge._search_skill({})["ok"] is False


def test_tool_search_accepts_skill_objects():
    """Omni 侧可能直接传 Skill 对象列表 —— 同一接口必须兼容。"""
    setup_tmp_root()
    lib = _mk("clock-skill", "org.fossify.clock", "add alarm in clock app")
    skills = lib.list_global()
    hits = search_skills(skills, "org.fossify.clock 闹钟", limit=3)
    assert hits and "clock" in hits[0]["name"]
