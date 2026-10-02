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


class _MarkBrain:
    """假 brain：按关键词勾选被印证/打脸的条目（印证判定已从脚本移出）。"""

    def __init__(self, keywords):
        self.keywords = keywords

    def chat(self, messages, tools):
        import json
        prompt = messages[-1]["content"]
        picked = []
        for ln in prompt.splitlines():
            ln = ln.strip()
            if not ln[:1].isdigit():
                continue
            num, _, text = ln.partition(". ")
            if any(k in text for k in self.keywords):
                picked.append(int(num))
        return {"content": json.dumps(
            {"corroborated": picked, "contradicted": picked, "note": "stub"},
            ensure_ascii=False)}


def test_defaults_are_bounded():
    """容量常量必须存在且有限（手机端不是企业服务，必须有上限）。"""
    for name in ("FACTS_PER_APP_MAX", "FACTS_TOTAL_MAX", "SKILLS_MAX",
                 "ROLLOUTS_MAX", "TASK_KEEP_RUNS"):
        assert getattr(C, name) > 0, name
    assert C.FACTS_APP_FLOOR <= C.FACTS_PER_APP_MAX


def test_corroborate_bumps_hits_for_llm_marked_facts():
    """印证由 LLM 判定：被勾选的条目 hits+1（无 brain 时不猜，命中为 0）。"""
    setup_tmp_root()
    write_facts([CLOCK_FACTS[0]])
    C.corroborate_facts('1. launch_app({"package": "org.fossify.clock"}) -> ok',
                        brain=_MarkBrain(["alarm_fab"]))
    meta = C._load_fact_meta()
    assert list(meta.values())[0]["hits"] == 1
    # 无 brain：脚本不再用"包名/控件出现"规则猜印证
    setup_tmp_root()
    write_facts([CLOCK_FACTS[0]])
    C.corroborate_facts('1. launch_app({"package": "org.fossify.clock"}) -> ok')
    assert not C._load_fact_meta()


def test_prune_drops_literal_duplicate_only():
    """删除路径只砍逐字重复；语义相近的两条都保留（语义归 LLM，不归脚本）。"""
    setup_tmp_root()
    near = CLOCK_FACTS[0]
    write_facts([near, near, CLOCK_FACTS[2], near + "（同义改写但非逐字）"])
    assert C.prune_memory() == 1
    left = read_facts()
    assert len(left) == 3
    assert CLOCK_FACTS[2] in left


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
        # 印证由 LLM 判定：假 brain 勾选含 alarm_fab / edit_alarm 的两条
        evidence = '2. tap_by_id({"view_id": "alarm_fab"}) -> ok'
        for _ in range(3):                     # 反复印证那两条
            C.corroborate_facts(evidence, brain=_MarkBrain(["alarm_fab", "edit_alarm"]))
        C.prune_memory()
        left = read_facts()
        assert CLOCK_FACTS[0] in left          # alarm_fab
        assert CLOCK_FACTS[2] in left          # edit_alarm
        assert CLOCK_FACTS[4] not in left      # 从未被印证的那条被淘汰
    finally:
        C.FACTS_PER_APP_MAX = old


def test_contradiction_penalises_and_evicts():
    """失败 run 会给相关事实记反证；累计到阈值直接淘汰（对齐 TDB 冲突检测）。"""
    setup_tmp_root()
    write_facts(CLOCK_FACTS[:3])
    evidence = '2. tap_by_id({"view_id": "alarm_fab"}) -> ERR: view not found'
    for _ in range(C.FACT_FAILS_EVICT):
        C.contradict_facts(evidence, brain=_MarkBrain(["alarm_fab"]))
    meta = C._load_fact_meta()
    assert sum(int(v.get("fails") or 0) for v in meta.values()
               if isinstance(v, dict) and "fails" in v) >= C.FACT_FAILS_EVICT
    dropped = C.prune_memory()
    assert dropped >= 1
    assert CLOCK_FACTS[0] not in read_facts()


def test_score_penalises_fails():
    setup_tmp_root()
    write_facts([CLOCK_FACTS[0], CLOCK_FACTS[1]])
    now = time.time()
    C.corroborate_facts('1. tap_by_id({"view_id": "alarm_fab"}) -> ok',
                        brain=_MarkBrain(["alarm_fab"]))
    C.contradict_facts(
        '1. tap_by_id({"view_id": "material_timepicker_mode_button"}) -> ERR',
        brain=_MarkBrain(["material_timepicker_mode_button"]))
    meta = C._load_fact_meta()
    s_ok = C._fact_score(CLOCK_FACTS[0], meta, now)
    s_bad = C._fact_score(CLOCK_FACTS[1], meta, now)
    assert s_ok > s_bad


def test_extract_cooldown():
    """写入端节流：同一 App 冷却期内不重复提取（省 LLM 调用 + 防灌水）。"""
    setup_tmp_root()
    assert C.should_extract_facts("org.fossify.clock")
    C.mark_facts_extracted("org.fossify.clock")
    assert not C.should_extract_facts("org.fossify.clock")
    assert C.should_extract_facts("org.fossify.notes")   # 别的 App 不受影响


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
