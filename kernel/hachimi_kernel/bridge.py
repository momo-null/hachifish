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
from hachimi_kernel.mini_loop import MiniLoop
from hachimi_kernel import runtime_paths as P   # K7 数据面/知识注入路径单一来源

_bridge = None            # Kotlin 注入的原语派发器（.call(name, args_json) -> result_json）
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


# ---------------- 工具面 ----------------

def _make_tools():
    def _call(name):
        def _fn(args):
            if _bridge is None:
                return {"ok": False, "error": "bridge not registered"}
            raw = _bridge.call(name, json.dumps(args, ensure_ascii=False))
            return json.loads(raw)
        return _fn

    tools = {}
    for spec in TOOL_SPECS:
        tools[spec["name"]] = {"description": spec["description"],
                               "parameters": spec.get("parameters", {"type": "object"}),
                               "call": _call(spec["name"])}

    def _wait(args):
        seconds = min(max(float((args or {}).get("seconds", 1.0)), 0.0), 10.0)
        time.sleep(seconds)
        return {"ok": True, "waited": seconds}

    tools["wait"] = {"description": "等待界面加载", "call": _wait}

    # P1 视觉兜底：look 工具（显式问屏）+ observe 全盲自动兜底（树为空/失败时）。
    # 视觉未配置（BYOK 不填视觉端点）时两者结构化降级，行为退回 P0（DoD4）。
    tools["look"] = {"description": LOOK_SPEC["description"],
                     "parameters": LOOK_SPEC["parameters"],
                     "call": _look}

    _plain_observe = tools["observe"]["call"]

    def _observe(args):
        r = _plain_observe(args or {})
        tree = r.get("tree") or [] if isinstance(r, dict) else []
        if (not r.get("ok") or not tree) and _vision_cfg and _vision_cfg.get("base_url"):
            v = _look({})
            if v.get("ok"):
                r = dict(r)
                r["ok"] = True
                r["visual"] = v.get("description", "")
                r["vision_fallback"] = True
        return r

    tools["observe"]["call"] = _observe

    # P2-R：load_skill —— 模型按需加载技能完整操作序列。目录摘要已在 system
    # （历史知识参考），仅凭摘要操作可能不完整（桌面 T2.4 同款语义）；本工具
    # 让模型在判断任务与某技能相符时主动拉取，池子规模化后不挤爆上下文。
    tools["load_skill"] = {
        "description": _LOAD_SKILL_SPEC["description"],
        "parameters": _LOAD_SKILL_SPEC["parameters"],
        "call": _load_skill}

    # P2-R：search_skill —— 关键词检索技能库（池子规模化后，目录 top-k 之外的
    # 技能对模型不可见；本工具让模型主动找到它们）。与 load_skill 同层：纯
    # Python、宿主无关，Omni 侧可直接注册同名工具复用 skill_library.search_skills。
    tools["search_skill"] = {
        "description": _SEARCH_SKILL_SPEC["description"],
        "parameters": _SEARCH_SKILL_SPEC["parameters"],
        "call": _search_skill}

    return tools


_LOAD_SKILL_SPEC = {
    "name": "load_skill",
    "description": "按名称加载已沉淀技能的完整操作序列（技能目录见 system 的"
                   "「历史知识参考」）。当任务与某技能的适用场景相符时调用，"
                   "可省去重新探索；加载后按序列操作，与当前界面不一致处再 observe 调整。",
    "parameters": {"type": "object",
                   "properties": {"name": {"type": "string",
                    "description": "技能名称（目录里 **粗体** 的名字）"}},
                   "required": ["name"]},
}


_SEARCH_SKILL_SPEC = {
    "name": "search_skill",
    "description": "按关键词检索已沉淀的技能库（应用名、操作动作、界面描述均可）。"
                   "当你隐约记得某类操作做过、但目录里没直接列出时调用；"
                   "命中后用 load_skill 加载完整操作序列。",
    "parameters": {"type": "object",
                   "properties": {"query": {"type": "string",
                    "description": "检索词，例如应用包名或操作描述"},
                    "limit": {"type": "integer",
                    "description": "返回条数上限（默认 5）"}},
                   "required": ["query"]},
}


def _skill_payloads():
    """全局技能 → payload dict 列表（检索/注入共用同一形态）。"""
    from hachimi_kernel.skill_library import SkillLibrary
    out, seen = [], set()
    for f in sorted(SkillLibrary._global_dir().glob("*.md")):
        s = SkillLibrary._parse(f)
        if s is None or s.disable_model_invocation:
            continue
        if s.entry_id and s.entry_id in seen:
            continue
        if s.entry_id:
            seen.add(s.entry_id)
        out.append({"name": s.name, "entry_id": s.entry_id,
                    "objective_pattern": s.objective_pattern,
                    "ops": _skill_ops(s)})
    return out


def _search_skill(args):
    """search_skill 实现：走 skill_library.search_skills（与 Omni 同一检索器）。"""
    from hachimi_kernel.skill_library import search_skills, record_skill_use
    q = str((args or {}).get("query", "")).strip()
    if not q:
        return {"ok": False, "error": "query required"}
    limit = (args or {}).get("limit", 5)
    hits = search_skills(_skill_payloads(), q, limit=limit)
    if not hits:
        return {"ok": False, "error": "no skill matched: %s" % q,
                "available": [p["name"] for p in _skill_payloads()][:10]}
    for h in hits:                      # 命中计数（簿记）：让效用淘汰有输入
        try:
            record_skill_use(h["name"])
        except Exception:
            pass
    return {"ok": True, "query": q, "results": hits,
            "note": "用 load_skill(\"<名称>\") 加载该套路的文字引导后再执行。"}


