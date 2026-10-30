# -*- coding: utf-8 -*-
"""M4b.3 Curator：触发式静默维护（轨迹 / world-model / 质量标记）。

任务完成后触发一次（bridge ``_curator_after_finish`` 调 ``run_once``），做三件维护：

1. **prune_trajectories**：清理过期轨迹（raw 30 天 / failures 14 天）。
2. **refine_world_model**：精炼 world-model facts —— 去重冗余、移除陈旧/空事实。
3. **flag_low_quality**：高 retry / 高 brain_intervention / 失败轨迹 → 标
   ``excluded_from_sft``，供未来数据集构建过滤。

**任务记忆蒸馏已整体移交 memory_brain（LTM 单脑，2026-10-30 对齐桌面）**：
对话 → brain.db 原子记忆的写链（提炼/价值判/一致性四态/巩固/画像）在上游
LTM 定稿后不再是「轨迹 → MEMORY.md 启发式蒸馏」——旧蒸馏线（rollouts/
MEMORY.md/容量缓存/画像候选区）随对齐整段移除，注入改走 ``inject_text_brain``
检索（L1），沉淀走 ``memory_writer`` 异步写链。本模块只剩轨迹域维护。

**skill 沉淀**：``SkillLibrary.auto_distill``（执行可验证的操作序列 = 操作缓存）
不再由 run_once 挂载（对齐桌面 skill 轴降级：自动线恒关、人工维护）；方法本体
保留在 skill_library 供人工/测试调用。

设计红线：
- 零场景硬编码（task_id 来自参数，资产跟 task 走）。
- **非决策者**：不介入任务执行，只在任务后静默维护。
- 所有操作幂等、容错（单文件损坏不中断整体 prune）。
- 返回 CuratorReport 供 telemetry / debug。
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from hachimi_kernel.world_model import WorldModel
from hachimi_kernel.runtime_paths import task_dir


# ---------------------------------------------------------------------------
# 质量判定阈值（Master Spec §6 / §5.2；全可配，不硬编码到业务）
# ---------------------------------------------------------------------------

DEFAULTS: Dict[str, Any] = {
    "prune_raw_days": 30,
    "prune_failure_days": 14,
    # flag_low_quality 阈值
    "high_retry_threshold": 3,        # retry_count >= 此值 → low_quality
    "high_intervention_threshold": 0.5,  # brain_intervention_rate >= 此值 → low_quality
}


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
    flagged_low_quality: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "triggered_at": self.triggered_at,
            "task_id": self.task_id,
            "pruned_files": self.pruned_files,
            "refined_facts_removed": self.refined_facts_removed,
            "flagged_low_quality": self.flagged_low_quality,
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
                        若提供，则用于 flag_low_quality。

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

        只对磁盘 current.md 操作（不依赖内存 WorldModel 实例）。
        返回移除的冗余事实条数。
        """
        wm_path = task_dir(self.task_id) / "world_model.md"
        if not wm_path.exists():
            return 0

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
    # 3. flag_low_quality
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


def _parse_llm_json(text: str) -> Optional[Dict[str, Any]]:
    """从 LLM 回复里抠 JSON 对象（容忍 ```json 围栏与前后废话）。

    skill_library 的 LLM 判定复用本函数（保持单出处，勿复制实现）。
    """
    import re
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
