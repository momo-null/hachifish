# -*- coding: utf-8 -*-
"""phone_tools —— 手机 GUI 原语注册为 omni 工具面（Phase 1 kernel host v2）。

壳侧代码：工具经 accessibility 桥（Kotlin PrimitiveDispatcher，由
hachimi_kernel.bridge.register_bridge / 本模块 bind 注入）调用真机；本模块
零策略零派发——安全前置门（S2 risk）由 omni 的 wrap_tool 统一包裹。

注册方式：``@function_tool``（omni_core.tools.base）import 即注册进
TOOL_REGISTRY，AgentCore 直接取用。**不走 loader 的目录扫描**：Android 上
python 源码在 AssetFinder 虚拟路径，loader 的真实目录扫描不适用（方案文档
full_kernel_hosting_plan_2026-10-31.md §2）；unit/source 自声明等价。

参数形状与 hachimi_kernel.bridge.TOOL_SPECS 逐字对齐（同一派发器、同一批
原语）；返回 dict 的 ok 字段语义与 omni 工具约定一致。
"""
from __future__ import annotations

import ctypes
import json
import os
import types
from typing import Any, Dict, List

# 运行环境自保（本模块被 import 时即生效，先于任何 omni 导入）：CWD → App 私有目录
# （上游 utils/logger import 时写 ./temp，Android 进程 CWD=/ 只读）。
_data_dir = os.environ.get("HACHIMI_DATA_DIR") or os.path.expanduser("~")
os.environ.setdefault("HOME", _data_dir)
os.chdir(_data_dir)

# import 存活桩：本模块 import 链（omni_core.tools.base → async_bridge → utils →
# window_finder）会撞上游 eager 的 ctypes.windll——桩必须在本文件任何 omni 导入之前。
# psutil 已真装；上游 3 行惰性化（待拍板）落地后可撤。
if not hasattr(ctypes, "windll"):
    ctypes.windll = types.SimpleNamespace(user32=None)

from omni_core.tools.base import function_tool  # noqa: E402

_dispatch: Any = None   # Kotlin PrimitiveDispatcher（.call(name, args_json) -> result_json）


def bind(dispatcher: Any) -> None:
    """注入原语派发器（KernelHostService 在 Python.start 后调用；幂等）。"""
    global _dispatch
    _dispatch = dispatcher


def _call(name: str, args: Dict[str, Any]) -> Dict[str, Any]:
    if _dispatch is None:
        return {"ok": False, "error": "phone bridge not registered"}
    raw = _dispatch.call(name, json.dumps(args, ensure_ascii=False))
    return json.loads(raw)


@function_tool(description="观察当前屏幕 UI 树（结构化节点：view_id/cls/text/bounds）。"
               "建议传 compact=true：只含有文本/可交互节点，噪声小、推荐导航用；"
               "compact=false 为全树（节点多）",
               unit="hachimi_phone", source="plugin", percept="state")
def observe(compact: bool = True) -> Dict[str, Any]:
    """观察当前屏幕。

    Args:
        compact: 只返回含文本/可交互节点的精简树（推荐）
    """
    return _call("observe", {"compact": compact})


@function_tool(description="等待界面加载（秒）——应用切换/加载转场后先 wait 再 observe",
               unit="hachimi_phone", source="plugin")
def wait(seconds: float = 1.0) -> Dict[str, Any]:
    """等待若干秒。

    Args:
        seconds: 等待秒数（0~10）
    """
    seconds = min(max(float(seconds), 0.0), 10.0)
    import time
    time.sleep(seconds)
    return {"ok": True, "waited": seconds}


@function_tool(description="按控件 resource-id 点击", unit="hachimi_phone",
               source="plugin", risk="actuate")
def tap_by_id(view_id: str) -> Dict[str, Any]:
    """按控件 id 点击。

    Args:
        view_id: 控件 resource-id（observe 结果里的 view_id）
    """
    return _call("tap_by_id", {"view_id": view_id})


@function_tool(description="按归一化坐标点击 (0-1)", unit="hachimi_phone",
               source="plugin", risk="actuate")
def tap_xy(x: float, y: float) -> Dict[str, Any]:
    """按归一化坐标点击。

    Args:
        x: 横坐标 0~1
        y: 纵坐标 0~1
    """
    return _call("tap_xy", {"x": x, "y": y})


@function_tool(description="设置聚焦可编辑控件的完整文本（替换语义，非光标插入——"
               "追加时须传入含原文的完整新文本）",
               unit="hachimi_phone", source="plugin", risk="actuate")
def type_text(text: str) -> Dict[str, Any]:
    """输入文本。

    Args:
        text: 要设置的完整文本
    """
    return _call("type_text", {"text": text})


@function_tool(description="手势滑动，points 为归一化坐标序列；duration_ms 可选"
               "（默认 300）。长按 = 起终点相同 + duration_ms>=800",
               unit="hachimi_phone", source="plugin", risk="actuate")
def gesture(points: List[float], duration_ms: float = 300) -> Dict[str, Any]:
    """执行手势。

    Args:
        points: 归一化坐标序列 [[x, y], ...]
        duration_ms: 手势时长毫秒
    """
    return _call("gesture", {"points": points, "duration_ms": duration_ms})


@function_tool(description="全屏截图（返回宽高，PNG 落盘）",
               unit="hachimi_phone", source="plugin")
def screenshot() -> Dict[str, Any]:
    """截取全屏。"""
    return _call("screenshot", {})


@function_tool(description="启动应用。package 可传包名（com.example.app）或应用名"
               "（如「便签」，系统按本机应用索引自动解析）：唯一命中直接跳转；"
               "返回 candidates 时从中选定一个再用其 package 重试",
               unit="hachimi_phone", source="plugin", risk="actuate")
def launch_app(package: str) -> Dict[str, Any]:
    """启动应用。

    Args:
        package: 包名或应用名
    """
    return _call("launch_app", {"package": package})


@function_tool(description="系统按键 back/home/recents", unit="hachimi_phone",
               source="plugin", risk="actuate")
def press(key: str) -> Dict[str, Any]:
    """按系统键。

    Args:
        key: back / home / recents
    """
    return _call("press", {"key": key})
