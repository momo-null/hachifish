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


def test_similar_merges_real_device_paraphrases():
    """真机提取出的同义句对（实测重叠率 0.29~0.43）必须被合并。"""
    pairs = [
        ("Fossify 时钟底部导航标签需通过坐标点按切换，id 定位不一定可靠",
         "fossify.clock 底部标签栏通过坐标点按切换，添加闹钟入口为 alarm_fab 悬浮按钮"),
        ("在 org.fossify.clock 中，material 时间选择器需先点按 mode 按钮切换到文本输入模式",
         "时间选择器默认可能是表盘模式，需先点模式切换按钮才会进入文本输入模式"),
        ("应用内的确认对话框使用系统级 id（android:id/button1）而非应用专属 id",
         "弹窗确认按钮使用系统通用 id（android:id/button1），可跨应用复用"),
    ]
    for a, b in pairs:
        assert _similar(a, b), (a, b)


def test_similar_merges_full_resource_id_variants():
    """同一控件的两种写法：短 id 与完整 resource-id（含 :/）。"""
    a = "在 org.fossify.clock 中，闹钟列表底部的添加按钮可通过 id `alarm_fab` 点击"
    b = "在 org.fossify.clock 中，新建闹钟悬浮按钮 resource-id 为 org.fossify.clock:id/alarm_fab"
    assert _similar(a, b)


def test_similar_not_fooled_by_shared_generic_words():
    """都提到"坐标点按"但讲不同控件 —— 不能合并。"""
    a = "Fossify 时钟底部导航标签需通过坐标点按切换，id 定位不一定可靠"
    b = "在 org.fossify.clock 中，material_minute_text_input 数字输入框无法 id 点击，需坐标 tap_xy"
    assert not _similar(a, b)


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
