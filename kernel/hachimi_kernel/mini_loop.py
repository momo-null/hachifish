# -*- coding: utf-8 -*-
"""hachimi_kernel.mini_loop —— 极简 ReAct loop（M2' 主路线）。

语义参照桌面 OmniAgent `omni_core/local/loop/core.py`（红线 R6：上游先行，本仓库跟随移植，
漂移 = bug）。设计要点（架构 §3.2 / §4.2）：

- **dict 边界**：工具参数/返回、消息、轨迹全部 JSON 可序列化 dict——与 Kotlin/Chaquopy
  边界同构，与桌面轨迹扁平 schema（state/action/result + verified/retry 质量信号）对齐。
- **感知由 agent 自主选择**（桌面"感知工具三角"语义）：loop 不自动 observe，工具面里放
  observe，模型自己决定何时看。
- **task_done 强制 verify**（桌面 §15.3 语义）：config 提供 verify 工具名时，task_done 必须
  先跑 verify；未通过 → 拒绝并回 observe 重规划，防幻觉式完成。
- **卡住检测**：连续 3 次相同 (tool, args) → 注入升级提示；第 4 次 → 判 stuck 终止。
- **预算**：max_steps / 墙钟预算，超限按 incomplete 终止（不伪装成功）。
- **收尾钩子**：on_finish 即桌面 `tool_loop._finish` 的 Curator 触发点（K 闭环四步挂载处）。

不引入 agents SDK / LangGraph / pydantic（spike 定案，评审 §2.1）；仅标准库。
"""

import json
import time

# 默认预算（手机域口径，预注册 §5.3；PC 对拍时可覆盖）
DEFAULT_MAX_STEPS = 24
DEFAULT_WALL_CLOCK_S = 600.0
STUCK_NOTE = 3                # 连续相同动作达到该次数 → 注入升级提示
STUCK_GIVE_UP = 4             # 达到该次数判 stuck 终止

SYSTEM_RULES = (
    "你通过调用工具完成任务。规则：\n"
    "1. 每轮调用一个工具；参数必须是合法 JSON。\n"
    "2. 动手前先 observe 观察界面，再决策；不要凭空猜测控件 id。\n"
    "3. 任务全部完成且自检通过后，才可调用 task_done；未完成不得谎报。\n"
    "4. task_done 会被强制校验（verify），失败会被拒绝并要求继续。"
)


