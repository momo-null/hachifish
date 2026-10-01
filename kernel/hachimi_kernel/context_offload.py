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

# 骨架行数与字符上限：超限截断并标注（证据在轨迹里，不丢）
MAX_NODES = 40
MAX_CHARS = 1500


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
    parts.append("[%s]" % vid if vid else "[-]")
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
    for n in tree:
        if not isinstance(n, dict):
            continue
        vid = (n.get("view_id") or "").strip()
        text = (n.get("text") or n.get("desc") or "").strip()
        clickable = bool(n.get("clickable"))
        b = n.get("bounds") or [0, 0, 0, 0]
        real = len(b) == 4 and b[2] > b[0]          # 零宽僵尸节点剔除
        if not (vid or (real and (text or clickable))):
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
