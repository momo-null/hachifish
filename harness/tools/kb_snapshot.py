# -*- coding: utf-8 -*-
"""冻结/恢复设备端知识快照（P2-R Phase 0）。

用途：A/B 实验两组必须吃**同一份**知识，消除组间漂移（2026-10-01 实测教训：
B 组连续跑时 Curator 会不断追加新沉淀，"B 组有知识"实际每轮都在变）。

用法：
  python kb_snapshot.py save <name>   # 设备当前知识 -> harness/experiments/kb_snapshots/<name>.tar
  python kb_snapshot.py load <name>   # 快照 -> 设备（先清空再恢复）
  python kb_snapshot.py clear         # 清空设备知识（memory + skills）
  python kb_snapshot.py list          # 列出本地快照

说明：
- 快照 = `files/memory` + `files/skills` 的 tar（设备端 toybox tar 打包，PC 端解包）。
- load 走 /data/local/tmp 中转（run-as 无法直接接收 stdin 流）。
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _env import adb, ROOT                      # noqa: E402

SNAP_DIR = os.path.join(ROOT, "harness", "experiments", "kb_snapshots")
PKG = "com.hachimi.app"
TMP_ON_DEVICE = "/data/local/tmp/kb_snap.tar"


def _ensure_dir():
    os.makedirs(SNAP_DIR, exist_ok=True)


def snapshot_path(name):
    if not name:
        raise SystemExit("snapshot name required")
    return os.path.join(SNAP_DIR, "%s.tar" % name)


def save(name):
    _ensure_dir()
    raw = subprocess.run(
        ["adb", "exec-out", "run-as", PKG, "tar", "-cf", "-", "-C", "files",
         "memory", "skills"], capture_output=True).stdout
    if not raw:
        raise SystemExit("save failed: empty tar (device reachable? app installed?)")
    p = snapshot_path(name)
    with open(p, "wb") as f:
        f.write(raw)
    print("saved %s (%d bytes) -> %s" % (name, len(raw), p))


def load(name):
    p = snapshot_path(name)
    if not os.path.isfile(p):
        raise SystemExit("snapshot not found: %s" % p)
    adb("push", p, TMP_ON_DEVICE)
    # 先清空再恢复：快照即"完整知识态"，不是叠加
    adb("shell", "run-as", PKG, "rm", "-rf", "files/memory", "files/skills")
    out = adb("shell", "run-as", PKG, "tar", "-xf", TMP_ON_DEVICE, "-C", "files")
    adb("shell", "rm", "-f", TMP_ON_DEVICE)
    if "not found" in out.lower() or "error" in out.lower():
        raise SystemExit("load failed: %s" % out.strip()[:200])
    print("loaded %s -> device (memory+skills restored)" % name)


def clear():
    adb("shell", "run-as", PKG, "rm", "-rf", "files/memory", "files/skills")
    print("device knowledge cleared (memory + skills)")


def listing():
    _ensure_dir()
    names = sorted(f[:-4] for f in os.listdir(SNAP_DIR) if f.endswith(".tar"))
    for n in names:
        print(n)
    if not names:
        print("(no snapshots)")


def main():
    args = sys.argv[1:]
    if len(args) >= 2 and args[0] == "save":
        save(args[1])
    elif len(args) >= 2 and args[0] == "load":
        load(args[1])
    elif args and args[0] == "clear":
        clear()
    elif args and args[0] == "list":
        listing()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
