# -*- coding: utf-8 -*-
"""M3' kernel-side PC tests: fake-completion rejection, trajectory persistence +
export, pause/stop controls, narrative sink events (bridge-level, fake dispatcher)."""
import json
import os
import sys
import tempfile
import threading
import time

tmp = tempfile.mkdtemp()
os.environ["HACHIMI_DATA_DIR"] = tmp
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "kernel"))

from hachimi_kernel import bridge


class FakeDispatcher(object):
    """原语派发替身：observe 返回可配置树，其余原语 ok。"""
    def __init__(self, tree_text="111111"):
        self.tree_text = tree_text
        self.calls = []

    def call(self, name, args_json):
        self.calls.append(name)
        if name == "observe":
            return json.dumps({"ok": True, "primitive": "observe", "tree": [
                {"view_id": "n1", "cls": "TextView", "text": self.tree_text,
                 "desc": "", "clickable": True, "editable": False,
                 "bounds": [0, 0, 100, 50]}]}, ensure_ascii=False)
        return json.dumps({"ok": True, "primitive": name})

    def set_tree(self, t):
        self.tree_text = t


events = []


class FakeSink(object):
    def push(self, s):
        events.append(json.loads(s))


bridge.register_bridge(FakeDispatcher())
bridge.register_narrative(FakeSink())

# ---- 1) fake completion must be rejected (M3' DoD 6) ----
r = bridge.start_task(json.dumps({
    "objective": "t1 fake", "done_when": "ZZZ-NOT-ON-SCREEN",
    "max_steps": 3,
    "script": [{"name": "task_done", "args": {"summary": "lying done"}}]}))
time.sleep(1.0)
st = bridge.status()
assert st["phase"] == "error", st
assert st["summary"]["task_done_rejected"] >= 1, st
assert st["summary"]["status"] == "incomplete", st
print("1) fake completion rejected: task_done_rejected=1 status=incomplete OK")

# ---- 2) happy path: trajectory persisted + exportable (M3' DoD 5) ----
r = bridge.start_task(json.dumps({
    "objective": "t2 notes", "done_when": "111111", "max_steps": 6,
    "script": [{"name": "launch_app", "args": {"package": "org.fossify.notes"}},
               {"name": "wait", "args": {"seconds": 0.1}},
               {"name": "task_done", "args": {"summary": "verified"}}]}))
time.sleep(1.0)
st = bridge.status()
assert st["phase"] == "done", st
run_id = st["run_id"]
assert "task_id" in st, st
ex = bridge.trajectory_export(run_id)
assert ex["ok"] and len(ex["lines"]) == 3, ex
kinds = {l.get("kind", "step") for l in ex["lines"]}
run_rec = [l for l in ex["lines"] if l.get("run_id") and l.get("objective") is not None]
print("2) export lines=%d run_record_on_disk=%s path=%s" % (
    len(ex["lines"]), os.path.exists(ex["path"]), ex["path"]))
assert os.path.exists(ex["path"])

# ---- 3) pause gate: loop blocks at step boundary, resume finishes ----
controls = {"paused": True, "stopped": False}   # start paused -> blocked before any brain call
from hachimi_kernel.mini_loop import MiniLoop


class SeqBrain(object):
    def __init__(self):
        self.script = [{"name": "observe", "args": {}},
                       {"name": "observe", "args": {}},
                       {"name": "task_done", "args": {"summary": "ok"}}]

    def chat(self, messages, tools):
        return {"content": None, "tool_calls": [self.script.pop(0)]}


done_holder = {}
loop = MiniLoop(objective="p", done_when="111111",
                tools=bridge._tools_with_verify(), brain=SeqBrain(),
                config={"max_steps": 6, "verify_tool": "verify_done"},
                controls=controls,
                on_finish=lambda out, s: done_holder.update(out))
th = threading.Thread(target=loop.run, daemon=True)
th.start()
time.sleep(0.7)
assert th.is_alive(), "loop should be blocked while paused"
assert loop.brain_calls == 0, loop.brain_calls
controls["paused"] = False
th.join(timeout=5)
assert not th.is_alive() and done_holder.get("status") == "done", done_holder
assert loop.brain_calls == 3, loop.brain_calls
print("3) start-paused blocked (0 brain calls), resume -> done with 3 calls OK")

# ---- 4) stop during pause -> incomplete without extra brain call ----
controls2 = {"paused": True, "stopped": False}
holder2 = {}
loop2 = MiniLoop(objective="p2", done_when="x", tools=bridge._tools_with_verify(),
                 brain=SeqBrain(), config={"max_steps": 6}, controls=controls2,
                 on_finish=lambda out, s: holder2.update(out))
th2 = threading.Thread(target=loop2.run, daemon=True)
th2.start()
time.sleep(0.5)
controls2["stopped"] = True
th2.join(timeout=5)
assert holder2.get("status") == "incomplete", holder2
assert holder2.get("summary") == "stopped by request", holder2
print("4) stop-during-pause -> incomplete 'stopped by request' OK")

# ---- 5) narrative events flow ----
kinds = [e.get("event") for e in events]
assert "step" in kinds and "finish" in kinds, kinds
print("5) narrative events: %d events, kinds=%s" % (len(events), sorted(set(kinds))))

print("ALL BRIDGE TESTS PASS; data dir:", tmp)
