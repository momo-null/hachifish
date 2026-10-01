# -*- coding: utf-8 -*-
"""dump 注入块：复现真正进入 system prompt 的知识字节（P2-R Phase 0 测量工具）。

用途：
  python dump_injection.py            # 打印当前设备知识对应的注入块
  python dump_injection.py --twice    # 跑两遍并 diff —— Phase 0 验收：同一知识复注入得到同一字节

原理：把设备端 files/memory + files/skills 拉到本地临时目录，设 HACHIMI_DATA_DIR
指向它，再用内核自己的 build_knowledge_block 组装 —— 保证看到的是 prompt 字节而非想象。
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "harness"))
from _env import ROOT as R_ROOT                     # noqa: E402,F401  (确保 adb 路径就绪)
from _env import adb as _adb                        # noqa: E402

sys.path.insert(0, os.path.join(ROOT, "kernel"))
PKG = "com.hachimi.app"


def pull_knowledge(dest):
    """设备知识 -> 本地目录（memory/ 与 skills/ 平铺在 dest 下）。"""
    os.makedirs(dest, exist_ok=True)
    raw = subprocess.run(
        ["adb", "exec-out", "run-as", PKG, "tar", "-cf", "-", "-C", "files",
         "memory", "skills"], capture_output=True).stdout
    if not raw:
        raise SystemExit("pull failed: empty tar")
    tar = os.path.join(dest, "..", "_kb.tar")
    with open(tar, "wb") as f:
        f.write(raw)
    subprocess.run(["tar", "-xf", tar, "-C", dest], capture_output=True)
    os.remove(tar)
    return dest


def build_block(data_dir):
    """用内核自身代码组装注入块（与 bridge._knowledge_parts 同构）。"""
    os.environ["HACHIMI_DATA_DIR"] = data_dir
    from hachimi_kernel import knowledge_inject as KI          # noqa: E402
    from hachimi_kernel.skill_library import SkillLibrary      # noqa: E402
    from hachimi_kernel import runtime_paths as P              # noqa: E402
    from hachimi_kernel.bridge import _skill_ops               # noqa: E402

    mem = KI.load_memory_text()
    skills = []
    for f in sorted(P.global_skills().glob("*.md")):
        s = SkillLibrary._parse(f)
        if s is not None and not s.disable_model_invocation:
            skills.append({"name": s.name,
                           "objective_pattern": s.objective_pattern,
                           "ops": _skill_ops(s)[:200]})
    return KI.build_knowledge_block(mem, skills)


def main():
    if "--twice" in sys.argv:
        # Phase 0 验收：同一知识，两次组装必须逐字节一致。
        # 必须用子进程跑：runtime_paths 在 import 时缓存 _GLOBAL，同进程内
        # 换 HACHIMI_DATA_DIR 不生效（2026-10-01 实测踩坑）。
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        outs = []
        for _ in range(2):
            r = subprocess.run([sys.executable, __file__, "--quiet"],
                               capture_output=True, env=env)
            outs.append(r.stdout.decode("utf-8"))
        if outs[0] == outs[1] and outs[0].strip():
            print("OK: two injections are byte-identical (%d chars)" % len(outs[0]))
        else:
            print("FAIL: injections differ (%d vs %d chars)"
                  % (len(outs[0]), len(outs[1])))
            raise SystemExit(1)
        return

    tmp = tempfile.mkdtemp(prefix="kb_dump_")
    pull_knowledge(tmp)
    block = build_block(os.path.join(tmp))
    if "--quiet" in sys.argv:
        sys.stdout.write(block)
        return
    print("=" * 78)
    print("INJECTED INTO SYSTEM PROMPT (%d chars):" % len(block))
    print("=" * 78)
    print(block)


if __name__ == "__main__":
    _adb("forward", "tcp:18071", "tcp:8071")   # 保持通道活跃（失败不影响离线复现）
    main()
