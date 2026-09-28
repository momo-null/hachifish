# -*- coding: utf-8 -*-
"""把 BYOK 大脑配置推给设备（set_brain 广播）。

配置来源：环境变量 HACHIMI_BRAIN_* > harness/config.local.json（*.local.json 已 gitignore）。
用法：
    python harness/tools/set_brain.py            # 推 config.local.json 里的配置
    python harness/tools/set_brain.py --show     # 只打印将要推送的 base_url/model（不打 key）
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "harness"))
from _env import adb, brain_config  # noqa: E402

COMPONENT = "com.hachimi.app/.DebugBridgeReceiver"


def main():
    cfg = brain_config()
    base, key, model = cfg.get("base_url"), cfg.get("api_key"), cfg.get("model")
    if not (base and key and model):
        print("no brain config (need base_url/api_key/model): %s" % cfg)
        return 2
    if "--show" in sys.argv:
        print("base_url=%s model=%s api_key=***%s" % (base, model, key[-4:]))
        return 0
    out = adb("shell", "am", "broadcast",
              "-a", "com.hachimi.app.DEBUG_PRIMITIVE",
              "-n", COMPONENT,
              "--es", "prim", "set_brain",
              "--es", "base_url", base,
              "--es", "api_key", key,
              "--es", "model", model)
    print(out.strip()[:600])
    return 0


if __name__ == "__main__":
    sys.exit(main())
