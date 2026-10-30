# -*- coding: utf-8 -*-
"""memory_brain —— LTM 单脑（brain.db）内核移植。

上游：桌面 OmniAgent ``omni_core/memory_brain.py``（LTM 定稿，doc/plans/ltm-brain-design.md）。
红线 R6：上游先行，本仓库跟随移植，漂移 = bug。四层分工与上游一致：

- L0 会话：``capture`` 收会话消息（进程内 buffer，任务收尾一次性提炼）；
- L1 原子记忆：``flush_brain`` 提炼 → 价值/kind/importance 两段判 → 一致性四态
  （novel/duplicate/merge/conflict）→ brain.db；``search_brain`` 融合排序
  （relevance × salience × recency，active-only，inject_floor）+ 召回即强化；
  ``inject_text_brain`` 出 ``<relevant-memories>``；
- L2 场景块：**不移植**（手机无 scene_executor）；
- L3 画像：``maintain_profile`` 由巩固周期驱动，brain.db 新 atoms 增量重写
  ``memory/user_profile.md``（读取在 knowledge_inject.load_profile_text）。

[内核移植差异]（仅四处，其余与上游逐字对齐）：
1. 语义判定走 ``BrainClient``（stdlib urllib，BYOK 直连）——上游 llm_judge 的
   ``chat_json`` 语义同构：任何失败 → None，调用方降级。
2. 配置为模块常量（内核无 config 体系）：``_LTM_DEFAULTS`` 与上游同名键同值；
   总开关 ``set_enabled``（bridge 接 Kotlin 弱注入开关），缺省开（上游缺省关是
   桌面灰度策略；手机记忆即当前方向，语义对齐「关掉知识 = 注入与沉淀一起停」）。
3. FTS 分词器恒 trigram：上游 simple 分词器是 Windows dll 扩展（wangfenjin/simple），
   Android 无；FTS5/trigram 不可用（旧 sqlite）时整库 **LIKE 兜底扫描**（降级模式，
   相关度固定 0.8，其余权重不变）——绝不因 sqlite 能力差异让读写链报废。
4. 日志走 print（内核无 utils.get_logger）。

不变量（与上游一致）：语义判断全 LLM、簿记全公式；记忆文件只由内核写；
遗忘是降级不是删除；任何异常静默降级不阻断主流程。
"""
from __future__ import annotations

import json
import math
import os
import re
import shutil
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from hachimi_kernel import runtime_paths as rp

_BRAIN_PATH = ""      # 惰性初始化（runtime_paths 注入点；测试可直接覆盖）
_ARCHIVE_DIR = ""
_GATE = {"on": True}  # 记忆层总开关（L1/L3 共用；bridge 接 Kotlin 弱注入开关）

_SCHEMA_V2 = """
CREATE TABLE IF NOT EXISTS atoms(
  record_id   TEXT PRIMARY KEY,
  content     TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'fact',
  persona     TEXT DEFAULT '',
  origin      TEXT DEFAULT '',
  source_task TEXT DEFAULT '',
  importance  REAL NOT NULL DEFAULT 0.5,
  salience    REAL NOT NULL DEFAULT 0.5,
  access_count INTEGER NOT NULL DEFAULT 0,
  last_access TEXT DEFAULT '',
  state       TEXT NOT NULL DEFAULT 'active',
  superseded_by TEXT DEFAULT '',
  version     INTEGER NOT NULL DEFAULT 0,
  created     TEXT NOT NULL,
  updated     TEXT NOT NULL
);
CREATE VIRTUAL TABLE IF NOT EXISTS atoms_fts USING fts5(
  content, record_id UNINDEXED, tokenize='{tokenizer}');
CREATE INDEX IF NOT EXISTS idx_atoms_state ON atoms(state, salience DESC);
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
"""

_LTM_DEFAULTS: Dict[str, Any] = {
    "persona": "",
    "forgetting_enabled": False,
    "top_k": 8,
    "inject_floor": 0.30,
    "rank_weights": {"relevance": 0.55, "salience": 0.30, "recency": 0.15},
    "stability_days": {"rule": 90, "preference": 120, "fact": 30, "episode": 5},
    "consolidation_every": 20,
    "dormant_threshold": 0.15,
    "archive_threshold": 0.05,
}

_lock = threading.Lock()
_consolidation_lock = threading.Lock()          # worker 独占（不阻塞收尾）
_dispatch_lock = threading.Lock()               # 派发闸（防重复起 worker）
_dispatch_state: Dict[str, bool] = {"running": False}


def _log(*a):
    print("[memory]", *a, flush=True)


# ---- 配置 / 开关（内核常量版，上游 _cfg 同名键同值） ------------------------

def _cfg(key: str, default: Any = None) -> Any:
    val = _LTM_DEFAULTS.get(key)
    if val is None:
        return default
    return val


def set_enabled(on: bool) -> None:
    """记忆层总开关（bridge 接 Kotlin 弱注入开关；关 = 注入与沉淀一起停）。"""
    _GATE["on"] = bool(on)


def enabled() -> bool:
    return bool(_GATE["on"])


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _days_since(ts: str) -> float:
    if not ts:
        return 0.0
    try:
        return max(0.0, (datetime.now(timezone.utc)
                         - datetime.fromisoformat(ts)).total_seconds() / 86400.0)
    except Exception:
        return 0.0


def _brain_path() -> str:
    global _BRAIN_PATH
    if not _BRAIN_PATH:
        _BRAIN_PATH = str(rp.brain_db())
    return _BRAIN_PATH


def _archive_dir() -> str:
    global _ARCHIVE_DIR
    if not _ARCHIVE_DIR:
        _ARCHIVE_DIR = str(rp.brain_archive())
    return _ARCHIVE_DIR


# ---- 语义判定（上游 llm_judge.chat_json 语义；BrainClient stdlib 实现） -----

def _extract_json(text: str) -> Optional[Any]:
    """从模型回复里抠出第一个 JSON 值（容忍代码围栏与前后缀噪声）；失败 → None。"""
    t = str(text or "").strip()
    if not t:
        return None
    if t.startswith("```"):
        t = t.strip("`")
        if t.lower().startswith("json"):
            t = t[4:].lstrip()
    dec = json.JSONDecoder()
    for i, ch in enumerate(t):
        if ch in "{[":
            try:
                val, _end = dec.raw_decode(t[i:])
                return val
            except Exception:
                continue
    return None


