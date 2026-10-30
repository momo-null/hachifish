"""执行验证型 skill 方案（trace→substeps + entry_id 晋升 + 回放省步）离线验证。

无需真机 / BYOK：用合成 RunRecord 驱动 skill_library 的新入口，覆盖
（1）entry_id 跨字面变动稳定、跨 App 不同；（2）N=3 晋升；（3）回放省步度量。

文件系统经 ``rp._GLOBAL`` monkeypatch 隔离到 tmp_path，不污染 ~/.omniagent。
"""
import shutil

import pytest

import hachimi_kernel.runtime_paths as rp
from hachimi_kernel import skill_library as sl
from hachimi_kernel.skill_library import (
    SkillLibrary, Skill, SkillSubstep, compute_entry_id, canonical_ops,
)


@pytest.fixture(autouse=True)
def _env(tmp_path, monkeypatch):
    monkeypatch.setattr(rp, "_GLOBAL", tmp_path)
    d = rp.global_skills()
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True, exist_ok=True)
    yield


def _canon(record):
    acts = SkillLibrary.executed_actions(record)
    return [SkillSubstep(tool=a["tool"], args=a.get("args") or {}) for a in acts]


def _rec(objective, package, texts, success=True):
    """合成一条成功轨迹：launch → 新建按钮 → 若干 type_text → 保存 → observe(被剔除)。"""
    steps = [
        {"tool": "launch_app", "args": {"package": package}},
        {"tool": "tap_by_id", "args": {"view_id": "new_btn"}},
    ]
    for t in texts:
        steps.append({"tool": "type_text", "args": {"text": t}})
    steps.append({"tool": "tap_by_id", "args": {"view_id": "save_btn"}})
    steps.append({"tool": "observe", "args": {}})
    return {"objective": objective, "success": success, "steps_data": steps}


def test_entry_id_stable_across_text_and_app_private():
    r1 = _rec("新建便签", "com.notes", ["hello"])
    r2 = _rec("新建便签", "com.notes", ["world"])          # 仅字面文本不同
    r3 = _rec("新建便签", "com.other", ["hello"])          # 同结构、不同 App
    r4 = _rec("计算器", "com.calc", ["1", "2", "3"])       # 不同结构
    e1 = compute_entry_id(_canon(r1))
    e2 = compute_entry_id(_canon(r2))
    e3 = compute_entry_id(_canon(r3))
    e4 = compute_entry_id(_canon(r4))
    assert e1 == e2, "同结构不同字面文本应同 entry_id"
    assert e1 != e3, "不同 App(package) 应不同 entry_id（App 私有边界）"
    assert e1 != e4, "不同操作结构应不同 entry_id"


def test_auto_distill_promotes_after_n3():
    lib = SkillLibrary("task_demo")
    eid = None
    for i, txt in enumerate(["a", "b", "c"]):
        res = lib.auto_distill(_rec("新建便签", "com.notes", [txt]))
        if eid is None:
            eid = res["entry_id"]
        if i < 2:
            assert res["action"] in ("created", "updated")
            assert res["success_count"] == i + 1
        else:
            assert res["action"] == "promoted"
            assert res["promoted"] is True
    sk = lib.find_by_entry_id(eid)
    assert sk is not None
    assert sk.metadata.status == "active"
    assert sk.metadata.success_count == 3


def test_auto_distill_skips_failures_and_short():
    lib = SkillLibrary("task_demo")
    assert lib.auto_distill(_rec("x", "com.notes", ["a"], success=False)) is None
    short = {"objective": "x", "success": True,
             "steps_data": [{"tool": "launch_app", "args": {"package": "p"}}]}
    assert lib.auto_distill(short, min_steps=2) is None


def test_replay_savings_prefix_match_and_divergence():
    lib = SkillLibrary("task_demo")
    r1 = _rec("新建便签", "com.notes", ["a"])
    lib.auto_distill(r1)
    sk = lib.find_by_entry_id(compute_entry_id(_canon(r1)))

    # 同结构、不同字面 → 全前缀匹配 = 4 步可跳（launch/tap/type/tap，observe 被剔除）
    saved_full = lib.replay_savings(sk, _rec("新建便签", "com.notes", ["zzz"]))
    assert saved_full == 4

    # 中途插入不同步骤 → 前缀在第 3 步失配，仅前 2 步可复用
    divergent = {
        "objective": "x", "success": True,
        "steps_data": [
            {"tool": "launch_app", "args": {"package": "com.notes"}},
            {"tool": "tap_by_id", "args": {"view_id": "new_btn"}},
            {"tool": "tap_by_id", "args": {"view_id": "menu_btn"}},  # 偏离
            {"tool": "type_text", "args": {"text": "x"}},
            {"tool": "tap_by_id", "args": {"view_id": "save_btn"}},
        ],
    }
    saved_div = lib.replay_savings(sk, divergent)
    assert saved_div == 2


def test_end_to_end_direct():
    """auto_distill 直调（skill 轴降级对齐：Curator.run_once 不再挂载自动线，
    操作缓存由人工/测试直调 SkillLibrary.auto_distill；本测试锁方法语义）。"""
    lib = SkillLibrary("task_demo")
    outs = [lib.auto_distill(_rec("新建便签", "com.notes", [t])) for t in ["a", "b", "c"]]
    promoted = sum(1 for o in outs if o and o.get("promoted"))
    created = sum(1 for o in outs if o and not o.get("promoted"))
    assert promoted == 1 and created == 2, outs
