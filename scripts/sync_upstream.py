#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sync_upstream —— 把上游 OmniAgent 的内核源码同步进 Android 源集（构建前置）。

完整内核上机（doc/full_kernel_hosting_plan_2026-10-31.md）的源码单一真源 =
上游 OmniAgent 仓；本脚本在构建前把所需子集拷入 android/app/src/main/python/
（该目录其余文件如 spike_check.py 属本仓，不受影响）。

用法：
    python scripts/sync_upstream.py                 # 上游 = 环境变量 OMNIAGENT_UPSTREAM
    python scripts/sync_upstream.py --upstream D:\\path\\to\\OmniAgent
    python scripts/sync_upstream.py --git https://github.com/momo-null/OmniAgent.git

先清后拷（防上游删文件后残留陈旧拷贝）；失败即刻退出非零，不静默降级。
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

# 上游仓里内核需要的子集（与 app/build.gradle.kts 的 Chaquopy 源集一致）
COPY_ITEMS = ["omni_core", "backend", "utils", "config.py", "config.yaml"]
DEST = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "python"
REPO_ROOT = Path(__file__).resolve().parent.parent
IGNORE = shutil.ignore_patterns("__pycache__", "*.pyc")


def resolve_upstream(args) -> Path:
    if args.git:
        tmp = Path(args.tmp or (REPO_ROOT / ".upstream-tmp"))
        if tmp.exists():
            shutil.rmtree(tmp)
        tmp.parent.mkdir(parents=True, exist_ok=True)
        print(f"[sync] shallow clone {args.git} -> {tmp}")
        subprocess.run([sys.executable, "-c",
                        f"import subprocess;subprocess.run(['git','clone','--depth','1',"
                        f"'{args.git}','{tmp}'],check=True)"], check=True)
        return tmp
    up = Path(args.upstream or os.environ.get("OMNIAGENT_UPSTREAM") or
              (REPO_ROOT.parent / "OmniAgent"))
    if not up.exists():
        sys.exit(f"[sync] 上游不存在: {up}（--upstream 指定路径 / --git 指定仓库 / "
                 f"OMNIAGENT_UPSTREAM 环境变量）")
    return up


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--upstream", help="上游 OmniAgent 工作区路径")
    ap.add_argument("--git", help="上游 git URL（浅克隆到临时目录后同步）")
    ap.add_argument("--tmp", help="浅克隆目标目录（默认 .upstream-tmp）")
    args = ap.parse_args()

    up = resolve_upstream(args)
    missing = [i for i in COPY_ITEMS if not (up / i).exists()]
    if missing:
        sys.exit(f"[sync] 上游缺少 {missing}——确认这是 OmniAgent 仓根")

    DEST.mkdir(parents=True, exist_ok=True)
    for item in COPY_ITEMS:
        dst = DEST / item
        if dst.is_dir():
            shutil.rmtree(dst)
        elif dst.exists():
            dst.unlink()
        shutil.copytree(up / item, dst, dirs_exist_ok=True, ignore=IGNORE) \
            if (up / item).is_dir() else shutil.copy2(up / item, dst)

    # 记录同步来源与版本（可复现性）
    rev = ""
    try:
        rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=up,
                             capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        pass
    (DEST / ".upstream_synced").write_text(
        f"upstream={up}\nrev={rev}\n", encoding="utf-8")
    print(f"[sync] {len(COPY_ITEMS)} 项已同步: {up} (rev={rev or '?'}) -> {DEST}")
    print("[sync] 下一步: ./gradlew assembleDebug（本机无 3.12 时 -PhachimiPython=<python3.12>）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
