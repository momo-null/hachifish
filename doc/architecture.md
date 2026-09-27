# Hachimi 架构设计（权威 · v1.0）

> **状态**：本文为 Hachimi（手机版 OmniAgent）的**架构决策权威（single source of truth）**。
> **依据**：《落地可行性评审 v2》（`doc/mobile_research_feasibility_review_2026-09-26.md`，含 2026-09-26 spike 定案）+《预注册 v1.2 修订清单》（`doc/preregistration_v1.2_revisions.md`）。
> **配套**：红线见 `spec/redlines.md`（含检查方式）；里程碑见 `doc/milestones.md`。
> **上游**：OmniAgent `main`（X3 master spec 为桌面侧权威）；本仓库与上游的关系是**语义移植 + 同构对拍**，不是共享代码。

---

## 1. 定位与硬约束

把「手机 GUI agent」作为 OmniAgent 知识级自升级研究的实验场：

| 硬约束 | 含义 | 来源 |
|--------|------|------|
| 完全上机 | 运行时零 PC 依赖；adb 仅调试/实验通道 | 方案决策 2026-09-26 |
| 不 root | 仅官方 API：AccessibilityService + MediaProjection | 预注册 v1.1 §1.2 |
| BYOK 零服务器 | 用户自购 key，HTTPS 直连，无自有后端 | 预注册 v1.1 |
| Android 11+ 基线 | `takeScreenshot` 为 API 30+；8–10 为投屏兜底档 | 修订② |
| 研究优先 | Go .so / 虚拟副屏 / 锁屏队列不入研究范围 | 评审 §3.1 |

## 2. 总体架构

```plain
┌─────────────────────────── Android 真机 ───────────────────────────┐
│                                                                     │
│  ┌────────────────────────  Kotlin 壳  ────────────────────────┐    │
│  │  AccessibilityBridge   MediaProjectionBridge   GateUI       │    │
│  │  ForegroundService     SettingsUI + Keystore    LoopbackAPI │    │
│  └───────────────┬─────────────────────────────────────────────┘    │
│                  │ Chaquopy 边界（Java ↔ Python，单线程 executor）    │
│  ┌───────────────▼─────────  Python 内核  ─────────────────────┐    │
│  │  mini_loop ── brain_client(规划/VLM 双通道, httpx)           │    │
│  │     │                                                       │    │
│  │  tools: UI 桥工具面（observe/tap/type/gesture/screenshot…）──┼──┐ │
│  │     │                                                       │  │ │
│  │  knowledge: trajectory · curator · skill_library             │  │ │
│  │             world_model · knowledge_inject · task_store      │  │ │
│  │  runtime_paths（App 私有目录注入）                             │  │ │
│  └──────────────────────────────────────────────────────────────┘  │
│                                       │ UI 桥原语（运行时唯一通道）   │
│  ┌────────────────────────────────────▼─────────────────────────┐  │
│  │  目标环境：测试/开源 App（前台引导 / 分屏，Android 11+）          │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
          ▲ 仅实验时：PC harness ── adb reverse ── LoopbackAPI
          ▼ 运行时：规划/VLM API（用户自购 key，HTTPS 直连）
```

**形态要点**：单层 ReAct loop（无本地执行器——BYOK 形态下规划与视觉全走 API，桌面版「大脑+4B 执行器」两层不上机）；多 agent 编排（LangGraph）整体不上机。

## 3. 组件职责

### 3.1 Kotlin 壳（`android/`）

| 组件 | 职责 | 关键点 |
|------|------|--------|
| `AccessibilityBridge` | UI 树序列化、`performAction` 控件操作、`dispatchGesture` 手势、`takeScreenshot`(API 30+) | 原语面与桌面 `EmulatorBackend` **同构**（§4.1） |
| `MediaProjectionBridge` | 一次授权连续取帧；Android 14+ 每会话确认的降级路径 | 观察通道，不作操作 |
| `ForegroundService` | 保活、常驻通知（含暂停/停止动作）、电池白名单引导 | 对抗系统回收无障碍 |
| `GateUI` | 授权门控：敏感操作（文本输入/支付类页面）先确认 | 仅本次/总是允许/拒绝；级别可配（§4.4） |
| `SettingsUI + Keystore` | BYOK 双通道配置；key 仅存 Keystore | 明文不落库 |
| `LoopbackAPI` | 仅 `127.0.0.1` HTTP：harness 派发/指标回收/健康检查 | 产品运行不依赖 |
| `KernelHost` | Chaquopy 解释器生命周期、Python↔Java 边界 | 见 §4.2 |