def _chat_json(brain_cfg: Optional[Dict[str, Any]], system: str, user: str,
               timeout: float = 60.0) -> Optional[Any]:
    """发一次补全并解析首个 JSON 值；任何失败 → ``None``（调用方降级）。

    ``brain_cfg`` 需要 ``base_url`` / ``model`` / ``api_key``（与 bridge._brain_cfg
    同构）；为空或缺 base_url 视为「无模型」，直接返回 None。
    """
    if not brain_cfg or not (brain_cfg.get("base_url") and brain_cfg.get("model")):
        return None
    try:
        from hachimi_kernel.brain_client import BrainClient
        client = BrainClient(str(brain_cfg["base_url"]),
                             str(brain_cfg.get("api_key") or ""),
                             str(brain_cfg["model"]),
                             timeout=timeout, thinking={"type": "disabled"})
        reply = client.chat([
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ], [])
        return _extract_json(reply.get("content") or "")
    except Exception:
        return None


# ---- 分词器 / 检索助手（内核恒 trigram；FTS5 不可用 → LIKE 兜底降级） -------

_FTS_STATE: Dict[str, Any] = {"ok": None}   # None=未探测 True/False=能力已知


def _fts_ok(conn: sqlite3.Connection) -> bool:
    """确保 FTS 表存在且 schema 版本匹配；FTS5/trigram 不可用 → False（降级模式）。"""
    if _FTS_STATE["ok"] is False:
        return False
    try:
        ver = None
        try:
            row = conn.execute("SELECT v FROM meta WHERE k='fts_schema_version'").fetchone()
            ver = row[0] if row else None
        except Exception:
            pass
        target = "trigram-1"
        if ver != target:
            conn.execute("DROP TABLE IF EXISTS atoms_fts")  # FTS5 无 ALTER,只能重建
            conn.execute(
                "CREATE VIRTUAL TABLE atoms_fts USING fts5("
                "content, record_id UNINDEXED, tokenize='trigram')")
            # 重建即回填：无回填 = 重建后检索恒空（工程上踩过的坑）
            try:
                conn.execute("INSERT INTO atoms_fts(record_id, content) "
                             "SELECT record_id, content FROM atoms")
            except Exception:
                pass  # 全新库 atoms 尚无数据 → 空索引即正确
            conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('fts_schema_version',?)",
                         (target,))
            conn.commit()
        _FTS_STATE["ok"] = True
        return True
    except Exception:
        # Android 旧 sqlite 缺 FTS5/trigram：进程级降级 LIKE 兜底，不再重试
        _FTS_STATE["ok"] = False
        _log("FTS5/trigram unavailable -> LIKE fallback")
        return False


def _match_expr(conn: sqlite3.Connection, query: str) -> Tuple[Optional[str], List[str]]:
    """query → (MATCH 表达式或 None, LIKE 兜底词列表)。

    trigram 模式：≥3 字符短语/3-gram OR + 2 字词 LIKE 兜底。
    """
    runs = re.findall(r"[0-9A-Za-z_㐀-鿿]+", query or "")
    fts_terms: List[str] = []
    like_terms: List[str] = []
    for run in runs:
        if len(run) >= 3:
            if run.isascii() and len(run) <= 32:
                fts_terms.append(run)
            else:
                fts_terms.extend(run[i:i + 3] for i in range(min(len(run) - 2, 24)))
        elif len(run) == 2:
            like_terms.append(run)
    match = " OR ".join('"' + t.replace('"', '""') + '"' for t in fts_terms) if fts_terms else None
    return match, like_terms


# ---- L0 会话缓冲 → L1 提炼原料 ----
_FLUSH_MIN_ROLES = 2        # buffer 少于此条不触发提炼(无内容可提炼)
_FLUSH_MAX_CHARS = 8000     # 提炼原料(对话片段)截断
_MAX_ATOMS_PER_FLUSH = 5
# buffer 上限（plan §3.3）：barge-in 频繁时（打断轮不 flush，且可能有并发通道）缓冲
# 会无限增长。超限即**滚动切段**——丢最旧的，只保留最近窗口，并记 dropped 计数。
# 之所以不是"超限就强制 flush"：flush 要跑 3 次 LLM，把它塞进消息路径等于把
# 尾延迟搬回主链路，正是本轮要去掉的东西。
_CAPTURE_MAX_MSGS = 80
_CAPTURE_MAX_CHARS = 24000
_buf_lock = threading.RLock()
_buffers: Dict[str, List[Dict[str, str]]] = {}  # pid -> [{role, content}]
_buf_dropped: Dict[str, int] = {}               # pid -> 被切掉的消息条数


def capture(project_id: str, role: str, content: str) -> None:
    """会话消息入 buffer(不提炼;提炼在任务收尾 flush_brain 时批量做)。"""
    if not enabled() or not project_id:
        return
    with _buf_lock:
        buf = _buffers.setdefault(project_id, [])
        buf.append({"role": role, "content": content})
        # 滚动切段：先按条数裁，再按字符裁（单条超长也吃得下，只要它在最新窗口里）
        dropped = 0
        while len(buf) > _CAPTURE_MAX_MSGS:
            buf.pop(0)
            dropped += 1
        while len(buf) > 1 and sum(len(m.get("content") or "") for m in buf) > _CAPTURE_MAX_CHARS:
            buf.pop(0)
            dropped += 1
        if dropped:
            _buf_dropped[project_id] = _buf_dropped.get(project_id, 0) + dropped


def buffer_stats(project_id: str) -> Dict[str, int]:
    """L0 缓冲状态（诊断面：证明切段确实在工作，而不是静默长胖）。"""
    with _buf_lock:
        buf = _buffers.get(project_id) or []
        return {"msgs": len(buf),
                "chars": sum(len(m.get("content") or "") for m in buf),
                "dropped": int(_buf_dropped.get(project_id, 0))}


def pop_capture(project_id: str) -> List[Dict[str, str]]:
    """取走 capture 缓冲（取走即清）。"""
    with _buf_lock:
        return _buffers.pop(project_id, [])


