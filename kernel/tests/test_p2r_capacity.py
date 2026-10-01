# -*- coding: utf-8 -*-
"""P2-R 测试：知识容量边界（效用驱动的滑动窗口，§8.2）。

定位：不追求完美归并 —— 漏掉的同义变体只占一个槽位，槽位满了按效用淘汰，
"弱者淘汰"天然完成收敛。这里验证的是这套自洽机制，而非去重精度。
"""

import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import runtime_paths as P                      # noqa: E402
from hachimi_kernel import curator as C                            # noqa: E402

# 同一 App 的 6 条**互不相同**的事实（真机风格；避免模板句导致互相"相似"）
CLOCK_FACTS = [
    "org.fossify.clock 的闹钟列表底部有 alarm_fab 悬浮按钮，可直接新建闹钟",
    "org.fossify.clock 时间选择器默认是表盘模式，需先点 material_timepicker_mode_button 切到文本输入",
    "org.fossify.clock 闹钟标签输入框 id 为 edit_alarm，时间显示为 edit_alarm_time",
    "org.fossify.clock 底部导航标签只能通过坐标点按切换，id 定位不可靠",
    "org.fossify.clock 的确认按钮是系统通用 android:id/button1，不是应用私有 id",
    "org.fossify.clock 时间选择器是独立对话框层，必须先点开再确认，直接点 ok 不生效",
]
OTHER_FACTS = [
    "org.fossify.notes 的列表条目即内联编辑器，点入后 type_text 会整行替换并自动保存",
    "org.fossify.notes 新建时会弹标签输入框，先填标签再点确定才会创建分类",
    "多数 Fossify 应用的确认弹窗都使用系统级 id（android:id/button1）",
]


def setup_tmp_root():
    tmp = tempfile.mkdtemp(prefix="hachimi_p2rc_test_")
    P._GLOBAL = P.Path(tmp) / "omni"
    P._GLOBAL.mkdir(parents=True, exist_ok=True)
    P.ensure_global_dirs()
    return tmp


def write_facts(items):
    P.memory_master().write_text(
        C._render_memory_sections({C._FACT_SEC: list(items), C._LESSON_SEC: []}),
        encoding="utf-8")


def read_facts():
    return C._split_memory_sections(
        P.memory_master().read_text(encoding="utf-8")).get(C._FACT_SEC) or []


def test_defaults_are_bounded():
    """容量常量必须存在且有限（手机端不是企业服务，必须有上限）。"""
    for name in ("FACTS_PER_APP_MAX", "FACTS_TOTAL_MAX", "SKILLS_MAX",
                 "ROLLOUTS_MAX", "TASK_KEEP_RUNS"):
        assert getattr(C, name) > 0, name
    assert C.FACTS_APP_FLOOR <= C.FACTS_PER_APP_MAX


def test_corroborate_bumps_hits_for_seen_app():
    """本次 run 跑过的 App，其事实被点亮（hits+1）。"""
    setup_tmp_root()
    write_facts([CLOCK_FACTS[0]])
    C.corroborate_facts('1. launch_app({"package": "org.fossify.clock"}) -> ok')
    meta = C._load_fact_meta()
    assert list(meta.values())[0]["hits"] == 1


def test_corroborate_cluster_credits_leader_only():
    """同义簇内只给代表加分 —— 变体之间靠此收敛（弱者淘汰代替精确归并）。"""
    setup_tmp_root()
    a = "org.fossify.notes 编辑笔记后按返回键即自动保存 text_note_view 的内容"
    b = "org.fossify.notes 编辑正文后按 back 返回会自动保存 text_note_view 的内容"
    write_facts([a, b])
    C.corroborate_facts('9. tap_by_id({"view_id": "org.fossify.notes:id/text_note_view"}) -> ok')
    meta = C._load_fact_meta()
    # 两条都被登记，但簇内只有代表被加分（另一条保持 0）
    assert sum(int(v["hits"]) for v in meta.values()) == 1


