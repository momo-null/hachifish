# -*- coding: utf-8 -*-
"""omni_host —— 环回内核宿主（Phase 1 kernel host v2）。

把完整 omni 内核（上游真身）以 FastAPI+uvicorn 起在 127.0.0.1，壳（Kotlin
remote client）与 Tier 2 遥控共用同一 wire contract——Tier 1 = 环回远程。

启动序（每一处都是真机实测得来的必要项，见方案文档 §Phase 0）：
1. HOME/CWD 注入：Path.home() 与上游 utils/logger 的 ./temp 都落 App 私有目录；
2. 配置注入：``_seed_settings`` 读-改-写 ``config.save_settings``（memory 开 +
   ``runtime.tools.plugin_dirs`` 指向壳从 assets 落盘的插件根，并为每个插件包播种
   ``enabled: false``——R5 能力默认关；安全档位由设置页经 /api/settings 持久化）
   + ``config.save_models_config``（BYOK → models.json，模型路由真源，仅目录空时）；
3. 导入顺序：**先聚合器 router_runtime 再 chat_runtime**——chat_runtime 的
   兼容 shim 反向 import router_runtime，PC 靠 server.py 的导入顺序保平安；
4. 工具面：import hachimi_kernel.phone_tools（@function_tool import 即注册）；
   能力插件面由上游 loader 按 plugin_dirs 装载（Kotlin PluginAssets 先落盘）；
5. uvicorn 后台线程，仅绑 127.0.0.1，等 /health 就绪后返回。

幂等：start 重复调用直接返回已启动；stop 供服务销毁时收尾。
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.request
from typing import Any, Dict, Optional

_PORT = 18471
_server: Optional[Any] = None
_thread: Optional[threading.Thread] = None
_started = False
_lock = threading.Lock()


def _log(msg: str) -> None:
    # ROM 会整档丢弃 app 的 logcat（Oplus 实测）——同步落一份文件（排障生命线）
    try:
        import os as _os
        import time as _time
        path = _os.path.join(_os.environ.get("HACHIMI_DATA_DIR", "."), "omni_host.log")
        with _os.fdopen(_os.open(path, _os.O_WRONLY | _os.O_CREAT | _os.O_APPEND,
                                 0o600), "a") as f:
            f.write(_time.strftime("%m-%d %H:%M:%S ") + msg + chr(10))
    except Exception:
        pass
    try:
        from java import jclass
        jclass("android.util.Log").i("OmniHost", "omni_host: %s" % msg)
    except Exception:
        print("[omni_host] %s" % msg, flush=True)


def _shim_windll() -> None:
    """ctypes.windll 桩：上游 utils/__init__ eager import window_finder（模块级
    ctypes.windll.user32 + psutil——psutil 已真装，windll 仅 import 存活用）。
    上游 3 行惰性化（待拍板）落地后本桩可撤。"""
    import ctypes
    import types
    if not hasattr(ctypes, "windll"):
        ctypes.windll = types.SimpleNamespace(user32=None)


def prepare() -> Dict[str, Any]:
    """运行环境预备（**零 omni 导入**，Kotlin 在 bind/import 任何重模块前调用）：
    HOME → Path.home()；CWD → App 私有目录（上游 utils/logger import 时写 ./temp）。
    """
    data_dir = os.environ.get("HACHIMI_DATA_DIR") or os.path.expanduser("~")
    os.environ.setdefault("HOME", data_dir)
    os.chdir(data_dir)
    # TLS 信任：httpx2/openai SDK 自建 ssl context 走默认验证路径（设备上
    # /usr/local/ssl/cert.pem 不存在 → CERTIFICATE_VERIFY_FAILED）。
    # SSL_CERT_FILE 指向 certifi（AssetFinder 有真实文件）后全链路可验。
    try:
        import certifi
        os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    except Exception:
        pass
    return {"ok": True, "home": os.environ.get("HOME"), "cwd": os.getcwd()}


def _seed_plugin_defaults(plugin_dir: str) -> list:
    """R5「能力默认关」：给插件根下每个包播种 ``plugins/<name>.yaml`` ``enabled: false``。

    只在文件不存在时写——用户经「知识 → 工具」做的选择优先级最高，重启不覆盖。
    单包失败只记日志（装载面照常降级，不阻断启动）。
    """
    out: list = []
    try:
        from pathlib import Path
        import yaml
        from omni_core.local.runtime_paths import plugin_config_file
    except Exception as e:
        _log("plugin default seed skipped (import): %s" % e)
        return out
    try:
        for d in sorted(Path(plugin_dir).iterdir()):
            try:
                if not d.is_dir() or not (d / "plugin.py").is_file():
                    continue
                if not d.name.isidentifier():
                    continue
                path = plugin_config_file(d.name)
                if path.exists():
                    continue
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(yaml.safe_dump({"enabled": False},
                                               allow_unicode=True, sort_keys=False),
                                encoding="utf-8")
                out.append(d.name)
            except Exception as e:
                _log("plugin default seed failed for %s: %s" % (d, e))
    except Exception as e:
        _log("plugin default seed failed: %s" % e)
    return out


def _seed_settings(payload: Dict[str, Any]) -> Dict[str, Any]:
    """启动期设置播种——**读-改-写**（save_settings 是整份覆盖，凭空构造会把用户
    经 ``PUT /api/settings`` 持久化的其他节一并冲掉；security.mode 曾因此丢失）。

    只种两件：
    - ``runtime.knowledge.memory.enabled = true``（LTM 记忆是当前主线）；
    - ``runtime.tools.plugin_dirs = [<壳落盘的插件根>]``——loader 的真实目录扫描
      需要真文件系统路径（Chaquopy 源码在 app.imy 归档里，扫不出目录），插件树由
      Kotlin ``PluginAssets.materialize`` 从 assets 落到 filesDir 后交给我们；
      同时为每个包播种 ``enabled: false``（R5，默认关，UI 显式开）。

    Returns: 实际生效的播种摘要（进 omni_host.log，真机排障用）。
    """
    import config as omni_config
    seed: Dict[str, Any] = {"runtime": {"knowledge": {"memory": {"enabled": True}}}}
    summary: Dict[str, Any] = {"memory": True, "plugin_dir": "", "seeded_off": []}
    plugin_dir = str(payload.get("plugin_dir") or "").strip()
    if plugin_dir and os.path.isdir(plugin_dir):
        seed["runtime"]["tools"] = {"plugin_dirs": [plugin_dir]}
        summary["plugin_dir"] = plugin_dir
        summary["seeded_off"] = _seed_plugin_defaults(plugin_dir)
    try:
        merged = omni_config.deep_merge(omni_config.load_settings() or {}, seed)
    except Exception:
        merged = seed
    omni_config.save_settings(merged)
    return summary


def start(brain_cfg_json: str = "") -> Dict[str, Any]:
    """启动环回内核（幂等）。

    Args:
        brain_cfg_json: 壳侧 JSON，含 BYOK 配置 {"base_url","api_key","model",
            "vision_base_url","vision_api_key","vision_model"}（可空 = 无模型，
            内核仍可起，chat 会报无模型），以及插件根 ``plugin_dir``（可空 =
            不暴露插件面，只剩 core 工具 + 手机 GUI 原语）。
    """
    global _server, _thread, _started
    with _lock:
        if _started:
            return {"ok": True, "port": _PORT, "already": True}

    # 0) import 存活桩（必须在任何 omni/utils 导入之前）
    _shim_windll()
    # langsmith 压缩缺 zstandard（C 扩展不进手机包）→ 官方开关关掉压缩链路
    os.environ.setdefault("LANGSMITH_DISABLE_RUN_COMPRESSION", "true")

    # 1) 运行环境（幂等：prepare 已由 Kotlin 先行调用，这里兜底）
    data_dir = os.environ.get("HACHIMI_DATA_DIR") or os.path.expanduser("~")
    os.environ.setdefault("HOME", data_dir)
    os.chdir(data_dir)
    _log("env ready: HOME=%s cwd=%s" % (os.environ.get("HOME"), os.getcwd()))

    # 2) 配置注入（走内核自身 API：deepMerge/缓存失效由它负责）
    import config as omni_config
    payload: Dict[str, Any] = {}
    if (brain_cfg_json or "").strip():
        try:
            parsed = json.loads(brain_cfg_json)
            payload = parsed if isinstance(parsed, dict) else {}
        except Exception as e:
            _log("payload json invalid (ignored): %s" % e)
            payload = {}
    _log("settings seeded: %s" % _seed_settings(payload))
    if payload.get("base_url") or payload.get("model"):
        cfg = payload
        # 播种只在模型目录为空时进行（2026-11-04）：目录（models.json）自多端点
        # 管理上线起是用户经 /api/runtime/models 维护的唯一真源——无条件覆盖会
        # 把设置页配好的多端点在每次重启时冲回单端点 BYOK。
        try:
            _existing = omni_config.load_models_config()
        except Exception:
            _existing = {}
        if (_existing or {}).get("providers"):
            _log("models catalog already seeded (providers present); skip BYOK inject")
        else:
            pid = "phone_byok"
            models: list = [{"id": cfg.get("model"), "label": cfg.get("model")}]
            if (cfg.get("vision_model") or "").strip():
                models.append({"id": cfg.get("vision_model"),
                               "label": cfg.get("vision_model"), "vision": True})
            omni_config.save_models_config({
                "providers": {pid: {
                    "label": "phone BYOK",
                    "base_url": cfg.get("base_url") or "",
                    "api_key": cfg.get("api_key") or "",
                    "models": models,
                }},
                "defaults": {"main": "%s/%s" % (pid, cfg.get("model"))},
            })
    omni_config.reload_config()
    _log("config injected")

    # 3) 工具面：手机 GUI 原语（import 即注册 TOOL_REGISTRY）
    import hachimi_kernel.phone_tools  # noqa: F401

    # 4) 导入序（PC server.py 同款）+ 环回 app
    import backend.api.router_runtime  # noqa: F401  （聚合器先导，保 chat_runtime 反向 shim）
    from backend.api.router_runtime import router as runtime_router
    from backend.api.routers.audio_api import router as audio_router
    from backend.api.router_system import router as system_router
    # 手机页面数据面所需：设置读写（完全访问开关/蒸馏开关）+ 调试记忆视图（知识栏）
    from backend.api.router_settings import router as settings_router
    from backend.api.routers.debug_api import router as debug_router

    from fastapi import FastAPI
    app = FastAPI(title="omni-host", docs_url=None, redoc_url=None)
    app.include_router(runtime_router)
    app.include_router(audio_router)
    app.include_router(system_router)
    app.include_router(settings_router)
    app.include_router(debug_router)

    # 审批 sink（standard 档：审批卡经 SSE 推到壳 → GateActivity 应答 → REST 决议）。
    # 未注册时内核回落 AutoDeny（fail-closed），所有危险动作被拒——必须安装。
    try:
        from backend.services.approvals import install_approval_sink
        install_approval_sink()
        _log("approval sink installed")
    except Exception as e:
        _log(f"approval sink install failed (AutoDeny fallback): {e}")

    @app.get("/debug-net")
    async def _debug_net():
        """环回诊断：TLS/httpx/httpx2 三层到模型端点的连通性（仅 127.0.0.1 可达）。"""
        out: Dict[str, Any] = {}
        target = "https://discovery-api.intern-ai.org.cn/v1/models"
        try:
            import socket
            import ssl
            ctx = ssl.create_default_context()
            s = ctx.wrap_socket(
                socket.create_connection(("discovery-api.intern-ai.org.cn", 443), timeout=8),
                server_hostname="discovery-api.intern-ai.org.cn")
            out["ssl"] = {"ok": True, "ver": s.version(),
                          "subject": str(s.getpeercert().get("subject"))[:120]}
            s.close()
        except Exception as e:
            out["ssl"] = {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
        for lib in ("httpx", "httpx2"):
            try:
                mod = __import__(lib)
                r = mod.get(target, timeout=8)
                out[lib] = {"ok": True, "status": r.status_code}
            except Exception as e:
                out[lib] = {"ok": False,
                            "err": "%s: %s" % (type(e).__name__, str(e)[:160]),
                            "cause": repr(getattr(e, "__cause__", None))[:200]}
        try:
            import certifi
            out["certifi"] = {"where": certifi.where(),
                              "exists": os.path.exists(certifi.where())}
            import httpx2 as _h2
            r = _h2.get(target, timeout=8, verify=certifi.where())
            out["httpx2_explicit_ca"] = {"ok": True, "status": r.status_code}
        except Exception as e:
            out["httpx2_explicit_ca"] = {"ok": False,
                                         "err": "%s: %s" % (type(e).__name__, str(e)[:160])}
        import ssl as _ssl
        p = _ssl.get_default_verify_paths()
        out["ssl_paths"] = {"cafile": p.cafile or "", "openssl_cafile": p.openssl_cafile or ""}
        return out

    import uvicorn
    _server = uvicorn.Server(uvicorn.Config(
        app, host="127.0.0.1", port=_PORT, log_level="warning", lifespan="off"))
    _thread = threading.Thread(target=_server.run, name="omni-host", daemon=True)
    _thread.start()

    # 5) 就绪验证：**以 uvicorn 自身的 started 标志为准**（bind 失败时 uvicorn
    # 静默退出——只探 /health 会误报到残留的旧监听者上，真机踩过）。
    for _ in range(60):                       # 至多 12s
        if getattr(_server, "started", False):
            break
        if not _thread.is_alive():
            return {"ok": False, "error": "uvicorn thread exited (port busy?)"}
        time.sleep(0.2)
    else:
        return {"ok": False, "error": "uvicorn not started in 12s"}
    for _ in range(10):                       # 路由就绪（started 后立即可应答）
        try:
            with urllib.request.urlopen(
                    "http://127.0.0.1:%d/health" % _PORT, timeout=1) as r:
                if r.status == 200:
                    with _lock:
                        _started = True
                    _log("kernel up on 127.0.0.1:%d" % _PORT)
                    return {"ok": True, "port": _PORT}
        except Exception:
            time.sleep(0.2)
    return {"ok": False, "error": "kernel server not responding"}


def stop() -> Dict[str, Any]:
    """停环回内核（服务销毁时调用；daemon 线程本会随进程退，这里优雅收）。"""
    global _started
    if _server is not None:
        _server.should_exit = True
    with _lock:
        _started = False
    return {"ok": True}


def status() -> Dict[str, Any]:
    return {"ok": True, "started": _started, "port": _PORT}