def _load_skill(args):
    """load_skill 实现：纯 Python（读全局技能库，不经 Kotlin 派发）。"""
    name = str((args or {}).get("name", "")).strip()
    if not name:
        return {"ok": False, "error": "name required"}
    from hachimi_kernel.skill_library import SkillLibrary, _safe_filename
    d = SkillLibrary._global_dir()
    s = None
    exact = d / (_safe_filename(name) + ".md")
    if exact.exists():
        s = SkillLibrary._parse(exact)
    if s is None:   # 模糊回退：目录名通常很长，模型可能只记得片段
        for f in sorted(d.glob("*.md")):
            if name in f.stem:
                s = SkillLibrary._parse(f)
                break
    if s is None:
        return {"ok": False, "error": "skill not found: %s" % name,
                "available": [f.stem for f in sorted(d.glob("*.md"))][:10]}
    try:                       # 命中计数（簿记）
        from hachimi_kernel.skill_library import record_skill_use
        record_skill_use(name)
    except Exception:
        pass
    if s.playbook:
        # 引导型形态（P2-R v3）：LLM 总结的文字 playbook——模型每步都重新
        # observe 现场落点，逐帧动作序列消费不动（S 臂"消费≠受益"×3）。
        # substeps 仍是机器真源（entry_id/防幻觉校验），不再直接进 prompt。
        return {"ok": True, "name": s.name,
                "applies": (s.objective_pattern or "")[:120],
                "guide": s.playbook,
                "note": "guide 是该套路的文字引导；结合当前屏幕逐条落地，"
                        "与界面不符处 observe 后调整。"}
    return {"ok": True, "name": s.name,
            "applies": (s.objective_pattern or "")[:120],
            "steps": _skill_ops(s),
            "note": "若序列与当前界面相符可直接沿用；与界面不一致处，observe 后调整。"}


# look 的工具面描述（与 TOOL_SPECS 分离：look 由内核注入，非 Kotlin 原语）
LOOK_SPEC = {
    "name": "look",
    "description": "截图并询问视觉模型，返回当前屏幕的画面描述（UI 树看不清的自绘界面/"
                   "无控件 id 时用；正常情况优先 observe）",
    "parameters": {"type": "object",
                   "properties": {"question": {"type": "string",
                    "description": "想从画面确认什么，不传则为通用描述"}},
                   },
}


# 坐标系常量：镜像帧纵横比与真实屏差异（VirtualDisplay 排除导航条，
# tap_xy 以真实屏 2400 归一化）→ y 需乘 mirror_h/(mirror_w×(2400/1080))
_REAL_W, _REAL_H = 1080.0, 2400.0

_POINT_PATTERNS = [
    # 模型专用 grounding 标记：<｜point｜>[[198,559]]<｜/point｜>
    (u"<｜point｜>\\s*\\[\\[\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\]\\]\\s*<｜/point｜>", "px"),
    # 像素对 [198, 559] / (198, 559)（含千位空格容错）
    (u"[(\\[]\\s*([0-9]{2,4})\\s*,\\s*([0-9]{2,4})\\s*[)\\]]", "px"),
    # 归一化对 (0.22, 0.55) / [0.22, 0.55]
    (u"[(\\[]\\s*(0\\.[0-9]+)\\s*,\\s*(0\\.[0-9]+)\\s*[)\\]]", "norm"),
]


def _parse_points(text, img_w, img_h):
    """从 VLM 回答里抽坐标点，统一换算为真实屏幕归一化 [x,y]。
    像素值按镜像帧尺寸(img_w,img_h)归一；归一值 y 乘 (img_h/img_w)×(1080/2400)
    修正镜像与真实屏的高度口径差。返回 [[x,y],...]（全部 ≤1.05 才采信）。"""
    out = []
    for pat, kind in _POINT_PATTERNS:
        for m in re.finditer(pat, text or ""):
            try:
                x, y = float(m.group(1)), float(m.group(2))
            except ValueError:
                continue
            if kind == "px":
                if x > img_w * 1.2 or y > img_h * 1.2:
                    continue
                nx, ny = x / img_w, y / img_h
            else:
                nx, ny = x, y
            ny = ny * (img_h / img_w) * (_REAL_W / _REAL_H)
            if 0 <= nx <= 1.05 and 0 <= ny <= 1.05:
                out.append([round(min(nx, 1.0), 3), round(min(ny, 1.0), 3)])
        if out:
            break   # 最具体的格式优先，取第一组命中即可
    return out


