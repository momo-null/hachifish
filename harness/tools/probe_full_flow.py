# -*- coding: utf-8 -*-
"""Full-fidelity device-flow reproduction: 11 tools, real MiniLoop system prompt,
same objective; loop until the device round-4 400 reproduces; then bisect."""
import sys
sys.path.insert(0, r"D:/code/hachimi/kernel")
from hachimi_kernel.brain_client import BrainClient, BrainError
from hachimi_kernel.mini_loop import MiniLoop

cli = BrainClient("https://api.deepseek.com/v1",
                  "REDACTED_API_KEY", "deepseek-flash", thinking={"type": "disabled"})

FAKE_TREE = '{"ok": true, "primitive": "observe", "tree": ' \
            '[{"view_id": "org.fossify.notes:id/note_1", "cls": "TextView", "text": "111111555555555555", ' \
            '"desc": "", "clickable": true, "editable": false, "bounds": [0, 300, 1080, 420]}]}'
FAKE = {
    "launch_app": '{"ok": true, "primitive": "launch_app", "package": "org.fossify.notes"}',
    "wait": '{"ok": true, "waited": 2.0}',
    "observe": FAKE_TREE,
    "verify_done": '{"passed": true, "reason": "text probe: 111111"}',
    "task_done": '{"ok": true, "note": "verified"}',
}

class FakeBridgeBrain(object):
    """Real BrainClient + real MiniLoop, tools faked to return the same shapes."""
    def __init__(self):
        self.client = cli
        self.round = 0

    def chat(self, messages, tools):
        self.round += 1
        try:
            out = self.client.chat(messages, tools)
            rc = out.get("reasoning_content")
            print("  round%d: 200 reasoning=%s" % (self.round, bool(rc)))
            return out
        except BrainError as e:
            print("  round%d: FAIL %s" % (self.round, str(e)[:130]))
            for i, m in enumerate(messages):
                tag = "REASON" if m.get("reasoning_content") else "      "
                print("    msg[%d] %-9s %s tc=%s" % (i, m.get("role"), tag,
                                                     bool(m.get("tool_calls"))))
            raise SystemExit(0)

def make_tool(name):
    def call(args):
        return __import__("json").loads(FAKE[name])
    return {"call": call}

import json
for trial in range(1, 4):
    print("trial %d" % trial)
    loop = MiniLoop(
        objective="Launch the app with package name org.fossify.notes, observe the screen "
                  "to confirm the note content containing 111111 is visible, then call task_done",
        done_when="111111",
        tools={n: make_tool(n) for n in ["observe", "wait", "tap_by_id", "tap_xy",
                                          "type_text", "gesture", "screenshot",
                                          "launch_app", "press", "verify_done"]},
        brain=FakeBridgeBrain(),
        config={"max_steps": 10, "verify_tool": "verify_done"})
    out = loop.run()
    print("  result: %s steps=%s" % (out["status"], out["steps"]))
print("ALL TRIALS DONE")
