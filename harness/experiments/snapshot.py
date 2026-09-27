# -*- coding: utf-8 -*-
"""M4' 注入快照工具（R9：冻结 + SHA 校验 + 只读装载）。

快照目录约定（设备 files/omniagent/snapshots/<id>/，内核 experiment.py 装载）：
  manifest.json            # {"files": {rel: sha256}, "frozen_at", "source"}
  memory/memory_summary.md
  skills/catalog.json

用法：
  python snapshot.py freeze <id> --src <PC目录>     # PC 目录 -> 快照 -> 推到设备
  python snapshot.py freeze <id> --pull             # 从设备 files/omniagent 拉取再冻结
  python snapshot.py verify <id>                    # 设备端重新哈希校验
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ADB = os.environ.get("HACHIMI_ADB", r"D:/code/hachimi/.tools/platform-tools/adb.exe")
APP = "com.hachimi.app"
# runtime_paths 把 HACHIMI_DATA_DIR（files/）直接当桌面 ~/.omniagent：
# tasks/skills/memory/snapshots 都在 files/ 下（架构 §5 的 omniagent/ 前缀示意不适用）
DEV_ROOT = "files/snapshots"
TMP = os.environ.get("HACHIMI_TMP", r"D:/code/hachimi/.tools/cmdtmp")


def adb(*args, timeout=120):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def run_as(*args):
    """直接参数形式（不经 sh -c）：adb→设备层会吃引号，嵌套解析不可靠。"""
    return adb("shell", "run-as", APP, *args)


def _device_read(rel):
    out = run_as("cat", "%s/%s" % (DEV_ROOT, rel))
    return out if "No such file" not in out else None


def _device_read_raw(rel):
    out = run_as("cat", rel)
    return None if "No such file" in out else out


def _device_write(rel, data_bytes):
    tmp_path = "%s/snap_push.bin" % TMP
    with open(tmp_path, "wb") as f:
        f.write(data_bytes)
    adb("push", tmp_path, "/data/local/tmp/hachimi_snap.bin")
    target = "%s/%s" % (DEV_ROOT, rel)
    parent = os.path.dirname(target)
    run_as("mkdir", "-p", parent)
    run_as("cp", "/data/local/tmp/hachimi_snap.bin", target)


def freeze(snapshot_id, src=None, pull=False):
    """构建快照目录 + manifest，推送到设备。src 为 PC 侧目录（含 memory/ skills/）。"""
    build_dir = os.path.join(TMP, "snapshots", snapshot_id)
    if os.path.isdir(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(os.path.join(build_dir, "memory"), exist_ok=True)
    os.makedirs(os.path.join(build_dir, "skills"), exist_ok=True)

    if pull:
        # 从设备 files/omniagent 拉取现网知识（蒸馏产物）
        data = _device_read_raw("files/omniagent/memory/memory_summary.md")
        if data:
            with open(os.path.join(build_dir, "memory", "memory_summary.md"), "w",
                      encoding="utf-8") as f:
                f.write(data)
        listing = run_as("ls", "files/omniagent/skills")
        catalog = []
        for name in (listing or "").split():
            if not name.endswith(".md"):
                continue
            body = run_as("cat", "files/omniagent/skills/%s" % name) or ""
            catalog.append({"name": name[:-3], "ops": body[:500]})
        with open(os.path.join(build_dir, "skills", "catalog.json"), "w",
                  encoding="utf-8") as f:
            json.dump(catalog, f, ensure_ascii=False)
    elif src:
        for sub in ("memory", "skills"):
            d = os.path.join(src, sub)
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                s = os.path.join(d, name)
                if not os.path.isfile(s):
                    continue
                if name.endswith(".json"):
                    shutil.copy(s, os.path.join(build_dir, "skills", name))
                elif sub == "memory" or name.endswith(".md"):
                    dst = os.path.join(build_dir, sub, name)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy(s, dst)

    # manifest（文件级 SHA256）
    files = {}
    for root, _dirs, names in os.walk(build_dir):
        for n in names:
            if n == "manifest.json":
                continue
            p = os.path.join(root, n)
            rel = os.path.relpath(p, build_dir).replace("\\", "/")
            files[rel] = sha256_file(p)
    if not files:
        print("freeze: no content files found; nothing to do")
        return
    manifest = {"files": files, "frozen_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "source": "pull" if pull else (src or "")}
    with open(os.path.join(build_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    # 推送（逐文件经 /data/local/tmp 中转 + run-as cp）
    run_as("sh", "-c", "mkdir -p '%s/%s'" % (DEV_ROOT, snapshot_id))
    for rel in list(files) + ["manifest.json"]:
        with open(os.path.join(build_dir, rel), "rb") as f:
            _device_write("%s/%s" % (snapshot_id, rel), f.read())
    print("freeze %s: %d files pushed" % (snapshot_id, len(files) + 1))
    verify(snapshot_id)


def verify(snapshot_id):
    """设备端重新计算 SHA 与 manifest 比对（R9 断言）。路径一律相对快照根
    （_device_read 内部拼 DEV_ROOT，传全路径会翻倍）。"""
    man_raw = _device_read("%s/manifest.json" % snapshot_id)
    if not man_raw or "files" not in man_raw:
        print("verify %s: manifest missing on device" % snapshot_id)
        return False
    manifest = json.loads(man_raw)
    ok = True
    for rel, expect in manifest["files"].items():
        raw = _device_read("%s/%s" % (snapshot_id, rel))
        if raw is None:
            print("  MISSING %s" % rel)
            ok = False
            continue
        actual = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        if actual != expect:
            print("  MISMATCH %s" % rel)
            ok = False
    print("verify %s: %s" % (snapshot_id, "PASS" if ok else "FAIL"))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["freeze", "verify"])
    ap.add_argument("snapshot_id")
    ap.add_argument("--src")
    ap.add_argument("--pull", action="store_true")
    args = ap.parse_args()
    if args.cmd == "freeze":
        freeze(args.snapshot_id, src=args.src, pull=args.pull)
    else:
        sys.exit(0 if verify(args.snapshot_id) else 1)


if __name__ == "__main__":
    main()
