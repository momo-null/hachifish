# -*- coding: utf-8 -*-
"""K7 知识模块接线测试（redesign_plan：注入 / Curator 挂钩 / 数据面）。

覆盖：
1. 弱注入：memory summary + 全局技能 → build_knowledge_block → MiniLoop
   system prompt；开关关 = 零注入（逐字节与无 knowledge 一致）。
2. Curator 挂钩：任务 finish（非实验组）→ Curator.run_once 被调（run_record
   传入）；实验组冻结 = 不调用；Curator 异常不拖垮 finish 流程。
3. 数据面：memory_json（MEMORY.md 全文 + 统计）/ skills_list_json（全局技能）/
   rollouts_list_json（倒序）。

纯标准库离线跑：`py -3 tests/test_k7_knowledge.py`。
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge                      # noqa: E402
from hachimi_kernel import runtime_paths as P          # noqa: E402
from hachimi_kernel.mini_loop import MiniLoop          # noqa: E402
from hachimi_kernel import knowledge_inject           # noqa: E402


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_k7_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


class NullBrain(object):
    def __init__(self):
        self.views = []

    def chat(self, messages, tools):
        self.views.append([(m.get("role"), m.get("content") or "")
                           for m in messages])
        return {"content": "ok", "tool_calls": []}


# ---------------- 1. 弱注入 ----------------

def test_knowledge_block_into_system_prompt():
    P.memory_summary().write_text("MIUI 桌面路径实际指向 Download", encoding="utf-8")
    block = bridge._knowledge_block()
    assert "历史记忆" in block and "MIUI" in block, block
    brain = NullBrain()
    MiniLoop(objective="x", done_when="", tools={}, brain=brain,
             config={"max_steps": 1, "knowledge": block},
             message="hi").run()
    system = brain.views[0][0][1]
    assert "历史记忆" in system and "MIUI" in system


def test_knowledge_includes_global_skills():
    d = P.global_skills()
    d.mkdir(parents=True, exist_ok=True)
    (d / "desktop-cleanup.md").write_text(
        "---\nname: desktop-cleanup\nobjective_pattern: 整理桌面\n---\n操作序列…",
        encoding="utf-8")
    block = bridge._knowledge_block()
    assert "desktop-cleanup" in block, block


def test_knowledge_disabled_zero_inject():
    bridge.set_knowledge_enabled(False)
    assert bridge._knowledge_block() == ""
    bridge.set_knowledge_enabled(True)   # 复位


def test_knowledge_block_empty_when_no_data():
    P.memory_summary().unlink(missing_ok=True)
    # 清掉上例写的技能
    for f in P.global_skills().glob("*.md"):
        f.unlink()
    assert bridge._knowledge_block() == ""


# ---------------- 2. Curator 挂钩 ----------------

def _finish_a_task():
    """跑一个最小任务（纯文本 chat 终态）触发 on_finish 挂钩。"""
    bridge._state = {"run_id": "r_k7_%d" % (abs(hash("x")) % 100000),
                     "phase": "running", "objective": "t", "started": 0,
                     "summary": None, "trajectory": [], "error": None}
    bridge._controls.update({"paused": False, "stopped": False})
    bridge._make_brain = lambda body: NullBrain()
    sink = _Sink()
    bridge.register_narrative(sink)
    bridge._run({"message": "你好", "max_steps": 4}, bridge._controls)
    return sink


class _Sink(object):
    def __init__(self): self.events = []
    def push(self, p): self.events.append(json.loads(p))


def test_curator_hook_invoked_on_finish():
    setup_tmp_root()
    calls = []
    origin = bridge._make_curator
    bridge._make_curator = lambda tid: _FakeCurator(calls)
    try:
        _finish_a_task()
        assert len(calls) == 1, calls
        assert calls[0][1] is not None   # run_record 传入（供 review/flag）
    finally:
        bridge._make_curator = origin


def test_curator_failure_does_not_break_finish():
    setup_tmp_root()
    origin = bridge._make_curator
    class BoomCurator(object):
        def run_once(self, run_record=None):
            raise RuntimeError("curator boom")
    bridge._make_curator = lambda tid: BoomCurator()
    try:
        sink = _finish_a_task()
        # finish 事件照常发出（Curator 崩溃不吞终态）
        assert any(e["event"] == "finish" for e in sink.events)
        assert bridge.status()["phase"] == "done"
    finally:
        bridge._make_curator = origin


class _FakeCurator(object):
    def __init__(self, calls):
        self._calls = calls

    def run_once(self, run_record=None):
        self._calls.append(("run_once", run_record))
        return {"ok": True}


# ---------------- 3. 数据面 ----------------

def test_memory_json_stats():
    setup_tmp_root()
    P.memory_master().write_text("# MEMORY\n- 用户爱用主人称呼", encoding="utf-8")
    P.memory_rollouts().mkdir(parents=True, exist_ok=True)
    (P.memory_rollouts() / "t_abc.md").write_text("rollout", encoding="utf-8")
    r = json.loads(bridge.memory_json())
    assert r["ok"] and "主人" in r["master"]
    assert r["rollouts"] == 1
    assert r["master_chars"] > 0


def test_skills_list_json():
    setup_tmp_root()
    d = P.global_skills()
    d.mkdir(parents=True, exist_ok=True)
    (d / "s1.md").write_text(
        "---\nname: s1\nobjective_pattern: 测试\nuse_count: 5\n"
        "success_count: 4\ncreated_at: 2026-09-28T10:00:00+00:00\n---\n序列",
        encoding="utf-8")
    r = json.loads(bridge.skills_list_json())
    assert r["ok"] and any(s["name"] == "s1" for s in r["skills"])
    # V7 统计字段：frontmatter 自维护字段透传（缺字段技能 = 0 默认）
    s1 = [s for s in r["skills"] if s["name"] == "s1"][0]
    assert s1["use_count"] == 5 and s1["success_count"] == 4, s1
    assert s1["created_at"].startswith("2026-09-28"), s1
    # 无统计字段技能：默认 0 不炸
    (d / "s2.md").write_text(
        "---\nname: s2\nobjective_pattern: x\n---\n序列", encoding="utf-8")
    r2 = json.loads(bridge.skills_list_json())
    s2 = [s for s in r2["skills"] if s["name"] == "s2"][0]
    assert s2["use_count"] == 0 and s2["success_count"] == 0


def test_rollouts_list_json():
    setup_tmp_root()
    rd = P.memory_rollouts()
    rd.mkdir(parents=True, exist_ok=True)
    (rd / "t_a.md").write_text("A", encoding="utf-8")
    (rd / "t_b.md").write_text("B", encoding="utf-8")
    r = json.loads(bridge.rollouts_list_json())
    assert r["ok"] and len(r["rollouts"]) == 2


def test_data_plane_empty_ok():
    setup_tmp_root()
    assert json.loads(bridge.memory_json())["ok"]
    assert json.loads(bridge.skills_list_json())["ok"]
    assert json.loads(bridge.rollouts_list_json())["ok"]


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
