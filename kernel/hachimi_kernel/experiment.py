# -*- coding: utf-8 -*-
"""experiment —— M4' 实验组条件注入（A/B/C/D）。

红线约束：
- R5 能力默认关：body 无 group/snapshot_id 时零注入（A 组/产品运行路径不变）。
- R9 实验效度：快照 SHA 校验失败拒载（宁可任务报错，不可污染处理组）；
  实验期 Curator 冻结（group 存在时 bridge 跳过一切蒸馏触发，当前蒸馏未挂载，防御性断言）。
- R4/R6：本模块只做装载与拼装，不含任何场景知识；语义复用桌面 knowledge_inject。
"""
import hashlib
import json
import os

from hachimi_kernel.brain_client import BrainError


def _snapshots_root():
    return os.path.join(os.environ.get("HACHIMI_DATA_DIR")
                        or os.path.join(os.path.expanduser("~"), ".omniagent"),
                        "snapshots")


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_snapshot(snapshot_id):
    """校验快照目录完整性（manifest.json 的 files[].sha256）。返回 manifest。"""
    root = os.path.join(_snapshots_root(), snapshot_id)
    manifest_path = os.path.join(root, "manifest.json")
    if not os.path.isfile(manifest_path):
        try:
            entries = ", ".join(sorted(os.listdir(_snapshots_root())))
        except Exception as e:
            entries = "listdir failed: %s: %s" % (type(e).__name__, e)
        raise BrainError("snapshot not found: %s (data_dir=%s, entries=[%s])"
                         % (snapshot_id, os.environ.get("HACHIMI_DATA_DIR"), entries))
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    for rel, expect in (manifest.get("files") or {}).items():
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            raise BrainError("snapshot %s missing file: %s" % (snapshot_id, rel))
        actual = _sha256_file(p)
        if actual != expect:
            raise BrainError("snapshot %s sha mismatch: %s" % (snapshot_id, rel))
    return manifest


def load_snapshot_texts(snapshot_id):
    """读快照中的记忆摘要与技能块（只读装载，不写任何文件）。

    目录约定（snapshot.py 冻结时生成）：
      manifest.json            # {"files": {rel: sha256}, "frozen_at": ...}
      memory/memory_summary.md # 全局记忆摘要（弱注入原文）
      skills/catalog.json      # [{"name", "objective_pattern", "ops", ...}]
    """
    root = os.path.join(_snapshots_root(), snapshot_id)
    memory_text = ""
    mem = os.path.join(root, "memory", "memory_summary.md")
    if os.path.isfile(mem):
        with open(mem, encoding="utf-8") as f:
            memory_text = f.read()
    skills = []
    cat = os.path.join(root, "skills", "catalog.json")
    if os.path.isfile(cat):
        with open(cat, encoding="utf-8") as f:
            skills = json.load(f)
    return memory_text, skills


def build_group_constraints(body):
    """按 body.group/snapshot_id 产出追加约束（弱注入块）；默认组返回 ""。

    - 无 group 且无 snapshot_id → ""（R5：默认关，A 组/产品路径零变化）
    - 有 snapshot_id → 先 SHA 校验（R9），失败抛 BrainError（任务以 error 终止）
    """
    group = (body or {}).get("group")
    snapshot_id = (body or {}).get("snapshot_id")
    if not group and not snapshot_id:
        return ""
    if snapshot_id:
        verify_snapshot(snapshot_id)
        memory_text, skills = load_snapshot_texts(snapshot_id)
    else:
        memory_text, skills = "", []
    # 延迟导入：仅注入路径触达知识层
    from hachimi_kernel.knowledge_inject import build_knowledge_block
    block = build_knowledge_block(memory_text, skills)
    header = "[实验组 %s] " % (group or "B") if group else ""
    return (header + block).strip()
