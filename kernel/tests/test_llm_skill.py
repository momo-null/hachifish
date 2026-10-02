# -*- coding: utf-8 -*-
"""P2-R v2 测试：技能蒸馏 = LLM 总结（非机械转录）。

用户拍板（2026-10-02）：记忆与 skill 都必须经 LLM 总结；脚本提取的技能已证伪
（脏录像重放 109 步）。对应实现：
1. validate_skill_summary：防幻觉校验（工具/参数必须真实执行过）；
2. from_summary：name/routine 用 LLM 归纳的语义名（跨措辞可复用）；
3. promote_or_insert：routine + 同包名 ⇒ 同一技能档；prefer_candidate_steps
   用提炼版覆盖存量录像；
4. 端到端：review_candidate_skills 消费 LLM JSON（```json 围栏可解析）。
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel.curator import Curator                        # noqa: E402
from hachimi_kernel.skill_library import (                        # noqa: E402
    SkillLibrary, SkillSubstep, validate_skill_summary,
)


def setup_tmp_root():
    tmp = P.Path(__import__("tempfile").mkdtemp(prefix="hachimi_llmskill_"))
    P._GLOBAL = tmp / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return str(tmp)


def _write_trajectory(path):
    lines = [
        {"action": {"tool": "launch_app", "args": {"package": "com.example.app"}},
         "result": {"ok": True}},
        {"action": {"tool": "tap_xy", "args": {"x": 0.5, "y": 0.08}},
         "result": {"ok": True}},
        {"action": {"tool": "type_text", "args": {"text": "减脂餐"}},
         "result": {"ok": True}},
        {"action": {"tool": "tap_xy", "args": {"x": 0.87, "y": 0.086}},
         "result": {"ok": True}},
        {"action": {"tool": "gesture",
                    "args": {"points": [{"x": 0.5, "y": 0.7}, {"x": 0.5, "y": 0.3}]}},
         "result": {"ok": True}},
    ]
    with open(path, "w", encoding="utf-8") as f:
        for ln in lines:
            f.write(json.dumps(ln, ensure_ascii=False) + "\n")


def _record(traj, objective="打开某应用搜索关键词"):
    return {"task_id": "t_s", "run_id": "r1", "success": True, "steps": 5,
            "objective": objective, "trajectory_file": traj}


def _summary():
    return {"name": "搜索并浏览", "routine": "应用内搜索并浏览",
            "description": "在应用内搜索关键词并浏览结果流",
            "steps": [
                {"tool": "launch_app", "args": {"package": "com.example.app"}},
                {"tool": "tap_xy", "args": {"x": 0.5, "y": 0.08}},
                {"tool": "type_text", "args": {"text": "<text>"}},
                {"tool": "tap_xy", "args": {"x": 0.87, "y": 0.086}},
                {"tool": "gesture",
                 "args": {"points": [{"x": 0.5, "y": 0.7}, {"x": 0.5, "y": 0.3}]}},
            ]}


# ---------------- 1. 防幻觉校验 ----------------

def test_validate_rejects_hallucinated_tool():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    rec = _record(traj)
    s = _summary()
    s["steps"] = [{"tool": "launch_app", "args": {"package": "com.example.app"}},
                  {"tool": "tap_by_id", "args": {"view_id": "org.x:id/never_seen"}}]
    assert validate_skill_summary(s, rec) is None      # 2 步全被剔除 → None


def test_validate_keeps_near_coords_and_masks_text():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    rec = _record(traj)
    s = _summary()
    s["steps"][2]["args"]["text"] = "<text>"           # 原样
    out = validate_skill_summary(s, rec)
    assert out is not None
    args = [st["args"] for st in out["steps"]]
    assert "<text>" in [a.get("text") for a in args if "text" in a]
    # 坐标 0.086 保留（在 ±0.06 邻域内）
    assert any(abs(a.get("y", -1) - 0.086) < 1e-9 for a in args if a.get("x"))


def test_validate_drops_off_trajectory_coord():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    rec = _record(traj)
    s = _summary()
    s["steps"][1]["args"] = {"x": 0.99, "y": 0.99}     # 轨迹里没点过这里
    out = validate_skill_summary(s, rec)
    assert out is not None and len(out["steps"]) == 4   # 该步被剔除，其余保留


# ---------------- 2. 语义身份 ----------------

def test_from_summary_uses_llm_routine_not_objective():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    s = validate_skill_summary(_summary(), _record(traj))
    sk = SkillLibrary.from_summary(s, _record(traj), task_id="t_s")
    assert sk.name == "skill_应用内搜索并浏览"          # 不是 objective 前 40 字
    assert sk.routine == "应用内搜索并浏览"
    assert sk.description == "在应用内搜索关键词并浏览结果流"


class _SameSkillBrain(object):
    """假 brain：预设"是否同一套路"的判定。"""

    def __init__(self, same_as=None):
        self.same_as = same_as

    def chat(self, messages, tools):
        return {"content": json.dumps(
            {"same_as": self.same_as, "reason": "stub", "description": ""},
            ensure_ascii=False)}


def test_promote_matches_same_routine_via_llm():
    """同一套路的措辞变体（routine 名字都不同）由 **LLM 判定**合并。"""
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    lib = SkillLibrary(task_id="t_s")
    s = validate_skill_summary(_summary(), _record(traj))
    sk1 = SkillLibrary.from_summary(s, _record(traj), task_id="t_s")
    lib.promote_or_insert(sk1)
    s2 = validate_skill_summary(_summary(), _record(traj))
    s2["routine"] = "边搜索边翻结果"
    rec2 = _record(traj, objective="帮我用某应用查一下「关键词」")
    sk2 = SkillLibrary.from_summary(s2, rec2, task_id="t_s2")
    r = lib.promote_or_insert(sk2, brain=_SameSkillBrain(same_as=sk1.name))
    assert r["action"] in ("promoted", "updated")
    assert r["success_count"] == 2
    # 跨 App 不合并：LLM 判否 ⇒ 新档
    s3 = validate_skill_summary(_summary(), rec2)
    s3["steps"][0]["args"]["package"] = "com.other.app"
    rec3 = _record(traj, objective="在别的应用里搜索")
    sk3 = SkillLibrary.from_summary(s3, rec3, task_id="t_s3")
    r3 = lib.promote_or_insert(sk3, brain=_SameSkillBrain(same_as=None))
    assert r3["action"] == "created"


def test_promote_without_brain_does_not_merge_by_rules():
    """无 brain ⇒ 绝不按 routine/objective 字符串规则合并（宁可不合并）。"""
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    lib = SkillLibrary(task_id="t_s")
    s = validate_skill_summary(_summary(), _record(traj))
    lib.promote_or_insert(SkillLibrary.from_summary(s, _record(traj), task_id="t_s"))
    s2 = validate_skill_summary(_summary(), _record(traj))
    s2["routine"] = "另一个套路"           # 名字不同、包名相同
    rec2 = _record(traj, objective="完全不同的一句话")
    r = lib.promote_or_insert(
        SkillLibrary.from_summary(s2, rec2, task_id="t_s2"))
    assert r["action"] == "created"


# ---------------- 3. 端到端：curator 消费 LLM JSON ----------------

class _JsonBrain(object):
    def __init__(self, payload):
        self.payload = payload

    def chat(self, messages, tools):
        return {"content": "```json\n%s\n```" % json.dumps(
            self.payload, ensure_ascii=False), "tool_calls": []}


def test_skill_autodistill_removed_from_curator():
    """自蒸馏产 skill 已下线（2026-10-02 定案），改为人工维护 skill。

    保留这条测试是为了**锁住下线状态**：Curator 不再有 review_candidate_skills，
    且蒸馏 prompt 不再要求模型产出 skill 字段（否则双写又回来了）。
    """
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    c = Curator(task_id="t_s")
    assert not hasattr(c, "review_candidate_skills")
    assert "skill" not in Curator._EXTRACT_PROMPT
    # facts 蒸馏链仍完好
    c.brain = _JsonBrain({"facts": ["搜索入口需要两步点按"]})
    assert c._llm_extract(_record(traj)) == ["搜索入口需要两步点按"]


# ---------------- 7. playbook（引导型文字套路，P2-R v3） ----------------

def test_playbook_roundtrip_and_multiline_collapse():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    rec = _record(traj)
    s = _summary()
    s["playbook"] = ("确认已在首页\n点搜索框输入关键词；点搜索；"
                     "陷阱：底部标签不可点\n成功=结果流出现")
    out = validate_skill_summary(s, rec)
    assert out is not None and "陷阱" in out["playbook"]
    assert "\n" not in out["playbook"]            # 压成单行（frontmatter 按行解析）
    skill = SkillLibrary.from_summary(out, rec, task_id="t_s")
    lib = SkillLibrary(task_id="t_s")
    lib.save(skill)
    back = lib.load(skill.name)
    assert back is not None and back.playbook == out["playbook"]
    assert "## Guide" in back.to_markdown()


def test_validate_allows_missing_playbook():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    out = validate_skill_summary(_summary(), _record(traj))
    assert out is not None and out["playbook"] == ""


# ---------------- 8. keywords（LLM 产检索关键词，P2-R v3） ----------------

def test_keywords_roundtrip_and_feed_retrieval():
    setup_tmp_root()
    traj = os.path.join(setup_tmp_root(), "t.jsonl")
    _write_trajectory(traj)
    rec = _record(traj)
    s = _summary()
    s["keywords"] = ["搜索", "搜一下", "查一下", "search", "翻翻"]
    out = validate_skill_summary(s, rec)
    assert out is not None and len(out["keywords"]) == 5
    skill = SkillLibrary.from_summary(out, rec, task_id="t_s")
    lib = SkillLibrary(task_id="t_s")
    lib.save(skill)
    back = lib.load(skill.name)
    assert back.keywords == out["keywords"]          # frontmatter 往返
    # 检索面：keywords 进入给 LLM 的候选摘要（选档由 LLM 做，不再有词面打分）
    from hachimi_kernel.skill_library import _skill_brief
    brief = _skill_brief(back)
    assert "keywords=" in brief and "查一下" in brief
