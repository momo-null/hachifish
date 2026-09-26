# -*- coding: utf-8 -*-
"""M2' 知识层对拍 conftest（红线 R6 验证工具）。

机制：把桌面测试原样跑在 Hachimi 内核副本上——
- sys.modules 别名：omni_core.local.* → hachimi_kernel.*（测试代码零改动）
- autouse `_isolated_home`：_GLOBAL 打到 tmp_path，绝不让对拍写入真实 ~/.omniagent
判定：桌面测试在内核副本上全绿 = 语义零漂移。
"""
import sys
import types
from pathlib import Path

import pytest

KERNEL_ROOT = Path(__file__).resolve().parents[3] / "kernel"
sys.path.insert(0, str(KERNEL_ROOT))

# ---- 模块别名：omni_core.local.* → hachimi_kernel.* ----
_omni = types.ModuleType("omni_core")
_local = types.ModuleType("omni_core.local")
sys.modules["omni_core"] = _omni
sys.modules["omni_core.local"] = _local
for _name in ("runtime_paths", "trajectory", "curator", "skill_library",
              "world_model", "knowledge_inject", "task_store"):
    _mod = __import__(f"hachimi_kernel.{_name}", fromlist=[_name])
    sys.modules[f"omni_core.local.{_name}"] = _mod
    setattr(_local, _name, _mod)
_omni.local = _local


@pytest.fixture(autouse=True)
def _isolated_home(tmp_path, monkeypatch):
    """桌面 conftest 同名夹具：数据根打到 tmp_path，对拍零污染真实数据。"""
    import hachimi_kernel.runtime_paths as _RP
    target = tmp_path / ".omniagent"
    monkeypatch.setattr(_RP, "_GLOBAL", target)
    yield target