class MiniLoop(object):
    """单任务 ReAct loop。

    参数
    ----
    objective : str
    done_when : str            完成判据（传给 verify 工具与 system prompt）
    constraints : str          可选约束
    tools : dict               name -> {"description": str, "parameters": dict,
                                      "call": fn(args: dict) -> dict}
                               注意：不含 task_done（loop 内建）；verify 工具按名单引用。
    brain : object             .chat(messages: list, tools: list) ->
                               {"content": str|None, "tool_calls": [{"name": str, "args": dict}]}
                               brain 侧负责真实的 LLM HTTP 调用（M2' 的 brain_client）。
    config : dict              可选键：max_steps / wall_clock_s / verify_tool(默认 "verify_done")
    on_step : fn(step: dict)   轨迹步回调（trajectory 落盘点）
    on_finish : fn(summary)    收尾回调（Curator 触发点，K 闭环）
    """

    def __init__(self, objective, done_when, tools, brain,
                 constraints="", config=None, on_step=None, on_finish=None):
        self.objective = objective
        self.done_when = done_when
        self.constraints = constraints
        self.tools = tools
        self.brain = brain
        self.config = config or {}
        self.on_step = on_step
        self.on_finish = on_finish
        self._verify_tool = self.config.get("verify_tool", "verify_done")
        self._messages = []
        self._pending_reply = None   # 当前轮 brain 回复（thinking 模型要求 reasoning_content 回传）
        self._last_identical = 0
        self._last_action_key = None
        self.task_done_rejected = 0

    # ---------------- 对外入口 ----------------

    def run(self):
        started = time.time()
        self._messages = [{"role": "system", "content": self._system_prompt()},
                          {"role": "user", "content": "目标: " + self.objective}]
        steps = []
        status = "incomplete"
        summary = ""
        max_steps = int(self.config.get("max_steps", DEFAULT_MAX_STEPS))
        deadline = started + float(self.config.get("wall_clock_s", DEFAULT_WALL_CLOCK_S))

        for i in range(1, max_steps + 1):
            if time.time() > deadline:
                status = "incomplete"
                summary = "墙钟预算耗尽"
                break
            reply = self.brain.chat(self._messages, self._tool_specs())
            self._pending_reply = reply
            calls = reply.get("tool_calls") or []
            if not calls:
                # 纯文本回复：注入提醒继续使用工具（防空转，不终止——桌面语义为升级判定，此处先简化）
                no_call = {"role": "assistant", "content": reply.get("content") or ""}
                if reply.get("reasoning_content"):
                    no_call["reasoning_content"] = reply["reasoning_content"]
                self._messages.append(no_call)
                self._messages.append({"role": "user",
                                       "content": "(系统) 请调用工具推进任务；完成后调用 task_done。"})
                continue
            for call in calls:
                name, args = call["name"], call.get("args") or {}
                result, step, terminal = self._dispatch(name, args, i)
                steps.append(step)
                if self.on_step:
                    self.on_step(step)
                if name == "task_done":
                    if step.get("verified"):
                        status, summary = "done", args.get("summary", "")
                    else:
                        status, summary = "incomplete", "task_done 被校验拒绝且预算耗尽"
                if terminal:
                    status, summary = "incomplete", "stuck: 连续相同动作 %d 次" % STUCK_GIVE_UP
                    break
                if name == "task_done" and step.get("verified"):
                    break
            if status == "done" or terminal:
                break

        out = {
            "status": status,
            "summary": summary,
            "steps": len(steps),
            "task_done_rejected": self.task_done_rejected,
            "wall_clock_s": round(time.time() - started, 3),
        }
        if self.on_finish:
            self.on_finish(out, steps)
        return out

    # ---------------- 分发与门控 ----------------

    def _dispatch(self, name, args, step_no):
        """执行一次工具调用并产出轨迹步。task_done 走强制校验门控。"""
        retry = 0
        if name == "task_done":
            verdict = self._verify()
            if verdict.get("passed"):
                result = {"ok": True, "note": "verified: " + verdict.get("reason", "")}
                verified = True
            else:
                self.task_done_rejected += 1
                retry = self.task_done_rejected
                result = {"ok": False,
                          "error": "task_done 被拒绝: " + verdict.get("reason", "")}
                verified = False
                # 拒绝注入回上下文，回 observe 重规划（桌面防幻觉式完成语义）
                self._messages.append({"role": "user",
                                       "content": "(系统) task_done 校验未通过: "
                                                  + verdict.get("reason", "") + "。请继续任务。"})
            step = self._record(step_no, name, args, result, verified, retry)
            self._push_tool_result(name, args, result)
            return result, step, False

        if name not in self.tools:
            result = {"ok": False, "error": "unknown tool: " + name}
            step = self._record(step_no, name, args, result, None, 0)
            self._push_tool_result(name, args, result)
            return result, step, False

        # 卡住检测（升级判定子集）
        key = name + "|" + json.dumps(args, sort_keys=True, ensure_ascii=False)
        if key == self._last_action_key:
            self._last_identical += 1
        else:
            self._last_identical, self._last_action_key = 1, key
        if self._last_identical >= STUCK_GIVE_UP:
            result = {"ok": False, "error": "stuck: 相同动作已连续 %d 次" % self._last_identical}
            step = self._record(step_no, name, args, result, None, 0)
            self._push_tool_result(name, args, result)
            return result, step, True

        try:
            result = self.tools[name]["call"](args)
            if not isinstance(result, dict):
                result = {"ok": True, "data": result}
        except Exception as e:  # 结构化错误，不抛穿 loop（桌面 tool-loop 约定）
            result = {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}

        if self._last_identical == STUCK_NOTE:
            result = dict(result)
            result["stuck_warning"] = "该动作已连续 %d 次，请换思路或先 observe" % self._last_identical
        step = self._record(step_no, name, args, result, None, 0)
        self._push_tool_result(name, args, result)
        return result, step, False

    def _verify(self):
        """task_done 强制校验。verify 工具返回 {"passed": bool, "reason": str}。"""
        if self._verify_tool and self._verify_tool in self.tools:
            try:
                out = self.tools[self._verify_tool]["call"]({"done_when": self.done_when})
                return {"passed": bool(out.get("passed")),
                        "reason": out.get("reason", "")}
            except Exception as e:
                return {"passed": False, "reason": "verify 工具异常: %s" % e}
        # 无 verify 工具：信任大脑（桌面语义：无条件时信任）
        return {"passed": True, "reason": "no verify tool configured"}

    # ---------------- 轨迹与消息 ----------------

    def _record(self, step_no, name, args, result, verified, retry):
        """扁平轨迹 schema（桌面 §5.2：state/action/result + verified/retry）。"""
        return {
            "step": step_no,
            "action": {"tool": name, "args": args},
            "result": result,
            "verified": verified,
            "retry": retry,
            "ts": round(time.time(), 3),
        }

    def _push_tool_result(self, name, args, result):
        # assistant 消息保留内核内部 schema（{name, args}）；wire 包装（id/tool_call_id/
        # reasoning_content 回传）由 brain 侧负责
        reply = self._pending_reply or {}
        m = {"role": "assistant", "content": reply.get("content"),
             "tool_calls": [{"name": name, "args": args}]}
        if reply.get("reasoning_content"):
            m["reasoning_content"] = reply["reasoning_content"]
        self._messages.append(m)
        self._messages.append({"role": "tool", "name": name,
                               "content": json.dumps(result, ensure_ascii=False)})

    def _tool_specs(self):
        specs = [{"name": n, "description": t.get("description", ""),
                  "parameters": t.get("parameters", {"type": "object"})}
                 for n, t in self.tools.items()]
        specs.append({
            "name": "task_done",
            "description": "任务完成时调用。会被强制校验，失败将被拒绝。",
            "parameters": {"type": "object",
                           "properties": {"summary": {"type": "string"}},
                           "required": ["summary"]},
        })
        return specs

    def _system_prompt(self):
        names = ", ".join(sorted(list(self.tools.keys()) + ["task_done"]))
        p = ("目标: %s\n完成判据: %s\n" % (self.objective, self.done_when))
        if self.constraints:
            p += "约束: %s\n" % self.constraints
        p += "可用工具: %s\n%s" % (names, SYSTEM_RULES)
        return p