_EXTRACT_SYSTEM = (
    "你是记忆提炼器。从对话片段提取关于环境规律、用户长期偏好、工具/系统行为的陈述句事实。"
    "规则:1) 1-5 条,每条一句陈述 ≤60 字;2) 只写会跨会话仍然成立的内容,"
    "一次性的操作过程/闲聊/纯主观感受不写;3) 观察不到规律就交白卷(facts 为空数组)"
    "——交白卷是正确答案,凑数写出的假规律是错误答案。"
    "\n类别纪律:只记「发生了什么、观察到什么事实」;禁止产出对系统内部机制的因果推断"
    "(「系统为什么这样/被拒的原因/策略如何」),禁止记录 assistant 自己的拒绝行为与应对过程"
    "(如「拒绝生成某内容/按某规则处理了某请求」——拒绝规则属场景块/角色卡层,不是记忆事实);"
    "对用户行为的描述只写客观事实,不带评判标签。"
    '只返回 JSON:{"facts": [{"text": "..."}]}'
)


def _extract_detail(digest: str, brain_cfg) -> Tuple[List[Dict[str, Any]], str]:
    """提炼 → ([{text}], 原因说明)；空列表时原因写明是白卷还是失败。"""
    try:
        # timeout=60s：reasoning 模型提炼思考常超 30s，默认 10s 会随机超时→假白卷
        data = _chat_json(brain_cfg, _EXTRACT_SYSTEM, digest, timeout=60.0)
        if data is None:
            return [], "提炼失败(无模型/请求失败/解析失败)"
        out: List[Dict[str, Any]] = []
        for f in (data.get("facts") or []):
            text = str((f.get("text") if isinstance(f, dict) else f) or "").strip()
            if text:
                out.append({"text": text[:200]})
        if not out:
            return [], "白卷(模型判无可沉淀的跨会话事实)"
        return out, ""
    except Exception:
        return [], "提炼失败(异常)"


# ---- 库连接 -----------------------------------------------------------------

def open_brain() -> sqlite3.Connection:
    """打开/初始化 brain.db（schema v2，trigram；FTS 不可用自动降级）。"""
    path = _brain_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.executescript("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;")
    conn.executescript(_SCHEMA_V2.format(tokenizer="trigram"))
    _ensure_owner_column(conn)
    conn.commit()
    return conn


def _ensure_owner_column(conn: sqlite3.Connection) -> None:
    """owner 三分档列（plan §5.2-4）：``user`` / ``persona`` / ``world``。

    本轮**只加列与默认值**，判定逻辑后置——先让写链有地方落归属，等多人格/
    worker 隔离真需要时再补判据，避免现在猜一套规则、以后又推翻重来。
    老库（schema v2 早期）没有该列 → ALTER 补上，默认 ``world``（环境与任务事实）。
    """
    try:
        cols = {str(r[1]) for r in conn.execute("PRAGMA table_info(atoms)").fetchall()}
        if "owner" not in cols:
            conn.execute("ALTER TABLE atoms ADD COLUMN owner TEXT NOT NULL DEFAULT 'world'")
            conn.commit()
    except Exception:
        pass


# ---- 强度模型（ACT-R 简化；参数配置化） --------------------------------------

def live_salience(importance: float, kind: str, access_count: int, last_touch: str) -> float:
    """即时 salience：importance × exp(-days/stability)，stability 随访问增长。"""
    days = _days_since(last_touch)
    s_days = float((_cfg("stability_days") or {}).get(kind, 30))
    stability = max(s_days, 1.0) * (1.0 + math.log(1.0 + max(0, access_count)))
    try:
        val = float(importance) * pow(2.718281828459045, -days / stability)
    except Exception:
        val = float(importance)
    return max(0.0, min(1.0, val))


# ---- 写链：flush → 两段判 → 落库 ----

_VALUE_SYSTEM = (
    "你是记忆价值评审器。给你一段对话摘要和从中初提的事实候选列表。"
    "对每条候选独立判定:"
    "keep:一次性操作过程/寒暄问候/纯主观感受/对系统内部机制的因果推断"
    "(「系统为什么这样/被拒的原因/策略如何」) → keep=false;"
    "值得跨会话保留的事实/偏好/规律/事件 → keep=true 并给 kind 与 importance。"
    "kind:fact(客观事实)|preference(用户长期偏好)|rule(规律/纪律/流程)|episode(具体事件片段)。"
    "importance:0~1(影响世界/跨会话成立/用户强偏好→高;临时性/局部细节→低)。"
    '只返回 JSON:{"items": [{"idx": 序号, "keep": true, "kind": "fact", "importance": 0.5}]}'
)

_CONSIST_SYSTEM = (
    "你是记忆一致性评审器。给你「新事实」列表和「已有记忆」候选(带编号)。"
    "对每条新事实判定:"
    "与某条候选表达同一事实 → duplicate(返回 target_id,旧条将强化而非新增);"
    "与某条互补可合并为更完整一条 → merge(返回 target_id 与 merged_content,≤100 字,不丢关键信息);"
    "与某条矛盾且新信息更可信 → conflict(返回 target_id,旧条将作废、新条入库);"
    "是新信息 → novel。拿不准 → novel。"
    '只返回 JSON:{"decisions": [{"idx": 序号, "action": "novel|duplicate|merge|conflict",'
    ' "target_id": "...", "merged_content": "..."}]}'
)


