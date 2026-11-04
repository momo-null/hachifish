# -*- coding: utf-8 -*-
"""omni_host 启动期播种单测（插件面上机后的两条硬约束）。

不依赖上游同步产物（新克隆未跑 sync_upstream 也能跑）：``config`` 与
``omni_core.local.runtime_paths`` 以桩模块注入（monkeypatch.setitem 自动还原），
yaml 与文件系统走真实路径。被测的是壳侧 ``omni_host`` 自己的播种逻辑：

① 读-改-写：``config.save_settings`` 是**整份覆盖**，启动播种不得冲掉用户经
   ``PUT /api/settings`` 持久化的其他节（``security.mode`` 曾因此每次重启丢失）；
② R5 能力默认关：为落盘插件根下的每个插件包播种 ``plugins/<name>.yaml``
   ``enabled: false``，且**只在文件不存在时写**——「知识 → 工具」里的用户选择
   优先级最高，重启不覆盖。

``_log`` 落文件到 HACHIMI_DATA_DIR（缺省 cwd）——fixture 里一并指向 tmp_path，
测试不污染仓目录。
"""
import copy
import os
import sys
import types
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import omni_host  # noqa: E402


class _FakeConfig:
    """上游 config 模块替身：只实现播种用到的三件套。

    ``deep_merge`` 与上游逐字同语义（dict 递归合并、非 dict 整体覆盖、None 不覆盖），
    否则「列表是否被替换」这类断言会测到假东西。
    """

    def __init__(self, initial):
        self.initial = initial
        self.saved = None
        self.save_calls = 0

    def load_settings(self):
        return copy.deepcopy(self.initial)

    def save_settings(self, settings):
        self.saved = settings
        self.save_calls += 1

    @staticmethod
    def deep_merge(target, source):
        result = dict(target)
        for key, val in source.items():
            if val is not None and isinstance(val, dict) and isinstance(result.get(key), dict):
                result[key] = _FakeConfig.deep_merge(result[key], val)
            else:
                result[key] = val
        return result


def _package(root: Path, name: str) -> Path:
    """造一个合法插件包（plugin.json + plugin.py，manifest.name 与目录名一致）。"""
    d = root / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "plugin.json").write_text(
        yaml.safe_dump({"name": name, "title": name, "enabled": True},
                       allow_unicode=True), encoding="utf-8")
    (d / "plugin.py").write_text("x = 1\n", encoding="utf-8")
    return d


@pytest.fixture
def env(tmp_path, monkeypatch):
    """桩 config + 桩 runtime_paths + 独立插件根；返回可直接断言的命名空间。"""
    monkeypatch.setenv("HACHIMI_DATA_DIR", str(tmp_path))
    fake = _FakeConfig({
        "security": {"mode": "full_access"},
        "runtime": {"knowledge": {"profile": {"enabled": False}}},
    })
    monkeypatch.setitem(sys.modules, "config", fake)

    plugins_dir = tmp_path / ".omniagent" / "plugins"
    paths_mod = types.ModuleType("omni_core.local.runtime_paths")
    paths_mod.plugin_config_file = lambda name: plugins_dir / (name + ".yaml")
    local_mod = types.ModuleType("omni_core.local")
    local_mod.runtime_paths = paths_mod
    core_mod = types.ModuleType("omni_core")
    core_mod.local = local_mod
    for key, mod in (("omni_core", core_mod), ("omni_core.local", local_mod),
                     ("omni_core.local.runtime_paths", paths_mod)):
        monkeypatch.setitem(sys.modules, key, mod)

    plugin_root = tmp_path / "plugins"
    plugin_root.mkdir()
    return types.SimpleNamespace(fake=fake, plugins_dir=plugins_dir,
                                 plugin_root=plugin_root)


def test_seed_keeps_other_user_sections(env):
    """播种是合并：用户已持久化的 security.mode / profile 开关必须原样留着。"""
    omni_host._seed_settings({"plugin_dir": ""})

    saved = env.fake.saved
    assert env.fake.save_calls == 1
    assert saved["security"]["mode"] == "full_access"
    assert saved["runtime"]["knowledge"]["profile"]["enabled"] is False
    assert saved["runtime"]["knowledge"]["memory"]["enabled"] is True


def test_seed_writes_no_tools_key_without_plugin_root(env):
    """插件根缺失（壳侧落盘失败）→ 完全不碰 plugin_dirs，上游 loader 用自身缺省。"""
    res = omni_host._seed_settings({"plugin_dir": str(env.plugin_root / "不存在")})

    assert res["plugin_dir"] == ""
    assert "tools" not in (env.fake.saved.get("runtime") or {})
    assert not env.plugins_dir.exists()


def test_seed_points_plugin_dirs_at_materialized_root(env):
    """落盘根存在 → plugin_dirs 指向它（绝对路径单元素），并把包逐个默认关。"""
    _package(env.plugin_root, "filesystem")
    _package(env.plugin_root, "web")

    res = omni_host._seed_settings({"plugin_dir": str(env.plugin_root)})

    assert env.fake.saved["runtime"]["tools"]["plugin_dirs"] == [str(env.plugin_root)]
    assert res["plugin_dir"] == str(env.plugin_root)
    assert sorted(res["seeded_off"]) == ["filesystem", "web"]
    for name in ("filesystem", "web"):
        data = yaml.safe_load((env.plugins_dir / f"{name}.yaml").read_text(encoding="utf-8"))
        assert data == {"enabled": False}


def test_seed_never_overwrites_existing_plugin_choice(env):
    """已有 plugins/<name>.yaml = 用户的选择（可能是开）——重启一个字节都不改。"""
    env.plugins_dir.mkdir(parents=True)
    (env.plugins_dir / "filesystem.yaml").write_text(
        yaml.safe_dump({"enabled": True}, allow_unicode=True), encoding="utf-8")
    _package(env.plugin_root, "filesystem")
    _package(env.plugin_root, "web")

    res = omni_host._seed_settings({"plugin_dir": str(env.plugin_root)})

    assert res["seeded_off"] == ["web"]
    assert yaml.safe_load((env.plugins_dir / "filesystem.yaml").read_text(
        encoding="utf-8")) == {"enabled": True}


def test_seed_skips_non_package_dirs(env):
    """只认「目录名合法 + 有 plugin.py」的包；杂目录/杂文件不留 yaml（loader 同规则）。"""
    _package(env.plugin_root, "web")
    (env.plugin_root / "notes").mkdir()          # 无 plugin.py 的普通目录
    (env.plugin_root / "README.md").write_text("x", encoding="utf-8")

    res = omni_host._seed_settings({"plugin_dir": str(env.plugin_root)})

    assert res["seeded_off"] == ["web"]
    assert sorted(p.name for p in env.plugins_dir.iterdir()) == ["web.yaml"]