def _look(args):
    """grab_frame 原语取帧 → 视觉模型 → 画面描述。视觉未配置返回结构化错误（不抛穿）。"""
    if not _vision_cfg or not _vision_cfg.get("base_url"):
        return {"ok": False, "error": "vision not configured (视觉模型未配置或已关闭)"}
    if _bridge is None:
        return {"ok": False, "error": "bridge not registered"}
    fr = json.loads(_bridge.call("grab_frame", "{}"))
    if not fr.get("ok"):
        return {"ok": False, "error": "grab_frame: %s" % fr.get("error", "?")}
    from hachimi_kernel.vision_client import VisionClient
    client = VisionClient(_vision_cfg["base_url"], _vision_cfg.get("api_key", ""),
                          _vision_cfg.get("model", ""))
    q = ((args or {}).get("question") or
         "描述当前屏幕：主要界面内容、可见文本、可交互控件（按钮/输入框/列表项）及其大致"
         "位置（上/下/左/右/中）。简明扼要，10 句以内。")
    # 坐标系约定（关键）：发给 VLM 的是等比降采样帧，归一化坐标与真实屏幕恒一致；
    # 不约定坐标系时模型会输出降采样图像素并错当屏幕像素（2026-09-29 实测脱靶根因）
    q += "\n(坐标约定：如需给出某控件位置，给出它在图中的像素坐标 [x,y] 即可，" \
         "系统会自动换算到真实屏幕。)"
    out = client.chat_vision(q, fr["jpeg_b64"])
    if out.get("ok"):
        resp = {"ok": True, "description": out.get("text", ""),
                "grab_ms": fr.get("ms"), "frame_age_ms": fr.get("frame_age_ms"),
                "vision_ms": out.get("latency_ms")}
        # 程序化换算：把 VLM 的点位答案变成 tap_xy 可直接用的归一化坐标
        pts = _parse_points(out.get("text", ""), fr.get("w") or 1, fr.get("h") or 1)
        if pts:
            resp["points"] = pts
            resp["description"] = (resp["description"] +
                                   "\n[系统换算] 可直接 tap_xy 的归一化坐标: %s"
                                   % json.dumps(pts[0])).strip()
        return resp
    return out


def _verify_done(args):
    """v1 完成校验：done_when 文本出现在当前屏幕任一节点文本中。
    2026-09-30 起仅在用户显式填写完成判据时被接线（verify_fn 门控）；
    判据留空 = 信任大脑（桌面语义），不再走目标关键词弱探测。"""
    try:
        r = json.loads(_bridge.call("observe", "{\"compact\":true}")) if _bridge \
            else {"ok": False}
        if not r.get("ok"):
            return {"passed": False, "reason": "observe unavailable"}
        blob = " ".join((n.get("text") or "") + (n.get("desc") or "")
                        for n in r.get("tree", []))
        cond = (args or {}).get("done_when", "")
        if cond:
            return {"passed": cond in blob, "reason": "text probe: %r" % cond[:40]}
        # 空判据防御分支（正常链路不会走到）：按桌面语义信任大脑
        return {"passed": True, "reason": "空判据：信任大脑（桌面语义）"}
    except Exception as e:
        return {"passed": False, "reason": "%s: %s" % (type(e).__name__, e)}


# ---------------- 大脑 ----------------

class ScriptedBrain(object):
    """剧本大脑：按序吐出预置动作（演示/对拍/无 key 场景）。"""

    def __init__(self, script):
        self.script = list(script)

    def chat(self, messages, tools):
        if not self.script:
            return {"content": None, "tool_calls": [{"name": "task_done",
                                                     "args": {"summary": "script exhausted"}}]}
        call = self.script.pop(0)
        return {"content": None, "tool_calls": [call]}


def _make_brain(body):
    if body.get("script"):
        return ScriptedBrain(body["script"])
    from hachimi_kernel.brain_client import BrainClient
    cfg = body.get("brain") or _brain_cfg
    if not cfg or not cfg.get("base_url"):
        raise BrainError("no brain configured (BYOK 未设置)")
    print("[task] brain: %s model=%s" % (cfg["base_url"], cfg["model"]), flush=True)
    # thinking 默认 disabled（brain_client 注释：自适应思考与多轮工具循环不兼容）。
    # timeout 取 brain_client.DEFAULT_TIMEOUT（单一出处）：网关长尾实测超过 120s
    # 仍未返回并报废整轮任务（2026-10-01 便签任务 15 步/247s 白跑），抬到 240s 并
    # 配合 chat() 内的退避重试。
    return BrainClient(cfg["base_url"], cfg.get("api_key", ""), cfg.get("model", ""),
                       timeout=DEFAULT_TIMEOUT,
                       thinking=cfg.get("thinking") or {"type": "disabled"})


_brain_cfg = None


def set_brain(base_url, api_key=None, model=None):
    """Kotlin 注入 BYOK 配置（Keystore 解密后以独立参数传入，避免 JSON 转义）。"""
    global _brain_cfg
    _brain_cfg = {"base_url": base_url, "api_key": api_key, "model": model}
    try:   # 检索选档与技能身份判定都用这个 brain（语义判断不归脚本）
        from hachimi_kernel.brain_client import BrainClient
        from hachimi_kernel.skill_library import set_retrieval_brain
        set_retrieval_brain(BrainClient(
            base_url, api_key or "", model or "", timeout=DEFAULT_TIMEOUT,
            thinking={"type": "disabled"}))
    except Exception as e:
        print("[brain] retrieval brain unavailable: %s" % e, flush=True)
    return {"ok": True}


_vision_cfg = None   # P1 视觉兜底端点；None/空 base_url = 关闭（行为退 P0，DoD4）