def flush_brain(project_id: str, brain_cfg: Optional[Dict[str, Any]] = None,
                source_task: str = "", persona: str = "",
                msgs: Optional[List[Dict[str, str]]] = None,
                allow_write: bool = True) -> Dict[str, int]:
    """任务收尾：buffer 摘要 → 提炼 → 价值/kind/importance → 一致性四态 → brain.db。

    Args:
        msgs: 已从 capture 缓冲取走的消息（异步写链在入队时取走，worker 后续消费）。
            ``None`` = 就地 pop（同步路径 / 测试）。
        allow_write: 本轮是否"真的干了活"（L2 门控；手机无 L2，仅随 stats 透传）。
    """
    persona = persona or str(_cfg("persona") or "")
    stats: Dict[str, int] = {"extracted": 0, "kept": 0, "stored": 0, "duplicated": 0,
                             "merged": 0, "conflict": 0, "dropped": 0}
    msgs = pop_capture(project_id) if msgs is None else msgs
    if not enabled() or not brain_cfg or len(msgs) < _FLUSH_MIN_ROLES:
        return stats
    digest = "\n".join(f"[{m['role']}] {m['content']}" for m in msgs)[-_FLUSH_MAX_CHARS:]
    try:
        atoms, _note = _extract_detail(digest, brain_cfg)
    except Exception:
        atoms, _note = [], "extract error"
    atoms = atoms[:_MAX_ATOMS_PER_FLUSH]
    stats["extracted"] = len(atoms)
    if not atoms:
        # 白卷也是"收尾了一次"：巩固的 flush 计数与触发不能只挂在非空提炼上，
        # 否则语音/闲聊为主的会话永远攒不到巩固条件
        _flush_tail(project_id, brain_cfg, digest, source_task, stats, persona, allow_write)
        return stats

    # 第一段：价值 / kind / importance（一次批量 LLM）
    cand_lines = "\n".join(f"[{i}] {a.get('text', '')}" for i, a in enumerate(atoms))
    items: List[Dict[str, Any]] = []
    try:
        data = _chat_json(brain_cfg, _VALUE_SYSTEM,
                          f"对话摘要:\n{digest[:2000]}\n\n事实候选:\n{cand_lines}",
                          timeout=60.0)
        items = list((data or {}).get("items") or [])
    except Exception:
        items = []
    if not items:
        # LLM 失败 → 宁保守保留（kind=fact, importance=0.5），不丢
        items = [{"idx": i, "keep": True, "kind": "fact", "importance": 0.5}
                 for i in range(len(atoms))]
    kept: List[Tuple[int, Dict[str, Any]]] = []
    for it in items:
        try:
            idx = int(it.get("idx"))
        except Exception:
            continue
        if not (0 <= idx < len(atoms)) or not it.get("keep", True):
            stats["dropped"] += 1
            continue
        kind = str(it.get("kind") or "fact")
        if kind not in ("fact", "preference", "rule", "episode"):
            kind = "fact"
        try:
            imp = max(0.0, min(1.0, float(it.get("importance", 0.5))))
        except Exception:
            imp = 0.5
        kept.append((idx, {"text": str(atoms[idx].get("text", ""))[:200],
                           "kind": kind, "importance": imp}))
    stats["kept"] = len(kept)
    if not kept:
        # 一条都没留下也要走收尾：巩固的触发不该被"这轮没沉淀事实"卡住
        # （闲聊/全部被判为一次性 也是"收尾了一次"，同白卷分支口径）。
        _flush_tail(project_id, brain_cfg, digest, source_task, stats, persona, allow_write)
        return stats

    # 第二段：一致性四态（候选 = 各条 top-3 并集，一次批量 LLM）
    conn = open_brain()
    cand_pool: Dict[str, str] = {}
    for _, it in kept:
        for h in search_brain(it["text"], persona=persona, k=3, reinforce=False, conn=conn):
            cand_pool[str(h["record_id"])] = h["content"]
    decisions: Dict[int, Dict[str, Any]] = {}
    if cand_pool:
        cand_lines = "\n".join(f"[{rid}] {txt}" for rid, txt in cand_pool.items())
        new_lines = "\n".join(f"[{i}] {it['text']}" for i, it in kept)
        try:
            data = _chat_json(brain_cfg, _CONSIST_SYSTEM,
                              f"新事实:\n{new_lines}\n\n已有记忆候选:\n{cand_lines}",
                              timeout=60.0)
            for d in (data or {}).get("decisions") or []:
                try:
                    decisions[int(d.get("idx"))] = d
                except Exception:
                    continue
        except Exception:
            decisions = {}

    now = _now()
    applied = 0
    for i, it in kept:
        d = decisions.get(i) or {"action": "novel"}
        action = str(d.get("action") or "novel")
        rid = _new_id()
        if action in ("duplicate", "merge", "conflict"):
            target = conn.execute("SELECT record_id FROM atoms WHERE record_id=?",
                                  (str(d.get("target_id") or ""),)).fetchone()
            if target is None:
                action = "novel"
        if action == "duplicate":
            conn.execute("UPDATE atoms SET access_count=access_count+1, last_access=?, "
                         "updated=?, importance=max(importance, ?) WHERE record_id=?",
                         (now, now, it["importance"], str(d.get("target_id"))))
            stats["duplicated"] += 1
        elif action == "merge":
            merged = str(d.get("merged_content") or it["text"])[:200]
            conn.execute("UPDATE atoms SET content=?, updated=?, importance=max(importance, ?), "
                         "access_count=access_count+1, last_access=? WHERE record_id=?",
                         (merged, now, it["importance"], now, str(d.get("target_id"))))
            _fts_put(conn, str(d.get("target_id")), merged)
            stats["merged"] += 1
        elif action == "conflict":
            conn.execute("UPDATE atoms SET state='tombstone', superseded_by=?, updated=? "
                         "WHERE record_id=?", (rid, now, str(d.get("target_id"))))
            _insert_atom(conn, rid, it, persona, project_id, source_task, now)
            stats["conflict"] += 1
        else:
            _insert_atom(conn, rid, it, persona, project_id, source_task, now)
            stats["stored"] += 1
        applied += 1
    conn.commit()
    conn.close()
    stats["applied"] = applied

    _flush_tail(project_id, brain_cfg, digest, source_task, stats, persona, allow_write)
    return stats


def _flush_tail(project_id: str, brain_cfg: Optional[Dict[str, Any]], digest: str,
                source_task: str, stats: Dict[str, int], persona: str,
                allow_write: bool) -> None:
    """收尾公共尾段：巩固调度（上游此处还有 L2 场景维护——手机不移植）。"""
    maybe_consolidate(brain_cfg, persona)


def _new_id() -> str:
    import uuid
    return f"m_{uuid.uuid4().hex[:12]}"


def _fts_put(conn: sqlite3.Connection, rid: str, content: str) -> None:
    """FTS 索引同步（FTS 不可用时静默跳过——降级模式读路同样不用索引）。"""
    try:
        conn.execute("INSERT OR REPLACE INTO atoms_fts(record_id, content) VALUES(?,?)",
                     (rid, content))
    except Exception:
        pass


def _insert_atom(conn: sqlite3.Connection, rid: str, it: Dict[str, Any],
                 persona: str, origin: str, source_task: str, now: str,
                 owner: str = "world") -> None:
    conn.execute("INSERT OR REPLACE INTO atoms(record_id, content, kind, persona, origin, "
                 "source_task, importance, salience, access_count, last_access, state, "
                 "version, created, updated, owner) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                 (rid, it["text"], it.get("kind", "fact"), persona or "", origin or "",
                  source_task or "", float(it.get("importance", 0.5)),
                  float(it.get("importance", 0.5)), 0, "", "active", 1, now, now,
                  owner or "world"))
    _fts_put(conn, rid, it["text"])


