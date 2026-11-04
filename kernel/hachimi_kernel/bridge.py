# -*- coding: utf-8 -*-
"""bridge —— Kotlin 壳 ↔ Python 内核的任务执行桥（M2'）。

- register_bridge(dispatcher)：Kotlin 注入原语派发器（JSON 进出，架构 §4.2）
- start_task(body)：派发任务（script=剧本大脑[测试/演示]，或 BYOK 真脑），
  MiniLoop 经桥调用真机原语；后台线程执行，状态/指标/轨迹由此模块持有
- status()/metrics()/trajectory()/stop()：回环 HTTP 六端点的数据面

工具面 = 八原语（architecture §4.1）+ verify_done（done_when 文本探测，v1）。
"""
import json
import re
import threading
import time

from hachimi_kernel.brain_client import BrainClient, BrainError, DEFAULT_TIMEOUT
from hachimi_kernel import runtime_paths as P   # K7 数据面/知识注入路径单一来源

_state = {
    "run_id": None,
    "phase": "idle",      # idle / running / done / error
    "objective": "",
    "started": 0.0,
    "summary": None,
    "trajectory": [],
    "error": None,
}
_lock = threading.Lock()

# 八原语工具面（架构 §4.1 同构面；verify_done 由内核实现，v1 = 文本探测）
TOOL_SPECS = [
    {"name": "observe", "description": "观察当前屏幕 UI 树（结构化节点：view_id/cls/text/bounds）。"
     "建议传 {\"compact\": true}：只含有文本/可交互节点，噪声小、推荐导航用；"
     "不传参数为全树（节点多）"},
    {"name": "wait", "description": "等待界面加载（秒）——应用切换/加载转场后先 wait 再 observe",
     "parameters": {"type": "object", "properties": {"seconds": {"type": "number"}}}},
    {"name": "tap_by_id", "description": "按控件 resource-id 点击",
     "parameters": {"type": "object", "properties": {"view_id": {"type": "string"}},
                    "required": ["view_id"]}},
    {"name": "tap_xy", "description": "按归一化坐标点击 (0-1)",
     "parameters": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}}}},
    {"name": "type_text", "description": "设置聚焦可编辑控件的完整文本（替换语义，"
     "非光标插入——追加时须传入含原文的完整新文本）",
     "parameters": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
    {"name": "gesture", "description": "手势滑动，points 为归一化坐标序列；"
     "duration_ms 可选（默认 300）。长按 = 起终点相同 + duration_ms>=800",
     "parameters": {"type": "object", "properties": {"points": {"type": "array"},
      "duration_ms": {"type": "number"}}, "required": ["points"]}},
    {"name": "screenshot", "description": "全屏截图（返回宽高，PNG 落盘）"},
    {"name": "launch_app", "description": "启动应用。package 可传包名（com.example.app）"
     "或应用名（如「微信」「便签」，系统按本机应用索引自动解析）：唯一命中直接跳转"
     "（比桌面视觉搜索快一个量级）；返回 candidates 时从中选定一个，再用其 package 重试；"
     "ok=false 且无 candidates 时回退桌面搜索（press home → observe）",
     "parameters": {"type": "object", "properties": {"package": {"type": "string"}}, "required": ["package"]}},
    {"name": "press", "description": "系统按键 back/home/recents",
     "parameters": {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}},
]


def register_bridge(dispatcher):
    global _bridge
    _bridge = dispatcher
def test_brain(base_url, api_key=None, model=None):
    """设置页「测试连通」（P0 DoD3/4）：走 BrainClient 真实链路发一个最小请求，
    与正式任务同一 URL 拼接/鉴权/thinking 语义——通过即任务链路可用。"""
    t0 = time.time()
    latency = lambda: round((time.time() - t0) * 1000)
    try:
        client = BrainClient(base_url, api_key or "", model or "",
                             timeout=20, thinking={"type": "disabled"})
        reply = client.chat([{"role": "user", "content": "连通性测试，请回复 pong"}], [])
        return {"ok": True, "latency_ms": latency(),
                "reply": (reply.get("content") or "")[:80]}
    except BrainError as e:
        return {"ok": False, "latency_ms": latency(), "error": str(e)[:300]}
    except Exception as e:
        return {"ok": False, "latency_ms": latency(),
                "error": ("%s: %s" % (type(e).__name__, e))[:300]}


