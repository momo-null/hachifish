# -*- coding: utf-8 -*-
"""Phase 0 spike：完整 omni 内核上机自检（spike/full-kernel-hosting 分支专用）。

五道闸全部以 ``SPIKE|GATE_X|PASS/FAIL`` 前缀打 logcat（tag=Spike），真机跑一遍即
出全部结论；任何 FAIL 附最后 1500 字符栈。本模块只做读侧验证，不写任何数据。

闸门：
- GATE_A  pydantic-core Rust 扩展在 Chaquopy 3.12 目标上加载并工作（wheel 闸）；
- GATE_B1 纯 Python 依赖面：fastapi / uvicorn / httpx / PyYAML / psutil；
- GATE_B2 agents SDK（openai-agents）——**预期阻塞**：jiter 暂无 Android wheel；
- GATE_C  上游内核导入链：utils(eager window_finder) → omni_core.local.loop.core
          → backend chat_runtime/audio_api/router_system（ctypes.windll 桩先行）；
- GATE_D  环回 uvicorn：挂 chat_runtime+audio_api+router_system，127.0.0.1 自探
          /health 与内联 ping。

零上游改动路线：ctypes.windll 桩是本模块打的（上游 3 行惰性化是 Phase 1 决策项）。
"""
import os
import sys
import threading
import time
import traceback
import types
import urllib.request

_PORT = 18471


def _log(tag, msg):
    line = "SPIKE|%s|%s" % (tag, msg)
    try:
        from java import jclass
        jclass("android.util.Log").i("Spike", line)
    except Exception:
        print(line, flush=True)


def _shim_windll():
    """ctypes.windll 桩：上游 utils/__init__ eager import window_finder（模块级
    ctypes.windll.user32）。仅 import 存活用，桩属性永不真正调用。"""
    import ctypes
    if not hasattr(ctypes, "windll"):
        ctypes.windll = types.SimpleNamespace(user32=None)


def _shim_sdks_if_missing():
    """agents / openai SDK 桩（仅当未安装时）：GATE_C 的目的是验证**内核本身**的
    导入链，而 chat_bridge/sdk_loop/pool 模块级 ``from agents import ...`` /
    ``import openai`` 会在 jiter 缺位时连带炸掉——与内核无关。桩让导入存活，
    真实 SDK 行为由 GATE_B2 把关。"""
    import importlib
    import importlib.abc
    import importlib.machinery

    missing = []
    for n in ("agents", "openai"):
        try:
            importlib.import_module(n)
        except Exception as e:
            _log("SDKCHK", "%s import FAIL %s: %s" % (n, type(e).__name__, str(e)[:150]))
            missing.append(n)
    if not missing:
        return []
    _missing = set(missing)

    class _Stub:
        """可继承、可实例化、可调用、属性链无限深入的空对象——import 存活专用。"""

        def __init__(self, *a, **k):
            pass

        def __call__(self, *a, **k):
            return _Stub()

        def __getattr__(self, name):
            return _Stub          # 返回类本身：继承（class X(agents.Model)）、链式属性都成立

        def __iter__(self):
            return iter(())

    class _Loader(importlib.abc.Loader):
        def create_module(self, spec):
            m = types.ModuleType(spec.name)
            m.__path__ = []
            m.__getattr__ = lambda name: _Stub
            return m
        def exec_module(self, module):
            pass

    class _Finder(importlib.abc.MetaPathFinder):
        def find_spec(self, fullname, path=None, target=None):
            root = fullname.split(".")[0]
            if root in _missing:
                return importlib.machinery.ModuleSpec(fullname, _Loader())
            return None

    sys.meta_path.insert(0, _Finder())
    return missing


def _importable(name):
    try:
        importlib.import_module(name)
        return True
    except Exception:
        return False


