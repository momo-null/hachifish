#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sync_upstream —— 把上游 OmniAgent 的内核源码同步进 Android 源集（构建前置）。

完整内核上机（doc/full_kernel_hosting_plan_2026-10-31.md）的源码单一真源 =
上游 OmniAgent 仓；本脚本在构建前把所需子集拷入 android/app/src/main/python/
（该目录其余文件如 spike_check.py 属本仓，不受影响）。

插件面走 **标准 AGP assets**（android/app/src/main/assets/plugins/），不进
Chaquopy 源集：Chaquopy 把 python 源码整体打进 assets/chaquopy/app.imy 单一归档，
单个 .py 不再是可寻址文件，而上游 loader 要用 ``Path.iterdir()`` 真扫目录 +
``spec_from_file_location`` 按文件路径装载。所以插件树以普通资产入库，首次启动由
Kotlin（PluginAssets.materialize）落到 filesDir/plugins/，再把
``runtime.tools.plugin_dirs`` 指过去——扫描/装载全部走上游原路，零分叉（R6）。
只带 filesystem / web：其余插件（host / emulator / ocr / vision / voice /
messaging）依赖桌面 shell、Windows API 或本机模型，上机即装载失败，不进包。

用法：
    python scripts/sync_upstream.py                 # 上游 = 环境变量 OMNIAGENT_UPSTREAM
    python scripts/sync_upstream.py --upstream D:\\path\\to\\OmniAgent
    python scripts/sync_upstream.py --git https://github.com/momo-null/OmniAgent.git
    python scripts/sync_upstream.py --no-plugins     # 只同步内核源码，不动插件面

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

#: 能力插件面（标准 assets，Kotlin 首次启动 materialize 到 filesDir/plugins/）
PLUGIN_ITEMS = ["filesystem", "web"]
PLUGIN_DEST = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "plugins"


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


def _copy_clean(src: Path, dst: Path) -> None:
    """先清后拷（目录整棵替换 / 文件覆盖），__pycache__ 与 .pyc 不入包。"""
    if dst.is_dir():
        shutil.rmtree(dst)
    elif dst.exists():
        dst.unlink()
    if src.is_dir():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(src, dst, dirs_exist_ok=True, ignore=IGNORE)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--upstream", help="上游 OmniAgent 工作区路径")
    ap.add_argument("--git", help="上游 git URL（浅克隆到临时目录后同步）")
    ap.add_argument("--tmp", help="浅克隆目标目录（默认 .upstream-tmp）")
    ap.add_argument("--no-plugins", action="store_true",
                    help="只同步内核源码，不动 assets/plugins（插件面未变更时省时间）")
    args = ap.parse_args()

    up = resolve_upstream(args)
    missing = [i for i in COPY_ITEMS if not (up / i).exists()]
    if missing:
        sys.exit(f"[sync] 上游缺少 {missing}——确认这是 OmniAgent 仓根")

    DEST.mkdir(parents=True, exist_ok=True)
    for item in COPY_ITEMS:
        _copy_clean(up / item, DEST / item)

    n_plugins = 0
    if not args.no_plugins:
        pmissing = [i for i in PLUGIN_ITEMS if not (up / "plugins" / i).is_dir()]
        if pmissing:
            sys.exit(f"[sync] 上游缺少插件 {pmissing}（plugins/ 下）——"
                     f"上游改名/删除时同步脚本必须显式跟进，用 --no-plugins 可暂时跳过")
        PLUGIN_DEST.mkdir(parents=True, exist_ok=True)
        for item in PLUGIN_ITEMS:
            _copy_clean(up / "plugins" / item, PLUGIN_DEST / item)
            n_plugins += 1
        # 上游仓根 plugins/ 里的其它插件一律不残留（先清后拷按项做，整目录级
        # 兜底：assets/plugins 下只允许 PLUGIN_ITEMS 出现）
        allowed = {PLUGIN_DEST / i for i in PLUGIN_ITEMS}
        for stray in list(PLUGIN_DEST.iterdir()):
            if stray.is_dir() and stray not in allowed:
                shutil.rmtree(stray)

    # 记录同步来源与版本（可复现性）
    rev = ""
    try:
        rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=up,
                             capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        pass
    (DEST / ".upstream_synced").write_text(
        f"upstream={up}\nrev={rev}\n"
        f"plugins={','.join(PLUGIN_ITEMS) if not args.no_plugins else '-'}\n",
        encoding="utf-8")
    print(f"[sync] 内核 {len(COPY_ITEMS)} 项 -> {DEST}")
    if not args.no_plugins:
        print(f"[sync] 插件面 {n_plugins} 项（{', '.join(PLUGIN_ITEMS)}）-> {PLUGIN_DEST}")
    print(f"[sync] 来源 {up} (rev={rev or '?'})")
    print("[sync] 下一步: ./gradlew assembleDebug（本机无 3.12 时 -PhachimiPython=<python3.12>）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
