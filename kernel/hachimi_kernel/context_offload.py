# -*- coding: utf-8 -*-
"""上下文卸载（P2-R Phase 2b，参照 TencentDB Agent Memory 的 Context Offloading）。

问题：`observe` 返回的完整 UI 树是每步最重的载荷（几十个节点 × 每节点数十字段），
且以 tool 消息形式**随每一步 LLM 调用重发**——这是 GUI 任务 Token 消耗的大头。

方案：**低层保留证据，高层保留结构**。
- 完整树照旧落轨迹（trajectory.jsonl 的 step.result，100% 可找回）；
- 发给 LLM 的 tool 消息只放「可操作骨架」：保留 view_id / 文本 / 可点性，
  砍掉零宽僵尸节点与纯容器；
- 下钻 = 模型再次 observe（本来就会做），无需显式工具。

红线：只压缩**发给模型的视图**，不碰 verify 文本探测与 Kotlin 端 tapById 的
实时查找——它们各自拿完整树，不受影响。
"""
import json
import os
import time

# 骨架行数与字符上限：超限截断并标注（证据在轨迹里，不丢）
MAX_NODES = 40
MAX_CHARS = 1500

# ---------------------------------------------------------------------------
# 低带宽模式（实验开关，2026-06-02）
#
# 动机：文献《When Skills Don't Help》提出"反馈带宽假设"——环境能给 Agent 的
# 确定性反馈越多，知识/技能层的边际价值越低。本项目的 observe 返回结构化 UI
# 树（含 view_id）、秒级反馈、动作结果确定，属高带宽环境；这被用来解释"知识
# 层在手机 GUI 上收益不显著"。
#
# 操纵：**只抹掉发给模型的 view_id**（保留文本、坐标、可点性）。环境从此不再
# 告诉模型"哪个控件是什么"，控件类知识变成"环境给不了的东西"。若假设成立，
# 同一批知识在低带宽下的收益应显著上升。
#
# 开关：设备上存在 ``files/flags/low_bw`` 即生效（adb run-as touch/rm 切换，
# 2 秒缓存），不需重编 APK。
# ---------------------------------------------------------------------------
_FLAG_CACHE = {"t": 0.0, "v": False}
_FLAG_TTL_S = 2.0


def _low_bandwidth() -> bool:
    """是否处于低带宽模式（读设备上的 flags/low_bw 文件，带 2s 缓存）。"""
    now = time.time()
    if now - _FLAG_CACHE["t"] < _FLAG_TTL_S:
        return _FLAG_CACHE["v"]
    val = False
    try:
        from hachimi_kernel.runtime_paths import global_memory
        val = os.path.exists(os.path.join(str(global_memory()), "..", "flags", "low_bw"))
    except Exception:
        val = False
    _FLAG_CACHE.update({"t": now, "v": val})
    return val


def _center(bounds):
    try:
        x0, y0, x1, y1 = bounds
        return ((x0 + x1) // 2, (y0 + y1) // 2)
    except Exception:
        return None


def _skeleton_line(node):
    vid = (node.get("view_id") or "").strip()
    text = (node.get("text") or node.get("desc") or "").strip()
    clickable = bool(node.get("clickable"))
    b = node.get("bounds") or []
    c = _center(b)
    parts = []
    # 低带宽模式：控件 id 不下发（环境不再告诉模型"哪个控件是什么"）
    if vid and not _low_bandwidth():
        parts.append("[%s]" % vid)
    else:
        parts.append("[-]")
    if text:
        t = text if len(text) <= 24 else text[:24] + "…"
        parts.append('"%s"' % t)
    if clickable:
        parts.append("click")
    if c:
        parts.append("@%d,%d" % c)
    return " ".join(parts)


def compress_observe(result, ref=""):
    """observe 结果 → 可操作骨架。非 dict / 无 tree 时原样返回。"""
    if not isinstance(result, dict):
        return result
    tree = result.get("tree")
    if not isinstance(tree, list):
        return result

    lines, total = [], 0
    kept = 0
    low_bw = _low_bandwidth()
    for n in tree:
        if not isinstance(n, dict):
            continue
        vid = (n.get("view_id") or "").strip()
        text = (n.get("text") or n.get("desc") or "").strip()
        clickable = bool(n.get("clickable"))
        b = n.get("bounds") or [0, 0, 0, 0]
        real = len(b) == 4 and b[2] > b[0]          # 零宽僵尸节点剔除
        # 低带宽模式下 id 不再可见 → 只有 id 的节点对模型无意义，按坐标/文本保留
        if not ((vid and not low_bw) or (real and (text or clickable))):
            continue
        kept += 1
        if len(lines) >= MAX_NODES:
            continue
        line = _skeleton_line(n)
        if total + len(line) > MAX_CHARS:
            lines.append("…(budget)")
            break
        lines.append(line)
        total += len(line)

    hidden = kept - len([l for l in lines if not l.startswith("…")])
    head = ("screen skeleton (%d operable nodes; full tree at trajectory %s)"
            % (kept, ref)) if ref else ("screen skeleton (%d operable nodes)" % kept)
    out = {"ok": result.get("ok", True), "screen": head, "nodes": lines}
    if hidden > 0:
        out["truncated"] = "%d more nodes omitted" % hidden
    return out


def offload_tool_result(name, result, ref=""):
    """工具结果进 LLM 消息前的统一卸载入口；非 observe 原样透传。"""
    if name != "observe":
        return result
    try:
        return compress_observe(result, ref=ref)
    except Exception:
        return result


def compact_ref_payload(result):
    """轨迹落盘用的完整 result（保留原样；独立函数便于未来加索引字段）。"""
    return result
