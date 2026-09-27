# -*- coding: utf-8 -*-
"""M4' kernel-side PC tests: group injection defaults OFF (R5), snapshot SHA
gate (R9), injection block composition via desktop knowledge_inject semantics."""
import json
import os
import sys
import tempfile

tmp = tempfile.mkdtemp()
os.environ["HACHIMI_DATA_DIR"] = tmp
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "kernel"))

from hachimi_kernel.brain_client import BrainError
from hachimi_kernel.experiment import (build_group_constraints, verify_snapshot,
                                       _sha256_file)

# ---- 1) R5: no group/snapshot -> zero injection ----
assert build_group_constraints({}) == ""
assert build_group_constraints({"objective": "x"}) == ""
assert build_group_constraints({"group": None, "snapshot_id": None}) == ""
print("1) R5 default-off: no group -> empty constraints OK")

# ---- 2) R9: missing / tampered snapshot refuses to load ----
try:
    build_group_constraints({"group": "B", "snapshot_id": "nope_v1"})
    raise AssertionError("expected BrainError for missing snapshot")
except BrainError as e:
    print("2a) missing snapshot refused:", str(e)[:60])

snap_root = os.path.join(tmp, "snapshots", "skills_v1")
os.makedirs(os.path.join(snap_root, "skills"))
os.makedirs(os.path.join(snap_root, "memory"))
with open(os.path.join(snap_root, "memory", "memory_summary.md"), "w",
          encoding="utf-8") as f:
    f.write("用户常用 org.fossify.notes 创建清单类笔记。")
skills = [{"name": "便签-创建提醒笔记", "objective_pattern": "便签|笔记",
           "ops": "tap 新建 → 输入文本 → 返回保存"}]
with open(os.path.join(snap_root, "skills", "catalog.json"), "w",
          encoding="utf-8") as f:
    json.dump(skills, f, ensure_ascii=False)
files = {
    "memory/memory_summary.md": _sha256_file(os.path.join(snap_root, "memory", "memory_summary.md")),
    "skills/catalog.json": _sha256_file(os.path.join(snap_root, "skills", "catalog.json")),
}
with open(os.path.join(snap_root, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump({"files": files, "frozen_at": "2026-09-27T10:00:00+00:00"}, f)

assert verify_snapshot("skills_v1")["files"], "valid snapshot should verify"
print("2b) valid snapshot verifies OK")

# tamper with a file -> refuse
with open(os.path.join(snap_root, "memory", "memory_summary.md"), "a",
          encoding="utf-8") as f:
    f.write("\ntampered")
try:
    build_group_constraints({"group": "B", "snapshot_id": "skills_v1"})
    raise AssertionError("expected BrainError for tampered snapshot")
except BrainError as e:
    print("2c) tampered snapshot refused:", str(e)[:60])

# ---- 3) group injection composes block via knowledge_inject ----
with open(os.path.join(snap_root, "memory", "memory_summary.md"), "w",
          encoding="utf-8") as f:
    f.write("用户常用清单类笔记。")  # restore content; manifest now stale -> rebuild
files["memory/memory_summary.md"] = _sha256_file(
    os.path.join(snap_root, "memory", "memory_summary.md"))
with open(os.path.join(snap_root, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump({"files": files, "frozen_at": "2026-09-27T10:00:00+00:00"}, f)

c = build_group_constraints({"group": "B", "snapshot_id": "skills_v1"})
assert "[实验组 B]" in c and "历史知识参考" in c and "便签-创建提醒笔记" in c, c[:200]
print("3) group injection block composed OK:", c[:80].replace("\n", " "))

print("ALL M4 KERNEL TESTS PASS; root:", tmp)
