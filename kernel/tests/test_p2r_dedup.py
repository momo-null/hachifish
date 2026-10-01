# -*- coding: utf-8 -*-
"""P2-R 测试：facts 语义级去重（同义不同表述）。

条目级精确去重（strip+小写全文比对）挡不住 LLM 生成的表述差异，
例如「按返回键自动保存」与「按 back 返回后即写入列表」会被当成两条。
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                     # noqa: E402
from hachimi_kernel.curator import (                              # noqa: E402
    Curator, semantic_dedup, _similar,
)


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rd_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def test_similar_merges_paraphrases():
    a = "在 Fossify Notes 编辑笔记后按返回键即自动保存，无需显式点击保存按钮"
    b = "Fossify Notes 编辑正文后按 back 返回会自动保存，无需额外点击保存按钮"
    assert _similar(a, b)


def test_similar_keeps_distinct_facts():
    a = "新建弹窗的标题字段被锁定，需点按后才能输入"
    b = "正文编辑区是占位行控件，需先点按取焦点"
    assert not _similar(a, b)


def test_semantic_dedup_compresses_variants():
    items = [
        "编辑笔记后按返回键自动保存，无需点击保存按钮",
        "编辑正文后按 back 返回会自动保存，无需额外保存操作",
        "标题字段被锁定需点按后才能输入",
    ]
    out = semantic_dedup(items)
    assert len(out) == 2, out          # 前两条合并，第三条保留
    assert "标题" in out[-1]


def test_merge_memory_skips_paraphrase():
    """既有条目的同义表述不应再进 MEMORY.md（防线性膨胀）。"""
    setup_tmp_root()
    P.memory_master().write_text(
        "# OmniAgent 全局长期记忆\n\n## 长期事实\n"
        "- 在 Fossify Notes 编辑笔记后按返回键即自动保存，无需显式点击保存按钮\n\n"
        "## 教训\n- (暂无)\n", encoding="utf-8")
    existing = P.memory_master().read_text(encoding="utf-8")
    new = ["Fossify Notes 编辑正文后按 back 返回会自动保存，无需额外点击保存按钮",
           "新建弹窗确认按钮使用系统 id android:id/button1"]
    text, added_f, added_l = _merge(existing, new, [])
    assert added_f == 1, (added_f, text)     # 同义那条被挡掉，只加第二条
    assert "button1" in text


def _merge(existing, facts, lessons):
    from hachimi_kernel.curator import _merge_memory_sections
    return _merge_memory_sections(existing, facts, lessons)
