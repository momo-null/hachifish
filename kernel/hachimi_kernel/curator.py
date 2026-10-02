"""M4b.3 Curator：触发式静默维护知识库锐度。

Hermes Autonomous Background Curator 范式；Master Spec §6 决策「仅触发式，不挂定时器」。
任务完成后触发一次（由 ``tool_loop._finish`` 调 ``run_once``），做三件维护：

1. **prune_trajectories**：清理过期轨迹（raw 30 天 / failures 14 天），调 TrajectoryStore.prune。
2. **refine_world_model**：精炼 world-model facts —— 去重已 Merge 的冗余、移除陈旧事实。
3. **flag_low_quality**：高 retry / 高 brain_intervention / 非稳定成功的轨迹 → 标
   ``excluded_from_sft``，供未来数据集构建过滤。

外加**任务记忆蒸馏**（``distill_task_memory``：轨迹 → 环境事实 → MEMORY.md）与容量维护。

**skill 平面已改为人工维护（2026-10-02 定案）**：自蒸馏不再产出 skill。
依据：facts 与 skill playbook 同源同一次 LLM 调用、内容 3/3 重叠（本是同一件东西被
拆成两层）；facts 有效靠的是**载体形态**（短 / 常驻 / 陈述句）而非内容分类——M 臂里
``android:id/button1`` 这类锚点型条目同样有效；skill 无跨 App 泛化能力，唯一真实用途是
同 App 近似任务省步（= 操作缓存，天然属于 facts）。四组真机对照里 skill 两种形态
（机械动作序列 / LLM playbook）均未优于无知识基线，而 facts 三轮方向一致为正。
⇒ 保留 ``skills/*.md`` 与 ``load_skill`` / ``search_skill`` 供**人工编写**的技能使用，
自动蒸馏产 skill 的入口（原 ``review_candidate_skills``）已删除。

设计红线：
- 零场景硬编码（task_id 来自参数，资产跟 task 走）。
- **非决策者**：不介入任务执行，只在任务后静默维护。
- 所有操作幂等、容错（单文件损坏不中断整体 prune）。
- 返回 CuratorReport 供 telemetry / debug（``skills_*`` 字段保留恒 0，勿删）。
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import shutil
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from hachimi_kernel.trajectory import TrajectoryStore
from hachimi_kernel.world_model import WorldModel
from hachimi_kernel.runtime_paths import (
    task_dir,
    tasks_root,
    global_memory,
    global_skills,
    memory_rollouts,
    memory_rollout_file,
    memory_master,
    memory_summary,
)


def _steady_converged() -> bool:
    """K5：域是否已收敛（稳态降频判据）。异常时保守返回 False（照常蒸馏）。"""
    try:
        from hachimi_kernel import steady_state as _ss
        return bool(_ss.converged())
    except Exception:
        return False


# ---------------------------------------------------------------------------
# 质量判定阈值（Master Spec §6 / §5.2；全可配，不硬编码到业务）
# ---------------------------------------------------------------------------

DEFAULTS: Dict[str, Any] = {
    "prune_raw_days": 30,
    "prune_failure_days": 14,
    # flag_low_quality 阈值
    "high_retry_threshold": 3,        # retry_count >= 此值 → low_quality
    "high_intervention_threshold": 0.5,  # brain_intervention_rate >= 此值 → low_quality
    "min_steps_for_skill": 2,         # 少于此步数不蒸馏（太短无信息；skill 平面下线后
    #                                   # 它只作用于 facts / lessons 短路，名字保留为兼容）
}

# 注入视图（memory_summary.md）截断上限（字符）。唯一出处：PUT /memory 与合并再生
# 都复用此常量，杜绝魔法数散落（设计 K1 §4.2 红线：勿复制魔数）。
SUMMARY_TRUNCATE = 20000


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

@dataclass
class CuratorReport:
    """单次 Curator 运行的维护报告。"""
    triggered_at: str = ""
    task_id: str = ""
    pruned_files: int = 0
    refined_facts_removed: int = 0
    skills_reviewed: int = 0
    skills_promoted: int = 0
    skills_created: int = 0
    flagged_low_quality: List[str] = field(default_factory=list)
    rollouts_distilled: int = 0
    memory_merged: int = 0
    profile_candidates: int = 0   # P0：本次蒸馏进画像候选区的条数（待人工确认）
    dedup_hit_rate: float = 0.0   # K5：新蒸馏 facts 中已被 MEMORY.md 覆盖比例
    errors: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "triggered_at": self.triggered_at,
            "task_id": self.task_id,
            "pruned_files": self.pruned_files,
            "refined_facts_removed": self.refined_facts_removed,
            "skills_reviewed": self.skills_reviewed,
            "skills_promoted": self.skills_promoted,
            "skills_created": self.skills_created,
            "flagged_low_quality": self.flagged_low_quality,
            "rollouts_distilled": self.rollouts_distilled,
            "memory_merged": self.memory_merged,
            "profile_candidates": self.profile_candidates,
            "dedup_hit_rate": self.dedup_hit_rate,
            "errors": self.errors,
        }


# ---------------------------------------------------------------------------
# Curator
# ---------------------------------------------------------------------------

class Curator:
    """触发式知识库维护者。非决策者，不介入任务执行。"""

    def __init__(
        self,
        task_id: str,
        config: Optional[Dict[str, Any]] = None,
    ):
        self.task_id = task_id
        # 资产根 = tasks/<task_id>/
        d = task_dir(task_id)
        self.traj_dir = str(d)
        self.skills_dir = str(d / "skills")
        self.world_model_dir = str(d)
        cfg = {**DEFAULTS, **(config or {})}
        self.prune_raw_days: int = int(cfg["prune_raw_days"])
        self.prune_failure_days: int = int(cfg["prune_failure_days"])
        self.high_retry_threshold: int = int(cfg["high_retry_threshold"])
        self.high_intervention_threshold: float = float(cfg["high_intervention_threshold"])
        self.min_steps_for_skill: int = int(cfg["min_steps_for_skill"])
        # K4：纠偏采集开关（红线②：默认关）。仅当显式 true 才把 user_corrections 入库。
        self.corrective_source: bool = bool(cfg.get("corrective_source", False))
        self._last_dedup_hit_rate: float = 0.0  # K5：最近一次蒸馏的去重命中率
        # P2-R：可选蒸馏大脑（facts/skill LLM 提取用）。Curator 自身仍零 brain
        # 依赖 —— bridge 注入 BYOK client；None = 纯启发式（facts 为空、
        # **不再产生任何技能**：机械转录已被证伪，2026-10-02 拍板）。
        self.brain = None
        self._extract_cache = None      # (run_record 身份, facts, skill_raw)

    # ==================================================================
    # 公开入口：任务完成后触发一次
    # ==================================================================

    def run_once(
        self,
        run_record: Optional[Dict[str, Any]] = None,
    ) -> CuratorReport:
        """任务完成后触发一次全量维护。

        Args:
            run_record: 刚结束的 RunRecord dict（含 success/steps/retry_count 等）。
                        若提供，则用于 flag_low_quality + 任务记忆蒸馏。

        Returns:
            CuratorReport
        """
        report = CuratorReport(
            triggered_at=datetime.now(timezone.utc).isoformat(),
            task_id=self.task_id,
        )

        # 1. prune 过期轨迹
        try:
            report.pruned_files = self.prune_trajectories()
        except Exception as e:
            report.errors.append(f"prune_trajectories: {type(e).__name__}: {e}")

        # 2. refine world-model
        try:
            report.refined_facts_removed = self.refine_world_model()
        except Exception as e:
            report.errors.append(f"refine_world_model: {type(e).__name__}: {e}")

        # 3. flag low-quality（从刚结束的 run_record 判定）
        if run_record is not None:
            try:
                flagged = self.flag_low_quality(run_record)
                report.flagged_low_quality = flagged
            except Exception as e:
                report.errors.append(f"flag_low_quality: {type(e).__name__}: {e}")

        # 5. 蒸馏任务记忆（第五件核心维护）+ 惰性合并校验
        #    K5 稳态降频：已收敛则跳过蒸馏（释放人力转下一域），仅记日志；
        #    未收敛则正常蒸馏，合并阈值在收敛后提升至 10（更稀疏合并）。
        if run_record is not None:
            try:
                if _steady_converged():
                    self._log_steady("已收敛：跳过蒸馏（低频维护）")
                    report.rollouts_distilled = 0
                else:
                    rollout = self.distill_task_memory(run_record)
                    if rollout is not None:
                        report.rollouts_distilled = 1
                        # P2 DoD4：合并阈值降为 1——「越用越顺手」要求下一次 run
                        # 就能看到本次沉淀；_merge_memory_sections 条目级去重
                        # （strip+小写全文比对）保证重复蒸馏不膨胀 MEMORY.md
                        report.memory_merged = self.maybe_merge_rollouts(threshold=1)
                report.dedup_hit_rate = getattr(self, "_last_dedup_hit_rate", 0.0)
                self._persist_curator_metric(
                    report.dedup_hit_rate, report.skills_promoted, report.skills_created)
                # P2-R §8.2：每个 run 收尾做一次容量维护（印证 + 淘汰 + 清理）。
                # 证据=轨迹摘要：本次跑过的 App/控件会点亮对应事实，让"常用"胜出。
                try:
                    cap = maintain_capacity(evidence=_trajectory_digest(run_record),
                                            success=bool(run_record.get("success")),
                                            brain=self.brain)
                    if any(cap.values()):
                        print("[curator] capacity maintained: %s" % cap, flush=True)
                except Exception as e:
                    report.errors.append("maintain_capacity: %s: %s"
                                         % (type(e).__name__, e))
            except Exception as e:
                report.errors.append(f"distill_task_memory: {type(e).__name__}: {e}")

        # 6. 用户画像候选蒸馏（P0）：纠偏消息 → 候选区（人工确认后晋升，避免任务噪声污染画像）
        if run_record is not None:
            try:
                report.profile_candidates = self.distill_user_profile(run_record)
            except Exception as e:
                report.errors.append(f"distill_user_profile: {type(e).__name__}: {e}")

        return report

    # ==================================================================
    # 1. prune_trajectories
    # ==================================================================

    def prune_trajectories(self) -> int:
        """清理过期轨迹文件（raw 30 天 / failures 14 天）。

        直接按 mtime 扫描删除，**不构造 TrajectoryStore**（避免 open 新空文件干扰）。
        """
        store_dir = task_dir(self.task_id)
        if not store_dir.exists():
            return 0
        return self._manual_prune(store_dir)

    def _manual_prune(self, store_dir: Path) -> int:
        """TrajectoryStore 构造失败时的兜底手动 prune。"""
        cutoff = time.time() - self.prune_raw_days * 86400
        fail_cutoff = time.time() - self.prune_failure_days * 86400
        removed = 0
        for p in store_dir.glob("*.jsonl"):
            try:
                if p.stat().st_mtime < cutoff:
                    p.unlink()
                    removed += 1
            except Exception:
                pass
        for p in store_dir.glob("*.run.json"):
            try:
                if p.stat().st_mtime < cutoff:
                    p.unlink()
                    removed += 1
            except Exception:
                pass
        fdir = store_dir / "failures"
        if fdir.exists():
            for p in fdir.glob("*.json"):
                try:
                    if p.stat().st_mtime < fail_cutoff:
                        p.unlink()
                        removed += 1
                except Exception:
                    pass
        return removed

    # ==================================================================
    # 2. refine_world_model
    # ==================================================================

    def refine_world_model(self) -> int:
        """精炼 world-model facts —— 去重 + 移除空/陈旧事实。

        只对磁盘 current.md 操作（不依赖���存 WorldModel 实例）。
        返回移除的冗余事实条数。
        """
        wm_path = task_dir(self.task_id) / "world_model.md"
        if not wm_path.exists():
            return 0

        content = wm_path.read_text(encoding="utf-8")
        # 复用 WorldModel.load 解析（但不覆盖内存实例）
        wm = WorldModel(task_id=self.task_id)
        wm.load()

        original_count = len(wm.facts)
        if original_count == 0:
            return 0

        # 去重（保留顺序）
        seen: set = set()
        refined: List[str] = []
        for f in wm.facts:
            key = f.strip().lower()
            if key and key not in seen:
                seen.add(key)
                refined.append(f.strip())
        # 移除空事实
        refined = [f for f in refined if f and f != "_(暂无)_"]

        removed = original_count - len(refined)
        if removed > 0:
            wm.facts = refined
            wm.save()
        return removed

    # ==================================================================
    # 3. facts LLM 提取（P2-R：环境事实生产者，对齐 TDB extraction）
    #
    # **2026-10-02：skill 字段整体删除。** 原来一次调用同时产出 facts + skill，
    # 但两者同源、内容 3/3 重叠（见模块 docstring 的定案依据）。facts 是唯一有实测
    # 收益的资产；skill 改由人工维护。⇒ 本 prompt 只产出 facts，条数上限提到 5。
    # ==================================================================

    _EXTRACT_PROMPT = (
        "你是手机 GUI 任务的知识蒸馏器。下面是一次成功执行轨迹（已脱敏："
        "type_text 的具体内容以 <text> 代替）。\n"
        "输出**一个 JSON 对象**（可用 ```json 包裹，不要输出别的解释）：\n"
        '{{"facts": ["跨任务可复用的环境事实 1-5 条：只写应用/系统行为规律与 UI 陷阱'
        '（如「某按钮在 X 条件下静默无效」「某类控件不可点需坐标点按」），'
        '禁止包含本次任务的临时内容"]}}\n'
        "facts 的写法要求：\n"
        "1. 每条是一句陈述，≤60 字，描述**环境本身**的规律，不写操作步骤；\n"
        "2. 禁止出现本次任务的临时内容（搜索词、标签文本、具体闹钟时间等）；\n"
        "3. 若轨迹只有操作过程、观察不到环境规律，就交白卷（facts 为空列表）——"
        "交白卷是正确答案，凑数写出来的假规律是错误答案。\n\n"
        "轨迹：\n{digest}"
    )

    def _llm_extract(self, run_record: Dict[str, Any]):
        """成功轨迹 → LLM 蒸馏出环境事实。

        同一 run_record 对象只调用一次（``_extract_facts_via_llm`` 用缓存）。
        无 brain / 轨迹空 / 调用失败 → ``[]``，绝不拖垮蒸馏。
        """
        cached = getattr(self, "_extract_cache", None)
        if cached is not None and cached[0] is run_record:
            return cached[1]
        if self.brain is None:
            return []
        digest = _trajectory_digest(run_record)
        if not digest:
            return []
        reply = None
        try:
            reply = self.brain.chat(
                [{"role": "user",
                  "content": self._EXTRACT_PROMPT.format(digest=digest)}], [])
        except Exception as e:
            print("[curator] llm extract failed: %s: %s"
                  % (type(e).__name__, e), flush=True)
        facts: List[str] = []
        data = _parse_llm_json((reply or {}).get("content"))
        if isinstance(data, dict):
            for ln in (data.get("facts") or []):
                ln = _strip_bullet(str(ln or ""))
                if len(ln) > 6:
                    facts.append(ln[:200])
                if len(facts) >= FACTS_PER_RUN_MAX:
                    break
        else:
            # 兜底：模型没吐 JSON 时按行格式收 facts
            for ln in ((reply or {}).get("content") or "").splitlines():
                ln = _strip_bullet(ln)
                if len(ln) > 6:
                    facts.append(ln[:200])
                if len(facts) >= FACTS_PER_RUN_MAX:
                    break
        self._extract_cache = (run_record, facts)
        if facts:
            print("[curator] facts extracted: %d" % len(facts), flush=True)
        return facts

    def _extract_facts_via_llm(self, run_record: Dict[str, Any]) -> List[str]:
        """成功轨迹 → LLM 提取环境事实（事实侧消费 `_llm_extract` 的结果）。

        **写入端节流**（对齐 TDB `everyNConversations`）：同一 App 在
        ``FACT_EXTRACT_COOLDOWN_S`` 内不重复**合并** facts（LLM 调用本身照常
        发生 —— 冷却只挡入库，不挡提取）。
        """
        facts = self._llm_extract(run_record)
        if not facts:
            return []
        digest = _trajectory_digest(run_record)
        pkg = _run_app(run_record) or _fact_pkg(digest)
        if not should_extract_facts(pkg):
            print("[curator] facts merge skipped (cooldown, app=%s)" % (pkg or "-"),
                  flush=True)
            return []
        mark_facts_extracted(pkg)
        # 本次 run 的 App 归属直接落进侧挂索引（不再让每条事实靠文本正则抽包名：
        # 纯描述性事实抽不出包名 ⇒ 效用永远最低档，滑动窗口里第一个被淘汰）。
        self._fact_pkg_hint = pkg
        return facts


    # ==================================================================
    # 4. flag_low_quality
    # ==================================================================

    def flag_low_quality(self, run_record: Dict[str, Any]) -> List[str]:
        """标记低质量执行记录（高 retry / 高 intervention / 非稳定成功）。

        判定规则（任一命中即标 excluded_from_sft）：
        - retry_count >= high_retry_threshold
        - brain_intervention_rate >= high_intervention_threshold
          （= brain_calls / max(decision_steps, 1)）
        - success == False（失败轨迹默认 low_quality）

        Returns:
            被标记的 run_id 列表（写 ``excluded_from_sft`` 标记文件）。
        """
        flagged: List[str] = []
        run_id = run_record.get("run_id", "")
        if not run_id:
            return flagged

        retry_count = int(run_record.get("retry_count", 0))
        brain_calls = int(run_record.get("brain_calls", 0))
        decision_steps = int(run_record.get("decision_steps", 0))
        success = bool(run_record.get("success", False))

        is_low = False
        reasons: List[str] = []

        if retry_count >= self.high_retry_threshold:
            is_low = True
            reasons.append(f"high_retry={retry_count}")

        intervention_rate = brain_calls / max(decision_steps, 1) if decision_steps > 0 else 0.0
        if intervention_rate >= self.high_intervention_threshold and decision_steps > 0:
            is_low = True
            reasons.append(f"high_intervention={intervention_rate:.2f}")

        if not success:
            is_low = True
            reasons.append("task_failed")

        if not is_low:
            return flagged

        # 写标记文件：tasks/<task_id>/excluded/<run_id>.json
        excl_dir = task_dir(self.task_id) / "excluded"
        excl_dir.mkdir(parents=True, exist_ok=True)
        marker = {
            "run_id": run_id,
            "reasons": reasons,
            "retry_count": retry_count,
            "brain_calls": brain_calls,
            "decision_steps": decision_steps,
            "intervention_rate": round(intervention_rate, 4),
            "success": success,
            "flagged_at": datetime.now(timezone.utc).isoformat(),
        }
        marker_path = excl_dir / f"{run_id}.json"
        try:
            marker_path.write_text(
                json.dumps(marker, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            flagged.append(run_id)
        except Exception:
            pass

        return flagged

    # ==================================================================
    # 5. distill_task_memory（第五件核心维护）
    # ==================================================================

    def distill_task_memory(self, run_record: Dict[str, Any]) -> Optional[Path]:
        """第五件维护·蒸馏：单任务轨迹 → ``memory/rollouts/<task_id>.md``。

        规则（§3.1 / §3.3）：
        - 步数低于最小技能萃取阈值 → 直接返回 None（短路，太短无信息）。
        - 成功任务：读取磁盘 world-model facts 生成 ``## facts``（过滤空与「_(暂无)_」），
          并计算新 facts 相对既有 MEMORY.md 的去重命中率（K5 信号）。
        - 失败 / 高重试任务：生成带归因的 ``## lessons``。
        - K4（默认关）：``user_corrections`` 经 C₁ 判定为证伪且含纠正的用户消息 →
          ``## user_corrections`` 段落（回指对话来源，append-only 去重）。
        - 无有效内容 → 返回 None；否则按规范模板落盘并返回路径。
        """
        steps = int(run_record.get("steps", 0) or 0)
        # K4 校正：纠偏是**人工提供**的高质量原料，价值与轨迹长度无关；
        # 原实现在此处无差别短路，导致 1 步任务里的纠偏被整条丢弃（真机验证发现）。
        # 现改为：仅有纠偏待入库时不短路；无纠偏仍保持原短路语义（避免短任务噪声）。
        _has_corrections = bool(
            (run_record.get("user_corrections") or []) and self.corrective_source)
        if steps < self.min_steps_for_skill and not _has_corrections:
            return None

        success = bool(run_record.get("success", False))
        objective = (run_record.get("objective") or "").strip()
        retry_count = int(run_record.get("retry_count", 0) or 0)
        reason = (run_record.get("reason") or "").strip()
        task_id = self.task_id

        facts: List[str] = []
        lessons: List[str] = []
        corrections: List[str] = []

        if success:
            try:
                wm = WorldModel(task_id=task_id)
                wm.load()
                facts = [f for f in (wm.facts or []) if f and f != "_(暂无)_"]
            except Exception:
                facts = []
            # P2-R Phase 1：不再回退「上次成功路径」——它与 skill 的 substeps
            # 同构，是记忆/技能重复的根源（doc/knowledge_redesign.md §1.7 #3）。
            # 分工从此明确：套路归 skill（entry_id 去重），facts 只装环境事实。
            if not facts:
                # P2-R：facts LLM 提取（对齐 TDB extraction 环节）——轨迹此前是
                # 死数据；把成功轨迹脱敏摘要交 BYOK 大脑提炼跨任务环境事实
                # （App 怪癖 / UI 语义）。无 brain / 失败 / 无产出 → []，不拖垮。
                facts = self._extract_facts_via_llm(run_record)
        else:
            if reason:
                lessons.append(f"[失败] {objective} —— {reason}")

        # 高重试（无论成败）均记录经验，避免重复踩坑
        if retry_count >= self.high_retry_threshold and retry_count > 0:
            lessons.append(f"[高重试({retry_count})] {objective} —— {reason or '未记录原因'}")

        # K4：第三来源——session 用户纠偏（Curator 级红线②：corrective_source 关时不入库）
        _corr = list(run_record.get("user_corrections") or [])
        if _corr and self.corrective_source:
            corrections = [f"- {c}" for c in _corr if c and c.strip()]

        # K5：去重命中率 = 新 facts 中已被既有 MEMORY.md 覆盖比例（蒸馏前快照比对）
        self._last_dedup_hit_rate = self._compute_dedup_hit_rate(facts)

        if not facts and not lessons and not corrections:
            return None

        path = memory_rollout_file(task_id)
        now = datetime.now(timezone.utc).isoformat()
        traj = f"tasks/{task_id}/trajectory.jsonl"
        lines = [
            f"# rollout: {task_id}",
            f"- task_id: {task_id} / objective: {objective} / success: {success} "
            f"/ steps: {steps} / distilled_at(UTC iso): {now} / trajectory: {traj}",
            "",
            "## facts",
        ]
        if facts:
            lines.extend(f"- {f}" for f in facts)
        else:
            lines.append("- (暂无)")
        lines.append("")
        lines.append("## lessons")
        if lessons:
            lines.extend(f"- {l}" for l in lessons)
        else:
            lines.append("- (暂无)")
        lines.append("")
        lines.append("## user_corrections")
        if corrections:
            lines.extend(corrections)
        else:
            lines.append("- (暂无)")
        lines.append("")

        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(lines), encoding="utf-8")
            return path
        except Exception:
            return None

    # ==================================================================
    # 6. distill_user_profile（P0：用户画像候选蒸馏）
    # ==================================================================

    def distill_user_profile(self, run_record: Dict[str, Any]) -> int:
        """P0：从用户纠偏消息蒸馏画像候选 → ``memory/profile_candidates.md``。

        只追加**候选**（conf=低 / status=pending），不直接写入画像正文——
        纠偏消息可能含任务级指令，需用户在前端确认后才晋升为高置信画像条目，
        避免任务噪声污染「用户是谁」的长期画像（画像无放行权、仅参考）。

        原料：``run_record["user_corrections"]``（已在会话层经 C₁=refuted 过滤 +
        长度门槛；默认 corrective_source 关时不采集，此处自然为空）。

        Returns:
            本次新增候选条数（去重后）。
        """
        corr = list(run_record.get("user_corrections") or [])
        if not corr:
            return 0
        path = global_memory() / "profile_candidates.md"
        try:
            existing = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
        except Exception:
            existing = []
        # seen 只取每条候选的正文（"- " 后、首个 " | " 前），按小写去重；
        # 不能把整行（含 source/conf 后缀）放进 seen，否则正文永远匹配不上。
        seen = set()
        for ln in existing:
            s = ln.strip()
            if s.startswith("- "):
                seen.add(s[2:].split(" | ", 1)[0].strip().lower())
        now = datetime.now(timezone.utc).isoformat()
        added = 0
        for c in corr:
            c = (c or "").strip()
            if not c or c.lower() in seen:
                continue
            seen.add(c.lower())
            existing.append(
                f"- {c} | source=纠偏 | conf=低 | status=pending | updated_at={now}")
            added += 1
        if added:
            try:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("\n".join(existing) + "\n", encoding="utf-8")
            except Exception:
                return 0
        return added

    # ------------------------------------------------------------------
    # K5 辅助：去重命中率 + 指标持久化 + 稳态降频判定
    # ------------------------------------------------------------------
    def _log_steady(self, msg: str) -> None:
        """K5 稳态事件日志（stdout，后端可观测）。"""
        try:
            print(f"[Curator][SteadyState] {msg}", flush=True)
        except Exception:
            pass

    def _compute_dedup_hit_rate(self, facts: List[str]) -> float:
        """新蒸馏 facts 中已被既有 MEMORY.md 覆盖的比例（K5 蒸馏去重命中率）。"""
        if not facts:
            return 0.0
        try:
            existing = memory_master().read_text(encoding="utf-8").lower() if memory_master().exists() else ""
        except Exception:
            existing = ""
        if not existing:
            return 0.0
        hit = sum(1 for f in facts if f.strip().lower() in existing)
        return round(hit / len(facts), 4)

    def _persist_curator_metric(self, dedup_hit_rate: float,
                                promoted: int, created: int) -> None:
        """滚动写 ``memory/curator_metrics.json``（最近 50 条），供 K5 稳态读取。"""
        try:
            p = global_memory() / "curator_metrics.json"
            data = {"metrics": []}
            if p.exists():
                try:
                    data = json.loads(p.read_text(encoding="utf-8")) or data
                except Exception:
                    data = {"metrics": []}
            data.setdefault("metrics", [])
            data["metrics"].append({
                "ts": datetime.now(timezone.utc).isoformat(),
                "task_id": self.task_id,
                "dedup_hit_rate": dedup_hit_rate,
                "skills_promoted": promoted,
                "skills_created": created,
            })
            data["metrics"] = data["metrics"][-50:]
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception:
            pass

    def maybe_merge_rollouts(self, threshold: int = 5) -> int:
        """第五件维护·合并：未合并 rollout 批量合并进 MEMORY.md + 再生 summary。

        双触发规则（§2.2）：本期落地「未合并数量 ≥ threshold」这一条（默认 5，可配置）。
        已合并 task_id 记入 ``memory/merged.json`` 保证幂等去重。

        Returns:
            本次追加进 MEMORY.md 的条目数（facts + lessons）。
        """
        rollouts_dir = memory_rollouts()
        if not rollouts_dir.exists():
            return 0

        merged_file = global_memory() / "merged.json"
        merged_ids: set = set()
        if merged_file.exists():
            try:
                merged_ids = set(
                    (json.loads(merged_file.read_text(encoding="utf-8")) or {}).get("merged_task_ids", [])
                )
            except Exception:
                merged_ids = set()

        pending = [p for p in sorted(rollouts_dir.glob("*.md")) if p.stem not in merged_ids]
        if len(pending) < threshold:
            return 0

        new_facts: List[str] = []
        new_lessons: List[str] = []
        for p in pending:
            try:
                text = p.read_text(encoding="utf-8")
            except Exception:
                continue
            f_sec, l_sec = _parse_rollout_sections(text)
            new_facts.extend(f_sec)
            new_lessons.extend(l_sec)

        # 更新合并清单（幂等：已合并 ∪ 本次待合并）
        merged_ids |= {p.stem for p in pending}
        _write_merged_ids(merged_file, merged_ids)

        if not new_facts and not new_lessons:
            return 0

        master = memory_master()
        existing = master.read_text(encoding="utf-8") if master.exists() else ""
        new_text, added_f, added_l = _merge_memory_sections(
            existing, new_facts, new_lessons, brain=self.brain)
        _register_fact_pkgs(new_facts, getattr(self, "_fact_pkg_hint", ""))
        try:
            master.write_text(new_text, encoding="utf-8")
        except Exception:
            return 0
        # 再生注入视图（截断 SUMMARY_TRUNCATE 字符，保证注入上下文体量可控）
        try:
            memory_summary().write_text(new_text[:SUMMARY_TRUNCATE], encoding="utf-8")
        except Exception:
            pass
        return added_f + added_l

    # ==================================================================
    # 辅助：批量扫描历史 run（可选，供离线分析用）
    # ==================================================================

    def scan_all_runs(self) -> List[Dict[str, Any]]:
        """扫描某 task 下所有 *.run.json，返回 RunRecord dict 列表（供批量分析）。"""
        store_dir = task_dir(self.task_id)
        if not store_dir.exists():
            return []
        runs: List[Dict[str, Any]] = []
        for p in sorted(store_dir.glob("*.run.json")):
            try:
                runs.append(json.loads(p.read_text(encoding="utf-8")))
            except Exception:
                pass
        return runs

    def flag_all_low_quality(self) -> List[str]:
        """批量扫描所有历史 run，标记 low_quality（离线维护用，非每次触发必跑）。"""
        all_flagged: List[str] = []
        for rec in self.scan_all_runs():
            all_flagged.extend(self.flag_low_quality(rec))
        return all_flagged


# ---------------------------------------------------------------------------
# 蒸馏 / 合并辅助（模块级，便于单测与无 Curator 实例复用）
# ---------------------------------------------------------------------------

def _read_actions(trajectory_file: str) -> List[Dict[str, Any]]:
    """读轨迹 jsonl 的动作序列（tool+args），过滤元/观察动作；异常返回空。"""
    out: List[Dict[str, Any]] = []
    try:
        for ln in Path(str(trajectory_file)).read_text(encoding="utf-8").splitlines():
            try:
                a = (json.loads(ln).get("action") or {})
                tool = a.get("tool")
                if tool and tool not in ("task_done", "verify_done",
                                         "observe", "look", "screenshot"):
                    out.append({"tool": tool, "args": a.get("args") or {}})
            except Exception:
                continue
    except Exception:
        pass
    return out


def _fmt_action(a: Dict[str, Any]) -> str:
    """动作 → 紧凑串（技能 ops 同风格）：launch_app(时钟)、tap_by_id(...id/new_note)。"""
    tool = a.get("tool", "?")
    args = a.get("args") or {}
    if tool == "launch_app":
        return "launch_app(%s)" % (args.get("package") or "?")
    if tool == "tap_by_id":
        return "tap_by_id(%s)" % (args.get("view_id") or "?")
    if tool == "type_text":
        return "type_text(%s)" % (str(args.get("text", ""))[:16])
    if tool == "tap_xy":
        return "tap_xy(%.2f,%.2f)" % (args.get("x", 0), args.get("y", 0))
    return tool


def _success_path_facts(run_record: Dict[str, Any]) -> List[str]:
    """成功轨迹 → 「上次成功路径」事实串。

    **已退出蒸馏链**（P2-R Phase 1，doc/knowledge_redesign.md §1.7 #3）：该内容
    与 skill 的 substeps 同构，是记忆/技能双写的根源。保留本函数仅作离线
    debug / 对拍用途，生产蒸馏不再调用。
    """
    actions = _read_actions(run_record.get("trajectory_file") or "")
    if len(actions) < 2:
        return []
    objective = (run_record.get("objective") or "").strip()
    return ["任务「%s」上次成功路径：%s" % (objective, " → ".join(_fmt_action(a) for a in actions[:12]))]


def _trajectory_digest(run_record: Dict[str, Any], max_steps: int = 40) -> str:
    """成功轨迹 → 脱敏摘要（动作 + ok/err），供 LLM 提取环境事实（P2-R）。

    轨迹此前是蒸馏的死数据：唯一消费者（_success_path_facts）只提炼操作序列，
    且已与 skill 重复而退出。本摘要让轨迹作为**环境事实**的原料重新进入
    蒸馏链 —— 与 TencentDB Agent Memory 的 extraction 环节同构。
    args 经 canonical_args 脱敏（text → <text>），防止任务临时内容泄入记忆。
    """
    from hachimi_kernel.skill_library import canonical_args
    lines: List[str] = []
    try:
        for ln in Path(str(run_record.get("trajectory_file") or "")).read_text(
                encoding="utf-8").splitlines():
            if len(lines) >= max_steps:
                lines.append("…(truncated)")
                break
            try:
                rec = json.loads(ln)
            except Exception:
                continue
            a = rec.get("action") or {}
            tool = a.get("tool")
            if not tool:
                continue
            r = rec.get("result") or {}
            if tool == "observe":
                status = "ok" if r.get("ok") else "ERR: %s" % str(r.get("error", ""))[:60]
                lines.append("%d. observe -> %s" % (len(lines) + 1, status))
                continue
            ca = json.dumps(canonical_args(a.get("args") or {}),
                            ensure_ascii=False, sort_keys=True)
            status = "ok" if r.get("ok") else "ERR: %s" % str(r.get("error", ""))[:80]
            lines.append("%d. %s(%s) -> %s" % (len(lines) + 1, tool, ca, status))
    except Exception:
        pass
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# 语义判断一律交给 LLM（用户红线：不要脚本化的语义判断）
#
# 被删除的旧实现（2026-10-02 连根移除）：重叠率阈值 0.35 / 控件共享 0.25 /
# 控件互斥 0.55 / 锚点 0.20、控件 id 正则（resource-id / 反引号短 id /
# snake_case）、CJK bigram 词面指纹、贪心同义聚类、按"包名或控件出现"判印证。
# 它们把语义问题写成了规则 + 参数，然后靠真机调参——"规则做不到语义识别"
# 是本项目反复验证的结论（objective 全等匹配、N=3 晋升形同虚设、entry_id
# 从未在真机生效，均出自同一类错误）。
#
# 脚本保留的只有簿记职责：逐字精确去重、计数、预算、排序、落盘。
# ---------------------------------------------------------------------------

_DEDUP_PROMPT = (
    "你在维护一个手机 GUI agent 的长期记忆库。已有条目与新提取条目如下。\n"
    "判断每条**新条目**与已有条目的关系，只允许三种裁决：\n"
    "- duplicate：与某条已有条目讲同一件事（同义改写）⇒ 不入库；\n"
    "- merge：与某条已有条目讲同一件事但新条目有新增信息 ⇒ 输出把两条合并后的"
    "一句话（不得丢掉任一方的关键信息）；\n"
    "- independent：讲的是不同的事（哪怕用词相似、提到同一个控件）⇒ 入库。\n"
    "判断依据是语义，不是字面相似度：提到同一个控件但讲不同的事（例："
    "\"该 id 列举\"vs\"必须先点它才打开对话框\"）必须判 independent。\n"
    "输出一个 JSON 对象：\n"
    '{{"verdicts": [{{"new": "新条目原文", "decision": "duplicate|merge|independent",'
    ' "target": "被合并的已有条目原文（duplicate/merge 必填）",'
    ' "merged": "合并后的一句话（仅 merge 必填）"}}]}}\n'
    "已有条目：\n{existing}\n\n新条目：\n{incoming}\n"
)


def exact_dedup(items: List[str]) -> List[str]:
    """逐字精确去重（簿记）：strip + 小写全文比对，保留先到者。"""
    seen = set()
    out: List[str] = []
    for it in items or []:
        s = _strip_bullet(it)
        k = s.lower()
        if not k or k in seen:
            continue
        seen.add(k)
        out.append(s)
    return out


def llm_dedup(existing: List[str], incoming: List[str],
              brain=None) -> tuple:
    """LLM 判定同义/合并/独立 → ``(合并后的 existing, 采纳的 incoming)``。

    无 brain / 调用失败 / 解析失败 ⇒ 退化为**逐字精确去重**（宁可不合并，
    也不靠规则猜语义；重复条目由容量淘汰兜底）。
    """
    existing = [s for s in (existing or []) if s and s.strip()]
    incoming = [s for s in (incoming or []) if s and s.strip()]
    if not incoming:
        return existing, []
    existing = exact_dedup(existing)
    incoming = exact_dedup([x for x in incoming
                            if x.lower() not in {e.lower() for e in existing}])
    if not incoming or brain is None:
        return existing, incoming
    try:
        reply = brain.chat([{"role": "user", "content": _DEDUP_PROMPT.format(
            existing="\n".join("- %s" % e for e in existing),
            incoming="\n".join("- %s" % i for i in incoming))}], [])
        data = _parse_llm_json((reply or {}).get("content"))
    except Exception as e:
        print("[curator] llm dedup failed: %s: %s" % (type(e).__name__, e), flush=True)
        return existing, incoming
    if not isinstance(data, dict):
        return existing, incoming
    merged_map: Dict[str, str] = {}     # 已有条目原文 -> 合并后文本
    verdict_by_new: Dict[str, str] = {}  # 新条目原文 -> duplicate | independent
    for v in (data.get("verdicts") or []):
        if not isinstance(v, dict):
            continue
        new = _strip_bullet(str(v.get("new") or ""))
        dec = str(v.get("decision") or "").strip().lower()
        tgt = _strip_bullet(str(v.get("target") or ""))
        mrg = _strip_bullet(str(v.get("merged") or ""))
        if not new or dec not in ("duplicate", "merge", "independent"):
            continue
        verdict_by_new[new] = dec
        if dec == "merge" and tgt and mrg:
            merged_map[tgt] = mrg
    if not verdict_by_new:
        return existing, incoming
    out_existing = [merged_map.get(e, e) for e in existing]
    kept_new = [i for i in incoming
                if verdict_by_new.get(i, "independent") == "independent"]
    return out_existing, kept_new


_MARKS_PROMPT = (
    "下面是手机 GUI agent 一次任务的**执行结果摘要**与它记忆库里的事实条目。\n"
    "判断：这次执行**印证**了哪些条目（条目描述的现象在轨迹里确实出现了/被依赖），"
    "以及**打脸**了哪些条目（按条目说的做却没生效或行为相反）。\n"
    "只输出确实被涉及的条目编号，不确定就不选。\n"
    '{{"corroborated": [编号...], "contradicted": [编号...], "note": "一句话说明"}}\n'
    "轨迹摘要：\n{evidence}\n\n事实条目：\n{items}\n"
)


_POOL_PROMPT = (
    "你在维护一个手机 GUI agent 的长期记忆库，下面是**同一分区**的已有条目列表。\n"
    "找出讲同一件事的重复条目（同义改写）：每组保留信息最全的一条，其余标为删除；"
    "若重复条目各有对方没有的信息，输出合并后的一句话。\n"
    "判断依据是语义而非字面相似：提到同一控件但讲不同事的必须各自保留"
    "（例：\"该 id 的列举\" vs \"必须先点它才打开对话框\"）。\n"
    "输出一个 JSON 对象：\n"
    '{{"drop": ["要删除的重复条目原文"], '
    '"rewrite": [{{"keep": "保留的条目原文", "text": "合并后的一句话"}}]}}\n'
    "条目列表：\n{items}\n"
)


def llm_dedup_pool(items: List[str], brain=None) -> List[str]:
    """存量条目之间互判去重（compact 用）→ 返回保留后的列表。

    无 brain ⇒ 只做逐字精确去重。LLM 不可用时不猜：宁可留重复，由容量淘汰兜底。
    """
    items = exact_dedup(items)
    if len(items) < 2 or brain is None:
        return items
    try:
        reply = brain.chat([{"role": "user", "content": _POOL_PROMPT.format(
            items="\n".join("- %s" % it for it in items))}], [])
        data = _parse_llm_json((reply or {}).get("content"))
    except Exception as e:
        print("[curator] llm pool dedup failed: %s: %s" % (type(e).__name__, e),
              flush=True)
        return items
    if not isinstance(data, dict):
        return items
    drop = {_strip_bullet(str(x or "")) for x in (data.get("drop") or [])}
    rewrite = {}
    for r in (data.get("rewrite") or []):
        if isinstance(r, dict):
            keep = _strip_bullet(str(r.get("keep") or ""))
            txt = _strip_bullet(str(r.get("text") or ""))
            if keep and txt:
                rewrite[keep] = txt
    out = []
    for it in items:
        if it in drop:
            continue
        out.append(rewrite.get(it, it))
    return exact_dedup(out)


def llm_marks(items: List[str], evidence: str, brain=None,
              positive: bool = True) -> List[int]:
    """LLM 判定本次轨迹印证/打脸了哪些事实条目（返回下标）。

    无 brain ⇒ 返回空（**不猜**）。脚本不做"包名或控件出现即命中"的规则判断。
    """
    if brain is None or not items or not evidence:
        return []
    try:
        reply = brain.chat([{"role": "user", "content": _MARKS_PROMPT.format(
            evidence=evidence[:4000],
            items="\n".join("%d. %s" % (i + 1, it[:200]) for i, it in enumerate(items)))}], [])
        data = _parse_llm_json((reply or {}).get("content"))
    except Exception as e:
        print("[curator] llm marks failed: %s: %s" % (type(e).__name__, e), flush=True)
        return []
    if not isinstance(data, dict):
        return []
    raw = data.get("corroborated" if positive else "contradicted") or []
    out = []
    for x in raw:
        try:
            i = int(x) - 1
        except (TypeError, ValueError):
            continue
        if 0 <= i < len(items):
            out.append(i)
    return out


def _strip_bullet(text: str) -> str:
    """去掉条目开头的列表符号（含 LLM 偶发的嵌套写法 ``- - xxx``）。"""
    # ``(?:[-*•]+\s*)+``：重复剥 —— "- - x" 只剥一层会留下 "- x"
    return re.sub(r"^(?:[-*•]+\s*)+", "", (text or "").strip()).strip()


def _parse_llm_json(text: str) -> Optional[Dict[str, Any]]:
    """从 LLM 回复里抠 JSON 对象（容忍 ```json 围栏与前后废话）。"""
    t = (text or "").strip()
    if not t:
        return None
    m = re.search(r"```(?:json)?\s*(\{.*\})\s*```", t, re.S)
    if m:
        t = m.group(1)
    else:
        i, j = t.find("{"), t.rfind("}")
        if i < 0 or j <= i:
            return None
        t = t[i:j + 1]
    try:
        data = json.loads(t)
    except Exception:
        return None
    return data if isinstance(data, dict) else None


def _parse_rollout_sections(text: str):
    """从 rollout md 解析 ``## facts`` / ``## lessons`` 段落的有效条目。"""
    facts: List[str] = []
    lessons: List[str] = []
    cur = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("## facts"):
            cur = "facts"
            continue
        if s.startswith("## lessons"):
            cur = "lessons"
            continue
        if s.startswith("# ") and not s.startswith("## "):
            cur = None
            continue
        if cur == "facts" and s.startswith("- "):
            v = _strip_bullet(s[2:])
            if v and v != "(暂无)":
                facts.append(v)
        elif cur == "lessons" and s.startswith("- "):
            v = _strip_bullet(s[2:])
            if v and v != "(暂无)":
                lessons.append(v)
    return facts, lessons


_MEMORY_HEADER = "# OmniAgent 全局长期记忆"
_FACT_SEC = "## 长期事实"
_LESSON_SEC = "## 教训"


def _split_memory_sections(existing: str) -> Dict[str, List[str]]:
    """MEMORY.md → {分区标题: [条目]}（``## `` 分区，``- `` 条目）。"""
    sections: Dict[str, List[str]] = {_FACT_SEC: [], _LESSON_SEC: []}
    cur = None
    for line in (existing or "").splitlines():
        s = line.strip()
        if s.startswith("# ") and not s.startswith("## "):
            cur = None
            continue
        if s.startswith("## "):
            cur = s
            sections.setdefault(cur, [])
            continue
        if cur in sections and s.startswith("- "):
            sections[cur].append(_strip_bullet(s[2:]))
    return sections


def _render_memory_sections(sections: Dict[str, List[str]]) -> str:
    """{分区: [条目]} → MEMORY.md 文本（双分区固定顺序，空分区写 ``(暂无)``）。"""
    out = [_MEMORY_HEADER, ""]
    for marker in (_FACT_SEC, _LESSON_SEC):
        out.append(marker)
        items = sections.get(marker) or []
        if items:
            out.extend("- %s" % it for it in items)
        else:
            out.append("- (暂无)")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def compact_memory_sections(text: str, brain=None):
    """回溯合并**已有**条目（LLM 判定同义），返回 ``(新文本, 合并掉条数)``。

    ``_merge_memory_sections`` 的去重只发生在新增 vs 存量（保护人工编辑），
    存量之间的合并必须显式调用本函数。无 brain ⇒ 只做逐字精确去重。
    """
    sections = _split_memory_sections(text)
    removed = 0
    for key in list(sections):
        items = sections[key] or []
        kept = llm_dedup_pool(items, brain)
        removed += len(items) - len(kept)
        sections[key] = kept
    return _render_memory_sections(sections), removed


# ---------------------------------------------------------------------------
# 容量边界：效用驱动的滑动窗口（P2-R §8.2，doc/knowledge_redesign.md）
#
# 定位：**不追求完美归并，只保证自洽**。手机端知识是"有槽位的缓存"而不是
# 数据库 —— 漏掉的同义变体只占一个槽位，而槽位满了会按效用淘汰最差的，
# 于是"弱者淘汰"天然完成了收敛，不必靠 embedding 提高归并率。
#
# 机制三条：
#   1. 印证（corroborate）：每次 run 用轨迹证据点亮命中的事实（hits+1）；
#      同义簇内只加分给簇代表 —— 被反复印证的表述分数越来越高。
#   2. 效用分：hits*2 + 新鲜度（30 天线性衰减到 0）。
#   3. 淘汰次序：同义簇内非代表 → 单 App 超容（每个 App 保底 N 条）→ 全局超容。
# ---------------------------------------------------------------------------

FACTS_PER_APP_MAX = 12      # 单 App 事实槽位（实测单 App 沉淀 ~9 条）
FACTS_APP_FLOOR = 3         # 单 App 保底：不允许把某个 App 的事实清空
FACTS_TOTAL_MAX = 40        # 全局事实槽位
SKILLS_MAX = 20             # 全局技能槽位（entry_id 唯一，同名套路累计不新增）
ROLLOUTS_MAX = 30           # rollout 保留条数
# 反证累计到几次直接淘汰（不必等容量收紧）：事实被现实反复否定就该退出。
FACT_FAILS_EVICT = 3

# 写入端节流（对齐 TDB 的 everyNConversations / maxMemoriesPerSession）：
# facts 的 LLM 提取既花钱又会往池子里灌水，同一个 App 在冷却期内不重复提取。
FACTS_PER_RUN_MAX = 3
FACT_EXTRACT_COOLDOWN_S = 300

TASK_KEEP_RUNS = 50         # 设备端保留最近 N 个 run 的资产
TASK_KEEP_DAYS = 7
TASK_MAX_MB = 100

_PKG_RE = re.compile(r"\b[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]+){2,}\b")

# 提取冷却状态存在 fact_meta.json 的这个保留 key 下（不进事实条目）
_EXTRACT_STATE_KEY = "__extract_state__"


def _fact_meta_file() -> Path:
    """事实效用索引（旁挂 json，不改 MEMORY.md 结构、不破坏人工编辑）。"""
    return global_memory() / "fact_meta.json"


def _fact_key(entry: str) -> str:
    return hashlib.sha1(_strip_bullet(entry).lower().encode("utf-8")).hexdigest()[:12]


def _register_fact_pkgs(facts: List[str], pkg: str) -> None:
    """给本次新增事实登记 App 归属（簿记：来自 run 记录，不从文本猜）。"""
    if not pkg or not facts:
        return
    meta = _load_fact_meta()
    now = time.time()
    changed = False
    for f in facts:
        key = _fact_key(f)
        rec = meta.get(key)
        if rec is None:
            meta[key] = {"text": f, "pkg": pkg, "hits": 0, "fails": 0,
                         "created": now, "last_seen": now}
            changed = True
        elif not rec.get("pkg"):
            rec["pkg"] = pkg
            changed = True
    if changed:
        _save_fact_meta(meta)


def _load_fact_meta() -> Dict[str, Any]:
    p = _fact_meta_file()
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8")) or {}
    except Exception:
        return {}


def _save_fact_meta(meta: Dict[str, Any]) -> None:
    try:
        _fact_meta_file().write_text(
            json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    except Exception:
        pass


def _fact_pkg(entry: str) -> str:
    """条目所属的 App 包名（无则空串，归入"通用"）。仅用于容量分组记账。"""
    m = _PKG_RE.findall(entry or "")
    return m[0] if m else ""


def should_extract_facts(pkg: str) -> bool:
    """同一 App 是否过了 facts 提取冷却期（写入端节流）。"""
    st = _extract_state()
    last = float(st.get(pkg or "-") or 0)
    return (time.time() - last) >= FACT_EXTRACT_COOLDOWN_S


def mark_facts_extracted(pkg: str) -> None:
    """记录某 App 本次提取时间（供冷却判定）。"""
    meta = _load_fact_meta()
    st = meta.get(_EXTRACT_STATE_KEY) or {}
    st[pkg or "-"] = time.time()
    # 只保留最近 20 个 App 的时间戳，避免索引无限增长
    if len(st) > 20:
        for k in sorted(st, key=lambda k: st[k])[:len(st) - 20]:
            st.pop(k, None)
    meta[_EXTRACT_STATE_KEY] = st
    _save_fact_meta(meta)


def _extract_state() -> Dict[str, Any]:
    return _load_fact_meta().get(_EXTRACT_STATE_KEY) or {}


def _run_app(run_record: Dict[str, Any]) -> str:
    """从 run 记录里取被操作的 App 包名（objective / 轨迹摘要都试）。"""
    obj = str((run_record or {}).get("objective") or "")
    return _fact_pkg(obj)


def corroborate_facts(evidence: str, brain=None) -> int:
    """本次 run 成功：LLM 判定哪些事实被印证，返回被加分的条目数。"""
    return _apply_evidence(evidence, positive=True, brain=brain)


def contradict_facts(evidence: str, brain=None) -> int:
    """本次 run 失败：LLM 判定哪些事实被"打脸"，记一次反证。

    按某条事实描述的路径操作却失败 ⇒ 这条事实至少在当下不可靠。记 ``fails+1``，
    效用分按 −3/次计；累计到 ``FACT_FAILS_EVICT`` 时，下次 prune 直接淘汰。
    """
    return _apply_evidence(evidence, positive=False, brain=brain)


def _apply_evidence(evidence: str, positive: bool, brain=None) -> int:
    """把本次 run 的证据写到事实效用索引上（印证 / 反证）。

    **判定交给 LLM**（:func:`llm_marks`）：脚本不做"包名或控件 id 出现即命中"
    的规则判断（那是把语义写成正则）。无 brain ⇒ 什么都不做（不猜）。
    """
    p = memory_master()
    if not p.exists():
        return 0
    items = _split_memory_sections(p.read_text(encoding="utf-8")).get(_FACT_SEC) or []
    if not items or not evidence or brain is None:
        return 0
    marked = llm_marks(items, evidence, brain, positive=positive)
    if not marked:
        return 0
    meta = _load_fact_meta()
    now = time.time()
    for i in marked:
        key = _fact_key(items[i])
        rec = meta.get(key) or {"text": items[i], "pkg": _fact_pkg(items[i]),
                                "hits": 0, "fails": 0,
                                "created": now, "last_seen": now}
        rec["last_seen"] = now
        if positive:
            rec["hits"] = min(99, int(rec.get("hits") or 0) + 1)
        else:
            rec["fails"] = min(99, int(rec.get("fails") or 0) + 1)
        meta[key] = rec
    _save_fact_meta(meta)
    return len(marked)


def _fact_score(entry: str, meta: Dict[str, Any], now: float) -> float:
    """效用分 = 印证×2 − 反证×3 + 新鲜度（30 天内从 1 线性衰减到 0）。"""
    rec = meta.get(_fact_key(entry)) or {}
    age_days = max(0.0, (now - float(rec.get("created") or now)) / 86400.0)
    return (int(rec.get("hits") or 0) * 2.0
            - int(rec.get("fails") or 0) * 3.0
            + max(0.0, 1.0 - age_days / 30.0))


def prune_memory(now: float = None) -> int:
    """按容量边界淘汰事实，返回淘汰条数（0 = 未超容）。

    淘汰次序：①反复被打脸的事实（反证达阈值）→ ②单 App 超容（保底
    ``FACTS_APP_FLOOR``）→ ③全局超容。**不做同义聚类淘汰** —— 那是语义判断，
    已随规则去重一并交给 LLM（合并在写入时完成），删除路径只按效用分排序。
    """
    p = memory_master()
    if not p.exists():
        return 0
    sections = _split_memory_sections(p.read_text(encoding="utf-8"))
    items = list(sections.get(_FACT_SEC) or [])
    if not items:
        return 0
    meta = _load_fact_meta()
    now = time.time() if now is None else now

    def score(i) -> float:
        return _fact_score(items[i], meta, now)

    drop = set()
    # ⓪ 反复被打脸的事实：不再排队，直接淘汰（反证 = 这条已经不可靠）
    for i, it in enumerate(items):
        if int((meta.get(_fact_key(it)) or {}).get("fails") or 0) >= FACT_FAILS_EVICT:
            drop.add(i)
    # ⓪' 逐字重复（簿记级精确去重，删除保守）
    by_text: Dict[str, int] = {}
    for i, it in enumerate(items):
        k = it.strip().lower()
        if k in by_text:
            drop.add(i)          # 保留首次出现的
        else:
            by_text[k] = i

    keep = [i for i in range(len(items)) if i not in drop]
    by_pkg: Dict[str, List[int]] = {}
    for i in keep:
        by_pkg.setdefault(_fact_pkg(items[i]) or "-", []).append(i)
    for pkg, idxs in by_pkg.items():
        excess = len(idxs) - FACTS_PER_APP_MAX
        if excess <= 0:
            continue
        ranked = sorted(idxs, key=score)
        removable = max(0, len(idxs) - FACTS_APP_FLOOR)
        for i in ranked[:min(excess, removable)]:
            drop.add(i)

    keep = [i for i in range(len(items)) if i not in drop]
    if len(keep) > FACTS_TOTAL_MAX:
        ranked = sorted(keep, key=score)
        drop.update(ranked[:len(keep) - FACTS_TOTAL_MAX])

    if not drop:
        return 0
    survivors = [items[i] for i in range(len(items)) if i not in drop]
    sections[_FACT_SEC] = survivors
    try:
        p.write_text(_render_memory_sections(sections), encoding="utf-8")
    except Exception:
        return 0
    for i in drop:
        meta.pop(_fact_key(items[i]), None)
    _save_fact_meta(meta)
    print("[curator] prune memory: -%d facts (kept %d)" % (len(drop), len(survivors)),
          flush=True)
    return len(drop)


def prune_skills(max_keep: int = SKILLS_MAX) -> int:
    """技能超容时按「成功次数 − 失败次数 + 新鲜度」淘汰最低者。"""
    try:
        from hachimi_kernel.skill_library import SkillLibrary
        d = SkillLibrary._global_dir()
        if not d.exists():
            return 0
        files = sorted(d.glob("*.md"))
        if len(files) <= max_keep:
            return 0
        now = time.time()

        def score(f) -> float:
            s = SkillLibrary._parse(f)
            if s is None:
                return 999.0
            m = s.metadata
            last = 0.0
            if m.last_used:
                try:
                    last = datetime.fromisoformat(
                        str(m.last_used).replace("Z", "")).timestamp()
                except Exception:
                    last = 0.0
            age_days = max(0.0, (now - last) / 86400.0) if last else 999.0
            return (int(m.success_count or 0) * 2 - int(m.failure_count or 0)
                    + max(0.0, 1.0 - age_days / 30.0))

        removed = 0
        for f in sorted(files, key=score)[:len(files) - max_keep]:
            try:
                f.unlink()
                removed += 1
            except Exception:
                pass
        return removed
    except Exception:
        return 0


def prune_rollouts(max_keep: int = ROLLOUTS_MAX) -> int:
    """rollout 只留最近 ``max_keep`` 条（按 mtime）。"""
    d = memory_rollouts()
    if not d.exists():
        return 0
    files = sorted(d.glob("*.md"), key=lambda f: f.stat().st_mtime, reverse=True)
    removed = 0
    for f in files[max_keep:]:
        try:
            f.unlink()
            removed += 1
        except Exception:
            pass
    return removed


def prune_tasks(max_runs: int = TASK_KEEP_RUNS, max_days: int = TASK_KEEP_DAYS,
                max_mb: int = TASK_MAX_MB) -> int:
    """设备端 run 资产清理：条数 / 天数 / 体积三选一触发（真机实测增速最快）。"""
    root = tasks_root()
    if not root.exists():
        return 0
    now = time.time()

    def _size(d: Path) -> int:
        return sum(f.stat().st_size for f in d.rglob("*") if f.is_file())

    dirs = [d for d in root.iterdir() if d.is_dir()]
    dirs.sort(key=lambda d: d.stat().st_mtime, reverse=True)
    removed = 0
    for d in dirs[max_runs:]:
        shutil.rmtree(d, ignore_errors=True)
        removed += 1
    cutoff = now - max_days * 86400
    for d in dirs[:max_runs]:
        try:
            if d.stat().st_mtime < cutoff:
                shutil.rmtree(d, ignore_errors=True)
                removed += 1
        except Exception:
            pass
    try:
        total = _size(root)
    except Exception:
        return removed
    if total > max_mb * 1024 * 1024:
        for d in sorted([d for d in root.iterdir() if d.is_dir()],
                        key=lambda d: d.stat().st_mtime):
            if total <= max_mb * 1024 * 1024:
                break
            try:
                total -= _size(d)
            except Exception:
                pass
            shutil.rmtree(d, ignore_errors=True)
            removed += 1
    return removed


def maintain_capacity(evidence: str = "", success: bool = True,
                      brain=None) -> Dict[str, int]:
    """一次跑完「印证或反证 + 四项容量维护」，返回各项清理条数。

    印证/反证的**判定在 LLM**（无 brain 则跳过，不猜）；淘汰按效用分排序，
    属簿记职责。Omni 可复用：与去重正交，属于"知识资产必须有上限"的那一层。
    """
    out = {"corroborated": 0, "contradicted": 0, "facts_pruned": 0,
           "skills_pruned": 0, "rollouts_pruned": 0, "tasks_pruned": 0}
    try:
        if success:
            out["corroborated"] = corroborate_facts(evidence, brain=brain)
        else:
            out["contradicted"] = contradict_facts(evidence, brain=brain)
    except Exception:
        pass
    try:
        out["facts_pruned"] = prune_memory()
    except Exception:
        pass
    try:
        out["skills_pruned"] = prune_skills()
    except Exception:
        pass
    try:
        out["rollouts_pruned"] = prune_rollouts()
    except Exception:
        pass
    try:
        out["tasks_pruned"] = prune_tasks()
    except Exception:
        pass
    return out


def compact_memory_master(brain=None) -> int:
    """对设备端 MEMORY.md 执行回溯合并（LLM 判定）并落盘，返回合并条数。"""
    master = memory_master()
    if not master.exists():
        return 0
    try:
        existing = master.read_text(encoding="utf-8")
    except Exception:
        return 0
    new_text, removed = compact_memory_sections(existing, brain=brain)
    if removed and new_text != existing:
        try:
            master.write_text(new_text, encoding="utf-8")
        except Exception:
            return 0
    return removed


def _merge_memory_sections(existing: str, new_facts: List[str],
                           new_lessons: List[str], brain=None):
    """把新 facts/lessons 去重追加进 MEMORY.md 双分区。

    返回 ``(新文本, 追加facts数, 追加lessons数)``。语义去重交给 LLM
    （:func:`llm_dedup`）；无 brain 时只做逐字精确去重。仅追加不覆写，
    保护人工编辑内容——**存量条目之间的合并见** :func:`compact_memory_sections`。
    """
    fact_sec, lesson_sec = _FACT_SEC, _LESSON_SEC
    sections = _split_memory_sections(existing)

    def _add(items: List[str], new: List[str]) -> int:
        before = len(items)
        merged, kept = llm_dedup(items, new, brain)
        items[:] = merged + kept
        return len(items) - before

    added_f = _add(sections[fact_sec], new_facts)
    added_l = _add(sections[lesson_sec], new_lessons)
    return _render_memory_sections(sections), added_f, added_l


def _write_merged_ids(path: Path, ids: set) -> None:
    """幂等写 ``merged.json``（已合并 task_id 清单）。"""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"merged_task_ids": sorted(ids)}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception:
        pass


def regenerate_summary() -> int:
    """K1 · 服务端重生成注入视图 ``memory_summary.md``（截断管控）。

    用户经 ``PUT /memory`` 改写 MEMORY.md 后调用，使下一轮注入视图与人工
    编辑内容同步；返回 summary 字符数。复用唯一常量 ``SUMMARY_TRUNCATE``，
    不复制魔法数。MEMORY.md 不存在时清空 summary 并返回 0。
    """
    master = memory_master()
    summary = memory_summary()
    if not master.exists():
        try:
            summary.write_text("", encoding="utf-8")
        except Exception:
            pass
        return 0
    try:
        text = master.read_text(encoding="utf-8")
    except Exception:
        return 0
    try:
        summary.write_text(text[:SUMMARY_TRUNCATE], encoding="utf-8")
    except Exception:
        return 0
    return min(len(text), SUMMARY_TRUNCATE)
