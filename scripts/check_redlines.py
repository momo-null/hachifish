#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""红线 R7 检查（spec/redlines.md）：kernel/ 依赖白名单。

规则：kernel/**/*.py 的 import 只允许标准库 + httpx + PyYAML；
黑名单（torch/easyocr/ultralytics/opencv/langgraph/openai-agents/pydantic/llama 等）
一旦出现即 FAIL。桌面专用重依赖不得进入手机包。

运行：python3 scripts/check_redlines.py   （CI 与本地构建前均可）
"""
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "kernel"))
ALLOWED_THIRD_PARTY = {"httpx", "yaml"}  # PyYAML 的 import 名是 yaml
FORBIDDEN = ("torch", "easyocr", "ultralytics", "cv2", "langgraph", "langchain",
             "agents", "pydantic", "openai", "llama_cpp", "llama", "transformers")
STDLIB = set(getattr(sys, "stdlib_module_names", ()))  # py3.10+；旧解释器走启发式
STDLIB |= {"os", "sys", "json", "re", "time", "math", "threading", "asyncio", " pathlib",
           "pathlib", "datetime", "dataclasses", "typing", "uuid", "hashlib", "urllib",
           "http", "socket", "logging", "collections", "itertools", "functools",
           "traceback", "warnings", "abc", "enum", "io", "tempfile", "shutil", "stat"}


def top_module(name):
    return name.split(".")[0].split(" ")[0]


def check_file(path):
    bad = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            m = None
            if line.startswith("import "):
                m = line[7:].split(",")[0].split(" as ")[0]
            elif line.startswith("from "):
                m = line[5:].split(" import ")[0].split(",")[0]
            if not m:
                continue
            top = top_module(m.strip().strip("."))
            if not top or top.startswith("."):
                continue  # 相对导入
            if top in FORBIDDEN:
                bad.append((lineno, line.strip(), "FORBIDDEN:" + top))
            elif top not in STDLIB and top not in ALLOWED_THIRD_PARTY \
                    and top != "hachimi_kernel":
                if not STDLIB:
                    # 旧解释器无 stdlib_module_names：启发式放行常见标准库以外的未知项会漏报，
                    # 此处宁可保守放行（构建期 Chaquopy pip 会再暴露真实依赖）
                    continue
                bad.append((lineno, line.strip(), "NOT-WHITELISTED:" + top))
    return bad


def main():
    if not STDLIB:
        print("[WARN] 当前解释器 <3.10，stdlib 判定降级为启发式；建议用 3.12 运行本检查")
    failures = []
    checked = 0
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in ("__pycache__", "tests")]
        for fn in filenames:
            if fn.endswith(".py"):
                checked += 1
                path = os.path.join(dirpath, fn)
                for lineno, line, reason in check_file(path):
                    failures.append("%s:%d %s | %s" % (os.path.relpath(path, ROOT), lineno, reason, line))
    print("checked %d files under kernel/" % checked)
    if failures:
        print("== R7 FAIL ==")
        for f in failures:
            print("  " + f)
        sys.exit(1)
    print("== R7 PASS: kernel 依赖白名单合规 ==")


if __name__ == "__main__":
    main()
