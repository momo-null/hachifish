# -*- coding: utf-8 -*-
"""bridge —— Kotlin 壳 ↔ Python 内核的任务执行桥（M2'）。

- register_bridge(dispatcher)：Kotlin 注入原语派发器（JSON 进出，架构 §4.2）
- start_task(body)：派发任务（script=剧本大脑[测试/演示]，或 BYOK 真脑），
  MiniLoop 经桥调用真机原语；后台线程执行，状态/指标/轨迹由此模块持有
- status()/metrics()/trajectory()/stop()：回环 HTTP 六端点的数据面

工具面 = 八原语（architecture §4.1）+ verify_done（done_when 文本探测，v1）。
"""
import json
import threading
import time

from hachimi_kernel.brain_client import BrainClient, BrainError
from hachimi_kernel.mini_loop import MiniLoop

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
    {"name": "gesture", "description": "手势滑动，points 为归一化坐标序列",
     "parameters": {"type": "object", "properties": {"points": {"type": "array"}}}},
    {"name": "screenshot", "description": "全屏截图（返回宽高，PNG 落盘）"},
    {"name": "launch_app", "description": "启动应用",
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
    return tools


def _verify_done(args):
    """v1 完成校验：done_when 文本出现在当前屏幕任一节点文本中。"""
    try:
        r = json.loads(_bridge.call("observe", "{\"compact\":true}")) if _bridge \
            else {"ok": False}
        if not r.get("ok"):
            return {"passed": False, "reason": "observe unavailable"}
        blob = " ".join((n.get("text") or "") + (n.get("desc") or "")
                        for n in r.get("tree", []))
        cond = (args or {}).get("done_when", "")
        return {"passed": cond in blob, "reason": "text probe: %r" % cond[:40]}
    except Exception as e:
        return {"passed": False, "reason": "%s: %s" % (type(e).__name__, e)}


def _tools_with_verify():
    tools = _make_tools()
    tools["verify_done"] = {"description": "完成校验（内核强制门控）", "call": _verify_done}
    return tools


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
    # thinking 默认 disabled（brain_client 注释：自适应思考与多轮工具循环不兼容）
    return BrainClient(cfg["base_url"], cfg.get("api_key", ""), cfg.get("model", ""),
                       thinking=cfg.get("thinking") or {"type": "disabled"})


_brain_cfg = None


def set_brain(base_url, api_key=None, model=None):
    """Kotlin 注入 BYOK 配置（Keystore 解密后以独立参数传入，避免 JSON 转义）。"""
    global _brain_cfg
    _brain_cfg = {"base_url": base_url, "api_key": api_key, "model": model}
    return {"ok": True}


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


def request_pause():
    if _state.get("phase") == "running":
        _controls["paused"] = True
    return {"ok": True, "paused": _controls["paused"]}


def request_resume():
    _controls["paused"] = False
    return {"ok": True, "paused": False}


def _make_store(run_id, objective, done_when):
    """轨迹落盘器（桌面 schema 对齐：tasks/<task_id>/<date>_<run_id>.jsonl）。
    落盘失败不阻塞任务执行（返回 None，仅进程内轨迹）。"""
    try:
        from hachimi_kernel.task_store import TaskStore
        from hachimi_kernel.trajectory import TrajectoryStore
        meta = TaskStore.create(objective, done_when)
        store = TrajectoryStore(meta["task_id"], run_id[1:] if run_id.startswith("r") else run_id)
        return meta["task_id"], store
    except Exception as e:
        print("[task] trajectory store unavailable: %s: %s" % (type(e).__name__, e), flush=True)
        return None, None


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


def _run(body, stop_flag=_controls):
    steps = []
    objective = body.get("objective", "")
    done_when = body.get("done_when", "")
    run_id = _state["run_id"]
    task_id, store = _make_store(run_id, objective, done_when)
    with _lock:
        if task_id:
            _state["task_id"] = task_id
    _emit({"event": "start", "run_id": run_id, "objective": objective})
    if body.get("mode") == "split":
        _emit({"event": "mode", "run_id": run_id, "docked": True})
    try:
        tools = _tools_with_verify()
        brain = _make_brain(body)

        def on_step(step):
            steps.append(step)
            a = step.get("action", {})
            print("[task] step %d: %s -> ok=%s" % (step.get("step"), a.get("tool"),
                  step.get("result", {}).get("ok")), flush=True)
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
                   "screen": _perception_summary(step.get("result"))
                             if a.get("tool") == "observe" else []})
            if stop_flag["stopped"]:
                raise RuntimeError("stopped by request")
            if stop_flag["paused"]:
                _emit({"event": "paused", "run_id": run_id, "step": step.get("step")})

        def on_finish(out, _steps):
            failures = sum(1 for s in steps if s.get("result", {}).get("ok") is False)
            if store is not None:
                try:
                    rec = store.finish_run(
                        objective=objective, done_when=done_when, expected=None,
                        backend="accessibility",
                        brain_model=(_brain_cfg or {}).get("model") if body.get("script") is None
                                    else "script",
                        exec_model="accessibility",
                        success=out["status"] == "done",
                        steps=out["steps"], brain_calls=out.get("brain_calls", 0),
                        decision_steps=out.get("brain_calls", 0),
                        action_count=out["steps"], retry_count=out["task_done_rejected"],
                        failures=failures, recoveries=0,
                        reason=out.get("summary", "") if out["status"] != "done" else "")
                    with _lock:
                        _runs[run_id] = {"task_id": task_id, "jsonl": str(store.path),
                                         "run": rec.to_dict()}
                except Exception as e:
                    print("[task] run record failed: %s" % e, flush=True)
            with _lock:
                _state["phase"] = "done" if out["status"] == "done" else "error"
                _state["summary"] = out
                _state["trajectory"] = steps
            _emit({"event": "finish", "run_id": run_id, "status": out["status"],
                   "steps": out["steps"], "wall_clock_s": out.get("wall_clock_s"),
                   "summary": out.get("summary", "")})

        loop = MiniLoop(objective=objective,
                        done_when=done_when,
                        tools=tools, brain=brain,
                        constraints=body.get("constraints", ""),
                        config={"max_steps": body.get("max_steps", 12),
                                "verify_tool": "verify_done"},
                        controls=_controls,
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
                                 reason=err[:200])
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


# ---------------- 状态面（回环端点数据源） ----------------

def status():
    with _lock:
        s = dict(_state)
    traj = s.pop("trajectory", [])
    s["steps_so_far"] = len(traj)
    s["paused"] = _controls["paused"] if s.get("phase") == "running" else False
    return {"ok": True, **{k: v for k, v in s.items() if k != "trajectory"}}


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
