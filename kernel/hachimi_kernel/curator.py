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
from hachimi_kernel.skill_library import SkillLibrary, Skill
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
                # P2-R §8.2：每个 run 收尾做一次容量维护（印证 + 淘汰 + 清理）。
                # 证据=轨迹摘要：本次跑过的 App/控件会点亮对应事实，让"常用"胜出。
                try:
                    cap = maintain_capacity(evidence=_trajectory_digest(run_record),
                                            success=bool(run_record.get("success")))
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
        - **写入端节流**（对齐 TDB `everyNConversations` / `maxMemoriesPerSession`）：
          同一 App 在 ``FACT_EXTRACT_COOLDOWN_S`` 内不重复提取（LLM 调用既花钱
          又往池子里灌水），单次产出上限 ``FACTS_PER_RUN_MAX``；
        - 输出经条目级清洗（strip / 长度截断），去重交给 ``_merge_memory_sections``。
        """
        if self.brain is None:
            return []
        digest = _trajectory_digest(run_record)
        if not digest:
            return []
        pkg = _run_app(run_record) or _fact_pkg(digest)
        if not should_extract_facts(pkg):
            print("[curator] facts extract skipped (cooldown, app=%s)" % (pkg or "-"),
                  flush=True)
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
            ln = _strip_bullet(ln)
            if len(ln) > 6:
                facts.append(ln[:200])
            if len(facts) >= FACTS_PER_RUN_MAX:
                break
        mark_facts_extracted(pkg)
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

# 两条目各自点名了不同具体控件时的更高门槛（见 _similar 注释）。
# 取 0.55：高于同义句对实测上限（0.43），留一档余量。
_DISJOINT_WIDGET_THRESHOLD = 0.55

# 共享具体控件时的降档门槛（见 _similar 注释）：真同义对实测 0.31，
# 共享控件却讲两件事的 pair 实测 0.19 —— 取 0.25 卡在两者之间。
_WIDGET_HIT_OVERLAP = 0.25

# 共享引用短语时的降档门槛（见 _similar 注释）：真同义 pair 实测 0.26。
_ANCHOR_OVERLAP = 0.20

# 具体控件 id（非通用）作为强信号：两条事实都提到同一个具体控件，
# 极可能讲的是同一件事（例如都讲 material_timepicker_mode_button）。
#
# 三种写法都要覆盖（2026-10-02 实测）：完整 resource-id
# （org.fossify.clock:id/alarm_fab）、反引号里的短 id（`alarm_fab`）、
# 以及裸写的 snake_case id（alarm_fab / material_timepicker_mode_button）。
#
# **不能**把包名当成控件：旧版用「点分 token」匹配，于是 `fossify.clock`
# 被当成具体控件 —— 同一 App 的任意两条事实都"共享控件"而被误合并
# （真机 dry-run 实证：底部导航坐标点按 与 悬浮按钮 id 点击 被并成一条）。
_RES_ID_RE = re.compile(r"[a-z][a-z0-9_.]*:[a-z0-9_]+/([a-z0-9_]+)")
_BACKTICK_ID_RE = re.compile(r"`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`")
_BACKTICK_ALL_RE = re.compile(r"`([^`]+)`")
_SNAKE_ID_RE = re.compile(r"\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b")
# 工具名也是 snake_case，但不是控件（否则"都要 type_text"会被当成同义证据）
_TOOL_WORDS = frozenset({
    "type_text", "tap_xy", "tap_by_id", "press_back", "press_home", "press_enter",
    "launch_app", "observe", "screenshot", "look", "wait", "task_done", "swipe",
    "long_press", "read_view", "scroll",
})
_GENERIC_WIDGETS = {"button1", "button2", "android", "id"}


def _strip_bullet(text: str) -> str:
    """去掉条目开头的列表符号（含 LLM 偶发的嵌套写法 ``- - xxx``）。

    2026-10-02 真机：模型返回过 ``- - 文本…``，旧代码只剥一层 ``- ``，
    于是 MEMORY.md 里出现 ``- - 文本…`` 双层符号，条目内容自带 "- " 前缀，
    既影响展示也干扰语义比对。
    """
    # ``(?:[-*•]+\s*)+``：重复剥 —— "- - x" 只剥一层会留下 "- x"
    return re.sub(r"^(?:[-*•]+\s*)+", "", (text or "").strip()).strip()


def _anchors(text: str) -> set:
    """条目里的**引用短语**（反引号内、含空格或标点，即非纯控件 id 的串）。

    两条事实引用同一句原文（如 ``projection not started``）几乎必然在讲同一件
    事 —— 真机实证：这类同义改写词面重叠只有 0.26（低于 0.35 阈值）而漏网。
    """
    out = set()
    for m in _BACKTICK_ALL_RE.findall(text or ""):
        s = m.strip()
        if len(s) >= 8 and ((" " in s) or (not re.fullmatch(r"[a-z0-9_.:/]+", s))):
            out.add(s.lower())
    return out


def _anchor_hit(anchors: set, other: str) -> bool:
    """``anchors`` 中任意引用短语是否作为原文出现在 ``other`` 里。"""
    t = (other or "").lower()
    return any(an in t for an in anchors or ())


def _widgets(text: str) -> set:
    """条目里的具体控件 id（排除通用件与工具名；包名不算控件）。"""
    t = text or ""
    out = set(_RES_ID_RE.findall(t)) | set(_BACKTICK_ID_RE.findall(t))
    out |= {m for m in _SNAKE_ID_RE.findall(t) if m not in _TOOL_WORDS}
    return out - _GENERIC_WIDGETS


def _similar(a: str, b: str, threshold: float = None, raw_only: bool = False) -> bool:
    """两条目是否同义：共享具体控件 id，或语义指纹重叠率 >= 阈值。

    ``raw_only=True``：只用**词面指纹**判定，忽略"共享控件/引用短语"的降门槛
    捷径 —— 删除路径专用（那条捷径是为召回设计的，用它删数据会误伤）。

    两条都点名了**互不相交**的具体控件 ⇒ 讲的是不同控件，需更强的文字证据
    才算同义（阈值抬到 ``_DISJOINT_WIDGET_THRESHOLD``）。2026-10-02 真机实证：
    「时间选择器切文本模式要点 material_timepicker_mode_button」与「必须先点
    edit_alarm_time 打开对话框」在 0.35 阈值下被误并，丢失了后者的关键信息。
    """
    threshold = _SIMILAR_THRESHOLD if threshold is None else float(threshold)
    if raw_only:
        fa, fb = _fact_fingerprint(a), _fact_fingerprint(b)
        if not fa or not fb:
            return False
        return len(fa & fb) / max(1, min(len(fa), len(fb))) >= threshold
    wa, wb = _widgets(a), _widgets(b)
    if wa & wb:
        # 共享控件只把门槛降到 _WIDGET_HIT_OVERLAP，不再无条件判同义：
        # 真机实证「edit_alarm_time 的 id 列举」与「必须先点它打开对话框」
        # 共享控件却是两件事（文字重叠仅 0.19，真同义对实测 0.31）。
        threshold = min(threshold, _WIDGET_HIT_OVERLAP)
    elif wa and wb:
        threshold = max(threshold, _DISJOINT_WIDGET_THRESHOLD)
    # 一方引用、另一方原文复述也算同主题（真机：只有 a 带反引号，b 是裸写）
    aa, ab = _anchors(a), _anchors(b)
    if (aa & ab) or _anchor_hit(aa, b) or _anchor_hit(ab, a):
        threshold = min(threshold, _ANCHOR_OVERLAP)
    fp_a = _fact_fingerprint(a)
    fp_b = _fact_fingerprint(b)
    if not fp_a or not fp_b:
        return False
    return len(fp_a & fp_b) / max(1, min(len(fp_a), len(fp_b))) >= threshold


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
    for it in items or []:
        s = _strip_bullet(it)
        if not s:
            continue
        # 与 _merge_memory_sections 共用 _similar：控件 id 强信号 + 指纹重叠，
        # 两条路径判定一致（此前 semantic_dedup 只比指纹，控件规则被漏掉）。
        if any(_similar(s, kept, threshold) for kept in out):
            continue
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


def compact_memory_sections(text: str):
    """回溯合并**已有**条目（P2-R 语义去重收口），返回 ``(新文本, 合并掉条数)``。

    为什么需要单独一个函数：``_merge_memory_sections`` 的去重只发生在**新增**
    条目对存量（保护人工编辑内容），**存量条目之间从不互相合并**。于是调参前
    沉淀下的重复会永久留在 MEMORY.md —— 2026-10-02 实测：阈值由 0.45 校准到
    0.35 后，设备上 12 条事实里仍留着 3 个「自动保存」变体。本函数对存量做
    一次性合并；纯函数不落盘，Omni 可直接复用。
    """
    sections = _split_memory_sections(text)
    removed = 0
    for key in list(sections):
        items = sections[key] or []
        kept = semantic_dedup(items)
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

# 淘汰用的同义门槛：比 _SIMILAR_THRESHOLD 严格得多。
# 印证聚类可以松（判错了只是把分数加给另一条），**删除必须保守** —— 真机实证
# (7,9) 两条共享 edit_alarm_time 却是不同事实，用 0.35 聚类会把有用的一条删掉。
_PRUNE_MERGE_THRESHOLD = 0.5

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
    """条目所属的 App 包名（无则空串，归入"通用"）。"""
    m = _PKG_RE.findall(entry or "")
    return m[0] if m else ""


def _similar_clusters(items: List[str], threshold: float = None,
                      raw_only: bool = False) -> List[List[int]]:
    """按 :func:`_similar` 把条目贪心聚成同义簇（返回下标簇）。

    ``threshold`` 省略时用基础阈值 —— 用于**印证聚类**（判错只是把分加给别人）；
    删除路径必须显式传更严的 ``_PRUNE_MERGE_THRESHOLD``。
    """
    clusters: List[List[int]] = []
    for i, it in enumerate(items):
        for c in clusters:
            if any(_similar(it, items[j], threshold, raw_only) for j in c):
                c.append(i)
                break
        else:
            clusters.append([i])
    return clusters


def _evidence_hit(entry: str, evidence: str) -> bool:
    """本次 run 的轨迹证据是否印证了这条事实（包名或具体控件出现）。"""
    if not evidence:
        return False
    pkg = _fact_pkg(entry)
    if pkg and pkg in evidence:
        return True
    return any(w in evidence for w in _widgets(entry))


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


def corroborate_facts(evidence: str) -> int:
    """本次 run 成功：点亮被印证的事实，返回被加分的条目数。"""
    return _apply_evidence(evidence, positive=True)


def contradict_facts(evidence: str) -> int:
    """本次 run 失败：给被"打脸"的事实记一次反证（对齐 TDB 的 conflict detection）。

    按某条事实描述的路径操作却失败 ⇒ 这条事实至少在当下不可靠。记 ``fails+1``，
    效用分按 −3/次计；累计到 ``FACT_FAILS_EVICT`` 时，下次 prune 直接淘汰
    （不再靠分数慢慢排队）——“记忆会认错，也会改错”。
    """
    return _apply_evidence(evidence, positive=False)


def _apply_evidence(evidence: str, positive: bool) -> int:
    """把本次 run 的证据写到事实效用索引上（印证 / 反证）。

    同义簇内**只给簇代表加减分**（hits 最高者）——变体之间靠此收敛：常被印证的
    那条分越来越高，其余变体在容量收紧时先被淘汰。这就是"滑动"替代"归并"。
    """
    p = memory_master()
    if not p.exists():
        return 0
    items = _split_memory_sections(p.read_text(encoding="utf-8")).get(_FACT_SEC) or []
    if not items or not evidence:
        return 0
    meta = _load_fact_meta()
    now = time.time()
    touched = 0
    for cluster in _similar_clusters(items):
        hit = any(_evidence_hit(items[i], evidence) for i in cluster)
        leader = max(cluster,
                     key=lambda i: int((meta.get(_fact_key(items[i])) or {}).get("hits") or 0))
        for i in cluster:
            key = _fact_key(items[i])
            rec = meta.get(key) or {"text": items[i], "pkg": _fact_pkg(items[i]),
                                    "hits": 0, "fails": 0,
                                    "created": now, "last_seen": now}
            rec["last_seen"] = now
            if hit and i == leader:
                if positive:
                    rec["hits"] = min(99, int(rec.get("hits") or 0) + 1)
                else:
                    rec["fails"] = min(99, int(rec.get("fails") or 0) + 1)
                touched += 1
            meta[key] = rec
    _save_fact_meta(meta)
    return touched


def _fact_score(entry: str, meta: Dict[str, Any], now: float) -> float:
    """效用分 = 印证×2 − 反证×3 + 新鲜度（30 天内从 1 线性衰减到 0）。"""
    rec = meta.get(_fact_key(entry)) or {}
    age_days = max(0.0, (now - float(rec.get("created") or now)) / 86400.0)
    return (int(rec.get("hits") or 0) * 2.0
            - int(rec.get("fails") or 0) * 3.0
            + max(0.0, 1.0 - age_days / 30.0))


def prune_memory(now: float = None) -> int:
    """按容量边界淘汰事实，返回淘汰条数（0 = 未超容）。

    淘汰次序：①同义簇内非代表 → ②单 App 超容（保底 ``FACTS_APP_FLOOR``）
    → ③全局超容。被淘汰条目的效用索引一并删除。
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
    # ① 近乎逐字重复的条目（严格门槛）：删除保守，只砍"几乎一样"的
    for c in _similar_clusters(items, _PRUNE_MERGE_THRESHOLD, raw_only=True):
        if len(c) > 1:
            leader = max(c, key=lambda i: (score(i), -i))
            drop.update(i for i in c if i != leader)

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