# ---- 读链：融合排序 + 召回强化 ----

def search_brain(query: str, persona: str = "", k: Optional[int] = None,
                 include_dormant: bool = False, reinforce: bool = True,
                 conn: Optional[sqlite3.Connection] = None) -> List[Dict[str, Any]]:
    """融合排序检索：relevance(bm25) × salience(即时) × recency，active-only，floor 过滤。

    FTS 不可用（降级模式）→ LIKE 兜底扫描：相关度固定 0.8，其余权重不变。
    """
    if not (query or "").strip():
        return []
    owned = conn is None
    c = conn or open_brain()
    try:
        k = int(k or _cfg("top_k", 8))
        floor = float(_cfg("inject_floor", 0.30))
        weights = _cfg("rank_weights") or {}
        w_r = float(weights.get("relevance", 0.55))
        w_s = float(weights.get("salience", 0.30))
        w_t = float(weights.get("recency", 0.15))
        states = "('active','dormant')" if include_dormant else "('active')"
        pfilter = "AND (a.persona='' OR a.persona=?)" if persona else ""
        rows: List[Any] = []
        like_terms: List[str] = []
        match: Optional[str] = None
        fts_alive = _fts_ok(c)
        if fts_alive:
            match, like_terms = _match_expr(c, query)
            # trigram 模式下纯 2 字 query 只有 like_terms（无 MATCH）——不能因此直接
            # 返回空，否则 LIKE 兜底永远走不到（2 字中文词召回恒 0 的老坑）
            if not match and not like_terms:
                return []
            if match:
                params: List[Any] = [match]
                if persona:
                    params.append(persona)
                params.append(max(k * 3, 15))
                rows = c.execute(
                    f"SELECT f.record_id, f.content, bm25(atoms_fts) AS r, a.kind, a.importance, "
                    f"a.access_count, a.updated, a.last_access FROM atoms_fts f "
                    f"JOIN atoms a ON a.record_id = f.record_id "
                    f"WHERE atoms_fts MATCH ? AND a.state IN {states} {pfilter} "
                    f"ORDER BY r LIMIT ?", tuple(params)).fetchall()
        else:
            # 降级模式：LIKE 扫描（≥2 字 run 全做兜底词）
            like_terms = [r for r in re.findall(r"[0-9A-Za-z_㐀-鿿]+", query or "")
                          if len(r) >= 2][:8]
            if not like_terms:
                return []
        hits: List[Dict[str, Any]] = []
        now = _now()
        # 相关性按本次候选集排名归一（首位 1.0、末位 0.5）：FTS5 的 bm25 量级随语料规模
        # 剧烈变化（实测单库单条 -2e-06），直接 clamp 成 [0,1] 会恒≈0 → relevance 权重
        # 形同虚设、低 importance 的精确命中还会被 inject_floor 误杀
        n_rows = len(rows)
        for i, (rid, content, rank, kind, imp, acc, upd, la) in enumerate(rows):
            rel = 1.0 - 0.5 * (i / max(n_rows - 1, 1))
            last_touch = upd or la or ""
            sal = live_salience(float(imp or 0.5), kind, int(acc or 0), last_touch)
            rec = pow(2.718281828459045, -_days_since(last_touch) / 180.0)
            final = w_r * rel + w_s * sal + w_t * rec
            if final < floor:
                continue
            hits.append({"record_id": rid, "content": content, "score": round(final, 6),
                         "kind": kind, "salience": round(sal, 4)})
        hits.sort(key=lambda h: h["score"], reverse=True)
        hits = hits[:k]
        if reinforce and hits:
            now2 = _now()
            for h in hits:
                c.execute("UPDATE atoms SET access_count=access_count+1, last_access=?, "
                          "updated=? WHERE record_id=?", (now2, now2, h["record_id"]))
            c.commit()
        # LIKE 兜底（2 字词在 trigram 模式；降级模式这里是唯一召回路）；persona 过滤
        # 与主路一致，否则兜底会绕过画像隔离把别人的记忆捞进来
        if like_terms and len(hits) < k:
            lsql = ("SELECT record_id, content, kind, importance, access_count, updated, "
                    "last_access FROM atoms WHERE content LIKE ? AND state IN " + states)
            if persona:
                lsql += " AND (persona='' OR persona=?)"
            lsql += " ORDER BY updated DESC LIMIT ?"
            for kw in like_terms:
                lp: List[Any] = [f"%{kw}%"]
                if persona:
                    lp.append(persona)
                lp.append(k)
                for rid, content, kind, imp, acc, upd, la in c.execute(lsql, tuple(lp)).fetchall():
                    if all(str(rid) != str(h["record_id"]) for h in hits):
                        hits.append({"record_id": rid, "content": content, "score": 0.0,
                                     "kind": kind, "salience": 0.0,
                                     "_imp": float(imp or 0.5), "_acc": int(acc or 0),
                                     "_touch": upd or la or ""})
        if not fts_alive:
            # 降级模式：LIKE 命中无 bm25，相关度固定 0.8（其余权重不变，floor 仍生效）
            fused: List[Dict[str, Any]] = []
            for h in hits:
                if h["score"] > 0.0:
                    h.pop("_imp", None); h.pop("_acc", None); h.pop("_touch", None)
                    fused.append(h)
                    continue
                sal = live_salience(h.pop("_imp", 0.5), h.get("kind", "fact"),
                                    h.pop("_acc", 0), h.pop("_touch", ""))
                rec = pow(2.718281828459045, -_days_since("") / 180.0)
                final = w_r * 0.8 + w_s * sal + w_t * rec
                if final < floor:
                    continue
                h["score"] = round(final, 6)
                h["salience"] = round(sal, 4)
                fused.append(h)
            hits = fused[:k]
        return hits[:k]
    except Exception:
        return []
    finally:
        if owned:
            try:
                c.close()
            except Exception:
                pass


def inject_text_brain(persona: str = "", query: str = "",
                      include_dormant: bool = False) -> Tuple[str, Dict[str, Any]]:
    """L1 检索注入（brain 模式）：<relevant-memories> 块；零命中也返回 meta（可观测）。"""
    hits: List[Dict[str, Any]] = []
    try:
        hits = search_brain(query, persona=persona, include_dormant=include_dormant,
                            reinforce=True)
    except Exception:
        hits = []
    meta = {"hits": len(hits), "chars": sum(len(h["content"]) for h in hits),
            "placement": "lead_in", "store": "brain"}
    if not hits:
        return "", meta
    lines = "\n".join(f"- {h['content']}" for h in hits)
    text = ("<relevant-memories>\n以下为检索到的历史记忆,仅作参考,不代表当前任务进程:\n"
            + lines + "\n</relevant-memories>")
    return text, meta


