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
    compact_memory_sections, compact_memory_master,
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


# ---------------- 存量回溯合并（compact） ----------------

def test_compact_merges_legacy_duplicates():
    """调参前沉淀的同义变体：新增去重挡不住，compact 必须能收口。"""
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- 在 Fossify Notes 编辑笔记后按返回键即自动保存，无需显式点击保存按钮\n"
            "- Fossify Notes 编辑正文后按 back 返回会自动保存，无需额外点击保存按钮\n"
            "- 新建弹窗的标题字段被锁定，需点按后才能输入\n\n"
            "## 教训\n- (暂无)\n")
    new_text, removed = compact_memory_sections(text)
    assert removed == 1, (removed, new_text)
    assert new_text.count("- ") == 3                  # 2 事实 + 1 占位
    assert "标题字段被锁定" in new_text


def test_compact_is_idempotent():
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- 编辑笔记后按返回键自动保存\n"
            "- 编辑正文后按 back 返回会自动保存\n\n"
            "## 教训\n- (暂无)\n")
    once, removed1 = compact_memory_sections(text)
    twice, removed2 = compact_memory_sections(once)
    assert removed1 == 1 and removed2 == 0


def test_compact_keeps_distinct_facts():
    text = ("# OmniAgent 全局长期记忆\n\n## 长期事实\n"
            "- 新建弹窗的标题字段被锁定，需点按后才能输入\n"
            "- 正文编辑区是占位行控件，需先点按取焦点\n\n"
            "## 教训\n- (暂无)\n")
    _, removed = compact_memory_sections(text)
    assert removed == 0


def test_shared_widget_still_needs_text_agreement():
    """共享控件 ≠ 同一件事：真机这两条共享 edit_alarm_time（重叠 0.19）。"""
    a = "时间选择器位于独立对话框层，必须先点 `edit_alarm_time` 打开再点确认按钮，否则直接点击 ok 按钮不生效"
    b = "闹钟标签输入框 resource-id 为 org.fossify.clock:id/edit_alarm，时间显示控件为 org.fossify.clock:id/edit_alarm_time。"
    assert not _similar(a, b)


def test_shared_widget_merges_when_text_agrees():
    """共享控件 + 文字吻合（重叠 0.31）：同一按钮的两种写法，应合并。"""
    a = "在 org.fossify.clock 中，闹钟列表底部的添加按钮可通过 id `alarm_fab` 点击；若使用坐标点按底部区域也能触发新建闹钟。"
    b = "在 org.fossify.clock 中，当底部导航或列表区可见时，主页面右下角的新建闹钟悬浮按钮 resource-id 为 org.fossify.clock:id/alarm_fab，可直接按 id 点按。"
    assert _similar(a, b)


def test_disjoint_widgets_need_strong_text_agreement():
    """各自点名不同控件：文字只在中度相似（0.21）时不合并。"""
    a = "时间选择器切换到文本输入模式需点击 `material_timepicker_mode_button`，之后输入框可直接输入时间。"
    b = "时间选择器位于独立对话框层，必须先点 `edit_alarm_time` 打开再点确认按钮，否则直接点击 ok 按钮不生效"
    assert not _similar(a, b)


def test_shared_quoted_phrase_merges_low_overlap_rewrite():
    """真机漏网 pair：词面重叠仅 0.26，但引用同一句 `projection not started`。"""
    a = "截图/视觉观察依赖屏幕投影（projection），在投影未启动时 screenshot 与 look 会失败并报错 `projection not started`；此时可用 wait 后重试，或改用 UI 层级/id 方式验证。"
    b = "grab_frame 类截图/视觉查询（look）可能因 projection not started 而失败，此时等待后仍可能失败，建议优先依赖 tap_by_id/observe 流程而非视觉查询。"
    assert _similar(a, b)


def test_strip_bullet_handles_nested_markers():
    """真机出现过 LLM 返回 ``- - xxx``，只剥一层会留下 "- " 前缀。"""
    from hachimi_kernel.curator import _strip_bullet
    assert _strip_bullet("- - 文本输入模式下需分别点按") == "文本输入模式下需分别点按"
    assert _strip_bullet("- 正常条目") == "正常条目"


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
        "- 在 org.fossify.clock 中，添加闹钟入口为 alarm_fab 悬浮按钮\n"
        "- 在 org.fossify.clock 中，新建闹钟悬浮按钮 resource-id 为 org.fossify.clock:id/alarm_fab\n\n"
        "## 教训\n- (暂无)\n", encoding="utf-8")
    assert compact_memory_master() == 1
    assert "alarm_fab" in P.memory_master().read_text(encoding="utf-8")
    assert compact_memory_master() == 0        # 再跑一次无重复
