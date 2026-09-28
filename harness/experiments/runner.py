# -*- coding: utf-8 -*-
"""M4' 实验运行器（preregistration §5.3：随机顺序、断点续跑、异常恢复、指标采集）。

用法：
  python runner.py --plan plan_a.json
  python runner.py --plan plan_a.json --only T1 --runs 1   # 冒烟

计划文件（plan_<group>.json）：
  {"plan_id": "gA_smoke", "group": "A", "snapshot_id": null, "mode": "foreground",
   "task_ids": ["T1","T2"], "runs_per_task": 30, "seed": 42}

产出（harness/experiments/results/<plan_id>/）：
  results.jsonl   # 每 run 一行：task/level/run_id/status/steps/brain_calls/wall_clock/...
  checkpoint.json # 已完成 run 集合（断点续跑：重跑自动跳过 done 的 run）
"""
import argparse
import json
import os
import random
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
import sys
sys.path.insert(0, os.path.dirname(HERE))
from _env import adb, LOOPBACK, list_devices, resolve_serial

MAX_RETRIES = 2
POLL_S = 2.0
RUN_TIMEOUT_S = 900


def http(method, path, body=None, timeout=15):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(LOOPBACK + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def parse_repr(text):
    """解析回环响应。

    2026-09-28 实录：/health 与 /trajectory 返回的是 JSON，而这里原来只用
    ast.literal_eval（原为 python-repr 形态设计）-> JSON 的 true/false 被当成
    Python 名字抛 `ValueError: malformed node or string`，health() 因此恒返回
    a11y=False：**每一轮 run 都误判无障碍掉线**，触发一次无效的 a11y_recover。
    先 repr 后 JSON（两条兼容，谁成功算谁）。"""
    import ast

    try:
        return ast.literal_eval(text)
    except Exception:
        return json.loads(text)


def health():
    """(device_ok, a11y_ok)。设备序列号经 _env.resolve_serial 自动取唯一设备。"""
    serial = resolve_serial()
    if not serial:
        devs = list_devices()
        return (False, False) if len(devs) != 1 else (True, False)
    devs = adb("devices")
    device_ok = serial in devs and "\tdevice" in devs
    a11y_ok = False
    if device_ok:
        adb("forward", "tcp:18071", "tcp:8071")
        try:
            a11y_ok = parse_repr(http("GET", "/health", timeout=8)).get("a11y") is True
        except Exception:
            a11y_ok = False
    return device_ok, a11y_ok


def ensure_healthy(reset_mod):
    """异常恢复（DoD⑤）：掉线重连 → 无障碍恢复配方 → 放弃前报错。"""
    for attempt in range(4):
        device_ok, a11y = health()
        if device_ok and a11y:
            return
        print("[recover] device_ok=%s a11y=%s (attempt %d)" % (device_ok, a11y, attempt + 1))
        if not device_ok:
            print("[recover] USB offline; replug needed - waiting 30s")
            time.sleep(30)
            continue
        if not a11y:
            if reset_mod.a11y_recover():
                print("[recover] a11y restored")
                return
        time.sleep(5)
    raise RuntimeError("device/a11y unrecoverable after 4 attempts")


def load_tasks():
    with open(os.path.join(HERE, "tasks.json"), encoding="utf-8") as f:
        return {t["id"]: t for t in json.load(f)["tasks"]}


def build_schedule(plan, tasks):
    runs = []
    for tid in plan["task_ids"]:
        t = tasks[tid]
        for i in range(plan.get("runs_per_task", 1)):
            runs.append({"key": "%s#%02d" % (tid, i + 1), "task": t})
    random.Random(plan.get("seed", 42)).shuffle(runs)
    return runs


def run_one(run, plan, reset_mod):
    """执行单 run：reset -> /task -> poll -> 指标。返回结果 dict。"""
    t = run["task"]
    reset_mod.reset_task(t)
    body = {"objective": t["objective"], "done_when": t["done_when"],
            "max_steps": t["max_steps"], "mode": plan.get("mode", "foreground")}
    if plan.get("group"):
        body["group"] = plan["group"]
    if plan.get("snapshot_id"):
        body["snapshot_id"] = plan["snapshot_id"]
    resp = parse_repr(http("POST", "/task", body))
    run_id = resp.get("run_id")
    t0 = time.time()
    status = "timeout"
    while time.time() - t0 < RUN_TIMEOUT_S:
        time.sleep(POLL_S)
        s = parse_repr(http("GET", "/status", timeout=10))
        if s.get("phase") in ("done", "error"):
            status = s.get("phase")
            break
    summary = (s.get("summary") or {})
    result = {
        "plan": plan["plan_id"], "group": plan.get("group", "A"),
        "run_key": run["key"], "task": t["id"], "level": t["level"],
        "run_id": run_id, "status": status,
        "loop_status": summary.get("status"),
        "success": status == "done" and summary.get("status") == "done",
        "steps": summary.get("steps"), "brain_calls": summary.get("brain_calls"),
        "task_done_rejected": summary.get("task_done_rejected"),
        "wall_clock_s": summary.get("wall_clock_s"),
        "objective": t["objective"][:60], "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    try:
        traj = parse_repr(http("GET", "/trajectory/%s" % run_id, timeout=20))
        result["trajectory_lines"] = len(traj.get("lines", []))
    except Exception:
        result["trajectory_lines"] = None
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--only", nargs="*", help="limit to task ids (smoke)")
    ap.add_argument("--runs", type=int, help="override runs_per_task")
    args = ap.parse_args()

    sys.path.insert(0, HERE)
    import reset as reset_mod

    with open(args.plan, encoding="utf-8") as f:
        plan = json.load(f)
    tasks = load_tasks()
    plan.setdefault("plan_id", os.path.splitext(os.path.basename(args.plan))[0])
    if args.only:
        plan["task_ids"] = args.only
    if args.runs:
        plan["runs_per_task"] = args.runs

    out_dir = os.path.join(HERE, "results", plan["plan_id"])
    os.makedirs(out_dir, exist_ok=True)
    results_path = os.path.join(out_dir, "results.jsonl")
    ckpt_path = os.path.join(out_dir, "checkpoint.json")
    done_keys = {}
    if os.path.isfile(ckpt_path):
        with open(ckpt_path, encoding="utf-8") as f:
            done_keys = json.load(f)

    schedule = build_schedule(plan, tasks)
    print("plan %s: %d runs (group=%s snapshot=%s)" % (
        plan["plan_id"], len(schedule), plan.get("group"), plan.get("snapshot_id")))

    ensure_healthy(reset_mod)
    outcomes = {}
    for run in schedule:
        key = run["key"]
        prior = done_keys.get(key)
        if prior and prior.get("success"):
            print("[skip] %s already done" % key)
            continue
        attempts = 0
        while attempts <= MAX_RETRIES:
            attempts += 1
            tag = key if attempts == 1 else "%s(retry%d)" % (key, attempts - 1)
            print("[run] %s -> %s" % (tag, run["task"]["title"]))
            try:
                ensure_healthy(reset_mod)
                result = run_one(run, plan, reset_mod)
            except Exception as e:
                result = {"plan": plan["plan_id"], "run_key": key,
                          "task": run["task"]["id"], "status": "harness_error",
                          "success": False, "error": "%s: %s" % (type(e).__name__, e),
                          "ts": time.strftime("%Y-%m-%dT%H:%M:%S")}
            result["attempt"] = attempts
            with open(results_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(result, ensure_ascii=False) + "\n")
            if result.get("success") or result.get("status") == "done":
                done_keys[key] = {"success": True, "run_id": result.get("run_id")}
                break
            print("[fail] %s (attempt %d/%d)" % (key, attempts, MAX_RETRIES + 1))
        outcomes[key] = result
        with open(ckpt_path, "w", encoding="utf-8") as f:
            json.dump(done_keys, f, ensure_ascii=False, indent=1)

    done = sum(1 for r in outcomes.values() if r.get("success"))
    print("PLAN DONE: %d/%d success" % (done, len(outcomes)))


if __name__ == "__main__":
    main()
