#!/usr/bin/env python3
"""M0' 真机环境 checklist（doc/milestones.md §2 M0' DoD 的自动化部分）。

用法：
    py -3 harness/checks/m0_checklist.py                # 全量检查
    py -3 harness/checks/m0_checklist.py --pkg com.example.app [--pkg ...]
    py -3 harness/checks/m0_checklist.py --serial <id>  # 多设备时指定

连接方式：USB（推荐）或无线 adb（Android 11+：开发者选项 → 无线调试 → 配对）。
检查项分三档：PASS（自动通过）/ FAIL（自动不通过）/ MANUAL（需人工在手机上操作后重跑）。
无障碍、电池白名单的「开启动作」必须人工完成（官方 API 不允许程序自开 —— 红线 R2 的体现），
本脚本只负责读取系统状态做验证。
"""
import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

MIN_SDK = 30  # Android 11+ 基线（架构 A6）


def adb(serial, *args, timeout=15):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def shell(serial, cmd, timeout=15):
    return adb(serial, "shell", cmd, timeout=timeout)


def main():
    ap = argparse.ArgumentParser(description="M0' device checklist")
    ap.add_argument("--serial", help="device serial（多设备时必填）")
    ap.add_argument("--pkg", action="append", default=[],
                    help="要检查安装状态的测试 App 包名，可重复")
    args = ap.parse_args()

    results = []  # (name, status, detail)

    def record(name, status, detail=""):
        results.append((name, status, detail))
        mark = {"PASS": "[PASS] ", "FAIL": "[FAIL] ", "MANUAL": "[MANUAL]"}.get(status, "[????] ")
        print(f"{mark} {name}" + (f" —— {detail}" if detail else ""))

    # 0. adb 可用性与设备选择
    code, out = adb(None, "devices")
    if code != 0:
        print("adb 不可用：请确认已安装 platform-tools 并在 PATH 中。")
        sys.exit(2)
    devices = [l.split("\t")[0] for l in out.splitlines()
               if "\tdevice" in l and "offline" not in l]
    if not devices:
        print("未发现设备：USB 连接后需在手机上允许「USB 调试」；"
              "无线则先在开发者选项开启「无线调试」并 adb connect <ip:port>。")
        sys.exit(2)
    if len(devices) > 1 and not args.serial:
        print(f"发现多台设备 {devices}，请用 --serial 指定。")
        sys.exit(2)
    serial = args.serial or devices[0]
    print(f"== 设备：{serial} ==\n")

    # 1. Android 版本 >= 11（SDK 30）
    _, sdk = shell(serial, "getprop ro.build.version.sdk")
    sdk = sdk.strip()
    if sdk.isdigit() and int(sdk) >= MIN_SDK:
        record("Android 版本 >= 11", "PASS", f"SDK {sdk}")
    else:
        record("Android 版本 >= 11", "FAIL", f"SDK {sdk or '未知'}（基线 A6：Android 11+）")

    # 2. 无障碍服务（只读验证；开启动作必须人工）
    _, acc = shell(serial, "settings get secure enabled_accessibility_services")
    acc = (acc or "").strip()
    if acc and acc not in ("null", ""):
        record("无障碍服务已开启", "PASS", acc)
    else:
        record("无障碍服务已开启", "MANUAL",
               "手机上：设置 → 无障碍 → 开启目标服务（M0' 阶段可先用任意调试服务验证保持性）")

    # 3. 电池优化白名单（读 dumpsys；加白动作人工）
    _, wl = shell(serial, "dumpsys deviceidle whitelist")
    record("电池优化白名单（当前读数）", "MANUAL",
           "手机上：设置 → 电池 → 无限制/不加优化。"
           + (f" 白名单样例：{wl.strip()[:80]}…" if wl.strip() else " 当前 whitelist 为空"))

    # 4. 亮屏常驻前置：屏幕自动锁定时长
    _, t = shell(serial, "settings get system screen_off_timeout")
    t = t.strip()
    if t.isdigit() and int(t) >= 30 * 60 * 1000:
        record("屏幕自动锁定 >= 30 分钟", "PASS", f"{int(t) // 60000} 分钟")
    else:
        record("屏幕自动锁定 >= 30 分钟", "MANUAL",
               f"当前 {int(t) // 60000 if t.isdigit() else '未知'} 分钟；建议设 30 分钟或开发期间用「充电时保持唤醒」")

    # 5. 采集链路前置验证（M0' DoD②：screenrecord 非空即通过；
    #    MediaProjection 完整授权流在 M1' 有 App 后验证）
    tmp = Path(tempfile.gettempdir()) / "hachimi_m0_capture.mp4"
    try:
        shell(serial, f"screenrecord --time-limit 3 /sdcard/hachimi_m0_test.mp4", timeout=20)
        time.sleep(1)
        code, _ = adb(serial, "pull", "/sdcard/hachimi_m0_test.mp4", str(tmp), timeout=30)
        shell(serial, "rm /sdcard/hachimi_m0_test.mp4")
        size = tmp.stat().st_size if tmp.exists() else 0
        if code == 0 and size > 10240:
            record("采集链路（screenrecord）", "PASS", f"{size // 1024} KB")
        else:
            record("采集链路（screenrecord）", "FAIL", f"pull={code == 0}, size={size}")
    except subprocess.TimeoutExpired:
        record("采集链路（screenrecord）", "FAIL", "screenrecord 超时")
    finally:
        if tmp.exists():
            tmp.unlink()

    # 6. 测试 App 安装状态
    if args.pkg:
        _, pkgs = shell(serial, "pm list packages")
        installed = {l.replace("package:", "").strip() for l in pkgs.splitlines() if l.strip()}
        for p in args.pkg:
            if p in installed:
                record(f"测试 App {p}", "PASS", "已安装")
            else:
                record(f"测试 App {p}", "FAIL", "未安装（adb install <apk> 后重跑）")
    else:
        record("测试 App 安装", "MANUAL", "未指定 --pkg；M0' DoD④ 要求前 5 个测试 App 装机")

    # 汇总
    n_pass = sum(1 for _, s, _ in results if s == "PASS")
    n_fail = sum(1 for _, s, _ in results if s == "FAIL")
    n_man = sum(1 for _, s, _ in results if s == "MANUAL")
    print(f"\n== 汇总：PASS {n_pass} / FAIL {n_fail} / MANUAL {n_man} ==")
    if n_fail:
        print("存在 FAIL：修复后重跑本脚本。")
        sys.exit(1)
    if n_man:
        print("存在 MANUAL：按提示在手机上人工完成 → 重跑到全 PASS（MANUAL 项允许人工确认后豁免）。")
        sys.exit(0)
    print("M0' 自动化检查全部通过：归档本输出作为 DoD⑤ 证据。")


if __name__ == "__main__":
    main()