### 3.2 Python 内核（`kernel/`）

| 模块 | 来源 | 说明 |
|------|------|------|
| `mini_loop.py` | 参照桌面 `omni_core/local/loop/core.py` **语义**新写 | ~500 行 ReAct loop：observe → LLM(tool_calls) → dispatch → verify / task_done；收尾门控、升级判定、预算墙钟的**子集**按手机域裁剪（裁剪项列于 §7 决策表） |
| `brain_client.py` | 移植桌面 `BrainClient` 客户端子集 | httpx，OpenAI 兼容；规划/VLM 双通道独立配置 |
| `knowledge/` 六模块 | 移植桌面 `omni_core/local/{trajectory,curator,skill_library,world_model,knowledge_inject,task_store}.py` | 仅标准库；语义保持（红线 R6） |
| `runtime_paths.py` | 改写 | 根路径由 Kotlin 注入 `Context.getFilesDir()` |
| `tools/` | 语义对齐桌面 `environments/emulator/tools.py` | 工具面即 UI 桥原语的可调用封装（能力==工具） |

## 4. 关键契约

### 4.1 UI 桥原语面（双实现同构 · 红线 R3）

| 原语 | u2 实现（调试，已有） | Accessibility 实现（运行时，M1'） |
|------|----------------------|--------------------------------|
| `observe()` → UI 树 | `d.dump_hierarchy()` XML | 无障碍节点树 → 同构序列化（bounds/text/resource-id/class） |
| `tap_by_id(id)` | `d(resourceId=...).click()` | `performAction(ACTION_CLICK)` on viewId |
| `tap_xy(x,y)` | `d.click(x,y)`（归一化坐标） | `dispatchGesture` 点按 |
| `type_text(t)` | FastinputIME + `send_keys` | `ACTION_SET_TEXT` / IME 通道 |
| `gesture(path)` | `d.swipe/drag` | `dispatchGesture` stroke |
| `screenshot()` | `d.screenshot()` | `takeScreenshot`(API 30+) / MediaProjection 兜底 |
| `launch_app(pkg)` | `d.app_start` | intent 启动 |
| `press(key)` | keyevent | `performAction` 全局动作 / keyevent |

内核只认左列原语名；后端切换零改动。**每次 action 50–300ms 时延特征纳入指标口径。**

### 4.2 Kotlin ↔ Python 边界（Chaquopy）

- Kotlin 经 `KernelHost` 以**单线程 executor** 调 Python callable（Python 侧保留常驻 asyncio loop 语义，移植桌面 `async_bridge` 子集）；
- 边界数据一律为 **JSON 字符串**（percept / action / result），与桌面轨迹 schema（扁平 `state/action/result` + `verified`/`retry` 质量信号）对齐；
- 崩溃域隔离：Python 异常结构化返回（不抛穿 Java），前台服务保持可停止。

### 4.3 回环 HTTP API（实验通道，仅 127.0.0.1）

| 端点 | 方法 | 用途 |
|------|------|------|
| `/health` | GET | harness 健康检查（无障碍/投屏/通道状态） |
| `/task` | POST | 派发任务（objective、组别、注入快照 id） |
| `/stop` | POST | 停止当前 run |
| `/status` | GET | 当前 run 状态（步骤/耗时/LLM 调用数） |
| `/metrics` | GET | run 结束指标（完成/步数/墙钟/token/轨迹文件名） |
| `/trajectory/<run_id>` | GET | 导出轨迹 JSONL |

### 4.4 授权门控级别（GateUI）

`每次询问` > `敏感操作确认`（默认：文本输入、跨 App 跳转、删除类操作）> `关闭`（仅研究机可设）。白名单按「App × 操作类型」记忆。

## 5. 数据流

**执行**：任务一句话 →（弱注入：技能+记忆摘要，默认关）→ mini_loop 循环 [observe(UI 树/截图) → VLM/规划决策 → 工具执行 → verify] → task_done（强制 verify，防幻觉式完成）。

