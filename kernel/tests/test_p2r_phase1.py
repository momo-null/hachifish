# -*- coding: utf-8 -*-
"""P2-R Phase 1 测试：entry_id 身份去重 + facts 退出成功路径。

对应 doc/knowledge_redesign.md §2（单源 / 类型分工 / ID 去重）：
1. entry_id：同套路不同字面内容（type_text 文本不同）⇒ 同一 id；
   结构性参数不同（view_id）⇒ 不同 id。
2. promote_or_insert：entry_id 命中 ⇒ 走 record_success（updated/promoted），
   不再另立新档 —— 写入端去重 = 重复根源的修复点。
3. distill：成功任务不再产出「上次成功路径」facts（与 skill 同构的双写退出）。
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel.curator import Curator, _success_path_facts   # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    Skill, SkillLibrary, SkillSubstep, SkillMetadata, compute_entry_id,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2r_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def _mk_record(text, trajectory=None):
    rec = {
        "task_id": "t_p2r", "run_id": "r1", "success": True,
        "steps": 6, "objective": "open notes and create a note",
        "trajectory_file": trajectory,
    }
    if trajectory is None:
        rec["steps_data"] = [
            {"tool": "launch_app", "args": {"package": "org.fossify.notes"}},
            {"tool": "tap_by_id", "args": {"view_id": "new_note"}},
            {"tool": "type_text", "args": {"text": text}},
        ]
    return rec


# ---------------- 1. entry_id 身份 ----------------

def test_entry_id_ignores_text_content():
    a = [SkillSubstep("launch_app", {"package": "org.x"}),
         SkillSubstep("type_text", {"text": "购物清单"})]
    b = [SkillSubstep("launch_app", {"package": "org.x"}),
         SkillSubstep("type_text", {"text": "明天上午备份实验数据"})]
    assert compute_entry_id(a) == compute_entry_id(b)
    assert compute_entry_id(a) != ""


def test_entry_id_distinguishes_structure():
    a = [SkillSubstep("tap_by_id", {"view_id": "new_note"})]
    b = [SkillSubstep("tap_by_id", {"view_id": "open_search"})]
    assert compute_entry_id(a) != compute_entry_id(b)


# ---------------- 2. 写入端去重 ----------------

def test_promote_or_insert_dedups_by_entry_id():
    setup_tmp_root()
    lib = SkillLibrary(task_id="t_p2r")

    def _skill(text):
        substeps = [SkillSubstep("tap_by_id", {"view_id": "fab"}),
                    SkillSubstep("type_text", {"text": text})]
        return Skill(name="skill_固定套路", routine="固定套路",
                     objective_pattern="固定 objective",
                     entry_id=compute_entry_id(substeps),
                     substeps=substeps,
                     metadata=SkillMetadata(success_count=1, total_uses=1,
                                            confidence=1.0, scope="global"))

    first = lib.promote_or_insert(_skill("购物清单"))
    assert first["action"] == "created", first

    # 同套路、不同字面内容 ⇒ 同 entry_id ⇒ 不另立新档，累计 success
    second = lib.promote_or_insert(_skill("明天上午备份实验数据"))
    assert second["action"] in ("updated", "promoted"), second
    assert second["success_count"] == 2, second

    # 全局只有一份技能档
    assert len(lib.list_global()) == 1


# ---------------- 3. facts 退出成功路径 ----------------

def _write_trajectory(tmp, path):
    lines = [
        {"run_id": "r1", "action": {"tool": "launch_app",
                                    "args": {"package": "org.fossify.notes"}}},
        {"run_id": "r1", "action": {"tool": "type_text",
                                    "args": {"text": "hello"}}},
    ]
    with open(path, "w", encoding="utf-8") as f:
        for ln in lines:
            f.write(json.dumps(ln, ensure_ascii=False) + "\n")


def test_distill_success_no_longer_writes_path_facts():
    tmp = setup_tmp_root()
    traj = os.path.join(tmp, "trajectory.jsonl")
    _write_trajectory(tmp, traj)
    rec = _mk_record("whatever", trajectory=traj)
    rec["trajectory_file"] = traj

    c = Curator(task_id="t_p2r")
    out = c.distill_task_memory(rec)
    # 成功任务零 facts / 零 lessons ⇒ 宁缺毋滥，不落 rollout
    assert out is None
    # debug 通道仍可独立调用（函数保留，仅退出生产链）
    assert _success_path_facts(rec)


def test_distill_failure_still_writes_lessons():
    tmp = setup_tmp_root()
    rec = _mk_record("whatever")
    rec.update({"success": False, "steps": 6, "reason": "步数预算耗尽"})
    c = Curator(task_id="t_p2r")
    out = c.distill_task_memory(rec)
    assert out is not None and out.exists()
    text = out.read_text(encoding="utf-8")
    assert "[失败]" in text