def set_vision(base_url, api_key=None, model=None):
    """Kotlin 注入视觉模型配置（可选；base_url 为空即关闭视觉兜底）。"""
    global _vision_cfg
    _vision_cfg = ({"base_url": base_url, "api_key": api_key, "model": model}
                   if base_url else None)
    return {"ok": True, "vision_enabled": _vision_cfg is not None}


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
_narrative = None  # Kotlin 注入的叙事推送器（悬浮面板数据源）


def register_narrative(sink):
    global _narrative
    _narrative = sink


def _emit(event):
    """叙事事件推给 Kotlin 悬浮面板（面板未装/PC 侧静默跳过）。"""
    if _narrative is None:
        return
    try:
        _narrative.push(json.dumps(event, ensure_ascii=False))
    except Exception:
        pass


def start_task(body_json):
    """body_json: {objective, done_when, script?, brain?, max_steps?}（JSON 字符串，
    Kotlin 直传原始 body）→ 后台线程跑 MiniLoop。"""
    global _state
    body = json.loads(body_json) if isinstance(body_json, str) else dict(body_json or {})
    with _lock:
        if _state["phase"] == "running":
            return {"ok": False, "error": "task already running"}
        _controls["paused"] = False
        _controls["stopped"] = False
        _state = {"run_id": "r%d" % int(time.time() * 1000), "phase": "running",
                  "objective": body.get("objective", ""), "started": time.time(),
                  "summary": None, "trajectory": [], "error": None}
    t = threading.Thread(target=_run, args=(body,), daemon=True)
    t.start()
    return {"ok": True, "run_id": _state["run_id"]}


def request_stop():
    _controls["stopped"] = True
    _controls["paused"] = False
    return {"ok": True}


# ---- JSON 包装（产品 UI 用）：Chaquopy PyObject.toString() 是 python-repr 而非 JSON，
# Kotlin 侧 JSONObject 解析不了；回环 HTTP 保持 repr 语义不动（harness 依赖）。 ----

def start_task_json(body_json):
    return json.dumps(start_task(body_json), ensure_ascii=False)


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


def request_pause():
    if _state.get("phase") == "running":
        _controls["paused"] = True
    return {"ok": True, "paused": _controls["paused"]}


def request_resume():
    _controls["paused"] = False
    return {"ok": True, "paused": False}


def _group_constraints(body, objective):
    """M4' 实验组注入（R5 默认关 / R9 快照 SHA 校验）：无 group 字段零变化。
    实验期 Curator 冻结 = 组任务标记 _frozen_run，蒸馏触发点见 on_finish 防御断言。"""
    try:
        from hachimi_kernel.experiment import build_group_constraints
        block = build_group_constraints(body)
        if block and (body or {}).get("group"):
            with _lock:
                _frozen_run.add(_state.get("run_id"))
        if body and body.get("constraints"):
            return (block + "\n" + body["constraints"]).strip() if block \
                else body["constraints"]
        return block
    except Exception as e:
        # 注入失败（含 SHA 不匹配）→ 任务以 error 终止，不静默降级到无注入
        raise BrainError("group injection failed: %s" % e)


_frozen_run = set()


# ---- K1a 统一入口：会话历史（ProjectStore session jsonl，桌面 P2.2 同款） ----

def _session_history(task_id, limit=40):
    """同 task 跨轮历史 → user/assistant 交替消息列表（MiniLoop.history）。

    读取失败/无历史返回空列表（首轮）。tool/system 角色不进模型上下文
    （工具结果已在本轮 run 内自洽，历史只承载对话语义——桌面同构）。
    """
    try:
        from hachimi_kernel.task_store import TaskStore, ProjectStore
        meta = TaskStore.get(task_id)
        if not meta:
            return []
        msgs = ProjectStore.read_session(meta.get("project_id", ""),
                                         meta.get("session_id", ""), limit)
        out = []
        for m in msgs or []:
            role = m.get("role")
            content = (m.get("content") or "").strip()
            if role in ("user", "assistant") and content:
                out.append({"role": role, "content": content})
        return out
    except Exception as e:
        print("[task] session history unavailable: %s: %s" % (type(e).__name__, e), flush=True)
        return []


def _append_session(task_id, role, content):
    """本轮 user/assistant 消息落盘（会话单一真源；失败不阻塞执行）。"""
    if not task_id or not (content or "").strip():
        return
    try:
        from hachimi_kernel.task_store import TaskStore, ProjectStore
        meta = TaskStore.get(task_id)
        if not meta:
            return
        ProjectStore.append_message(meta.get("project_id", ""),
                                    meta.get("session_id", ""),
                                    role, content, task_id=task_id)
    except Exception as e:
        print("[task] session append failed: %s: %s" % (type(e).__name__, e), flush=True)


def _make_store(run_id, objective, done_when, task_id=None):
    """轨迹落盘器（桌面 schema 对齐：tasks/<task_id>/<date>_<run_id>.jsonl）。
    task_id 传入且存在时复用既有任务（「继续对话」= 同 task 追加 run）；
    否则新建任务。落盘失败不阻塞任务执行（返回 None，仅进程内轨迹）。"""
    try:
        from hachimi_kernel.task_store import TaskStore
        from hachimi_kernel.trajectory import TrajectoryStore
        meta = TaskStore.get(task_id) if task_id else None
        if meta is None:
            meta = TaskStore.create(objective, done_when)
        store = TrajectoryStore(meta["task_id"], run_id[1:] if run_id.startswith("r") else run_id)
        return meta["task_id"], store
    except Exception as e:
        print("[task] trajectory store unavailable: %s: %s" % (type(e).__name__, e), flush=True)
        return None, None


