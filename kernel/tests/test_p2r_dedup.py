# -*- coding: utf-8 -*-
"""facts 去重测试：**语义判定在 LLM，脚本只做逐字精确去重**。

2026-10-02 用户红线：不要脚本化的语义判断。旧实现（重叠率阈值 0.35/0.25/
0.55/0.20 + 控件 id 正则 + CJK bigram 词面指纹）整套删除，连同它的调参记录。
本文件改测 LLM 判定路径（:func:`llm_dedup` / :func:`llm_marks`）与无 brain
时的降级行为（只精确去重、不猜语义）。
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel.curator import (                              # noqa: E402
    exact_dedup, llm_dedup, llm_marks,
    compact_memory_sections, compact_memory_master,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rd_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


class _Brain:
    """按脚本返回固定 JSON 的假 brain（只测接线，不测模型）。"""

    def __init__(self, payload):
        self.payload = payload
        self.calls = 0

    def chat(self, messages, tools):
        self.calls += 1
        return {"content": "```json\n%s\n```" % json.dumps(
            self.payload, ensure_ascii=False)}


# ---------------- 脚本侧：只有逐字精确去重 ----------------

def test_exact_dedup_keeps_distinct_wordings():
    items = ["编辑笔记后按返回键自动保存", "编辑正文后按 back 返回会自动保存",
             "标题字段被锁定需点按后才能输入"]
    out = exact_dedup(items)
    assert len(out) == 3           # 语义相近也保留 —— 语义不归脚本管


def test_exact_dedup_removes_literal_duplicates():
    out = exact_dedup(["同一条事实", "同一条事实", "  - 另一条 "])
    assert out == ["同一条事实", "另一条"]


# ---------------- LLM 侧：duplicate / merge / independent ----------------

def test_llm_dedup_duplicate_and_independent():
    a = "编辑笔记后按返回键即自动保存"
    b = "编辑正文后按 back 返回会自动保存"
    c = "标题字段被锁定需点按后才能输入"
    brain = _Brain({"verdicts": [
        {"new": b, "decision": "duplicate", "target": a},
        {"new": c, "decision": "independent"},
    ]})
    kept_existing, kept_new = llm_dedup([a], [b, c], brain)
    assert kept_existing == [a]
    assert kept_new == [c]          # 同义那条被 LLM 判掉


def test_llm_dedup_merge_rewrites_existing():
    a = "时间选择器需先切文本输入模式"
    b = "时间选择器默认是指针模式，必须先切到文本输入模式才能输入数字"
    brain = _Brain({"verdicts": [
        {"new": b, "decision": "merge", "target": a,
         "merged": "时间选择器默认为指针模式，需先切到文本输入模式才能输入数字"},
    ]})
    kept_existing, kept_new = llm_dedup([a], [b], brain)
    assert kept_existing == ["时间选择器默认为指针模式，需先切到文本输入模式才能输入数字"]
    assert kept_new == []


def test_llm_dedup_falls_back_to_exact_without_brain():
    """无 brain ⇒ 只做逐字精确去重，绝不用规则猜语义。"""
    a = "编辑笔记后按返回键即自动保存"
    kept_existing, kept_new = llm_dedup([a], ["编辑正文后按 back 返回会自动保存"], None)
    assert kept_existing == [a]
    assert len(kept_new) == 1       # 语义可能重复，但脚本不猜


def test_llm_dedup_survives_broken_json():
    class Broken:
        def chat(self, messages, tools):
            return {"content": "not json at all"}
    a = "第一条"
    kept_existing, kept_new = llm_dedup([a], ["第二条"], Broken())
    assert kept_existing == [a] and kept_new == ["第二条"]


# ---------------- compact（存量回溯合并）走同一条 LLM 判定 ----------------

def test_compact_uses_llm_verdict():
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- 在 Fossify Notes 编辑笔记后按返回键即自动保存，无需点击保存按钮\n"
            "- Fossify Notes 编辑正文后按 back 返回会自动保存，无需额外保存操作\n"
            "- 新建弹窗的标题字段被锁定，需点按后才能输入\n\n"
            "## 教训\n- (暂无)\n")
    brain = _Brain({"drop": ["Fossify Notes 编辑正文后按 back 返回会自动保存，无需额外保存操作"],
                    "rewrite": []})
    new_text, removed = compact_memory_sections(text, brain=brain)
    assert removed == 1
    assert "标题字段被锁定" in new_text


def test_compact_without_brain_only_dedups_exact():
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- 编辑笔记后按返回键自动保存\n"
            "- 编辑笔记后按返回键自动保存\n\n"
            "## 教训\n- (暂无)\n")
    new_text, removed = compact_memory_sections(text)
    assert removed == 1               # 只去掉逐字重复那条


def test_compact_normalizes_legacy_double_bullets():
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- - 闹钟标签输入框 resource-id 为 org.fossify.clock:id/edit_alarm\n\n"
            "## 教训\n- (暂无)\n")
    new_text, removed = compact_memory_sections(text)
    assert removed == 0
    assert "- - " not in new_text
    assert "- 闹钟标签输入框" in new_text


def test_compact_memory_master_writes_file():
    setup_tmp_root()
    P.memory_master().write_text(
        "# OmniAgent 全局长期记忆\n\n## 长期事实\n"
        "- 编辑笔记后按返回键自动保存\n- 编辑笔记后按返回键自动保存\n\n"
        "## 教训\n- (暂无)\n", encoding="utf-8")
    assert compact_memory_master() == 1
    assert compact_memory_master() == 0        # 再跑一次无重复


# ---------------- 印证/反证判定也在 LLM ----------------

def test_llm_marks_reads_indices():
    items = ["时间选择器需先切文本模式", "标题字段被锁定"]
    brain = _Brain({"corroborated": [1], "contradicted": [], "note": "x"})
    assert llm_marks(items, "轨迹：点 mode 按钮后输入 07", brain, positive=True) == [0]


def test_llm_marks_without_brain_returns_empty():
    items = ["时间选择器需先切文本模式"]
    assert llm_marks(items, "任意证据", None, positive=True) == []


def test_strip_bullet_handles_nested_markers():
    from hachimi_kernel.curator import _strip_bullet
    assert _strip_bullet("- - 文本输入模式下需分别点按") == "文本输入模式下需分别点按"
    assert _strip_bullet("- 正常条目") == "正常条目"
