# -*- coding: utf-8 -*-
"""P2-R 测试：facts LLM 提取器 + load_skill 原语。

对应 doc/knowledge_redesign.md §6/待拍板项 2：
1. facts 提取：成功轨迹（死数据）经脱敏摘要交 brain 提炼环境事实，
   落 rollout facts 区并可合并进 MEMORY.md；prompt 不含任务临时内容。
2. 失败安全：brain 异常 → facts 空，不拖垮蒸馏。
3. load_skill：精确名 / 模糊名 / 未找到；返回脱敏序列（<text>）。
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel import bridge                                 # noqa: E402
from hachimi_kernel.curator import Curator                        # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    Skill, SkillLibrary, SkillSubstep, SkillMetadata, compute_entry_id,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rf_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def _mk_record(trajectory):
    return {"task_id": "t_f", "run_id": "r1", "success": True, "steps": 4,
            "objective": "create a note", "trajectory_file": trajectory}


def _write_trajectory(path):
    lines = [
        {"action": {"tool": "launch_app", "args": {"package": "org.fossify.notes"}},
         "result": {"ok": True}},
        {"action": {"tool": "tap_by_id", "args": {"view_id": "android:id/button1"}},
         "result": {"ok": False, "error": "OK button silent (label empty)"}},
        {"action": {"tool": "type_text", "args": {"text": "我的购物清单临时内容"}},
         "result": {"ok": True}},
        {"action": {"tool": "tap_by_id", "args": {"view_id": "android:id/button1"}},
         "result": {"ok": True}},
    ]
    with open(path, "w", encoding="utf-8") as f:
        for ln in lines:
            f.write(json.dumps(ln, ensure_ascii=False) + "\n")


class FakeBrain(object):
    """记录 prompt、返回固定 facts 行；可注入异常。"""

    def __init__(self, reply, boom=False):
        self.reply, self.boom, self.prompts = reply, boom, []

    def chat(self, messages, tools):
        self.prompts.append(messages[0]["content"])
        if self.boom:
            raise RuntimeError("gateway down")
        return {"content": self.reply, "tool_calls": []}


# ---------------- 1. facts 提取 ----------------

def test_facts_extracted_into_rollout_and_memory():
    tmp = setup_tmp_root()
    traj = os.path.join(tmp, "trajectory.jsonl")
    _write_trajectory(traj)
    rec = _mk_record(traj)

    c = Curator(task_id="t_f")
    c.brain = FakeBrain("- 新建弹窗 OK 在 label 为空时静默无效\n"
                        "- 临时内容购物abc\n"        # 不含原始 text，伪造一条含关键词的
                        "不是条目格式\n"
                        "- 占位行可点可直接输入")
    out = c.distill_task_memory(rec)
    assert out is not None and out.exists()
    text = out.read_text(encoding="utf-8")
    assert "label 为空时静默无效" in text
    assert "占位行可点可直接输入" in text
    assert "不是条目格式" not in text

    # 合并进 MEMORY.md（threshold=1）
    assert c.maybe_merge_rollouts(threshold=1) >= 2
    master = P.memory_master().read_text(encoding="utf-8")
    assert "label 为空时静默无效" in master


def test_extract_prompt_is_sanitized():
    tmp = setup_tmp_root()
    traj = os.path.join(tmp, "trajectory.jsonl")
    _write_trajectory(traj)
    brain = FakeBrain("- x")
    c = Curator(task_id="t_f")
    c.brain = brain
    c.distill_task_memory(_mk_record(traj))
    prompt = brain.prompts[0]
    assert "我的购物清单临时内容" not in prompt      # 任务临时内容被 <text> 脱敏
    assert "<text>" in prompt


def test_brain_failure_is_swallowed():
    tmp = setup_tmp_root()
    traj = os.path.join(tmp, "trajectory.jsonl")
    _write_trajectory(traj)
    c = Curator(task_id="t_f")
    c.brain = FakeBrain("", boom=True)
    assert c.distill_task_memory(_mk_record(traj)) is None   # 无 facts 无 lessons


def test_no_brain_means_no_facts():
    tmp = setup_tmp_root()
    traj = os.path.join(tmp, "trajectory.jsonl")
    _write_trajectory(traj)
    c = Curator(task_id="t_f")
    assert c.distill_task_memory(_mk_record(traj)) is None   # brain=None 纯启发式


# ---------------- 2. load_skill ----------------

def _seed_skill():
    sub = [SkillSubstep("launch_app", {"package": "org.fossify.notes"}),
           SkillSubstep("type_text", {"text": "上次的内容"})]
    lib = SkillLibrary(task_id="t_f")
    lib.save(Skill(name="skill_Open_the_app_org_fossify_notes__create_a",
                   objective_pattern="Open the app org.fossify.notes, create a note",
                   substeps=sub, entry_id=compute_entry_id(sub),
                   metadata=SkillMetadata(scope="global")))


def test_load_skill_exact_and_sanitized():
    setup_tmp_root()
    _seed_skill()
    r = bridge._load_skill({"name": "skill_Open_the_app_org_fossify_notes__create_a"})
    assert r["ok"] is True
    assert "type_text(<text>)" in r["steps"]
    assert "上次的内容" not in json.dumps(r, ensure_ascii=False)


def test_load_skill_fuzzy_and_missing():
    setup_tmp_root()
    _seed_skill()
    assert bridge._load_skill({"name": "Open_the_app"})["ok"] is True    # 模糊
    r = bridge._load_skill({"name": "no-such-skill"})
    assert r["ok"] is False and "available" in r