def _continuation_context(task_id, limit=8):
    """「继续对话」背景（R3）：同一 task 追加 run 时，把上一轮轨迹尾部压缩成
    约束注入，消除跨 run 失忆——新 run 不至于对已做过的事从零瞎找。
    读不到历史/解析失败返回空串（不阻塞任务）。"""
    try:
        from hachimi_kernel import runtime_paths as P
        d = P.task_trajectory(task_id).parent
        if not d.exists():
            return ""
        # 新 run 的轨迹文件在 _make_store 阶段才创建，此处 glob 只会拿到上一轮的历史
        files = sorted(d.glob("*.jsonl"), key=lambda p: p.stat().st_mtime)
        if not files:
            return ""
        lines = []
        for x in files[-1].read_text(encoding="utf-8").splitlines():
            x = x.strip()
            if x:
                try:
                    lines.append(json.loads(x))
                except Exception:
                    pass
        acted = [ln for ln in lines if ln.get("action")]
        if not acted:
            return ""
        meta = {}
        rj = sorted(d.glob("*.run.json"), key=lambda p: p.stat().st_mtime)
        if rj:
            try:
                meta = json.loads(rj[-1].read_text(encoding="utf-8"))
            except Exception:
                meta = {}
        rows = []
        for ln in acted[-limit:]:
            a = ln.get("action") or {}
            r = ln.get("result") or {}
            rows.append("#%s %s %s -> %s" % (
                ln.get("step"), a.get("tool"),
                json.dumps(a.get("args"), ensure_ascii=False)[:60],
                "ok" if r.get("ok") is True else ("err: %s" % str(r.get("error", ""))[:60])))
        return ("(续话背景) 本任务是同一任务 ID 下的新一轮。上一轮目标「%s」共执行 %d 步，"
                "结束状态 %s；最近动作：%s。当前屏幕即上一轮结束时的状态，先 observe 再动手，"
                "已完成的事不要重做。"
                % (str(meta.get("objective", ""))[:80], len(acted),
                   "成功" if meta.get("success") else "未完成",
                   "；".join(rows)))
    except Exception as e:
        print("[task] continuation context unavailable: %s" % e, flush=True)
        return ""


def _perception_summary(result):
    """observe 结果 → 感知摘要（前 5 条非空文本/描述，每条 ≤24 字符）。
    分屏控制台的「感知描述」数据源；解析失败静默降级为空。"""
    try:
        tree = (result or {}).get("tree") or []
        out = []
        for n in tree:
            t = ((n.get("text") or "") + " " + (n.get("desc") or "")).strip()
            if t:
                out.append(t[:24])
            if len(out) >= 5:
                break
        return out
    except Exception:
        return []


def _persona_block():
    """角色卡注入（K0，redesign_plan）：character.md 随 system prompt 注入。

    knowledge_inject.load_character_text 已做截断管控与异常兜底（不存在/异常
    返回空串 = 零注入，不影响主任务执行）；此处再兜一层，保证接线失败绝不
    拖垮任务。内容 run 内由 MiniLoop._system_prompt 逐次拼接（同一文本，
    前缀缓存友好）。
    """
    try:
        from hachimi_kernel.knowledge_inject import load_character_text
        return load_character_text()
    except Exception as e:
        print("[task] persona unavailable: %s" % e, flush=True)
        return ""


# ---- K7 知识模块接线（注入 / Curator 挂钩 / 数据面） ----

# 弱注入开关（Kotlin set_knowledge_enabled 注入；默认开 = 桌面 P2 定稿口径）
_knowledge_enabled = {"on": True}


def set_knowledge_enabled(enabled):
    """Kotlin 设置页「弱注入」开关落 kernel（true=注入 memory/技能摘要）。"""
    _knowledge_enabled["on"] = bool(enabled)
    return {"ok": True, "knowledge_enabled": _knowledge_enabled["on"]}


def _skill_ops(s) -> str:
    """Skill.substeps → 紧凑操作序列（注入块 ops 字段；Skill dataclass 无 ops）。

    P2-R Phase 2：内容性参数（text）显示为 ``<text>`` —— 上次任务的字面值对
    新任务是噪声且有诱导照抄风险；结构性参数（view_id / package）保留。
    """
    parts = []
    for ss in (s.substeps or []):
        t, a = ss.tool, (ss.args or {})
        if t == "launch_app":
            parts.append("launch_app(%s)" % (a.get("package") or "?"))
        elif t == "tap_by_id":
            parts.append("tap_by_id(%s)" % (a.get("view_id") or "?"))
        elif t == "type_text":
            parts.append("type_text(<text>)")
        elif t == "tap_xy":
            parts.append("tap_xy(%.2f,%.2f)" % (a.get("x", 0), a.get("y", 0)))
        else:
            parts.append(t)
    return " → ".join(parts)


