# -*- coding: utf-8 -*-
"""对设备端 MEMORY.md 做存量回溯合并（P2-R 语义去重收口）。

``_merge_memory_sections`` 的语义去重只在「新增 vs 存量」时生效，调参前沉淀的
同义变体会一直留在库里。本脚本用 PC 侧内核代码对设备存量做一次合并：
- 默认 **dry-run**：只打印会合并掉哪些（不改设备）；
- ``--apply``：回写设备（adb push + run-as 拷贝回私有目录）。

Omni 复用：核心是 ``curator.compact_memory_sections``（纯函数，不依赖安卓）。

用法::

    python compact_memory.py            # 预览
    python compact_memory.py --apply    # 落盘
"""

import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "..", "kernel"))

PKG = "com.hachimi.app"
REMOTE = "files/memory/MEMORY.md"


def _adb(*args):
    return subprocess.run(["adb"] + list(args), capture_output=True)


def pull() -> str:
    r = _adb("shell", "run-as", PKG, "cat", REMOTE)
    return r.stdout.decode("utf-8", "ignore")


def push(text: str) -> bool:
    """把文本写回设备私有目录（经 /data/local/tmp 中转）。"""
    tmp_local = os.path.join(tempfile.gettempdir(), "MEMORY_compact.md")
    # newline="\n"：默认会把 \n 译成 \r\n，回写后条目间凭空多出空行
    with open(tmp_local, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    _adb("push", tmp_local, "/data/local/tmp/MEMORY_compact.md")
    # 用 cp 而非 `sh -c "cat > path"`：run-as 下的重定向在部分 shell 上不生效
    r = _adb("shell", "run-as", PKG, "cp", "/data/local/tmp/MEMORY_compact.md", REMOTE)
    _adb("shell", "rm", "-f", "/data/local/tmp/MEMORY_compact.md")
    if r.returncode != 0:
        return False
    return bool(_adb("shell", "run-as", PKG, "cat", REMOTE).stdout.decode("utf-8", "ignore").strip())


def main():
    from hachimi_kernel.curator import compact_memory_sections

    text = pull()
    if not text.strip():
        print("设备上没有 MEMORY.md（或为空）")
        return
    new_text, removed = compact_memory_sections(text)

    def _items(t):
        # 归一化后比对：否则"仅剥掉嵌套项目符号"的条目会被误报成"被合并"
        from hachimi_kernel.curator import _strip_bullet
        out, cur = [], None
        for line in t.splitlines():
            s = line.strip()
            if s.startswith("## "):
                cur = s
            elif s.startswith("- ") and s != "- (暂无)":
                out.append((cur, _strip_bullet(s[2:])))
        return out

    before, after = _items(text), _items(new_text)
    print("条目：%d -> %d（合并掉 %d 条）" % (len(before), len(after), removed))
    print("字符：%d -> %d" % (len(text), len(new_text)))
    print("-" * 70)
    for cur, it in before:
        if (cur, it) not in after:
            print("  合并掉：%s" % it)
    print("-" * 70)
    for cur, it in after:
        print("  保留：%s" % it)

    if "--apply" in sys.argv:
        if removed == 0:
            print("\n无重复可合并，未改动设备")
            return
        if push(new_text):
            print("\n已写回设备（%s）" % REMOTE)
        else:
            print("\n写回失败（见 adb 输出）")
    else:
        print("\ndry-run：加 --apply 才会写回设备")


if __name__ == "__main__":
    main()
