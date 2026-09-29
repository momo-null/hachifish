# -*- coding: utf-8 -*-
"""vision_client —— OpenAI 兼容视觉模型客户端（stdlib urllib，红线 R7）。

P1 观察兜底（product_milestones.md P1）：a11y 树看不清的界面（自绘/无控件 id），
以 grab_frame 的 JPEG base64 问 VLM。语义对齐 brain_client：同 URL 拼接
（base_url + /chat/completions）、Bearer 鉴权、image_url data URL 图片载荷。

兼容注记（2026-09-29 实测 discovery-api.intern-ai.org.cn）：
- 该网关会把「单消息多段 content」拆成多条消息再校验（标准格式 400）→ 请求先走
  标准格式，命中 400 自动降级为「图/文分两条消息」格式（对标准端点同样合法），
  成功格式按 base_url 缓存，进程内后续请求直走已知可用形态。
- 部分 VL 模型是推理型：content 为空时回退读 reasoning_content；max_tokens 默认
  1000 防止思考段截断导致空回复。
- 网关限流敏感（429 常见）：单次退避重试。
"""

import json
import time
import urllib.error
import urllib.request

# base_url -> True（该网关需要拆分格式）；命中 400 时学习并缓存
_SPLIT_MODE = {}
# base_url -> True（该网关拒绝 thinking 字段，须裸发）
_NO_THINKING = {}


def _post_json(url, body, api_key, timeout):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key},
        method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


class VisionClient(object):

    def __init__(self, base_url, api_key, model, timeout=60, max_tokens=1000):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.max_tokens = max_tokens

    def build_payload(self, prompt, jpeg_b64, split=False, thinking=True):
        """标准 OpenAI 视觉载荷（单消息 text+image 两段）；split=True 为
        网关兼容形态（图/文分两条 user 消息——两者对标准端点均合法）。
        thinking=True 附 thinking disabled：推理型 VL 模型不开会把预算烧进
        思考段（2026-09-29 实测 deepseek-v4-flash-vision：103s 空答 vs 1.5s）。"""
        image_part = {"type": "image_url",
                      "image_url": {"url": "data:image/jpeg;base64," + jpeg_b64}}
        if split:
            messages = [{"role": "user", "content": [image_part]},
                        {"role": "user", "content": prompt}]
        else:
            messages = [{"role": "user", "content":
                         [{"type": "text", "text": prompt}, image_part]}]
        payload = {"model": self.model, "messages": messages,
                   "max_tokens": self.max_tokens}
        if thinking:
            payload["thinking"] = {"type": "disabled"}
        return payload

    def chat_vision(self, prompt, jpeg_b64):
        """返回 {"ok": bool, "text"/"error": str, "latency_ms": int}。
        400 时按序降级（thinking → 格式拆分）并缓存学习结果；429 单次退避重试。"""
        t0 = time.time()
        lat = lambda: int((time.time() - t0) * 1000)
        split = _SPLIT_MODE.get(self.base_url, False)
        thinking = not _NO_THINKING.get(self.base_url, False)
        last_err = None
        for attempt in range(4):
            try:
                data = _post_json(self.base_url + "/chat/completions",
                                  self.build_payload(prompt, jpeg_b64, split, thinking),
                                  self.api_key, self.timeout)
            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8", "ignore")[:300]
                if e.code == 429 and attempt == 0:
                    time.sleep(1.5)
                    continue
                if e.code == 400:
                    # 降级链：先丢 thinking，再拆格式（各自学习缓存）
                    if thinking:
                        _NO_THINKING[self.base_url] = True
                        thinking = False
                        continue
                    if not split:
                        _SPLIT_MODE[self.base_url] = True
                        split = True
                        continue
                return {"ok": False, "latency_ms": lat(),
                        "error": "HTTP %d: %s" % (e.code, err_body)}
            except Exception as e:
                return {"ok": False, "latency_ms": lat(),
                        "error": "%s: %s" % (type(e).__name__, e)}
            try:
                msg = data["choices"][0]["message"]
            except (KeyError, IndexError):
                return {"ok": False, "latency_ms": lat(),
                        "error": "unexpected response shape (非 OpenAI 兼容视觉响应)"}
            text = (msg.get("content") or "").strip()
            if not text:
                # 推理型模型：最终答案可能在 reasoning_content（或被截断时只剩思考段）
                text = (msg.get("reasoning_content") or "").strip()
            return {"ok": bool(text), "text": text[:2000],
                    "via_reasoning": not bool(msg.get("content")),
                    "latency_ms": lat()}
        return {"ok": False, "latency_ms": lat(), "error": "unreachable: %s" % last_err}