# ---- 巩固周期（后台；备份先行；遗忘执行 gated） ----

def _consolidation_due() -> bool:
    try:
        conn = open_brain()
        try:
            row = conn.execute("SELECT v FROM meta WHERE k='last_consolidation'").fetchone()
            last = row[0] if row else ""
            cnt = int((conn.execute("SELECT v FROM meta WHERE k='flush_count'").fetchone()
                       or ["0"])[0] or 0)
            # 必须在使用 conn 之后再关：此前 close 后再 execute 会抛 ProgrammingError
            # 被 except 吞掉，导致巩固（去重/抽象/强度重算）永不触发
            total = conn.execute("SELECT COUNT(*) FROM atoms").fetchone()[0]
        finally:
            conn.close()
        if total == 0:
            return False  # 空库不巩固
        every = int(_cfg("consolidation_every", 20))
        due_time = (not last) or _days_since(last) > 1.0
        return cnt >= every or due_time
    except Exception:
        return False


def maybe_consolidate(brain_cfg: Optional[Dict[str, Any]] = None, persona: str = "") -> None:
    """任务收尾调用：满足触发条件则在后台线程跑巩固（绝不阻塞收尾）。"""
    if not enabled():
        return
    try:
        conn = open_brain()
        cnt = int((conn.execute("SELECT v FROM meta WHERE k='flush_count'").fetchone()
                   or ["0"])[0] or 0) + 1
        conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('flush_count',?)", (str(cnt),))
        conn.commit()
        conn.close()
    except Exception:
        return
    if not _consolidation_due():
        return
    # 派发闸：_consolidation_lock 由 worker 自己持有——若这里先 acquire 再让 worker
    # 去 with 同一把（非可重入）锁，worker 会永久阻塞、巩固永不执行
    with _dispatch_lock:
        if _dispatch_state["running"]:
            return
        _dispatch_state["running"] = True
    t = threading.Thread(target=_run_consolidation,
                         args=(brain_cfg or {}, persona), daemon=True)
    t.start()


def _run_consolidation(brain_cfg: Dict[str, Any], persona: str) -> None:
    try:
        _consolidation_worker(brain_cfg, persona)
    finally:
        with _dispatch_lock:
            _dispatch_state["running"] = False


def _consolidation_worker(brain_cfg: Dict[str, Any], persona: str) -> None:
    with _consolidation_lock:
        stats: Dict[str, Any] = {"ts": _now()}
        try:
            os.makedirs(_archive_dir(), exist_ok=True)
            backup = os.path.join(
                _archive_dir(),
                f"backup-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}.db")
            shutil.copyfile(_brain_path(), backup)
            stats["backup"] = backup
        except Exception:
            stats["backup"] = ""
        try:
            stats.update(_consolidate_impl(brain_cfg, persona))
        except Exception as e:
            stats["error"] = f"{type(e).__name__}: {e}"
        try:
            conn = open_brain()
            conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('last_consolidation',?)",
                         (_now(),))
            conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('flush_count','0')")
            conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('last_consolidation_stats',?)",
                         (json.dumps(stats, ensure_ascii=False)[:2000],))
            conn.commit()
            conn.close()
        except Exception:
            pass
        _log("consolidation: " + json.dumps(stats, ensure_ascii=False)[:500])


