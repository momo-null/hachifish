# -*- coding: utf-8 -*-
"""P1 视觉兜底测试（离线，不触网）：look 工具 / observe 全盲兜底 / set_vision 开关 /
VisionClient 载荷格式。`py -3 tests/test_vision_tools.py`。"""

import sys
import os
import json

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from hachimi_kernel import bridge  # noqa: E402
from hachimi_kernel.vision_client import VisionClient  # noqa: E402


class FakeBridge(object):
    """伪 Kotlin 派发器：observe 返回预置树；grab_frame 返回预置 b64。"""

    def __init__(self, tree=None, frame_ok=True):
        self.tree = tree if tree is not None else []
        self.frame_ok = frame_ok
        self.calls = []

    def call(self, name, args_json):
        self.calls.append((name, args_json))
        if name == "observe":
            return json.dumps({"ok": True, "tree": self.tree})
        if name == "grab_frame":
            if self.frame_ok:
                return json.dumps({"ok": True, "w": 100, "h": 200,
                                   "jpeg_b64": "ZmFrZQ==", "ms": 42, "frame_age_ms": 7})
            return json.dumps({"ok": False, "error": "projection not started"})
        return json.dumps({"ok": True})


class FakeVision(object):
    """替身 VisionClient：记录调用，返回预置结果。"""

    last_instance = None

    def __init__(self, base_url, api_key, model, timeout=45):
        self.base_url, self.api_key, self.model = base_url, api_key, model
        FakeVision.last_instance = self

    def chat_vision(self, prompt, jpeg_b64):
        self.prompt, self.jpeg = prompt, jpeg_b64
        return {"ok": True, "text": "屏幕中上方有一个红色按钮", "latency_ms": 123}


def make_tools():
    return bridge._make_tools()


def teardown():
    bridge._bridge = None
    bridge._vision_cfg = None
    import hachimi_kernel.vision_client as vc
    vc.VisionClient = _real_vision


def setup_stub():
    # bridge._look 函数内延迟 import，须替换模块属性才能打桩
    import hachimi_kernel.vision_client as vc
    vc.VisionClient = FakeVision


_real_vision = VisionClient


# ---------------- VisionClient 载荷 ----------------

def test_vision_payload_shape():
    c = VisionClient("https://v.example.com/v1", "k", "vl-model")
    p = c.build_payload("描述屏幕", "QUJD")
    assert p["model"] == "vl-model" and p["max_tokens"] >= 500
    assert p["thinking"] == {"type": "disabled"}
    content = p["messages"][0]["content"]
    assert content[0] == {"type": "text", "text": "描述屏幕"}
    assert content[1]["type"] == "image_url"
    assert content[1]["image_url"]["url"] == "data:image/jpeg;base64,QUJD"
    assert c.base_url == "https://v.example.com/v1"
    # thinking=False 时不带字段
    assert "thinking" not in c.build_payload("x", "QUJD", thinking=False)


def test_vision_payload_split_shape():
    c = VisionClient("https://v.example.com/v1", "k", "vl-model")
    p = c.build_payload("主色？", "QUJD", split=True)
    m0, m1 = p["messages"]
    assert m0["content"][0]["type"] == "image_url"
    assert m1["content"] == "主色？"


def test_vision_400_falls_back_to_split():
    """标准格式 400 → 自动学习为拆分格式并成功（网关兼容路径）。"""
    import io
    import urllib.error
    import hachimi_kernel.vision_client as vc
    calls = []

    def fake_post(url, body, api_key, timeout):
        calls.append(body)
        if len(body["messages"]) == 1:
            raise urllib.error.HTTPError(url, 400, "bad",
                                         {"Content-Type": "application/json"},
                                         io.BytesIO(b'{"error":"content required"}'))
        return {"choices": [{"message": {"content": "红色"}}]}

    real_post = vc._post_json
    vc._post_json = fake_post
    vc._SPLIT_MODE.clear()
    vc._NO_THINKING.clear()
    try:
        c = VisionClient("https://g.example.com/v1", "k", "m")
        r = c.chat_vision("主色？", "QUJD")
        assert r["ok"] is True and r["text"] == "红色", r
        # 降级链：标准+thinking → 标准+无thinking → 拆分格式（3 次）
        assert len(calls) == 3, len(calls)
        assert "thinking" not in calls[1], calls[1]
        assert vc._SPLIT_MODE["https://g.example.com/v1"] is True
        assert vc._NO_THINKING["https://g.example.com/v1"] is True
    finally:
        vc._post_json = real_post
        vc._SPLIT_MODE.clear()
        vc._NO_THINKING.clear()


