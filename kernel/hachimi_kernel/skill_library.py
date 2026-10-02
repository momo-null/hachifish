"""M4b.2 Skill 库：成功轨迹提炼 + N=3 晋升门。

格式：Hermes 风格 SKILL.md（Markdown + YAML frontmatter，对齐 agentskills.io）。
存储：task 级 `tasks/<task_id>/skills/<skill_name>.md`；通用 skill 落用户级 `~/.omniagent/skills/`。
子结构（substeps/metadata）用 JSON 内嵌在 frontmatter 中，避免手写 YAML 解析。

Skill 生命周期：
  task success → 从 RunRecord 提取 substeps → 创建 candidate skill
  → 后续相同 objective_pattern 成功 → success_count += 1
  → 成功 3 次（连续）→ 晋升 active
  → 中间一次失败 → success_count 归零（打断连续性）
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from hachimi_kernel.runtime_paths import task_skills, global_skills


# ---------------------------------------------------------------------------
# 规范化 / 条目身份（P2-R Phase 1：doc/knowledge_redesign.md §2）
# ---------------------------------------------------------------------------

# 内容性参数：不同任务的字面值（上次输出了什么），不参与技能身份；
# 结构性参数（view_id / package 等）保留 —— 它们才是可复用套路的骨架。
_TEXTUAL_ARGS = frozenset({"text"})

# 包名形态（相关度检索的最强信号：操作序列与 App 强绑定）；通用形态，非领域词表
_PACKAGE_RE = re.compile(r"[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+")


def canonical_args(args: Dict[str, Any]) -> Dict[str, Any]:
    """脱敏后的 args：内容性参数归一为 ``<text>``，结构性参数原样保留。"""
    return {k: ("<text>" if k in _TEXTUAL_ARGS else v)
            for k, v in (args or {}).items()}


def canonical_ops(substeps: List["SkillSubstep"]) -> List[str]:
    """脱敏动作序列（``tool(canonical_args)`` 紧凑串列表）—— 注入与 ID 共用。"""
    parts = []
    for s in (substeps or []):
        parts.append("%s(%s)" % (s.tool, json.dumps(
            canonical_args(s.args), ensure_ascii=False, sort_keys=True)))
    return parts


def _skill_field(s, key, default=""):
    """同时兼容 Skill 对象与注入 payload dict（两边共用同一检索器）。"""
    if s is None:
        return default
    if isinstance(s, dict):
        v = s.get(key)
    else:
        v = getattr(s, key, None)
    return default if v is None else v


# ---------------------------------------------------------------------------
# 检索：**选档由 LLM 做**（2026-10-02 用户红线：语义判断不归脚本）
#
# 被删除的旧实现：score_skill —— 包名交集 ×10 > 拉丁词元重叠 > CJK bigram 重叠。
# 那是把"这个技能和当前任务相关吗"写成正则与权重，再没有事后来校准的余地
# （keywords 拼进打分文本也救不了：打分仍是词面统计）。
#
# 现在：把候选清单交给 LLM 选；无 brain 时**保持原序、不做判断**（不猜）。
# 注入侧目录与 search_skill 工具共用本实现，避免两套口径。
# ---------------------------------------------------------------------------

_retrieval_brain = None


def set_retrieval_brain(brain) -> None:
    """注入检索用的 brain（由 :func:`bridge.set_brain` 在配置 BYOK 时调用）。"""
    global _retrieval_brain
    _retrieval_brain = brain


_SELECT_PROMPT = (
    "下面是手机 GUI agent 技能库的一个候选清单（每条含名称、适用场景、套路名、"
    "检索关键词、所属 App 包名）与用户当前的任务描述。\n"
    "挑出**对完成这个任务真正有用**的条目（最多 {limit} 条）；挑选依据是任务意图"
    "与条目是否讲同一件事，不是字面相似度。都不相关就返回空数组。\n"
    "按有用程度从高到低排列。\n"
    '{{"picked": ["条目名称", "..."]}}\n'
    "任务：{query}\n\n候选清单：\n{items}\n"
)


def _skill_brief(skill) -> str:
    """候选清单里给 LLM 看的条目摘要（不含完整步骤，避免上下文膨胀）。"""
    parts = [("name", str(_skill_field(skill, "name", "") or "")),
             ("routine", str(_skill_field(skill, "routine", "") or "")),
             ("desc", str(_skill_field(skill, "description", "") or "")[:80])]
    kws = [k for k in (_skill_field(skill, "keywords", []) or []) if isinstance(k, str)]
    if kws:
        parts.append(("keywords", ",".join(kws)))
    if isinstance(skill, Skill):
        pkg = _candidate_pkg(skill)
        if pkg:
            parts.append(("pkg", pkg))
    return " | ".join("%s=%s" % (k, v) for k, v in parts if v)


def _llm_pick(skills, query: str, limit: int):
    """LLM 选档 → 返回选中的条目列表；无 brain / 失败 ⇒ ``None``（=别猜）。"""
    if _retrieval_brain is None:
        return None
    try:
        reply = _retrieval_brain.chat([{"role": "user", "content": _SELECT_PROMPT.format(
            limit=max(1, int(limit or 5)), query=(query or "")[:1500],
            items="\n".join("- %s" % _skill_brief(s) for s in skills))}], [])
        from hachimi_kernel.curator import _parse_llm_json
        data = _parse_llm_json((reply or {}).get("content"))
    except Exception as e:
        print("[skill] llm select failed: %s: %s" % (type(e).__name__, e), flush=True)
        return None
    if not isinstance(data, dict):
        return None
    picked = [str(x or "").strip() for x in (data.get("picked") or []) if str(x or "").strip()]
    if not picked:
        return []
    by_name = {str(_skill_field(s, "name", "")): s for s in skills}
    out = [by_name[n] for n in picked if n in by_name]
    return out[:max(1, int(limit or 5))]


def select_skills(skills, query: str, limit: int = 5) -> List[Any]:
    """选出与任务相关的条目（LLM 判定；无 brain 或无 query 时保持原序，不猜）。"""
    items = list(skills or [])
    if not items:
        return []
    if not (query or "").strip():
        return items[:max(1, int(limit or 5))]
    picked = _llm_pick(items, query, limit)
    if picked is None:
        return items[:max(1, int(limit or 5))]
    return picked


def search_skills(skills, query: str, limit: int = 5):
    """统一技能检索：**LLM 选档** + 结构化结果（UI / 工具 / 埋点共用契约）。"""
    chosen = select_skills(skills, query, limit=limit)
    out = []
    for rank, s in enumerate(chosen, 1):
        pat = str(_skill_field(s, "objective_pattern", "")).strip()
        out.append({
            "name": str(_skill_field(s, "name", "")),
            "applies": pat[:120] + ("…" if len(pat) > 120 else ""),
            "rank": rank,
            "entry_id": str(_skill_field(s, "entry_id", "")),
        })
    return out


def _skill_pkg_of(skill) -> str:
    """条目所属 App 包名（Skill 对象取首个 launch_app；dict payload 无则空）。"""
    return _candidate_pkg(skill) if isinstance(skill, Skill) else ""


def record_skill_use(name: str) -> bool:
    """命中计数（簿记）：``load_skill`` / ``search_skill`` 命中即记一次使用。

    此前 ``_load_skill`` / ``_search_skill`` 不调它 ⇒ ``total_uses`` 恒 1、
    ``last_used`` 恒空 ⇒ 命中率不可观测、效用淘汰无输入。
    """
    d = SkillLibrary._global_dir()
    for fn in (name, _safe_filename(name)):
        p = d / (fn + ".md")
        if p.exists():
            s = SkillLibrary._parse(p)
            if s is not None:
                s.metadata.total_uses += 1
                s.metadata.last_used = datetime.now(timezone.utc).isoformat()
                SkillLibrary._save_to(s, d)
                return True
    return False


def compute_entry_id(substeps: List["SkillSubstep"]) -> str:
    """条目主键 = 脱敏动作序列的 sha1 前 12 位。

    同一套路、不同字面内容（如两次 type_text 的文本不同）⇒ 同一 entry_id，
    写入端据此去重、注入端据此合并（P2-R：记忆/技能重复的根治点）。
    """
    canon = "|".join(canonical_ops(substeps))
    return hashlib.sha1(canon.encode("utf-8")).hexdigest()[:12] if canon else ""


# ---------------------------------------------------------------------------
# Skill 数据结构
# ---------------------------------------------------------------------------

@dataclass
class SkillSubstep:
    tool: str
    args: Dict[str, Any] = field(default_factory=dict)


@dataclass
class SkillMetadata:
    success_count: int = 0
    failure_count: int = 0
    total_uses: int = 0
    confidence: float = 0.0
    status: str = "candidate"
    last_used: str = ""
    created: str = ""
    scope: str = "project"          # project | global（升级 skill 跨项目复利）
    environment: Dict[str, Any] = field(default_factory=dict)
    known_failures: List[str] = field(default_factory=list)


@dataclass
class Skill:
    name: str = ""
    objective_pattern: str = ""
    entry_id: str = ""              # 脱敏动作序列摘要（P2-R Phase 1）：身份主键
    task_id: str = ""
    description: str = ""
    routine: str = ""               # P2-R v2：LLM 归纳的**规范套路名**（语义身份）
    tags: List[str] = field(default_factory=list)
    substeps: List[SkillSubstep] = field(default_factory=list)
    playbook: str = ""              # 引导型文字版套路（load_skill 优先返回它而非动作序列）
    keywords: List[str] = field(default_factory=list)  # LLM 产的检索关键词（意图级同义词）
    metadata: SkillMetadata = field(default_factory=SkillMetadata)
    body: str = ""
    disable_model_invocation: bool = False  # frontmatter 字段：True 时不可被模型直接调用

    def to_frontmatter(self) -> str:
        """YAML frontmatter（子结构用 JSON 内嵌）。"""
        ss = json.dumps(
            [{"tool": s.tool, "args": s.args} for s in self.substeps],
            ensure_ascii=False, separators=(",", ":"),
        )
        md = json.dumps({
            "success_count": self.metadata.success_count,
            "failure_count": self.metadata.failure_count,
            "total_uses": self.metadata.total_uses,
            "confidence": self.metadata.confidence,
            "status": self.metadata.status,
            "last_used": self.metadata.last_used,
            "created": self.metadata.created,
            "scope": self.metadata.scope,
            "known_failures": self.metadata.known_failures,
        }, ensure_ascii=False, separators=(",", ":"))
        # playbook 压成单行（_parse_front 按行解析，多行值会截断）；
        # disable_model_invocation 必须写出（此前不写出 ⇒ 保存即丢失禁用标记）。
        return (
            f"---\n"
            f"name: {self.name}\n"
            f"objective_pattern: {self.objective_pattern}\n"
            f"entry_id: {self.entry_id}\n"
            f"task_id: {self.task_id}\n"
            f"description: {self.description}\n"
            f"routine: {self.routine}\n"
            f"playbook: {' '.join((self.playbook or '').split())}\n"
            f"keywords_json: {json.dumps(self.keywords, ensure_ascii=False)}\n"
            f"tags_json: {json.dumps(self.tags, ensure_ascii=False)}\n"
            f"substeps_json: {ss}\n"
            f"metadata_json: {md}\n"
            f"disable_model_invocation: {str(self.disable_model_invocation).lower()}\n"
            f"---"
        )

    def to_markdown(self) -> str:
        body = [
            f"# Skill: {self.name}",
            f"## Objective",
            f"`{self.objective_pattern}`",
        ]
        if self.playbook:
            body += ["## Guide", self.playbook]
        body += ["## Substeps"]
        for i, s in enumerate(self.substeps, 1):
            a = json.dumps(s.args, ensure_ascii=False) if s.args else "{}"
            body.append(f"{i}. `{s.tool}({a})`")
        body += [
            f"## Status: {self.metadata.status}",
            f"- Success: {self.metadata.success_count}",
            f"- Failures: {self.metadata.failure_count}",
            f"- Confidence: {self.metadata.confidence:.2f}",
        ]
        return "\n".join(body)

    # --- N=3 晋升 ------------------------------------------------------------
    def record_success(self) -> bool:
        self.metadata.success_count += 1
        self.metadata.total_uses += 1
        self.metadata.last_used = datetime.now(timezone.utc).isoformat()
        self.metadata.confidence = self.metadata.success_count / max(self.metadata.total_uses, 1)
        if self.metadata.success_count >= 3 and self.metadata.status == "candidate":
            self.metadata.status = "active"
            return True
        return False

    def record_failure(self, reason: str = "") -> None:
        self.metadata.failure_count += 1
        self.metadata.total_uses += 1
        self.metadata.success_count = 0
        self.metadata.last_used = datetime.now(timezone.utc).isoformat()
        self.metadata.confidence = self.metadata.success_count / max(self.metadata.total_uses, 1)
        if reason and reason not in self.metadata.known_failures:
            self.metadata.known_failures.append(reason[:200])


# ---------------------------------------------------------------------------
# Skill 库持久化
# ---------------------------------------------------------------------------

def _safe_filename(name: str) -> str:
    return "".join(c if c.isalnum() or c in "_-" else "_" for c in name)[:60]


class SkillLibrary:
    """skill 池 = task 级 tasks/<task_id>/skills/ + 全局 ~/.omniagent/skills/。

    recall（load/list/find）合并两层、task 优先；写入按 scope 分流：
    - scope == "global"（通用 skill，如 Curator 升级产出）→ 全局
    - 默认 / scope == "task" → task 级 tasks/<task_id>/skills/
    """

    def __init__(self, task_id: str):
        self.task_id = task_id
        self._proj_dir = task_skills(task_id)
        self._proj_dir.mkdir(parents=True, exist_ok=True)

    def _dir(self) -> Path:
        return self._proj_dir

    @staticmethod
    def _global_dir() -> Path:
        d = global_skills()
        d.mkdir(parents=True, exist_ok=True)
        return d

    def _path(self, name: str) -> Path:
        return self._dir() / f"{_safe_filename(name)}.md"

    # ---- 双层级读取（项目优先 + 全局） ----
    def _all_paths(self) -> List[Path]:
        paths: List[Path] = []
        # 全局先列（项目优先：项目同名覆盖全局，故项目后列）
        g = self._global_dir()
        paths.extend(sorted(g.glob("*.md")))
        paths.extend(sorted(self._dir().glob("*.md")))
        return paths

    @staticmethod
    def _save_to(skill: Skill, directory: Path) -> str:
        if not skill.metadata.created:
            skill.metadata.created = datetime.now(timezone.utc).isoformat()
        text = skill.to_frontmatter() + "\n\n" + skill.to_markdown()
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{_safe_filename(skill.name)}.md"
        path.write_text(text, encoding="utf-8")
        return str(path)
    def save(self, skill: Skill) -> str:
        """按 scope 分流落盘：global → 用户级；否则 → task 级 tasks/<task_id>/skills/。"""
        scope = skill.metadata.scope or "task"
        if scope == "global":
            return self._save_to(skill, self._global_dir())
        return self._save_to(skill, self._dir())

    def save_global(self, skill: Skill) -> str:
        """显式落全局（Curator 升级产出的通用 skill 用）。"""
        return self._save_to(skill, self._global_dir())

    def load(self, name: str) -> Optional[Skill]:
        # 项目优先：先看项目级，再回退全局
        path = self._path(name)
        if path.exists():
            return self._parse(path)
        gpath = self._global_dir() / f"{_safe_filename(name)}.md"
        if gpath.exists():
            return self._parse(gpath)
        return None

    def list_all(self) -> List[Skill]:
        # 项目优先：同名时项目覆盖全局（全局后列，但需去重同名）
        seen: set = set()
        out: List[Skill] = []
        for f in self._all_paths():
            if f.name in seen:
                continue
            seen.add(f.name)
            s = self._parse(f)
            if s:
                out.append(s)
        return out

    def list_global(self) -> List[Skill]:
        """仅列全局通用 skill（~/.omniagent/skills/），不含 task 私有。

        用于 Web 面板展示：task 私有 skill 不进全局列表、不共享，只在所属 task 内被消费
        （召回经 SkillLibrary(task_id) / `load_skill` 工具可见）。
        """
        out: List[Skill] = []
        for f in sorted(self._global_dir().glob("*.md")):
            s = self._parse(f)
            if s:
                out.append(s)
        return out

    def delete(self, name: str) -> bool:
        path = self._path(name)
        if path.exists():
            path.unlink()
            return True
        gpath = self._global_dir() / f"{_safe_filename(name)}.md"
        if gpath.exists():
            gpath.unlink()
            return True
        return False

    # --- 录制 ----------------------------------------------------------------

    @staticmethod
    def _meta_tool(tool: str) -> bool:
        """元/观察动作不进技能操作序列（P2 DoD4 教训）：技能=执行路径，
        observe/look/screenshot 是模型的验证行为而非套路；task_done/verify
        是完成声明与校验门，wait 无信息。"""
        return tool in ("task_done", "verify_done", "wait",
                        "observe", "look", "screenshot")

    @staticmethod
    def executed_actions(record: dict) -> List[Dict[str, Any]]:
        """RunRecord → 实际执行过的动作列表（剔除元/观察动作）。

        skill_library 的 LLM 校验（:func:`validate_skill_summary`）与机械提取
        （:meth:`from_run_record`）共用同一真源；bridge 的 RunRecord 不带
        steps_data 时回退读轨迹 jsonl（每行 StepRecord.action）。
        """
        sd = list(record.get("steps_data") or [])
        if len(sd) < 2 and record.get("trajectory_file"):
            try:
                tf = Path(str(record["trajectory_file"]))
                if tf.exists():
                    for ln in tf.read_text(encoding="utf-8").splitlines():
                        try:
                            a = (json.loads(ln).get("action") or {})
                            if a.get("tool"):
                                sd.append({"tool": a["tool"], "args": a.get("args") or {}})
                        except Exception:
                            continue
            except Exception:
                pass
        return [s for s in sd
                if isinstance(s, dict) and s.get("tool")
                and not SkillLibrary._meta_tool(s["tool"])]

    @classmethod
    def from_summary(cls, summary: Dict[str, Any], record: dict,
                     task_id: str) -> Optional[Skill]:
        """LLM 提炼的套路摘要 → Skill（P2-R v2：蒸馏 = 总结，不是转录）。

        ``summary`` 必须先过 :func:`validate_skill_summary`（防幻觉步骤）。
        **name/routine 都来自 LLM 归纳的规范套路名** —— 短名称不再是
        ``objective[:40]`` 的写死规则（用户文字可中可英、可随便换措辞，
        规则起名必然把同一套路拆成多档）。description 直接采用
        （UI 技能列表因此有可读摘要，2026-10-02 之前恒空）。
        """
        substeps = [SkillSubstep(tool=s["tool"], args=s.get("args") or {})
                    for s in summary["steps"]]
        routine = str(summary.get("routine") or summary.get("name", "")).strip()[:24]
        safe = re.sub(r"[^a-zA-Z0-9\u4e00-\u9fff_]", "_", routine).strip("_")
        return Skill(
            name="skill_%s" % (safe or "unnamed"),
            routine=routine,
            objective_pattern=str(record.get("objective", "")),
            entry_id=compute_entry_id(substeps),
            task_id=task_id,
            description=str(summary.get("description", "")),
            playbook=str(summary.get("playbook", "")),
            keywords=[str(k) for k in (summary.get("keywords") or [])],
            substeps=substeps,
            metadata=SkillMetadata(success_count=1, total_uses=1, confidence=1.0,
                                   status="candidate", scope="global",
                                   created=datetime.now(timezone.utc).isoformat()),
        )

    # --- 晋升判定 ------------------------------------------------------------
    _IDENTITY_PROMPT = (
        "你要判断一条**新沉淀的操作套路**是否与技能库里某条**已有套路**讲的是"
        "同一件事（同义命名、同一步骤、同一目的都算同一件事）。\n"
        "判据是语义与目的，不是名字是否逐字相同 —— 同一个套路被不同措辞命名"
        "（如「新建闹钟并保存」vs「新建闹钟并设置时间标签」）仍算同一条。\n"
        "不同 App 的同一目的**不算**同一条（控件 id 与坐标是 App 私有的）。\n"
        "都不算同一条时返回 same_as: null。\n"
        '{{"same_as": "已有条目名称 或 null", "reason": "一句话依据", '
        '"description": "若已有条目缺描述，给一句合并后的描述（可空）"}}\n'
        "新套路：\n{candidate}\n\n已有套路：\n{existing}\n"
    )

    def promote_or_insert(self, candidate: Skill, brain=None,
                          prefer_candidate_steps: bool = True) -> Dict[str, Any]:
        """写入端身份判定：**LLM 判是否同一套路**（``brain=None`` 时只按 name 精确
        命中兜底，绝不用 routine 字符串相等 / objective 全等这类规则猜）。

        命中原档时用候选的提炼内容覆盖（步骤/playbook/keywords），并累计成功次数
        （N=3 晋升 active 由此成立）。未命中则新建一档。
        """
        existing = self.load(candidate.name)      # 同名 = 同一文件（簿记级身份）
        if existing is not None and _candidate_pkg(existing) != _candidate_pkg(candidate):
            existing = None      # 同名但跨 App：包名是硬边界，不合并
        if existing is None:
            existing = self._llm_same_skill(candidate, brain)
        if existing is not None:
            if not existing.entry_id:
                existing.entry_id = candidate.entry_id or compute_entry_id(existing.substeps)
            if prefer_candidate_steps and candidate.substeps:
                existing.substeps = candidate.substeps
                existing.entry_id = candidate.entry_id or existing.entry_id
                if candidate.playbook:
                    existing.playbook = candidate.playbook
                if candidate.keywords:
                    existing.keywords = candidate.keywords
            if not existing.description and candidate.description:
                existing.description = candidate.description
            promoted = existing.record_success()
            self.save(existing)
            return {"action": "promoted" if promoted else "updated",
                    "skill_name": existing.name, "promoted": promoted,
                    "success_count": existing.metadata.success_count}
        self.save(candidate)
        return {"action": "created", "skill_name": candidate.name,
                "promoted": False, "success_count": 1}

    def _llm_same_skill(self, candidate: Skill, brain=None) -> Optional[Skill]:
        """LLM 判定候选是否与已有某条同套路；无 brain / 判定失败 ⇒ 不合并。"""
        pool = [s for s in self.list_all() if s.name != candidate.name]
        if brain is None or not pool:
            return None
        try:
            reply = brain.chat([{"role": "user", "content": self._IDENTITY_PROMPT.format(
                candidate=_skill_brief(candidate) + " | steps=%d" % len(candidate.substeps or []),
                existing="\n".join("- %s | steps=%d"
                                   % (_skill_brief(s), len(s.substeps or []))
                                   for s in pool))}], [])
            from hachimi_kernel.curator import _parse_llm_json
            data = _parse_llm_json((reply or {}).get("content"))
        except Exception as e:
            print("[skill] llm identity failed: %s: %s" % (type(e).__name__, e), flush=True)
            return None
        if not isinstance(data, dict):
            return None
        same_as = str(data.get("same_as") or "").strip()
        if not same_as:
            return None
        for s in pool:
            if s.name == same_as:
                desc = str(data.get("description") or "").strip()[:60]
                if desc and not s.description:
                    s.description = desc
                return s
        return None

    def record_use(self, name: str) -> bool:
        """命中计数（簿记）：load_skill / search_skill 命中即记一次使用。"""
        return record_skill_use(name)

    def record_failure_on_pattern(self, objective: str, reason: str = "") -> None:
        for s in self.list_all():
            if s.objective_pattern and s.objective_pattern.lower() in objective.lower():
                s.record_failure(reason)
                self.save(s)

    # --- 内部 ----------------------------------------------------------------
    @staticmethod
    def _parse(path: Path) -> Optional[Skill]:
        try:
            text = path.read_text(encoding="utf-8")
            front, body = _split_frontmatter(text)
            data = _parse_front(front)
            # 无 name 时回退文件名（兼容纯 .md 技能，不要求固定 frontmatter）
            name = data.get("name") or path.stem
            ss = json.loads(data.get("substeps_json", "[]"))
            md = json.loads(data.get("metadata_json", "{}"))
            return Skill(
                name=name,
                objective_pattern=data.get("objective_pattern", ""),
                task_id=data.get("task_id", ""),
                description=data.get("description", ""),
                routine=data.get("routine", ""),
                playbook=data.get("playbook", ""),
                keywords=_parse_tags(data.get("keywords_json") or data.get("keywords")),
                tags=_parse_tags(data.get("tags_json") or data.get("tags")),
                substeps=[SkillSubstep(tool=s.get("tool", ""), args=s.get("args") or {}) for s in ss],
                metadata=SkillMetadata(
                    success_count=int(md.get("success_count", 0) or 0),
                    failure_count=int(md.get("failure_count", 0) or 0),
                    total_uses=int(md.get("total_uses", 0) or 0),
                    confidence=float(md.get("confidence", 0) or 0),
                    status=str(md.get("status", "candidate")),
                    last_used=str(md.get("last_used", "")),
                    created=str(md.get("created", "")),
                    scope=str(md.get("scope", "project")),
                    known_failures=md.get("known_failures") or [],
                ),
                body=body or "",
                disable_model_invocation=_parse_disable(data.get("disable_model_invocation")),
            )
        except Exception:
            return None


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def _split_frontmatter(text: str):
    parts = text.split("---", 2)
    return (parts[1], parts[2]) if len(parts) >= 3 else ("", text)


def _parse_front(front: str) -> Dict[str, str]:
    r: Dict[str, str] = {}
    for line in front.strip().split("\n"):
        line = line.strip()
        if ":" not in line:
            continue
        k, _, v = line.partition(":")
        k = k.strip().lstrip("- ").strip()
        v = v.strip().strip('"').strip("'")
        if k:
            r[k] = v
    return r


def _parse_disable(raw) -> bool:
    """解析 frontmatter 的 disable_model_invocation 布尔字段。

    默认 False；显式 true/1/yes 为 True；false/0/no/空为 False；
    无法识别的脏值视为 True（保守：不让模型调用不确定禁用的技能）。
    """
    if raw is None:
        return False
    if isinstance(raw, bool):
        return raw
    s = str(raw).strip().lower()
    if s in ("true", "1", "yes", "y"):
        return True
    if s in ("false", "0", "no", "n", ""):
        return False
    return True


def _parse_tags(raw) -> List[str]:
    """frontmatter 的 tags 解析：支持 JSON 数组（tags_json）或逗号/空白分隔回退。"""
    if isinstance(raw, list):
        return [str(t) for t in raw]
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list):
            return [str(t) for t in parsed]
    except Exception:
        pass
    return [t.strip() for t in re.split(r"[,\s]+", str(raw).strip()) if t.strip()]


def _cjk_bigrams(s: str) -> set:
    """仅取汉字（CJK）字符成 bigram 集合，忽略空白与拉丁/数字字符。

    用于中文弱匹配：过滤掉 rimworld / http 等拉丁噪声，仅按汉字重叠判定相关性，
    且汉字 bigram 天然语序无关（"建造基地" 与 "基地建造" 共享 建造/基地）。
    长度≤1 时退化为单字符集合。
    """
    cjk = [c for c in (s or "") if "\u4e00" <= c <= "\u9fff"]
    if len(cjk) <= 1:
        return set(cjk)
    return {"".join(cjk[i:i + 2]) for i in range(len(cjk) - 1)}


# ---------------------------------------------------------------------------
# LLM 提炼套路的校验（P2-R v2：蒸馏=总结，不是转录）
# ---------------------------------------------------------------------------

def _candidate_pkg(skill: Skill) -> str:
    """技能步骤里的首个 launch 包名（App 归属；routine 匹配要求同 App）。"""
    for s in skill.substeps or []:
        if s.tool == "launch_app" and (s.args or {}).get("package"):
            return str(s.args["package"])
    return ""


def _exec_index(executed):
    """实际执行动作的检索索引（工具集合 / view_id 集合 / 包名集合 / 坐标列表）。"""
    tools = {s["tool"] for s in executed}
    vids = {str((s.get("args") or {}).get("view_id"))
            for s in executed if s["tool"] == "tap_by_id"}
    pkgs = {str((s.get("args") or {}).get("package"))
            for s in executed if s["tool"] == "launch_app"}
    taps = [(float((s.get("args") or {}).get("x", -1)),
             float((s.get("args") or {}).get("y", -1)))
            for s in executed if s["tool"] == "tap_xy"]
    return tools, vids, pkgs, taps


def validate_skill_summary(raw, record):
    """LLM 产出 → 可入库的套路摘要；不合法返回 ``None``。

    校验规则（防幻觉，全部可离线复核）：
    1. name/description 非空；steps 是 2~40 条的列表；
    2. 每个步骤的工具必须真实执行过（LLM 不得发明动作）；
    3. tap_by_id 的 view_id / launch_app 的 package 必须在轨迹里出现过；
    4. tap_xy 坐标必须落在某个实际点按的 ±0.06 邻域内（LLM 量化坐标可容忍）。
    通过后步骤参数统一过 canonical_args（text → <text>）。
    """
    if not isinstance(raw, dict):
        return None
    # prompt 只要求 routine（name 由 from_summary 生成 skill_<routine>），
    # 旧版此处硬性要求 raw["name"] ⇒ LLM 的合法产出被整体拒收（skills_created 恒 0）。
    name = str(raw.get("name") or raw.get("routine") or "").strip()
    desc = str(raw.get("description") or "").strip()
    steps = raw.get("steps")
    if not name or not desc or not isinstance(steps, list):
        return None
    executed = SkillLibrary.executed_actions(record)
    if len(executed) < 2:
        return None
    tools, vids, pkgs, taps = _exec_index(executed)
    out = []
    for st in steps:
        if not isinstance(st, dict):
            continue
        tool = str(st.get("tool") or "").strip()
        if tool not in tools:
            continue
        args = st.get("args") or {}
        if not isinstance(args, dict):
            continue
        if tool == "tap_by_id" and args.get("view_id") \
                and str(args["view_id"]) not in vids:
            continue
        if tool == "launch_app" and args.get("package") \
                and str(args["package"]) not in pkgs:
            continue
        if tool == "tap_xy" and args.get("x") is not None:
            try:
                x, y = float(args["x"]), float(args.get("y", 0.0))
            except (TypeError, ValueError):
                continue
            if not any(abs(x - tx) <= 0.06 and abs(y - ty) <= 0.06
                       for tx, ty in taps):
                continue
        out.append({"tool": tool, "args": canonical_args(dict(args))})
    if not (2 <= len(out) <= 40):
        return None
    # playbook（引导型文字版）可选：压成单行、限长；不合法不影响整条技能
    playbook = " ".join(str(raw.get("playbook") or "").split())[:600]
    # keywords（检索面）：清洗为短词列表，上限 8 个——只影响检索，不校验动作
    kws = []
    for k in (raw.get("keywords") or []):
        k = " ".join(str(k or "").split())[:16]
        if k and k not in kws:
            kws.append(k)
        if len(kws) >= 8:
            break
    return {"name": name[:24], "description": desc[:60],
            "routine": str(raw.get("routine") or "").strip()[:24],
            "precondition": str(raw.get("precondition") or "")[:60],
            "verify": str(raw.get("verify") or "")[:60],
            "playbook": playbook, "keywords": kws, "steps": out}
