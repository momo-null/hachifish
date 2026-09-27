# -*- coding: utf-8 -*-
"""Hachimi harness 共用环境解析（阶段 1 · 仓库可移植）。

取代散落在各脚本里的 `D:/code/hachimi/...` 与写死设备序列号。
所有路径从本仓库根推导，并允许环境变量覆盖：

  HACHIMI_ADB     adb 可执行文件（默认 <root>/.tools/platform-tools/adb.exe）
  HACHIMI_TMP     临时目录（默认 <root>/.tools/cmdtmp）
  HACHIMI_SERIAL  目标 adb 设备序列号（默认自动取唯一已连接设备）
  HACHIMI_ROOT    仓库根（默认由本文件位置上溯）
"""
import os
import subprocess

# 仓库根：harness/_env.py -> 父目录 = 仓库根
ROOT = os.environ.get("HACHIMI_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ADB = os.environ.get("HACHIMI_ADB") or os.path.join(ROOT, ".tools", "platform-tools", "adb.exe")
TMP = os.environ.get("HACHIMI_TMP") or os.path.join(ROOT, ".tools", "cmdtmp")

# 回环 HTTP（设备侧 Hachimi 服务经 adb reverse 暴露到本机）
LOOPBACK = "http://127.0.0.1:18071"


def ensure_tmp():
    os.makedirs(TMP, exist_ok=True)
    return TMP


def adb(*args, serial=None, timeout=60):
    """调用 adb。serial=None 时用 HACHIMI_SERIAL（自动取唯一设备）。"""
    cmd = [ADB]
    ser = serial or resolve_serial()
    if ser:
        cmd += ["-s", ser]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def list_devices():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=60).stdout
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def resolve_serial(explicit=None):
    """返回目标设备序列号：显式环境变量 > 唯一已连接设备 > None。"""
    if explicit:
        return explicit
    env = os.environ.get("HACHIMI_SERIAL")
    if env:
        return env
    devs = list_devices()
    if len(devs) == 1:
        return devs[0]
    return None  # 多设备时由调用方处理（runner 会报错提示）


def device_brand(serial=None):
    """读取设备品牌（ro.product.brand，小写）。"""
    out = adb("shell", "getprop", "ro.product.brand", serial=serial)
    return out.strip().lower()


def load_profiles():
    import json
    path = os.path.join(ROOT, "harness", "devices", "profiles.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def current_profile(serial=None):
    """按品牌匹配设备 profile；无匹配回退 default。返回 dict（含全部字段）。"""
    profiles = load_profiles()
    brand = device_brand(serial) or ""
    prof = dict(profiles.get("default", {}))
    for key in (brand,):  # 品牌精确匹配；可扩展为 model 匹配
        if key in profiles:
            prof.update(profiles[key])
            break
    return prof