def test_prune_drops_near_verbatim_duplicate_only():
    """删除路径保守：近乎逐字重复才砍；共享控件但不相同的两条必须都留下。"""
    setup_tmp_root()
    near = CLOCK_FACTS[0]
    write_facts([near, near + "。", CLOCK_FACTS[2], CLOCK_FACTS[3]])
    assert C.prune_memory() == 1
    left = read_facts()
    assert len(left) == 3
    assert CLOCK_FACTS[2] in left and CLOCK_FACTS[3] in left


def test_prune_enforces_per_app_cap():
    setup_tmp_root()
    old = C.FACTS_PER_APP_MAX
    C.FACTS_PER_APP_MAX = 3
    try:
        write_facts(CLOCK_FACTS)
        assert C.prune_memory() == 3
        assert len(read_facts()) == 3
    finally:
        C.FACTS_PER_APP_MAX = old


def test_prune_keeps_every_app_a_floor():
    """超容时也不能把某个 App 的事实清空（保底优先于上限）。"""
    setup_tmp_root()
    old = C.FACTS_PER_APP_MAX
    C.FACTS_PER_APP_MAX = 2          # 上限低于保底：以保底为准
    try:
        write_facts(CLOCK_FACTS)
        C.prune_memory()
        assert len(read_facts()) >= C.FACTS_APP_FLOOR
    finally:
        C.FACTS_PER_APP_MAX = old


def test_prune_enforces_total_cap():
    setup_tmp_root()
    old = C.FACTS_TOTAL_MAX
    C.FACTS_TOTAL_MAX = 5
    try:
        write_facts(CLOCK_FACTS + OTHER_FACTS)
        assert len(read_facts()) == 9
        C.prune_memory()
        assert len(read_facts()) == 5
    finally:
        C.FACTS_TOTAL_MAX = old


def test_prune_prefers_well_corroborated_facts():
    """常被印证的事实分数高 —— 超容时先淘汰没人印证过的（滑动窗口的核心）。"""
    setup_tmp_root()
    old = C.FACTS_PER_APP_MAX
    C.FACTS_PER_APP_MAX = 3
    try:
        write_facts(CLOCK_FACTS)
        # 证据只含具体控件、不含包名：包名会把该 App 的所有事实一起点亮，
        # 那样就分不出高下了（跨 App 竞争时仍然有效，此处要验的是 App 内部）
        evidence = ('2. tap_by_id({"view_id": "alarm_fab"}) -> ok\n'
                    '3. tap_by_id({"view_id": "edit_alarm"}) -> ok')
        for _ in range(3):                     # 反复印证前两条
            C.corroborate_facts(evidence)
        C.prune_memory()
        left = read_facts()
        assert CLOCK_FACTS[0] in left          # alarm_fab
        assert CLOCK_FACTS[2] in left          # edit_alarm
        assert CLOCK_FACTS[4] not in left      # 从未被印证的那条被淘汰
    finally:
        C.FACTS_PER_APP_MAX = old


def test_prune_rollouts_keeps_newest():
    setup_tmp_root()
    d = P.memory_rollouts()
    for i in range(5):
        f = d / ("t_%d.md" % i)
        f.write_text("x", encoding="utf-8")
        os.utime(f, (time.time() - (5 - i) * 100, time.time() - (5 - i) * 100))
    assert C.prune_rollouts(max_keep=3) == 2
    assert len(list(d.glob("*.md"))) == 3


def test_prune_tasks_keeps_newest_runs():
    setup_tmp_root()
    root = P.tasks_root()
    for i in range(5):
        d = root / ("t_%d" % i)
        d.mkdir(parents=True, exist_ok=True)
        (d / "trajectory.jsonl").write_text("{}", encoding="utf-8")
        os.utime(d, (time.time() - (5 - i) * 100, time.time() - (5 - i) * 100))
    assert C.prune_tasks(max_runs=2, max_days=365, max_mb=9999) == 3
    assert len([d for d in root.iterdir() if d.is_dir()]) == 2


def test_maintain_capacity_is_safe_on_empty_state():
    setup_tmp_root()
    out = C.maintain_capacity(evidence="")
    assert out["facts_pruned"] == 0
    assert out["tasks_pruned"] == 0
