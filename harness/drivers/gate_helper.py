# -*- coding: utf-8 -*-
"""Gate observation helper: dump current screen tree via the debug bridge and
locate gate buttons (allow-once / allow-always / deny) with absolute centers."""
import json
import subprocess
import sys

ADB = r"D:/code/hachimi/.tools/platform-tools/adb.exe"


def adb(*args, timeout=30):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          timeout=timeout).stdout


def observe_tree():
    adb("shell", "am broadcast -a com.hachimi.app.DEBUG_PRIMITIVE "
                  "-p com.hachimi.app --es prim observe",
        timeout=60)
    time_sleep()
    raw = adb("shell", "run-as com.hachimi.app cat files/bridge_result.json",
              timeout=60)
    return json.loads(raw)["result"].get("tree", [])


def time_sleep():
    subprocess.run(["sleep", "1"])


def find_button(tree, needle):
    for n in tree:
        t = ((n.get("text") or "") + " " + (n.get("desc") or "")).strip()
        if needle in t:
            b = n["bounds"]
            cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            return t, cx, cy
    return None


def main():
    op = sys.argv[1] if len(sys.argv) > 1 else "peek"
    tree = observe_tree()
    if op == "tap" and len(sys.argv) > 2:
        needle = {"always": "总是允许", "once": "仅本次允许",
                  "deny": "拒绝并停止"}.get(sys.argv[2], sys.argv[2])
        hit = find_button(tree, needle)
        if not hit:
            print("NOT FOUND:", needle)
            for n in tree[:60]:
                t = ((n.get("text") or "") + " " + (n.get("desc") or "")).strip()
                if t:
                    print("  node:", repr(t[:50]), n["bounds"])
            sys.exit(1)
        label, cx, cy = hit
        print("TAP", label, "->", cx, cy)
        adb("shell", "input tap %d %d" % (cx, cy))
    else:
        for n in tree[:80]:
            t = ((n.get("text") or "") + " " + (n.get("desc") or "")).strip()
            if t:
                print(repr(t[:60]), n["bounds"])


if __name__ == "__main__":
    main()