def maintain_capacity(evidence: str = "", success: bool = True) -> Dict[str, int]:
    """一次跑完「印证或反证 + 四项容量维护」，返回各项清理条数。

    ``success=False`` 时走反证：本次跑过的 App/控件对应的事实记一次失败，
    累计到阈值直接淘汰（对齐 TDB 的 conflict detection）。

    Omni 可复用：与去重正交，属于"知识资产必须有上限"的那一层治理。
    """
    out = {"corroborated": 0, "contradicted": 0, "facts_pruned": 0,
           "skills_pruned": 0, "rollouts_pruned": 0, "tasks_pruned": 0}
    try:
        if success:
            out["corroborated"] = corroborate_facts(evidence)
        else:
            out["contradicted"] = contradict_facts(evidence)
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


def compact_memory_master() -> int:
    """对设备端 MEMORY.md 执行回溯合并并落盘，返回合并掉的条数（0 = 无重复）。"""
    master = memory_master()
    if not master.exists():
        return 0
    try:
        existing = master.read_text(encoding="utf-8")
    except Exception:
        return 0
    new_text, removed = compact_memory_sections(existing)
    if removed and new_text != existing:
        try:
            master.write_text(new_text, encoding="utf-8")
        except Exception:
            return 0
    return removed


def _merge_memory_sections(existing: str, new_facts: List[str], new_lessons: List[str]):
    """把新 facts/lessons 去重追加进 MEMORY.md 双分区。

    返回 ``(新文本, 追加facts数, 追加lessons数)``。

    去重规则（§3.1）：条目全文 strip + 小写比对，杜绝重复沉淀；再加上语义
    指纹/控件 id 同义判定（P2-R）。仅追加不覆写，保护人工编辑内容——**存量
    条目之间的合并见** :func:`compact_memory_sections`。
    """
    fact_sec, lesson_sec = _FACT_SEC, _LESSON_SEC
    sections = _split_memory_sections(existing)

    def _add(items: List[str], new: List[str]) -> int:
        seen = {x.strip().lower() for x in items if x}
        added = 0
        for n in new:
            item = _strip_bullet(n)
            key = item.lower()
            if not key:
                continue
            if key in seen:                      # 1) 精确重复
                continue
            if any(_similar(key, s) for s in seen):   # 2) 同义重复（语义指纹）
                continue
            seen.add(key)
            items.append(item)
            added += 1
        return added

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
