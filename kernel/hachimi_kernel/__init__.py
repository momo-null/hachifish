# -*- coding: utf-8 -*-
"""hachimi_kernel · 手机版 OmniAgent 内核包（Chaquopy 嵌入）。"""

_KERNEL_VERSION = "0.1.0"


def kernel_info():
    """Kotlin 壳的 Python 桥冒烟探测：Chaquopy 集成验证（M2' DoD②）。"""
    import sys
    return {
        "kernel": "hachimi_kernel",
        "version": _KERNEL_VERSION,
        "python": sys.version.split()[0],
        "stdlib_only": True,
    }