**学习**（K 闭环四步，挂在 loop 收尾）：轨迹落盘 `trajectory.jsonl` → Curator 触发式蒸馏（任务完成后一次，无定时器）→ 候选 skill（N=3 连续成功晋升）/ world_model merge / 全局记忆 → 下次任务弱注入。

**存储布局**（App 私有目录，替代桌面 `~/.omniagent/`；2026-09-27 实测更正：
runtime_paths 把 `HACHIMI_DATA_DIR`（= filesDir）直接映射为桌面 `~/.omniagent` 根，
故无 `omniagent/` 中间层，与最初示意不同）：

```plain
files/
├── config.json            # 三通道配置（key 仅 Keystore 别名引用）
├── tasks/<task_id>/       # task.json · <date>_<run_id>.jsonl · <run_id>.run.json · failures/
├── skills/                # 全局通用 skill（SKILL.md + frontmatter）
├── memory/                # MEMORY.md · memory_summary.md · rollouts/ · merged.json
└── snapshots/<id>/        # M4' 注入快照（manifest.json SHA256 + 只读装载，R9）
```

## 6. 执行模式的 UI 组成

- **前台引导**：目标 App 全屏 + 悬浮叙事面板（步骤/当前动作/日志）+ 定位指示框（红色虚线，视觉 grounding）+ GateUI 弹层 + 暂停/停止。
- **分屏**：上半目标 App（MediaProjection 取帧）+ 下半 agent 控制台（感知描述/决策/时间线）。
- 交互细节以 `doc/ux_mockup.html`（S1–S8）为示意基准；视觉终稿后置。

## 7. 决策记录（ADR 简表）

| # | 决策 | 理由 | 否决的备选 |
|---|------|------|-----------|
| A1 | 内核 = Python 子集 + Chaquopy 嵌入 | 知识层即实验处理组，语义保真优先 | Go 重写（4-6 周，后置产品化）；Kotlin 全原生（重写研究仪器） |
| A2 | 上机 loop 自写（~500 行） | agents SDK 硬依赖 pydantic-core，无 Android wheel（spike #1017/#1409） | 复用 openai-agents（条件项：上游出 wheel 再评估） |
| A3 | 存储 = JSON/Markdown 文件 | 沿用上游 Plan C 已验证模型，移植成本最低 | SQLite（取消） |
| A4 | 单层 loop，无本地执行器 | BYOK 全 API；4B/llama-server 链不上机 | 两层分级（桌面特有） |
| A5 | LangGraph 不上机 | 手机单 agent 无扇出需求 | 多 agent 编排 |
| A6 | Android 11+ 基线 | takeScreenshot API 30+ | 8–10（投屏兜底档，不入主实验） |
| A7 | 双后端同构原语面 | 内核零改动切换；对拍测试可行 | 仅 Accessibility 单实现 |
| A8 | 实验通道 = 回环 HTTP + adb reverse | 自动化不破坏「完全上机」 | u2 运行时通道（降为调试） |

## 8. 上游对应关系（语义移植地图）

| OmniAgent（桌面） | Hachimi（手机） | 移植方式 |
|-------------------|-----------------|----------|
| `omni_core/brain/sdk_loop.py`（Agents SDK Runner） | `kernel/mini_loop.py` | 语义重写（A2） |
| `omni_core/brain/llm.py` BrainClient | `kernel/brain_client.py` | 子集移植 |
| `omni_core/local/*` 知识层六模块 | `kernel/knowledge/*` | 移植 + 路径适配 |
| `environments/emulator/backend.py`(u2) | `android/AccessibilityBridge` | 同构重实现（A7） |
| `environments/emulator/tools.py` 工具面 | `kernel/tools/*` | 语义对齐 |
| K0–K5 知识闭环 + 信号 | 同构保留 | 移植；判据按修订③ |
| `scripts/effectiveness.py` / K2 信号 | `harness/`（PC 侧） | vendor + 按修订③调判据 |

> 语义变更流向：**上游先行 → 本仓库跟随移植 → 对拍测试验证**（红线 R6）。桌面侧 loop 语义升级时，本仓库在下一个里程碑边界内同步。