def _consolidate_impl(brain_cfg: Dict[str, Any], persona: str) -> Dict[str, Any]:
    conn = open_brain()
    out: Dict[str, Any] = {"dedup_checked": 0, "merged": 0, "abstracted": 0,
                           "conflict": 0, "downgraded": 0}
    fts_alive = _fts_ok(conn)
    rows = conn.execute(
        "SELECT record_id, content, kind, importance, access_count, updated, last_access "
        "FROM atoms WHERE state='active' ORDER BY updated DESC LIMIT 200").fetchall()
    now = _now()

    # 1) 有界去重 + 矛盾检测：近段条目 vs 各自候选（LLM 四态）
    checked = 0
    for rid, content, kind, imp, acc, upd, la in rows[:20]:
        if checked >= 8:
            break
        if not fts_alive:
            # 降级模式：内容前 12 字 LIKE 互查
            try:
                cands = conn.execute(
                    "SELECT record_id, content FROM atoms WHERE content LIKE ? "
                    "AND record_id != ? LIMIT 4",
                    ("%" + content[:12] + "%", rid)).fetchall()
            except Exception:
                cands = []
        else:
            # trigram/simple 通吃的内容子串召回：取 content 前 12 字的引号短语
            try:
                probe = content[:12]
                cands = conn.execute(
                    "SELECT record_id, content FROM atoms_fts WHERE atoms_fts MATCH ? "
                    "AND record_id != ? LIMIT 4", ('"' + probe.replace('"', '""') + '"', rid)).fetchall()
            except Exception:
                cands = []
        if not cands:
            continue
        checked += 1
        cand_lines = "\n".join(f"[{crid}] {ct}" for crid, ct in cands)
        try:
            data = _chat_json(brain_cfg, _CONSIST_SYSTEM,
                              f"新事实:\n[{rid}] {content}\n\n已有记忆候选:\n{cand_lines}",
                              timeout=60.0)
        except Exception:
            continue
        for d in (data or {}).get("decisions") or []:
            act = str(d.get("action") or "")
            tgt = str(d.get("target_id") or "")
            if act == "merge" and tgt and d.get("merged_content"):
                merged = str(d["merged_content"])[:200]
                conn.execute("UPDATE atoms SET content=?, updated=? WHERE record_id=? AND record_id!=?",
                             (merged, now, tgt, rid))
                _fts_put(conn, tgt, merged)
                conn.execute("UPDATE atoms SET state='dormant', superseded_by=?, updated=? WHERE record_id=?",
                             (tgt, now, rid))
                out["merged"] += 1
            elif act == "conflict" and tgt:
                # 以信息更全的新条为准：目标 tombstone 指向新?此处新=旧条目自身，语义取「目标作废」
                conn.execute("UPDATE atoms SET state='tombstone', superseded_by=?, updated=? "
                             "WHERE record_id=? AND record_id!=?", (rid, now, tgt, rid))
                out["conflict"] += 1

    # 2) 抽象提升：同主题 episode ≥3（以内容前 12 字粗聚）→ 1 条 rule（有界）
    episodes = conn.execute(
        "SELECT record_id, content FROM atoms WHERE state='active' AND kind='episode' "
        "ORDER BY updated DESC LIMIT 30").fetchall()
    groups: Dict[str, List[Tuple[str, str]]] = {}
    for rid, content in episodes:
        key = content[:6]
        groups.setdefault(key, []).append((rid, content))
    abstracted = 0
    for key, grp in groups.items():
        if len(grp) < 3 or abstracted >= 2:
            continue
        lines = "\n".join(f"- {c}" for _, c in grp)
        try:
            data = _chat_json(
                brain_cfg,
                "你是记忆抽象器。把同一主题的多条具体事件片段提炼为 1 条通用 rule(≤60 字,陈述句)。"
                "无法提炼(事件间无共同规律)则返回 {\"rule\": \"\"}。"
                '只返回 JSON:{"rule": "..."}',
                lines, timeout=60.0)
            rule = str((data or {}).get("rule") or "").strip()
        except Exception:
            rule = ""
        if not rule:
            continue
        rid = _new_id()
        _insert_atom(conn, rid, {"text": rule, "kind": "rule", "importance": 0.8},
                     persona, "", "consolidation", now)
        for erid, _ in grp:
            conn.execute("UPDATE atoms SET kind='episode', salience=salience*0.5, updated=? "
                         "WHERE record_id=?", (now, erid))
        abstracted += 1
    out["abstracted"] = abstracted

    # 3) 强度重算 + 状态降级（forgetting_enabled 门控；dry-run 只统计）
    fe = bool(_cfg("forgetting_enabled", False))
    dt = float(_cfg("dormant_threshold", 0.15))
    at = float(_cfg("archive_threshold", 0.05))
    for rid, content, kind, imp, acc, upd, la in rows:
        sal = live_salience(float(imp or 0.5), kind, int(acc or 0), upd or la or "")
        conn.execute("UPDATE atoms SET salience=? WHERE record_id=?", (sal, rid))
        if fe:
            age = _days_since(upd or la or "")
            state = conn.execute("SELECT state FROM atoms WHERE record_id=?",
                                 (rid,)).fetchone()
            state = state[0] if state else "active"
            if state == "active" and sal < dt and int(acc or 0) == 0 and age > 30:
                conn.execute("UPDATE atoms SET state='dormant' WHERE record_id=?", (rid,))
                out["downgraded"] += 1
            elif state in ("active", "dormant") and sal < at and age > 90:
                conn.execute("UPDATE atoms SET state='archived' WHERE record_id=?", (rid,))
                out["downgraded"] += 1
    conn.commit()
    conn.close()

    # 4) 画像重写（L3）：brain.db 新 atoms → user_profile.md（带外信号强制）
    if maintain_profile(brain_cfg, persona):
        out["profile_rewritten"] = 1
    return out


# ---- 自管 / 人工面（落盘权在内核） -------------------------------------------

def remember(text: str, kind: str = "fact", importance: float = 0.8,
             persona: str = "", brain_cfg: Optional[Dict[str, Any]] = None,
             origin: str = "", source_task: str = "remember") -> str:
    """显式写入一条记忆（agent/用户主动）。返回 record_id；失败返回空串。"""
    text = (text or "").strip()[:200]
    if not text or not enabled():
        return ""
    kind = kind if kind in ("fact", "preference", "rule", "episode") else "fact"
    try:
        importance = max(0.0, min(1.0, float(importance)))
    except Exception:
        importance = 0.5
    conn = open_brain()
    try:
        now = _now()
        rid = _new_id()
        _insert_atom(conn, rid, {"text": text, "kind": kind, "importance": importance},
                     persona or str(_cfg("persona") or ""), origin, source_task, now)
        conn.commit()
        return rid
    except Exception:
        return ""
    finally:
        conn.close()


def update_memory(record_id: str, new_text: str, persona: str = "") -> str:
    """更正记忆：旧条 tombstone(superseded_by=新条)，新条入库。返回新 record_id 或空串。"""
    new_text = (new_text or "").strip()[:200]
    if not new_text or not enabled():
        return ""
    conn = open_brain()
    try:
        row = conn.execute("SELECT record_id FROM atoms WHERE record_id=? "
                           "AND state IN ('active','dormant')", (record_id,)).fetchone()
        if row is None:
            return ""
        now = _now()
        rid = _new_id()
        _insert_atom(conn, rid, {"text": new_text, "kind": "fact", "importance": 0.7},
                     persona or str(_cfg("persona") or ""), "", "update", now)
        conn.execute("UPDATE atoms SET state='tombstone', superseded_by=?, updated=? "
                     "WHERE record_id=?", (rid, now, record_id))
        conn.commit()
        return rid
    except Exception:
        return ""
    finally:
        conn.close()


def forget(record_id: str, reason: str = "", persona: str = "") -> bool:
    """主动遗忘：state → tombstone（保留内容与原因，审计可溯）。"""
    if not enabled():
        return False
    conn = open_brain()
    try:
        cur = conn.execute(
            "UPDATE atoms SET state='tombstone', superseded_by=?, updated=? "
            "WHERE record_id=? AND state IN ('active','dormant')",
            (f"forget:{(reason or '')[:160]}", _now(), record_id))
        conn.commit()
        return cur.rowcount > 0
    except Exception:
        return False
    finally:
        conn.close()


# ---- 自检 / 数据面 ----

def overview_brain() -> Dict[str, Any]:
    conn = open_brain()
    try:
        total = conn.execute("SELECT COUNT(*) FROM atoms").fetchone()[0]
        by_state: Dict[str, int] = {}
        for st, n in conn.execute("SELECT state, COUNT(*) FROM atoms GROUP BY state"):
            by_state[st] = n
        by_kind: Dict[str, int] = {}
        for kd, n in conn.execute("SELECT kind, COUNT(*) FROM atoms GROUP BY kind"):
            by_kind[kd] = n
        last = (conn.execute("SELECT v FROM meta WHERE k='last_consolidation'").fetchone()
                or [""])[0]
        recent = [r[0] for r in conn.execute(
            "SELECT content FROM atoms WHERE state='active' ORDER BY updated DESC LIMIT 5")]
        return {"total": total, "by_state": by_state, "by_kind": by_kind,
                "last_consolidation": last, "recent": recent}
    except Exception:
        return {"total": 0, "by_state": {}, "by_kind": {},
                "last_consolidation": "", "recent": []}
    finally:
        conn.close()