def _knowledge_parts(objective: str = ""):
    """弱注入块 + 元数据（K7a / P2 DoD2）：返回 ``(block, meta)``。

    P2-R Phase 2：按 objective 做相关度排序取 top-k + 字符预算；条目不再
    携带整段 objective 原文（旧版注入块 ~60% 字符是这份冗余）。
    meta 供主界面「本次注入了哪些知识」指示：memory 有无、技能名列表、块字符数；
    开关关 / 无任何内容 → 空串（零注入，prompt 逐字节不变）。
    """
    if not _knowledge_enabled["on"]:
        return "", {"memory": False, "skills": [], "chars": 0}
    try:
        from hachimi_kernel.knowledge_inject import (
            load_memory_text, load_memory_items, build_knowledge_block)
        skills = []
        mem = ""
        try:
            mem = load_memory_text()
        except Exception:
            mem = ""
        try:
            from hachimi_kernel.skill_library import SkillLibrary
            # 全局目录直读 + _parse（构造器需合法 task_id，全局列表面板/注入
            # 无 task 上下文——与 knowledge_inject.build_skill_catalog 同款）。
            # P2-R Phase 1：按 entry_id 去重（防御性；写入端已去重，这里兜住
            # 旧数据/手工文件造成的重复注入）。
            seen_ids = set()
            for f in SkillLibrary._global_dir().glob("*.md"):
                s = SkillLibrary._parse(f)
                if s is not None and not s.disable_model_invocation:
                    if s.entry_id and s.entry_id in seen_ids:
                        continue
                    if s.entry_id:
                        seen_ids.add(s.entry_id)
                    skills.append({"name": s.name,
                                   "entry_id": s.entry_id,
                                   "objective_pattern": s.objective_pattern,
                                   "ops": _skill_ops(s)[:200],
                                   "guide": (s.playbook or "")})
        except Exception:
            skills = []
        block = build_knowledge_block(mem, skills, objective=objective or "")
        return block, {"memory": bool(mem.strip()),
                       "skills": [s["name"] for s in skills],
                       "facts": load_memory_items(),
                       "chars": len(block)}
    except Exception as e:
        print("[task] knowledge block unavailable: %s: %s" % (type(e).__name__, e),
              flush=True)
        return "", {"memory": False, "skills": [], "chars": 0}


def _knowledge_block():
    """弱注入块（K7a）：memory summary + 全局技能摘要 → build_knowledge_block。"""
    return _knowledge_parts()[0]


def _make_curator(task_id):
    """Curator 工厂（K7b 挂钩注入点；测试可 monkeypatch）。"""
    from hachimi_kernel.curator import Curator
    return Curator(task_id)


def _curator_after_finish(task_id, run_record):
    """任务收尾后的知识维护（K7b）：蒸馏/prune/复核——**绝不拖垮主流程**。

    实验组冻结（R9）由调用方判定后跳过；Curator 自身启发式、无 brain 依赖。
    P2-R：注入可选蒸馏大脑（facts LLM 提取用）——构造/注入失败 = 纯启发式，
    任何异常都不拖垮主流程。
    """
    if not task_id or run_record is None:
        return
    try:
        curator = _make_curator(task_id)
        try:
            cfg = dict(_brain_cfg or {})
            if cfg.get("base_url"):
                from hachimi_kernel.brain_client import BrainClient
                curator.brain = BrainClient(
                    cfg["base_url"], cfg.get("api_key", ""), cfg.get("model", ""),
                    timeout=90, thinking={"type": "disabled"})
        except Exception as e:
            print("[curator] facts brain unavailable: %s" % e, flush=True)
        report = curator.run_once(run_record=run_record)
        print("[curator] task=%s pruned=%s errors=%d"
              % (task_id, report.pruned_files, len(report.errors)), flush=True)
    except Exception as e:
        print("[curator] failed (task continues): %s: %s" % (type(e).__name__, e),
              flush=True)


def memory_json():
    """K7c 数据面：记忆聚合只读视图（对齐父 memory_api GET /memory）。"""
    try:
        master = P_memory_master_text()
        rollouts = len(list(P.memory_rollouts().glob("*.md"))) \
            if P.memory_rollouts().exists() else 0
        return json.dumps({"ok": True, "master": master,
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
    """一键清空（DoD3）：scope=memory 清 MEMORY.md+rollouts+summary；
    scope=all 再加全局技能（画像/角色卡是用户资产，不清）。"""
    try:
        cleared = {"rollouts": 0, "skills": 0}
        if scope in ("memory", "all"):
            d = P.memory_rollouts()
            if d.exists():
                for f in d.glob("*.md"):
                    f.unlink()
                    cleared["rollouts"] += 1
            for p in (P.memory_master(), ):
                if p.exists():
                    p.unlink()
            # 注入视图同步清空（regenerate_summary 在 MEMORY.md 缺失时清空并返 0）
            try:
                from hachimi_kernel.curator import regenerate_summary
                regenerate_summary()
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
    """整理知识：回溯合并存量重复 + 按容量边界淘汰（P2-R §8.2）。

    - 合并：``_merge_memory_sections`` 的语义去重只作用于**新增**条目（保护人工
      编辑），调参前沉淀的重复会一直留在库里；
    - 淘汰：知识是**有槽位的缓存**，超容时按「印证次数 + 新鲜度」淘汰最差的，
      并把过期 rollout / run 资产一并清理（手机端不是企业服务，必须有上限）。

    返回 ``{ok, removed, pruned}``；removed=0 / pruned=0 表示当前无需整理。
    """
    try:
        from hachimi_kernel.curator import compact_memory_master, maintain_capacity
        before = 0
        p = P.memory_master()
        if p.exists():
            before = len(p.read_text(encoding="utf-8").splitlines())
        removed = compact_memory_master()
        cap = maintain_capacity()
        print("[knowledge] compact: removed=%d duplicates, capacity=%s"
              % (removed, cap), flush=True)
        return json.dumps({"ok": True, "removed": removed, "lines_before": before,
                           "pruned": cap}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e),
                           "removed": 0}, ensure_ascii=False)


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


