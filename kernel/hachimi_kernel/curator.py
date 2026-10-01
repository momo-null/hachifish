"""M4b.3 Curator：触发式静默维护知识库锐度。

Hermes Autonomous Background Curator 范式；Master Spec §6 决策「仅触发式，不挂定时器」。
任务完成后触发一次（由 ``tool_loop._finish`` 调 ``run_once``），做四件维护：

1. **prune_trajectories**：清理过期轨迹（raw 30 天 / failures 14 天），调 TrajectoryStore.prune。
2. **refine_world_model**：精炼 world-model facts —— 去重已 Merge 的冗余、移除陈旧事实。
3. **review_candidate_skills**：从成功 RunRecord 提取 candidate + 晋升判定（N=3 门）。
4. **flag_low_quality**：高 retry / 高 brain_intervention / 非稳定成功的轨迹 → 标
   ``excluded_from_sft``，供未来数据集构建过滤。

设计红线：
- 零场景硬编码（task_id 来自参数，资产跟 task 走）。
- **非决策者**：不介入任务执行，只在任务后静默维护。
- 所有操作幂等、容错（单文件损坏不中断整体 prune）。
- 返回 CuratorReport 供 telemetry / debug。
"""
from __future__ import annotations

import json
import math
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from hachimi_kernel.trajectory import TrajectoryStore
from hachimi_kernel.world_model import WorldModel
from hachimi_kernel.skill_library import SkillLibrary, Skill
from hachimi_kernel.runtime_paths import (
    task_dir,
    global_memory,
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
    "min_steps_for_skill": 2,         # 少于此步数的成功轨迹不提取 skill（太短无信息）
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
        # P2-R：可选蒸馏大脑（facts LLM 提取用）。Curator 自身仍零 brain 依赖 ——
        # bridge 注入 BYOK client；None = 纯启发式（facts 无源时保持空）。
        self.brain = None

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
                        若提供，则用于 review_candidate_skills + flag_low_quality。

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

        # 3. review candidate skills（从刚结束的 run_record 提取）
        if run_record is not None:
            try:
                skill_result = self.review_candidate_skills(run_record)
                report.skills_reviewed = skill_result["reviewed"]
                report.skills_promoted = skill_result["promoted"]
                report.skills_created = skill_result["created"]
            except Exception as e:
                report.errors.append(f"review_candidate_skills: {type(e).__name__}: {e}")

        # 4. flag low-quality（从刚结束的 run_record 判定）
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
    # ==================================================================

    _FACTS_PROMPT = (
        "你是知识蒸馏器。下面是一次手机 GUI 任务的成功执行轨迹（已脱敏，"
        "type_text 的具体内容以 <text> 代替）。\n"
        "提取 2-5 条【跨任务可复用的环境事实】：只描述应用/系统的行为规律与 UI "
        "陷阱（例如「某按钮在 X 条件下静默无效」「空列表没有占位行」「某类控件 "
        "不可点需坐标点按」），禁止包含本次任务的临时内容（具体输入文本、任务目"
        "标、页面临时状态）。每条一行，以 \"- \" 开头，直接输出条目，不要解释。\n\n"
        "轨迹：\n{digest}"
    )

    def _extract_facts_via_llm(self, run_record: Dict[str, Any]) -> List[str]:
        """成功轨迹 → LLM 提取环境事实（P2-R；对齐 TDB extraction 环节）。

        - 无 brain / 轨迹无内容 / 调用失败 / 无产出 → ``[]``（绝不拖垮蒸馏）；
        - 输出经条目级清洗（strip / 长度截断 / 上限 5 条），去重交给
          ``_merge_memory_sections``（strip+小写全文比对）。
        """
        if self.brain is None:
            return []
        digest = _trajectory_digest(run_record)
        if not digest:
            return []
        try:
            reply = self.brain.chat(
                [{"role": "user",
                  "content": self._FACTS_PROMPT.format(digest=digest)}], [])
        except Exception as e:
            print("[curator] facts extract failed: %s: %s"
                  % (type(e).__name__, e), flush=True)
            return []
        facts: List[str] = []
        for ln in (reply.get("content") or "").splitlines():
            ln = ln.strip()
            if ln.startswith("- ") and len(ln) > 6:
                facts.append(ln[2:].strip()[:200])
            if len(facts) >= 5:
                break
        if facts:
            print("[curator] facts extracted: %d" % len(facts), flush=True)
        return facts


    def review_candidate_skills(self, run_record: Dict[str, Any]) -> Dict[str, Any]:
        """从成功 RunRecord 提取 candidate skill + 晋升判定。

        Returns:
            {reviewed, promoted, created}
        """
        result = {"reviewed": 0, "promoted": 0, "created": 0}

        if not run_record.get("success"):
            return result

        steps = int(run_record.get("steps", 0))
        if steps < self.min_steps_for_skill:
            return result

        # 从 run_record 提取 candidate
        lib = SkillLibrary(task_id=self.task_id)
        candidate = SkillLibrary.from_run_record(run_record, task_id=self.task_id)
        if candidate is None:
            return result

        result["reviewed"] = 1
        promote_result = lib.promote_or_insert(candidate)
        if promote_result.get("action") == "created":
            result["created"] = 1
        if promote_result.get("promoted"):
            result["promoted"] = 1

        return result

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
        new_text, added_f, added_l = _merge_memory_sections(existing, new_facts, new_lessons)
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


# 语义去重阈值（重叠率 = 交集 / min(两指纹大小)）。
# 实测校准（真机提取出的同义句对）：同义 0.29~0.43，不同事实 <= 0.06。
# 取 0.35：覆盖大部分同义改写，与"不同事实"仍有 6 倍安全边际。
# 局限：用词完全不同的同义改写（实测最低 0.29）仍会漏 —— 需要 embedding 或
# LLM 辅助合并，属于后续项（见 doc/knowledge_redesign.md §6.3 遗留）。
_SIMILAR_THRESHOLD = 0.35

# 具体控件 id（非通用）作为强信号：两条事实都提到同一个具体控件，
# 极可能讲的是同一件事（例如都讲 material_timepicker_mode_button）。
# 覆盖两种写法：反引号里的短 id（alarm_fab）与完整 resource-id
# （org.fossify.clock:id/alarm_fab）—— 后者含 `:`/`/`，旧正则匹配不到，
# 会让"alarm_fab 可点"的两条变体漏网（2026-10-01 实测残留）。
_WIDGET_RE = re.compile(r"[a-z][a-z0-9_]{4,}(?:[.:/][a-z0-9_]+)+")
_GENERIC_WIDGETS = {"android:id/button1", "android:id/button2"}


def _widgets(text: str) -> set:
    """条目里的具体控件 id（排除 android:id/button1 这类到处都有的通用件）。"""
    return {m for m in _WIDGET_RE.findall(text or "")} - _GENERIC_WIDGETS


def _similar(a: str, b: str) -> bool:
    """两条目是否同义：共享具体控件 id，或语义指纹重叠率 >= 阈值。"""
    if _widgets(a) & _widgets(b):
        return True
    fp_a = _fact_fingerprint(a)
    fp_b = _fact_fingerprint(b)
    if not fp_a or not fp_b:
        return False
    return len(fp_a & fp_b) / max(1, min(len(fp_a), len(fp_b))) >= _SIMILAR_THRESHOLD


def _fact_fingerprint(text: str) -> set:
    """条目语义指纹 = CJK bigram 集合 ∪ 拉丁词元集合（纯标准库，零 embedding）。

    用于识别 LLM 提取出的**同义不同表述**：条目级精确去重（strip+小写全文比对）
    挡不住「按返回键自动保存」vs「按 back 返回后即写入列表」这类差异。
    """
    from hachimi_kernel.skill_library import _cjk_bigrams
    toks = {t.lower() for t in re.findall(r"[a-zA-Z]{3,}", text or "")}
    return (toks | _cjk_bigrams(text or "")) or set(str(text or "")[:40])


def semantic_dedup(items: List[str], threshold: float = None) -> List[str]:  # noqa: F821
    """模糊去重：相似度 = 交集 / min(两集合大小)，超阈值视为同一条，保留先到者。

    Omni 可复用：facts / lessons / 纠偏条目都能用同一函数压掉同义重复，
    避免记忆块随使用次数线性膨胀（2026-10-01：3 次提取就攒出 12 条，
    其中"自动保存"类事实出现 3 个变体）。
    """
    threshold = _SIMILAR_THRESHOLD if threshold is None else float(threshold)
    out: List[str] = []
    seen: List[set] = []
    for it in items or []:
        s = str(it or "").strip()
        if not s:
            continue
        fp = _fact_fingerprint(s)
        dup = False
        for prev in seen:
            if not prev or not fp:
                continue
            inter = len(prev & fp)
            if inter / max(1, min(len(prev), len(fp))) >= threshold:
                dup = True
                break
        if dup:
            continue
        seen.append(fp)
        out.append(s)
    return out


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
            v = s[2:].strip()
            if v and v != "(暂无)":
                facts.append(v)
        elif cur == "lessons" and s.startswith("- "):
            v = s[2:].strip()
            if v and v != "(暂无)":
                lessons.append(v)
    return facts, lessons


def _merge_memory_sections(existing: str, new_facts: List[str], new_lessons: List[str]):
    """把新 facts/lessons 去重追加进 MEMORY.md 双分区。

    返回 ``(新文本, 追加facts数, 追加lessons数)``。

    去重规则（§3.1）：条目全文 strip + 小写比对，杜绝重复沉淀；
    仅追加不覆写，保护人工编辑内容；首次自动创建固定头部。
    """
    fact_sec, lesson_sec = "## 长期事实", "## 教训"
    sections = {fact_sec: [], lesson_sec: []}
    cur = None
    for line in existing.splitlines():
        s = line.strip()
        if s.startswith("# ") and not s.startswith("## "):
            cur = None
            continue
        if s.startswith("## "):
            cur = s
            if cur not in sections:
                sections[cur] = []
            continue
        if cur in sections and s.startswith("- "):
            sections[cur].append(s[2:].strip())

    def _add(items: List[str], new: List[str]) -> int:
        seen = {x.strip().lower() for x in items if x}
        added = 0
        for n in new:
            key = n.strip().lower()
            if not key:
                continue
            if key in seen:                      # 1) 精确重复
                continue
            if any(_similar(key, s) for s in seen):   # 2) 同义重复（语义指纹）
                continue
            seen.add(key)
            items.append(n.strip())
            added += 1
        return added

    added_f = _add(sections[fact_sec], new_facts)
    added_l = _add(sections[lesson_sec], new_lessons)

    out = ["# OmniAgent 全局长期记忆", ""]
    for marker in (fact_sec, lesson_sec):
        out.append(marker)
        if sections[marker]:
            out.extend(f"- {it}" for it in sections[marker])
        else:
            out.append("- (暂无)")
        out.append("")
    return "\n".join(out).rstrip() + "\n", added_f, added_l


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