def run():
    t0 = time.time()
    _log("BOOT", "python=%s platform=%s" % (sys.version.split()[0], sys.platform))
    # CWD 切到 App 私有目录：上游 utils/logger 等会在 import 时写 ./temp（相对 CWD），
    # Android 进程 CWD=/ 只读。与 HOME/DATA_DIR 注入同型的运行环境约定，零上游改动。
    data_dir = os.environ.get("HACHIMI_DATA_DIR")
    if data_dir:
        os.chdir(data_dir)
        _log("BOOT", "cwd=%s" % data_dir)

    # ---- GATE_A: pydantic-core Rust 扩展 ----
    try:
        import pydantic
        import pydantic_core

        class _M(pydantic.BaseModel):
            x: int
            y: str = "ok"

        m = _M(x=1)
        assert m.x == 1 and m.y == "ok"
        assert _M.model_validate_json('{"x": 2}').x == 2
        _log("GATE_A", "PASS pydantic=%s core=%s" % (pydantic.VERSION,
                                                     pydantic_core.__version__))
    except Exception as e:
        _log("GATE_A", "FAIL %s: %s" % (type(e).__name__, e))
        _log("GATE_A", "TRACE %s" % _tail_trace())
        return

    # ---- GATE_B1: 纯 Python 依赖面 ----
    try:
        import fastapi
        import uvicorn
        import httpx
        import yaml
        import psutil

        _log("GATE_B1", "PASS fastapi=%s uvicorn=%s httpx=%s yaml=%s psutil=%s"
             % (fastapi.__version__, getattr(uvicorn, "__version__", "?"),
                getattr(httpx, "__version__", "?"), getattr(yaml, "__version__", "?"),
                getattr(psutil, "__version__", "?")))
    except Exception as e:
        _log("GATE_B1", "FAIL %s: %s" % (type(e).__name__, e))
        _log("GATE_B1", "TRACE %s" % _tail_trace())
        return

    # ---- GATE_B2: agents SDK（预期阻塞：jiter 无 Android wheel）----
    try:
        import agents
        _log("GATE_B2", "PASS agents=%s（jiter 竟已可用？记 Phase 1 加速）"
             % getattr(agents, "__version__", "?"))
    except Exception as e:
        kind = "EXPECTED-BLOCKER(jiter)" if "jiter" in str(e) else "UNEXPECTED"
        _log("GATE_B2", "%s %s: %s" % (kind, type(e).__name__, str(e)[:200]))

    # ---- GATE_C: 上游内核导入链 ----
    _shim_windll()
    stubbed = _shim_sdks_if_missing()
    try:
        import utils                      # eager window_finder 在桩下存活
        import omni_core.local.loop.core  # AgentCore（SDK loop 模块族）
        # 与 PC server.py 同序：先导聚合器 router_runtime（它反向聚合 chat_runtime，
        # 先导 chat_runtime 会撞循环导入——PC 上同样是这个顺序在保平安）
        import backend.api.router_runtime as _rrt
        import backend.api.routers.chat_runtime as crt
        import backend.api.routers.audio_api
        import backend.api.router_system as rsys
        _log("GATE_C", "PASS utils + omni_core.local.loop.core + "
                       "router_runtime + chat_runtime + audio_api + router_system imports ok"
                       + ("（SDK 桩: %s，真实行为由 GATE_B2 把关）" % "+".join(stubbed) if stubbed else ""))
    except Exception as e:
        _log("GATE_C", "FAIL %s: %s" % (type(e).__name__, e))
        _log("GATE_C", "TRACE %s" % _tail_trace())
        return

    # ---- GATE_D: 环回 uvicorn ----
    try:
        import fastapi as _fa

        app = _fa.FastAPI()

        @app.get("/spike-ping")
        def _ping():
            return {"ok": True}

        app.include_router(crt.router)
        app.include_router(rsys.router)
        app.include_router(
            __import__("backend.api.routers.audio_api", fromlist=["router"]).router)

        import uvicorn
        cfg = uvicorn.Config(app, host="127.0.0.1", port=_PORT,
                             log_level="warning", lifespan="off")
        server = uvicorn.Server(cfg)
        th = threading.Thread(target=server.run, daemon=True)
        th.start()
        ping_ok = health = None
        for _ in range(75):                       # 至多 15s
            time.sleep(0.2)
            if ping_ok is None:
                try:
                    with urllib.request.urlopen(
                            "http://127.0.0.1:%d/spike-ping" % _PORT, timeout=2) as r:
                        ping_ok = (r.status == 200)
                except Exception:
                    pass
            if ping_ok and health is None:
                try:
                    with urllib.request.urlopen(
                            "http://127.0.0.1:%d/health" % _PORT, timeout=2) as r:
                        health = "%s %s" % (r.status, r.read()[:100])
                except Exception as e:
                    health = "ERR %s" % e
            if ping_ok and health is not None:
                break
        if ping_ok:
            _log("GATE_D", "PASS loopback uvicorn up; /health -> %s" % health)
        else:
            _log("GATE_D", "FAIL server not responding in 15s")
            server.should_exit = True
            return
        server.should_exit = True
    except Exception as e:
        _log("GATE_D", "FAIL %s: %s" % (type(e).__name__, e))
        _log("GATE_D", "TRACE %s" % _tail_trace())
        return

    _log("DONE", "ALL REQUIRED GATES PASSED in %.1fs" % (time.time() - t0))


def _tail_trace():
    return traceback.format_exc()[-1500:].replace("\n", " || ")