def _run(body, stop_flag=_controls):
    steps = []
    # K1a 统一入口：message 优先（会话式）；无 message = 旧表单 objective 路径
    message = (body.get("message") or "").strip()
    objective = message or body.get("objective", "")
    done_when = body.get("done_when", "")
    run_id = _state["run_id"]
    # 「继续对话」：body 带 task_id = 同任务追加 run（内核自动携带上一轮轨迹背景）
    reuse = body.get("task_id") or None
    cont_ctx = _continuation_context(reuse) if reuse else ""
    # 首条消息（无 task_id）由 _make_store 自动建 task（objective=首条消息，
    # 桌面「先立会话、名字后回填」同语义）；有 task_id 恢复会话历史
    task_id, store = _make_store(run_id, objective, done_when, reuse)
    history = _session_history(task_id) if (message and task_id) else []
    with _lock:
        if task_id:
            _state["task_id"] = task_id
    # 本轮 user 消息落盘（会话单一真源；统一入口形态才记）
    if message and task_id:
        _append_session(task_id, "user", message)
    # start 带 task_id：前端捕获后续轮同会话续发（start_task 异步建 task，
    # 返回值拿不到——事件通道是唯一可靠来源）
    _emit({"event": "start", "run_id": run_id, "objective": objective,
           "task_id": task_id or ""})
    if body.get("mode") == "split":
        _emit({"event": "mode", "run_id": run_id, "docked": True})
    else:
        # foreground 强制回到浮条形态（2026-09-27 实测：docked 残留跨 run 存续，
        # 底部按钮条吞掉目标 App 底部触摸——时钟类任务 tab 栏不可点）
        _emit({"event": "mode", "run_id": run_id, "docked": False})
    try:
        # verify 不进模型工具面（只作 loop 强制门控）：防模型绕过 task_done 自调校验刷假绿
        tools = _make_tools()
        # 任务级工具白名单（only_tools）：封闭任务（如 DoD3 视觉夹具）硬性收窄工具面。
        # prompt 约束挡不住漂移——r1790692433817 实证：模型受挫后自调 screenshot/observe/
        # press/launch_app 烧穿墙钟。verify_fn 走注入通道不经 tools，过滤不影响校验
        only = body.get("only_tools")
        if only:
            dropped = sorted(set(tools) - set(only))
            tools = {k: v for k, v in tools.items() if k in set(only)}
            print("[task] only_tools kept %s, dropped %s" % (sorted(tools), dropped), flush=True)
        brain = _make_brain(body)

        def on_step(step):
            steps.append(step)
            a = step.get("action", {})
            with _lock:
                # status() 数据面：主界面「当前动作」行（P0 DoD1）+ 运行期步数实时可见
                _state["last_action"] = {"step": step.get("step"), "tool": a.get("tool"),
                                         "args": a.get("args"),
                                         "ok": step.get("result", {}).get("ok")}
                _state["trajectory"] = steps
            if store is not None:
                try:
                    store.log_step(state="device", observation={},
                                   action=a, result=step.get("result"),
                                   metrics={"retry": step.get("retry", 0)},
                                   verified=bool(step.get("verified")))
                except Exception as e:
                    print("[task] trajectory log failed: %s" % e, flush=True)
            _emit({"event": "step", "run_id": run_id, "step": step.get("step"),
                   "tool": a.get("tool"), "args": a.get("args"),
                   "ok": step.get("result", {}).get("ok"),
                   "error": step.get("result", {}).get("error"),
                   # K2：进度分母（前端算 n/total；浮窗进度环同源）
                   "total": int(body.get("max_steps", 24)),
                   "screen": _perception_summary(step.get("result"))
                             if a.get("tool") == "observe" else []})
            if stop_flag["stopped"]:
                raise RuntimeError("stopped by request")
            if stop_flag["paused"]:
                _emit({"event": "paused", "run_id": run_id, "step": step.get("step")})

        def on_finish(out, _steps):
            failures = sum(1 for s in steps if s.get("result", {}).get("ok") is False)
            # K1a：本轮 assistant 回复落盘（chat=口播答案 / done=完成说明；
            # incomplete 落 summary 供下一轮「上次没做完」上下文）
            if message and task_id and (out.get("summary") or "").strip():
                _append_session(task_id, "assistant", out["summary"])
            if store is not None:
                try:
                    rec = store.finish_run(
                        objective=objective, done_when=done_when, expected=None,
                        backend="accessibility",
                        brain_model=(_brain_cfg or {}).get("model") if body.get("script") is None
                                    else "script",
                        exec_model="accessibility",
                        # chat = 对话轮（K1c 语义）：对话成功即成功，不算任务失败
                        success=out["status"] in ("done", "chat"),
                        steps=out["steps"], brain_calls=out.get("brain_calls", 0),
                        decision_steps=out.get("brain_calls", 0),
                        action_count=out["steps"], retry_count=out["task_done_rejected"],
                        failures=failures, recoveries=0,
                        reason=out.get("summary", "") if out["status"] not in ("done", "chat") else "",
                        # R10：终态语义与结果文本随 run 落盘（会话回放的唯一真源，
                        # 此前成功 run 只留 success=True，回放端拿不到 status/summary）
                        status=out.get("status", ""), summary=out.get("summary", ""))
                    with _lock:
                        _runs[run_id] = {"task_id": task_id, "jsonl": str(store.path),
                                         "run": rec.to_dict()}
                except Exception as e:
                    print("[task] run record failed: %s" % e, flush=True)
            with _lock:
                _state["phase"] = "done" if out["status"] in ("done", "chat") else "error"
                _state["summary"] = out
                _state["trajectory"] = steps
            # K 闭环挂载点（桌面 tool_loop._finish → Curator 触发，K7b 接线）。
            # R9：实验期冻结——组任务（_frozen_run 命中）禁止蒸馏
            with _lock:
                frozen = _state.get("run_id") in _frozen_run
            if frozen:
                print("[task] CURATOR SUPPRESSED (experiment freeze)", flush=True)
            elif task_id and not _knowledge_enabled["on"]:
                # P2 DoD1：设置里关掉知识 = 注入与沉淀一起停（风险触发即总开关）
                print("[task] CURATOR OFF (knowledge disabled)", flush=True)
            elif task_id:
                # 只对真实任务蒸馏（chat 轮 run_record 同样传入——Curator
                # 内部按步数阈值短路，短对话零成本跳过）
                try:
                    rec_dict = rec.to_dict()
                except Exception:
                    rec_dict = None   # store 落盘失败分支：无 run 记录则不蒸馏
                _curator_after_finish(task_id, rec_dict)
            _emit({"event": "finish", "run_id": run_id, "status": out["status"],
                   "steps": out["steps"], "wall_clock_s": out.get("wall_clock_s"),
                   "summary": out.get("summary", ""),
                   "objective": objective})   # K3：前端终态面板回显标题

        # 完成判断（2026-09-30 对齐桌面 OmniAgent 语义）：仅在用户显式填写完成判据时
        # 启用机械校验（done_when 文本探测）；留空 = 信任大脑（模型自判完成），
        # 不再做目标关键词弱探测（强制写目标的假拒通道，2026-09-30 用户反馈移除）
        verify = _verify_done if (done_when or "").strip() else None
        constraints = _group_constraints(body, objective)
        if cont_ctx:
            constraints = ((constraints + "\n" + cont_ctx).strip()
                           if constraints else cont_ctx)

        def on_reply(content):
            # K3：口播/答案 → message 事件（手机端 assistant 气泡；穿插口播同型）
            _emit({"event": "message", "run_id": run_id, "content": content})

        # P2 DoD2：注入指示事件——主界面显示「本次注入了哪些知识」
        #（块算一次，事件与 config 共用；开关关/无内容 = 空块零注入）。
        # P2-R Phase 2：objective 参与相关度排序（结构化字段匹配 + 词元重叠）。
        kblock, kmeta = _knowledge_parts(objective=objective)
        _emit({"event": "knowledge", "run_id": run_id,
               "enabled": _knowledge_enabled["on"],
               "memory": kmeta["memory"],
               "skills_names": "、".join(kmeta["skills"]),
               "chars": kmeta["chars"]})

        loop = MiniLoop(objective=objective,
                        done_when=done_when,
                        tools=tools, brain=brain,
                        constraints=constraints,
                        # 默认 24 与内核 DEFAULT_MAX_STEPS 对齐（12 是实验期口径，
                        # 便签类任务实测需 ~15 步，产品 UI 不传 max_steps 会撞墙）；
                        # wall_clock_s 可由 /task body 覆盖（视觉任务网关慢，需放宽）
                        config={"max_steps": body.get("max_steps", 24),
                                "wall_clock_s": body.get("wall_clock_s", 900),
                                "verify_tool": "verify_done",
                                "persona": _persona_block(),
                                # K7a：弱注入块（memory summary + 技能摘要；
                                # 开关关/无内容 = 空串零注入）
                                "knowledge": kblock},                        controls=_controls,
                        verify_fn=verify,
                        # K1a 统一入口：历史 + 本轮消息（闲聊/任务大脑自决，K1b）
                        history=history,
                        message=message,
                        on_reply=on_reply,
                        on_step=on_step, on_finish=on_finish)
        loop.run()
    except Exception as e:
        err = "%s: %s" % (type(e).__name__, e)
        if store is not None:
            try:
                store.finish_run(objective=objective, done_when=done_when, expected=None,
                                 backend="accessibility",
                                 brain_model="error", exec_model="accessibility",
                                 success=False, steps=len(steps),
                                 brain_calls=0, decision_steps=0, action_count=len(steps),
                                 retry_count=0, failures=len(steps), recoveries=0,
                                 reason=err[:200], status="error")
            except Exception:
                pass
        with _lock:
            _state["phase"] = "error"
            _state["error"] = err
            _state["trajectory"] = steps
        _emit({"event": "error", "run_id": run_id, "error": err})


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