def test_vision_reasoning_fallback():
    """推理型模型 content 为空 → 读 reasoning_content。"""
    import hachimi_kernel.vision_client as vc

    def fake_post(url, body, api_key, timeout):
        return {"choices": [{"message": {"content": None,
                                         "reasoning_content": "答案是红色"}}]}

    real_post = vc._post_json
    vc._post_json = fake_post
    try:
        c = VisionClient("https://v.example.com/v1", "k", "m")
        r = c.chat_vision("主色？", "QUJD")
        assert r["ok"] is True and r["text"] == "答案是红色" and r["via_reasoning"] is True
    finally:
        vc._post_json = real_post


# ---------------- set_vision 开关 ----------------

def test_set_vision_toggle():
    r = bridge.set_vision("", "k", "m")
    assert r["vision_enabled"] is False and bridge._vision_cfg is None
    r = bridge.set_vision("https://v.example.com/v1", "k", "m")
    assert r["vision_enabled"] is True and bridge._vision_cfg["model"] == "m"
    teardown()


# ---------------- look 工具 ----------------

def test_look_not_configured_is_structured_error():
    bridge._vision_cfg = None
    tools = make_tools()
    r = tools["look"]["call"]({})
    assert r["ok"] is False and "vision not configured" in r["error"]
    teardown()


def test_look_grab_failure_structured():
    bridge._vision_cfg = {"base_url": "https://v.example.com/v1", "api_key": "k", "model": "m"}
    bridge._bridge = FakeBridge(frame_ok=False)
    tools = make_tools()
    r = tools["look"]["call"]({})
    assert r["ok"] is False and "grab_frame" in r["error"]
    teardown()


def test_look_ok_returns_description():
    setup_stub()
    bridge._vision_cfg = {"base_url": "https://v.example.com/v1", "api_key": "k", "model": "m"}
    bridge._bridge = FakeBridge()
    tools = make_tools()
    r = tools["look"]["call"]({"question": "有什么按钮？"})
    assert r["ok"] is True and "红色按钮" in r["description"]
    assert r["grab_ms"] == 42 and r["vision_ms"] == 123
    teardown()


# ---------------- observe 全盲自动兜底 ----------------

def test_observe_fallback_on_empty_tree():
    setup_stub()
    bridge._vision_cfg = {"base_url": "https://v.example.com/v1", "api_key": "k", "model": "m"}
    bridge._bridge = FakeBridge(tree=[])   # a11y 全盲：树为空
    tools = make_tools()
    r = tools["observe"]["call"]({"compact": True})
    assert r.get("vision_fallback") is True
    assert "红色按钮" in r.get("visual", "")
    assert r["ok"] is True
    teardown()


def test_observe_no_fallback_when_tree_present():
    bridge._vision_cfg = {"base_url": "https://v.example.com/v1", "api_key": "k", "model": "m"}
    tree = [{"view_id": "a/btn", "text": "确定", "clickable": True}]
    bridge._bridge = FakeBridge(tree=tree)
    tools = make_tools()
    r = tools["observe"]["call"]({"compact": True})
    assert "vision_fallback" not in r and r["tree"] == tree
    teardown()


def test_observe_no_fallback_when_vision_off():
    bridge._vision_cfg = None
    bridge._bridge = FakeBridge(tree=[])
    tools = make_tools()
    r = tools["observe"]["call"]({"compact": True})
    assert r["ok"] is True and "vision_fallback" not in r   # 退化为 P0 行为，不报错
    teardown()


# ---------------- 工具面完整性 ----------------

def test_tool_specs_include_look():
    tools = make_tools()
    assert "look" in tools
    assert "自绘" in tools["look"]["description"] or "视觉" in tools["look"]["description"]
    teardown()


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for fn in fns:
        try:
            fn()
            print("[PASS]", fn.__name__)
        except AssertionError as e:
            failed += 1
            print("[FAIL]", fn.__name__, "--", e)
    print("== %d/%d passed ==" % (len(fns) - failed, len(fns)))
    sys.exit(1 if failed else 0)