def dump_atoms(limit: int = 200) -> List[Dict[str, Any]]:
    """调试/人工面：导出 atoms（状态/强度全字段，按更新时间倒序）。"""
    conn = open_brain()
    try:
        rows = conn.execute(
            "SELECT record_id, content, kind, persona, origin, source_task, importance, "
            "salience, access_count, state, superseded_by, updated, owner FROM atoms "
            "ORDER BY updated DESC LIMIT ?", (limit,)).fetchall()
        keys = ("record_id", "content", "kind", "persona", "origin", "source_task",
                "importance", "salience", "access_count", "state", "superseded_by", "updated",
                "owner")
        return [dict(zip(keys, r)) for r in rows]
    except Exception:
        return []
    finally:
        conn.close()


def hard_delete(record_id: str) -> bool:
    """人工物理删除（唯一物理删除通道；不进 agent 写入路径）。"""
    if not record_id:
        return False
    conn = open_brain()
    try:
        cur = conn.execute("DELETE FROM atoms WHERE record_id=?", (record_id,))
        try:
            conn.execute("DELETE FROM atoms_fts WHERE record_id=?", (record_id,))
        except Exception:
            pass
        conn.commit()
        return cur.rowcount > 0
    except Exception:
        return False
    finally:
        conn.close()


def hard_delete_all() -> int:
    """清空全部记忆（knowledge_clear 的 memory 口径；物理删除，幂等）。"""
    conn = open_brain()
    try:
        n = conn.execute("SELECT COUNT(*) FROM atoms").fetchone()[0]
        conn.execute("DELETE FROM atoms")
        try:
            conn.execute("DELETE FROM atoms_fts")
        except Exception:
            pass
        conn.commit()
        return int(n or 0)
    except Exception:
        return 0
    finally:
        conn.close()


# ---- L3 画像（brain.db 驱动；人工编辑可覆盖） --------------------------------

_PROFILE_TRIGGER_EVERY = 20
_PROFILE_MAX_CHARS = 2000
_PROFILE_TS_KEY = "last_profile_ts"
_PROFILE_SIGNAL_KEY = "persona_update_request"

_PROFILE_SYSTEM = (
    "你是用户画像维护器。给你「当前用户画像」和「最近沉淀的新事实」。"
    "任务:输出更新后的完整用户画像——保留仍然成立的内容,把新事实中关于"
    "用户本人/用户长期偏好/用户环境的部分合并进去,剔除已过时或与用户无关的条目;"
    "矛盾的以新事实为准。不要虚构,没有可合并的新信息就原样返回。"
    "\n类别边界:画像只记录关于用户本人的客观事实/长期偏好/习惯/背景。"
    "以下内容一律视为「与用户无关」剔除,不得合并进画像:assistant 的应对策略、"
    "拒绝规则、边界设定,以及对用户行为的评判/模式判定条款(如「用户反复索取某类"
    "内容……此类内容不生成」)——这类内容属于场景块/角色卡层;即使新事实以"
    "「优先处理」或带外信号标记出现,也照此过滤。"
    "只返回 JSON:{\"profile\": \"<完整画像全文,≤2000字符>\"}"
)


def _meta_get(conn: sqlite3.Connection, key: str) -> str:
    try:
        row = conn.execute("SELECT v FROM meta WHERE k=?", (key,)).fetchone()
        return str(row[0] or "") if row else ""
    except Exception:
        return ""


def _meta_set(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", (key, value))


def request_profile_update(reason: str) -> None:
    """带外信号：落 brain meta，下次巩固强制画像重写。"""
    reason = (reason or "").strip()
    if not reason:
        return
    conn = open_brain()
    try:
        _meta_set(conn, _PROFILE_SIGNAL_KEY, reason[:500])
        conn.commit()
    except Exception:
        pass
    finally:
        conn.close()


def maintain_profile(brain_cfg: Optional[Dict[str, Any]], persona: str = "") -> bool:
    """L3 画像重写：上次水位之后的新 atoms → LLM 增量合并 → user_profile.md（旧版 .bak）。

    - 触发：新增 active atoms ≥ ``_PROFILE_TRIGGER_EVERY``（缺省 20），
      或带外信号（``persona_update_request``）强制一次
    - LLM 失败/输出为空 → 不动现文件；信号仍消费（避免永久绕过节流）
    """
    try:
        conn = open_brain()
        try:
            signal = _meta_get(conn, _PROFILE_SIGNAL_KEY)
            last_ts = _meta_get(conn, _PROFILE_TS_KEY)
            rows = conn.execute(
                "SELECT content FROM atoms WHERE state='active' AND updated > ? "
                "ORDER BY updated DESC LIMIT 40", (last_ts,)).fetchall()
            if not signal and len(rows) < _PROFILE_TRIGGER_EVERY:
                return False
            if signal:
                _meta_set(conn, _PROFILE_SIGNAL_KEY, "")
                conn.commit()
        finally:
            conn.close()
        pp = rp.user_profile()
        old_profile = pp.read_text(encoding="utf-8") if pp.exists() else ""
        new_facts = "\n".join(f"- {c}" for (c,) in rows)
        if signal:
            new_facts = f"[场景整理请求画像更新(优先处理)]\n{signal}\n\n{new_facts}"
        data = _chat_json(
            brain_cfg, _PROFILE_SYSTEM,
            f"当前用户画像:\n{old_profile or '(空)'}\n\n最近新事实:\n{new_facts}",
            timeout=60.0)
        text = str((data or {}).get("profile") or "").strip()
        if not text:
            return False
        pp.parent.mkdir(parents=True, exist_ok=True)
        if pp.exists():
            pp.replace(pp.with_suffix(".md.bak"))  # 上一版画像 → .bak
        pp.write_text(text[:_PROFILE_MAX_CHARS], encoding="utf-8")
        conn2 = open_brain()
        try:
            _meta_set(conn2, _PROFILE_TS_KEY, _now())
            conn2.commit()
        finally:
            conn2.close()
        return True
    except Exception:
        return False