def test_vision(base_url, api_key=None, model=None, jpeg_b64=None):
    """设置页「测试视觉模型」（P1）：真实发一张图（Kotlin 生成纯色 JPEG）问主色。
    视觉链路（data URL 载荷）真机可用性以 ok 为准；回复文本回显给用户自行判读。"""
    t0 = time.time()
    latency = lambda: round((time.time() - t0) * 1000)
    if not jpeg_b64:
        return {"ok": False, "latency_ms": 0, "error": "no test image"}
    try:
        from hachimi_kernel.vision_client import VisionClient
        client = VisionClient(base_url, api_key or "", model or "", timeout=30)
        out = client.chat_vision("这张图片的主色是什么？用一个中文颜色词回答。", jpeg_b64)
        out["latency_ms"] = latency()
        return out
    except Exception as e:
        return {"ok": False, "latency_ms": latency(),
                "error": ("%s: %s" % (type(e).__name__, e))[:300]}


# ---------------- 任务执行（回环 /task 数据面） ----------------

_controls = {"paused": False, "stopped": False}
_runs = {}      # run_id -> {"task_id", "jsonl", "run"}（轨迹导出索引；进程内）
def status_json():
    return json.dumps(status(), ensure_ascii=False)


def session_messages_json(task_id):
    """K6 会话消息回放（V13 会话切换 / 冷启动恢复数据面）。

    同 task 的 session jsonl → [{"role","content","ts"}]（仅 user/assistant，
    保序）；工具过程不在其中（回放为对话语义，与 _session_history 注入同源）。
    task 不存在/读失败返回 ok:false（UI 保持空态，不崩）。
    """
    try:
        from hachimi_kernel.task_store import TaskStore, ProjectStore
        meta = TaskStore.get(task_id)
        if not meta:
            return json.dumps({"ok": False, "error": "task not found"}, ensure_ascii=False)
        msgs = ProjectStore.read_session(meta.get("project_id", ""),
                                         meta.get("session_id", "")) or []
        return json.dumps({"ok": True, "messages": [
            {"role": m.get("role"), "content": m.get("content"), "ts": m.get("ts")}
            for m in msgs
            if m.get("role") in ("user", "assistant") and (m.get("content") or "").strip()
        ]}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def delete_task_json(task_id):
    """删除一个历史任务（P1.5 UI 会话面板「删除」数据面）。

    清理面：task.json 索引（TaskStore.remove）+ tasks/<task_id>/ 全部资产
    （trajectory / run.json / skills / world_model 等，runtime_paths 口径一致）。
    运行中的任务拒绝删除（防误删正在执行的会话）；task 不存在返回 ok:false。
    """
    import shutil as _sh
    try:
        from hachimi_kernel.task_store import TaskStore
        from hachimi_kernel import runtime_paths as P
        # 不存在：诚实 ok=false（防 UI 误删后仍显示）
        meta = TaskStore.get(task_id)
        if meta is None:
            return json.dumps({"ok": False, "error": "task not found"},
                              ensure_ascii=False)
        # 运行中拒绝：当前 phase=running 且 run 属于该 task
        if _state.get("phase") == "running" and _state.get("run_id"):
            run = _runs.get(_state.get("run_id"))
            if run is not None and run.get("task_id") == task_id:
                return json.dumps({"ok": False, "error": "task running"},
                                  ensure_ascii=False)
        d = P.task_dir(task_id)
        if d.exists():
            _sh.rmtree(str(d), ignore_errors=True)
        TaskStore.remove(task_id)
        return json.dumps({"ok": True, "task_id": task_id}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def test_brain_json(base_url, api_key=None, model=None):
    return json.dumps(test_brain(base_url, api_key, model), ensure_ascii=False)


def test_vision_json(base_url, api_key=None, model=None, jpeg_b64=None):
    return json.dumps(test_vision(base_url, api_key, model, jpeg_b64), ensure_ascii=False)


# ---- K7 知识模块数据面（记忆/技能聚合只读；开关真源 = /api/settings，2026-11-04 起） ----


def memory_json():
    """K7c 数据面：记忆聚合只读视图。

    ``brain`` = LTM 单脑概览（对齐后的一等数据源：条数/状态/最近条目）；
    ``profile`` = L3 画像全文；``master``/``rollouts`` 为旧蒸馏线遗留文件的
    只读视图（不再更新，清空入口仍可清理）。
    """
    try:
        from hachimi_kernel import memory_brain
        brain = memory_brain.overview_brain()
        profile = ""
        try:
            from hachimi_kernel.knowledge_inject import load_profile_text
            profile = load_profile_text()
        except Exception:
            profile = ""
        master = P_memory_master_text()
        rollouts = len(list(P.memory_rollouts().glob("*.md"))) \
            if P.memory_rollouts().exists() else 0
        return json.dumps({"ok": True, "brain": brain, "profile": profile,
                           "master": master,
                           "master_chars": len(master), "rollouts": rollouts},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def skills_list_json():
    """K7c 数据面：全局技能列表（名称/匹配规则/描述 + 使用统计，V7 对齐）。"""
    try:
        from hachimi_kernel.skill_library import SkillLibrary
        import re as _re
        out = []
        for f in SkillLibrary._global_dir().glob("*.md"):
            s = SkillLibrary._parse(f)
            if s is None:
                continue
            # V7 统计：frontmatter 自维护字段（Curator 晋升时写入/模型调用时更新；
            # 缺字段 = 0，展示层不猜）。created_at 供 NEW 徽标（7 天内）。
            meta = {}
            try:
                text = f.read_text(encoding="utf-8")
                if text.startswith("---"):
                    fm = text.split("---")[1]
                    for key in ("success_count", "use_count", "created_at"):
                        m = _re.search(r"^%s:\s*(.+)$" % key, fm, _re.M)
                        if m:
                            meta[key] = m.group(1).strip()
                else:
                    meta["created_at"] = getattr(s.metadata, "created", "") or ""
            except Exception:
                meta = {}
            use = 0
            success = 0
            try:
                use = int(meta.get("use_count", 0))
                success = int(meta.get("success_count", 0))
            except Exception:
                pass
            out.append({"name": s.name,
                        "description": (getattr(s, "description", "") or "")[:200],
                        "objective_pattern": s.objective_pattern,
                        "disabled": bool(s.disable_model_invocation),
                        "use_count": use,
                        "success_count": success,
                        "created_at": meta.get("created_at", "")})
        return json.dumps({"ok": True, "skills": out}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "skills": [],
                           "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


# ---- P2 知识管理面（DoD3 启停/删除/清空 + DoD5 导出） -----------------------

def _global_skills_dir():
    """全局技能目录（惰性导入，防循环依赖；目录不存在则创建——_global_dir 语义）。"""
    from hachimi_kernel.skill_library import SkillLibrary
    return SkillLibrary._global_dir()


def _skill_file(name):
    """全局技能文件路径（_safe_filename 防目录穿越；无效名返回 None）。"""
    try:
        from hachimi_kernel.skill_library import _safe_filename
        fn = _safe_filename(str(name or "").strip())
        if not fn:
            return None
        return _global_skills_dir() / f"{fn}.md"
    except Exception:
        return None


def skill_toggle_json(name, disabled):
    """启/停技能（DoD3）：frontmatter 写 disable_model_invocation。

    停用技能不进注入块、不进模型目录（recall 的 load_skill 仍可手工加载）；
    裸 md（无 frontmatter）补一段最小 frontmatter。
    """
    try:
        f = _skill_file(name)
        if f is None or not f.exists():
            return json.dumps({"ok": False, "error": "no such skill: %s" % name},
                              ensure_ascii=False)
        flag = "true" if disabled else "false"
        text = f.read_text(encoding="utf-8")
        if text.startswith("---"):
            lines = text.split("\n")
            insert_at = None
            for i in range(1, len(lines)):
                ln = lines[i].strip()
                if ln == "---":          # frontmatter 结束：插在收尾 --- 前
                    insert_at = i
                    break
                if ln.startswith("disable_model_invocation:"):
                    lines[i] = "disable_model_invocation: %s" % flag
                    insert_at = None
                    break
            if insert_at is not None:
                lines.insert(insert_at, "disable_model_invocation: %s" % flag)
            out = "\n".join(lines)
        else:
            out = "---\nname: %s\ndisable_model_invocation: %s\n---\n\n%s" \
                  % (str(name).strip(), flag, text)
        f.write_text(out, encoding="utf-8")
        return json.dumps({"ok": True, "name": name, "disabled": bool(disabled)},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def skill_delete_json(name):
    """删除全局技能文件（DoD3）。

    skill 自 2026-10-02 起**由人工维护**，删除后不会被蒸馏自动再生成。
    """
    try:
        f = _skill_file(name)
        if f is None or not f.exists():
            return json.dumps({"ok": False, "error": "no such skill: %s" % name},
                              ensure_ascii=False)
        f.unlink()
        return json.dumps({"ok": True, "name": name}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def knowledge_clear_json(scope="memory"):
    """一键清空（DoD3）：scope=memory 清 brain.db 全部原子记忆 + 旧蒸馏线遗留
    （MEMORY.md / rollouts / summary / fact_meta / merged）；
    scope=all 再加全局技能（画像/角色卡是用户资产，不清）。"""
    try:
        cleared = {"rollouts": 0, "skills": 0, "atoms": 0}
        if scope in ("memory", "all"):
            d = P.memory_rollouts()
            if d.exists():
                for f in d.glob("*.md"):
                    f.unlink()
                    cleared["rollouts"] += 1
            for p in (P.memory_master(), P.global_memory() / "memory_summary.md",
                      P.global_memory() / "fact_meta.json",
                      P.global_memory() / "merged.json"):
                if p.exists():
                    p.unlink()
            # LTM 单脑：物理清空（唯一物理删除通道 memory_brain.hard_delete_all）
            try:
                from hachimi_kernel import memory_brain
                cleared["atoms"] = memory_brain.hard_delete_all()
            except Exception:
                pass
        if scope == "all":
            g = _global_skills_dir()
            if g.exists():
                for f in g.glob("*.md"):
                    f.unlink()
                    cleared["skills"] += 1
        print("[knowledge] cleared scope=%s %s" % (scope, cleared), flush=True)
        return json.dumps({"ok": True, "scope": scope, **cleared}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def knowledge_compact_json():
    """整理记忆（LTM 对齐后语义）：手动触发一次巩固并等结果。

    巩固 = 备份 brain.db → LLM 有界去重/矛盾检测 → 同主题 episode 抽象提升 →
    强度重算（+遗忘降级，gated）→ L3 画像重写。用户显式触发走同步路径；
    常规巩固仍由写链按 flush 计数自动调度。
    返回 ``{ok, brain}``，brain 为整理后的库概览。
    """
    try:
        from hachimi_kernel import memory_brain
        memory_brain._consolidation_worker(dict(_brain_cfg or {}), "")
        return json.dumps({"ok": True, "brain": memory_brain.overview_brain()},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def knowledge_export_json():
    """导出数据面（DoD5）：知识域全部 md 文件（skills/ + memory/ 全目录）。

    Kotlin 侧打包 zip 走系统分享；单文件 100KB 截断管控。
    """
    try:
        files = []
        for root, prefix in ((_global_skills_dir(), "skills"),
                             (P.global_memory(), "memory")):
            if not root.exists():
                continue
            for f in sorted(root.rglob("*.md")):
                if not f.is_file():
                    continue
                files.append({
                    "path": "%s/%s" % (prefix, f.relative_to(root).as_posix()),
                    "content": f.read_text(encoding="utf-8", errors="replace")[:100000],
                })
        return json.dumps({"ok": True, "files": files}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "files": [],
                           "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def rollouts_list_json(limit=50):
    """K7c 数据面：蒸馏记忆列表（倒序文件名）。

    2026-09-30 UI 反馈：记忆行要显示实际内容（非 task_id）。返回 preview=
    首行（去掉 markdown 标题井号），并保留 task_id（点击进详情定位文件）。
    """
    try:
        d = P.memory_rollouts()
        items = []
        if d.exists():
            files = sorted(d.glob("*.md"), key=lambda f: f.stat().st_mtime,
                           reverse=True)[:max(0, int(limit))]
            for f in files:
                preview = ""
                content = ""
                try:
                    text = f.read_text(encoding="utf-8", errors="ignore")
                    lines = text.splitlines()
                    for ln in lines[:6]:
                        s = ln.strip().lstrip("#").strip()
                        if s:
                            preview = s
                            break
                    content = text.strip()[:1500]
                except Exception:
                    pass
                items.append({"task_id": f.stem, "chars": f.stat().st_size,
                              "preview": preview, "content": content})
        return json.dumps({"ok": True, "rollouts": items}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "rollouts": [],
                           "error": "%s: %s" % (type(e).__name__, e)},
                          ensure_ascii=False)


def P_memory_master_text():
    """MEMORY.md 全文（截断管控 20000，对齐 knowledge_inject 口径）。"""
    try:
        p = P.memory_master()
        if not p.exists():
            return ""
        return p.read_text(encoding="utf-8")[:20000]
    except Exception:
        return ""


def trajectory_export(run_id):
    """导出 run 轨迹（JSONL 按行解析返回）；进程内索引未命中则扫描落盘目录。"""
    path = _runs.get(run_id, {}).get("jsonl")
    if not path:
        try:
            from hachimi_kernel.runtime_paths import global_omni
            for p in sorted(global_omni().glob("tasks/*/*_%s.jsonl" % _strip_r(run_id))):
                path = str(p)
                break
        except Exception:
            pass
    if not path:
        return {"ok": False, "error": "run not found: %s" % run_id}
    lines = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                lines.append(json.loads(line))
    return {"ok": True, "run_id": run_id, "path": path, "lines": lines}


def _strip_r(run_id):
    return run_id[1:] if run_id.startswith("r") else run_id


def task_trace_json(task_id):
    """任务详情数据面（R3）：某 task 全部 run 的轨迹行 + run 指标，按文件名升序
    （日期前缀即时间序）。供详情页渲染执行轨迹；每 run 轨迹行截尾 200 行防肥。
    task 目录不存在返回 ok=false（诚实空态）。"""
    runs_out = []
    try:
        from hachimi_kernel import runtime_paths as P
        d = P.task_trajectory(task_id).parent
        if not d.exists():
            return json.dumps({"ok": False, "error": "task not found: %s" % task_id,
                               "runs": []}, ensure_ascii=False)
        run_jsons = {}
        for p in d.glob("*.run.json"):
            try:
                run_jsons[p.name[:-len(".run.json")]] = json.loads(p.read_text(encoding="utf-8"))
            except Exception:
                pass
        for p in sorted(d.glob("*.jsonl"), key=lambda x: x.name):
            lines = []
            for line in p.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line:
                    try:
                        lines.append(json.loads(line))
                    except Exception:
                        pass
            rid = next((ln.get("run_id") for ln in lines if ln.get("run_id")), "")
            runs_out.append({"run_id": rid, "file": p.name,
                             "run": run_jsons.get(rid), "lines": lines[-200:]})
        return json.dumps({"ok": True, "task_id": task_id, "runs": runs_out},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e),
                           "runs": runs_out}, ensure_ascii=False)


def list_tasks_json(limit=20):
    """主界面「最近任务」数据面：TaskStore 元数据倒序 + 最近 run 指标。
    指标取 run.json（finish_run 落盘的 success/steps/brain_calls/起止时间）——
    TaskStore meta 不回写运行态（研究线口径不动），UI 侧以 run.json 为准；
    无 run 记录的 task（只建了元数据）state 保持 meta 原值。"""
    out = []
    try:
        from hachimi_kernel.task_store import TaskStore
        from hachimi_kernel import runtime_paths as P
        for m in TaskStore.list()[:limit]:
            tid = m.get("task_id")
            run = None
            try:
                d = P.task_trajectory(tid).parent
                run_files = sorted(d.glob("*.run.json"))
                if run_files:
                    with open(run_files[-1], encoding="utf-8") as f:
                        run = json.load(f)
            except Exception:
                run = None
            success = (run or {}).get("success")
            state = m.get("state", "pending")
            if success is True:
                state = "done"
            elif success is False:
                state = "failed"
            out.append({
                "task_id": tid,
                "objective": m.get("objective", ""),
                "done_when": m.get("done_when", ""),
                "state": state,
                "success": success,
                "created_at": m.get("created_at", ""),
                "steps": (run or {}).get("steps", -1),
                "wall_s": _run_wall_s(run),
                "brain_calls": (run or {}).get("brain_calls", -1),
                "runs": m.get("runs") or [],
            })
        return json.dumps({"ok": True, "tasks": out}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e),
                           "tasks": out}, ensure_ascii=False)


def _run_wall_s(run):
    """run 起止 ISO 时间差（秒）；解析失败/缺字段返回 -1（UI 显示跳过）。"""
    if not run:
        return -1.0
    s, e = run.get("start_ts"), run.get("end_ts")
    if not s or not e:
        return -1.0
    try:
        from datetime import datetime
        return round((datetime.fromisoformat(e) - datetime.fromisoformat(s)).total_seconds(), 1)
    except Exception:
        return -1.0


# ---------------- 状态面（回环端点数据源） ----------------

def status():
    with _lock:
        s = dict(_state)
    traj = s.pop("trajectory", [])
    s["steps_so_far"] = len(traj)
    # 轨迹随 status 下发（2026-09-30 修复：此前被整体裁掉，主界面结果卡时间线与
    # 「查看完整轨迹」永远空）；截尾 40 行防长任务撑肥 500ms 轮询载荷
    s["trajectory"] = traj[-40:]
    s["paused"] = _controls["paused"] if s.get("phase") == "running" else False
    return {"ok": True, **s}


def metrics():
    with _lock:
        s = dict(_state)
    return {"ok": True, "run_id": s.get("run_id"),
            "phase": s.get("phase"), "summary": s.get("summary")}


def trajectory():
    with _lock:
        return {"ok": True, "trajectory": _state.get("trajectory", [])}


def health(extra):
    return {"ok": True, **(extra or {})}
