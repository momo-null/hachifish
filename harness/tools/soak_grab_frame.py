# -*- coding: utf-8 -*-
"""P1 DoD2 取帧压测：连续 grab_frame ~10 分钟，统计延迟与内存，验收
p95 ≤ 300ms、无 OOM/崩溃。

前置：Hachimi 已装且内核挂载、无障碍已开、录屏授权已生效（projection running）。
运行：python soak_grab_frame.py [--seconds 600] [--interval 1.0]
"""

import argparse
import json
import os
import subprocess
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "harness"))
from _env import ADB  # noqa: E402  （环境变量 HACHIMI_ADB 可覆盖）

PKG = "com.hachimi.app"


def adb(*args, timeout=30):
    out = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout)
    return out.stdout.decode("utf-8", "replace")


def broadcast_grab_frame():
    """显式组件广播 grab_frame + run-as 轮询结果（reset.bridge_call 同款机制）。"""
    subprocess.run([ADB, "shell", "am", "broadcast", "-a", "com.hachimi.app.DEBUG_PRIMITIVE",
                    "-n", PKG + "/.DebugBridgeReceiver", "--es", "prim", "grab_frame"],
                   capture_output=True, timeout=15)
    deadline = time.time() + 15
    while time.time() < deadline:
        raw = adb("shell", "run-as", PKG, "cat", "files/bridge_result.json")
        try:
            obj = json.loads(raw)
        except Exception:
            obj = None
        if isinstance(obj, dict) and obj.get("prim") == "grab_frame":
            return obj.get("result", {})
        time.sleep(0.4)
    return None


def mem_total_kb():
    out = adb("shell", "dumpsys", "meminfo", PKG)
    for line in out.splitlines():
        if "TOTAL PSS" in line or line.strip().startswith("TOTAL"):
            parts = line.split()
            for p in parts:
                if p.isdigit():
                    return int(p)
    return None


def pct(sorted_vals, q):
    if not sorted_vals:
        return 0
    i = min(len(sorted_vals) - 1, int(q * len(sorted_vals)))
    return sorted_vals[i]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=600)
    ap.add_argument("--interval", type=float, default=1.0)
    args = ap.parse_args()

    first = broadcast_grab_frame()
    if not first or not first.get("ok"):
        print("[soak] grab_frame 不可用（projection 未授权或首帧未到）：", first)
        return 2
    print("[soak] 首帧 ok w=%(w)s h=%(h)s ms=%(ms)s frame_age_ms=%(frame_age_ms)s" % first)

    pid0 = adb("shell", "pidof", PKG).strip()
    stats, ages, mem = [], [], []
    t_end = time.time() + args.seconds
    n = fail = 0
    while time.time() < t_end:
        t0 = time.time()
        r = broadcast_grab_frame()
        n += 1
        if r and r.get("ok"):
            stats.append(int(r.get("ms", 0)))
            ages.append(int(r.get("frame_age_ms", 0)))
        else:
            fail += 1
        if n % 60 == 0:
            m = mem_total_kb()
            mem.append(m)
            s = sorted(stats)
            print("[soak] t=%3ds n=%d fail=%d ms p50=%d p95=%d max=%d mem=%sKB"
                  % (args.seconds - int(t_end - t0), n, fail,
                     pct(s, 0.5), pct(s, 0.95), s[-1] if s else 0, m))
        wait = args.interval - (time.time() - t0)
        if wait > 0:
            time.sleep(wait)

    pid1 = adb("shell", "pidof", PKG).strip()
    s = sorted(stats)
    m_final = mem_total_kb()
    print("[soak] == 结果 ==")
    print("n=%d fail=%d" % (n, fail))
    print("grab 延迟 ms: p50=%d p95=%d max=%d（验收 ≤300ms）"
          % (pct(s, 0.5), pct(s, 0.95), s[-1] if s else 0))
    print("帧龄 ms: p50=%d p95=%d（静态屏幕会变大，仅参考）"
          % (pct(sorted(ages), 0.5), pct(sorted(ages), 0.95)))
    print("内存 KB: 首测=%s 末测=%s 峰值=%s（进程存活=%s）"
          % (mem[0] if mem else m_final, m_final,
             max([x for x in mem if x] or [0]), pid0 == pid1 and bool(pid1)))
    ok = (fail == 0 and s and pct(s, 0.95) <= 300 and pid0 == pid1 and bool(pid1))
    print("[soak] RESULT:", "PASS" if ok else "NOT-PASSED")
    return 0 if ok else 4


if __name__ == "__main__":
    sys.exit(main())
