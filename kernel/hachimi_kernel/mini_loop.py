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
    "3. 是否完成由你对照目标与完成判据自行判断；确认全部完成后才调用 task_done，"
    "未完成不得谎报。\n"
    "4. 若系统启用强制校验，task_done 未通过会被拒绝并要求继续——此时换做法重试，"
    "不要重复同一动作；未启用校验时完成即可直接调用。\n"
    "5. 输入或编辑后必须再 observe 复核是否生效，并按界面需要点确认/保存；\n"
    "   不得重复提交完全相同的输入（重复意味着上一步没生效，应换做法，而不是再输一遍）。\n"
    "6. 不要自行调用校验类工具；是否完成只以 task_done 为准（若启用校验，由系统自动执行）。\n"
    "7. 若本轮只是闲聊或提问，无需调用工具，直接用文字回答即可（说完就停）。"
)

# 同名工具连续失败达该次数 → 注入「换方案」提醒（只注一次后重新计数，防刷屏）
REPEAT_GUARD_THRESHOLD = 3


def _is_context_overflow(msg):
    """上下文溢出判定（父项目 errors.py 语义 stdlib 化）：按通用协议关键词。"""
    m = str(msg or "").lower()
    return ("context length" in m or "context window" in m
            or "maximum context" in m)


def _hard_keep(messages, keep_rounds=8):
    """硬滑窗（父项目 sdk_loop._hard_keep 语义 stdlib 化）：保留 system + 最近
    keep_rounds 轮（一轮 = 一次 assistant 工具调用），更早历史无条件丢弃。"""
    if len(messages) <= 2:
        return list(messages)
    system = [m for m in messages if m.get("role") == "system"]
    rest = [m for m in messages if m.get("role") != "system"]
    rounds = [i for i, m in enumerate(rest)
              if m.get("role") == "assistant" and m.get("tool_calls")]
    if len(rounds) <= keep_rounds:
        return list(messages)
    cut = rest[rounds[-keep_rounds]]
    return system + rest[rest.index(cut):]


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
                 constraints="", config=None, on_step=None, on_finish=None,
                 controls=None, verify_fn=None,
                 history=None, message="", on_reply=None):
        self.objective = objective
        self.done_when = done_when
        self.constraints = constraints
        self.tools = tools
        self.brain = brain
        self.config = config or {}
        self.on_step = on_step
        self.on_finish = on_finish
        # ---- K1 统一入口（P2）----
        # history：同 task 跨轮历史（user/assistant 交替，messages.jsonl 恢复）
        # message：本轮用户消息（统一入口形态；空 = 旧表单"目标: objective"路径）
        # on_reply：纯文本回复回调（chat 终态 / 口播，bridge 侧 emit message 事件）
        self._history = list(history or [])
        self._message = (message or "").strip()
        self.on_reply = on_reply
        # 外部控制面（bridge 共享 dict）：{"paused": bool, "stopped": bool}
        # 暂停在步边界生效（下一步前阻塞，暂停时长不计入墙钟预算）
        self._controls = controls or {}
        self._verify_tool = self.config.get("verify_tool", "verify_done")
        # 校验优先走内核注入的 verify_fn（不进模型工具面，防模型自己调校验刷假绿）；
        # 未注入时回退旧语义：从 tools 里按名字取（PC 对拍用例走这条）。
        self._verify_fn = verify_fn
        # 完成判断语义（2026-09-30 对齐桌面 OmniAgent）：有无校验面决定 task_done
        # 的提示口径——无校验面 = 信任大脑（桌面语义：无条件时信任）。
        self._has_verify = self._verify_fn is not None or (
            bool(self._verify_tool) and self._verify_tool in self.tools)
        self._messages = []
        self._pending_reply = None   # 当前轮 brain 回复（thinking 模型要求 reasoning_content 回传）
        self._last_identical = 0
        self._last_action_key = None
        self.task_done_rejected = 0
        self.brain_calls = 0
        # RepeatGuard（父项目 T4.5 语义）：同名工具连续失败计数 + 待注入提醒
        self._fail_streak = {}
        self._fail_reminders = []
        # verify_stopped 例外（父项目 L1274）：task_done 被拒后紧接的纯文本
        # 不可触发 chat 终态（防「被拒后口头宣布完成」绕过校验）
        self._recent_verify_reject = False

    # ---------------- 对外入口 ----------------

    def run(self):
        started = time.time()
        # K1 统一入口形态：message/history 任一非空 → 会话式组装（本轮消息即指令，
        # 历史提供上下文）；两者皆空 → 旧表单路径逐字节不变（回归红线）。
        unified = bool(self._message or self._history)
        self._messages = [{"role": "system", "content": self._system_prompt()}]
        if unified:
            self._messages.extend({"role": m.get("role"), "content": m.get("content")}
                                  for m in self._history)
            self._messages.append({"role": "user", "content": self._message or self.objective})
        else:
            self._messages.append({"role": "user", "content": "目标: " + self.objective})
        steps = []
        status = "incomplete"
        summary = ""
        reply_turn = False          # 本轮是否以纯文本收尾（chat 记账防停摆）
        overflow_retried = False    # 上下文溢出同一 run 只重试一次（父项目 T3.3）
        max_steps = int(self.config.get("max_steps", DEFAULT_MAX_STEPS))
        deadline = started + float(self.config.get("wall_clock_s", DEFAULT_WALL_CLOCK_S))

        for i in range(1, max_steps + 1):
            # 暂停门控：阻塞在步边界，暂停时长顺延墙钟预算
            if self._controls.get("paused"):
                pause_started = time.time()
                while self._controls.get("paused") and not self._controls.get("stopped"):
                    time.sleep(0.2)
                deadline += time.time() - pause_started
            if self._controls.get("stopped"):
                status, summary = "incomplete", "stopped by request"
                break
            if time.time() > deadline:
                status = "incomplete"
                summary = "墙钟预算耗尽"
                break
            # RepeatGuard 提醒（同名连续失败达阈值注入一次；成功清零在 _dispatch）
            if self._fail_reminders:
                note = self._fail_reminders.pop(0)
                self._messages.append({"role": "user", "content": note})
            # 异常自愈（父项目 A 系语义 stdlib 化）：溢出硬截重试一次；其余不崩 run
            try:
                reply = self.brain.chat(self._messages, self._tool_specs())
            except Exception as e:
                msg = "%s: %s" % (type(e).__name__, e)
                if _is_context_overflow(msg):
                    if not overflow_retried:
                        overflow_retried = True
                        self._messages = _hard_keep(self._messages)
                        continue
                    status, summary = "incomplete", "上下文长度超限（重试后仍溢出）"
                else:
                    status, summary = "incomplete", msg[:200]
                break
            self.brain_calls += 1
            self._pending_reply = reply
            calls = reply.get("tool_calls") or []
            content = (reply.get("content") or "").strip()
            # 口播穿插（K3，父项目 _merge_message_step 语义）：有动作也有话 → 先吐话
            if content and calls and self.on_reply:
                try:
                    self.on_reply(content)
                except Exception:
                    pass
            if not calls:
                if not content:
                    # 空回复无动作：防空转注入（旧语义保留）
                    no_call = {"role": "assistant", "content": reply.get("content") or ""}
                    if reply.get("reasoning_content"):
                        no_call["reasoning_content"] = reply["reasoning_content"]
                    self._messages.append(no_call)
                    self._messages.append({"role": "user",
                                           "content": "(系统) 请调用工具推进任务；完成后调用 task_done。"})
                    continue
                # ---- 方案 B 纯文本收尾（父项目 sdk_loop L1462 语义移植） ----
                done_msg = {"role": "assistant", "content": content}
                if reply.get("reasoning_content"):
                    done_msg["reasoning_content"] = reply["reasoning_content"]
                self._messages.append(done_msg)
                # verify_stopped 例外：被拒后改口纯文本 = 不许绕过校验收尾
                if self._recent_verify_reject:
                    self._recent_verify_reject = False
                    self._messages.append({"role": "user",
                                           "content": "(系统) 上次 task_done 校验未通过，"
                                                      "请继续推进任务，不要只是口头说明完成。"})
                    continue
                if self._has_verify:
                    # 有完成判据：先校验（过 = done；拒 = 注入继续，不假绿不误杀）
                    verdict = self._verify()
                    if verdict.get("passed"):
                        status, summary = "done", content
                        reply_turn = True
                        if self.on_reply:
                            try:
                                self.on_reply(content)
                            except Exception:
                                pass
                        break
                    self.task_done_rejected += 1
                    self._recent_verify_reject = True
                    self._messages.append({"role": "user",
                                           "content": "(系统) 你已停止输出工具调用，但完成条件"
                                                      "尚未满足：%s。请继续推进直到达成目标，"
                                                      "不要反复声明完成。" % verdict.get("reason", "")})
                    continue
                # 无验证条件：信任大脑（桌面语义）——纯文本即本轮答案，chat 终态
                status, summary = "chat", content
                reply_turn = True
                if self.on_reply:
                    try:
                        self.on_reply(content)
                    except Exception:
                        pass
                break
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

        if status == "incomplete" and not summary:
            summary = "步数预算 %d 步耗尽，任务未完成" % max_steps

        out = {
            "status": status,
            "summary": summary,
            # chat 终态也计 1 步（父项目 L1382 防记账停摆：纯文本回合是真实消耗）
            "steps": len(steps) + (1 if (reply_turn and status in ("chat", "done")) else 0),
            "brain_calls": self.brain_calls,
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
                # verify_stopped 标记：被拒后改口纯文本不可绕过校验（run 纯文本分支消费）
                self._recent_verify_reject = True
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
        # RepeatGuard（父项目 T4.5 语义）：同名工具连续**失败**达阈值 → 排队一条
        # 「换方案」提醒（run 循环顶部注入一次后计数清零，防刷屏）；成功清零。
        # 与 stuck 检测互补：stuck 管「完全相同动作」，本层管「同名不同参的连败」。
        if result.get("ok") is True:
            self._fail_streak.pop(name, None)
        else:
            streak = self._fail_streak.get(name, 0) + 1
            self._fail_streak[name] = streak
            if streak >= REPEAT_GUARD_THRESHOLD:
                self._fail_reminders.append(
                    "(系统) 工具 %s 已连续失败 %d 次。请更换执行方案"
                    "（换别的工具、换参数或换路径），不要继续重复同一失败调用。"
                    % (name, streak))
                self._fail_streak[name] = 0
        step = self._record(step_no, name, args, result, None, 0)
        self._push_tool_result(name, args, result, ref="step #%d" % step_no)
        return result, step, False

    def _verify(self):
        """task_done 强制校验。verify 返回 {"passed": bool, "reason": str}。"""
        if self._verify_fn is not None:
            try:
                # objective 一并传入：空 done_when 时校验方走目标关键短语弱探测（P0）
                out = self._verify_fn({"done_when": self.done_when,
                                       "objective": self.objective})
                return {"passed": bool(out.get("passed")),
                        "reason": out.get("reason", "")}
            except Exception as e:
                return {"passed": False, "reason": "verify 异常: %s" % e}
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

    def _push_tool_result(self, name, args, result, ref=""):
        # assistant 消息保留内核内部 schema（{name, args}）；wire 包装（id/tool_call_id/
        # reasoning_content 回传）由 brain 侧负责。
        # P2-R Phase 2b：进 LLM 消息前做上下文卸载（observe 完整树 → 可操作骨架，
        # 证据留在轨迹 step.result）；轨迹与 verify 均拿完整数据，不受影响。
        from hachimi_kernel.context_offload import offload_tool_result
        reply = self._pending_reply or {}
        m = {"role": "assistant", "content": reply.get("content"),
             "tool_calls": [{"name": name, "args": args}]}
        if reply.get("reasoning_content"):
            m["reasoning_content"] = reply["reasoning_content"]
        self._messages.append(m)
        self._messages.append({"role": "tool", "name": name,
                               "content": json.dumps(
                                   offload_tool_result(name, result, ref=ref),
                                   ensure_ascii=False)})

    def _tool_specs(self):
        specs = [{"name": n, "description": t.get("description", ""),
                  "parameters": t.get("parameters", {"type": "object"})}
                 for n, t in self.tools.items()]
        specs.append({
            "name": "task_done",
            "description": "任务完成时调用。" + ("会被强制校验，失败将被拒绝。"
                             if self._has_verify else "完成与否由你判断，不要谎报。"),
            "parameters": {"type": "object",
                           "properties": {"summary": {"type": "string"}},
                           "required": ["summary"]},
        })
        return specs

    def _system_prompt(self):
        names = ", ".join(sorted(list(self.tools.keys()) + ["task_done"]))
        p = ""
        # 角色卡注入（K0，redesign_plan）：稳定人格设定置于 prompt 首位（桌面语义：
        # character.md 随 system prompt 注入，命中前缀缓存）。文本由调用方传入
        # （bridge 读文件），loop 保持纯函数、无磁盘依赖；空文本零注入。
        persona = (self.config.get("persona") or "").strip()
        if persona:
            p += "# 助手人格设定\n%s\n\n" % persona
        # 弱注入块（K7a，桌面 §3.4 语义）：memory/技能摘要，自带弱化提示；
        # 空文本零注入。run 内由 config 一次性传入（逐字节稳定，前缀缓存友好）。
        knowledge = (self.config.get("knowledge") or "").strip()
        if knowledge:
            p += "%s\n\n" % knowledge
        unified = bool(self._message or self._history)
        if unified:
            # K1 统一入口形态：本轮消息即指令（用户消息在会话流末尾），
            # 大脑自决闲聊/执行（对齐桌面 /chat），不再写死「目标:」模板。
            p += "你是用户的手机助手。用户消息即本轮指令：闲聊或提问直接文字回答；"
            p += "需要操作手机时调用工具推进；确认全部完成后调用 task_done。\n"
            if self.done_when:
                p += "完成判据: %s\n" % self.done_when
        else:
            p += ("目标: %s\n完成判据: %s\n" % (self.objective, self.done_when))
        if self.constraints:
            p += "约束: %s\n" % self.constraints
        p += "可用工具: %s\n%s" % (names, SYSTEM_RULES)
        return p
