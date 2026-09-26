# 手机版 OmniAgent 研究方案 v1.1 · 落地可行性评审

> **日期**：2026-09-26（v2 修订）　**分支**：`mobile-feasibility`
> **评审对象**：《手机版 OmniAgent 研究方案 v1.1》(预注册文档，2026-09-25)
> **评审基准**：本仓库 `main` 分支 `f059631` 实际代码 + `doc/agent-control-master-spec.md`(X3 权威总规)
>
> **v2 修订记录**：按 2026-09-26 方案讨论收口的两项决策更新——
> ① **完全上机运行为硬约束**（内核必须能在手机上独立运行，不依赖开发机常驻）；
> ② **Go 非必要**，内核语言按工程代价选型。结论从「u2 先行、Accessibility 后置」修正为「Accessibility 回到关键路径、Python 嵌入替代 Go 重写」。
>
> **结论先行**：**可以落地**。推荐栈 = Kotlin 原生壳（AccessibilityService/MediaProjection）+ **Chaquopy 嵌入 Python 内核**，替代方案原文的「Go 重写 .so」——上机目标不变，工作量减半以上；Go 降级为产品化可选优化。研究主线约 **7-9 周单人**，成本不变（¥500-1400）。

---

## 1. 方案与仓库现状的对照

方案 v1.1 假设大部分组件需要新建，但仓库实际状态如下：

| 方案组件 | 方案估计 | 仓库实际状态 | 差异 |
|----------|----------|--------------|------|
| UI 桥 U2 实现(开发调试) | 新建(M1) | **已存在且真机闭环验证通过**：`environments/emulator/backend.py`(uiautomator2/ADB，`dump_hierarchy`/`tap_by_id`/`set_fastinput_ime`/`launch_app`/OCR 兜底)，master spec §0 M1 行明确「GUI 设备真机闭环验证通过」 | ✅ 直接复用为调试通道 |
| UI 桥 Accessibility 实现 | 新建(M1) | **不存在**，需原生 Kotlin 服务；完全上机约束下**在关键路径上**（见 §3.1） | ⚠️ 真实工作量，有同构接口可对照 |
| 知识层(轨迹→Curator 蒸馏→skill/记忆/world-model→注入) | Go 语义移植 | **Python 版已完整落地**(K0–K5)：`omni_core/local/{trajectory,curator,skill_library,world_model,knowledge_inject,task_store}.py` 约 3k 行，仅依赖标准库 | ✅ 上机路线直接复用 |
| 有效性 ablation 工具 | M3 新建 | **部分存在**：`scripts/effectiveness.py`(K3 ablation 聚合)、`scripts/review_rollouts.py`、K2 三实体一致率信号、K5 稳态信号 | ✅ 有地基 |
| 内核上机(Go .so 嵌入 APK) | M1，2-3 周 | **无任何 Go 代码**；`sdk_loop.py`(1672 行)与 `local/loop/core.py` 均深度绑定 OpenAI Agents SDK(Runner/Model/RunHooks/FunctionTool)，多 agent 编排依赖 LangGraph | 🔴 Go 重写被低估；**Python 嵌入可绕开**(见 §2.1) |
| 存储层 SQLite | 新设计 | 实际为 **JSON/Markdown 文件存储**(`~/.omniagent/`，Hermes 风格 SKILL.md)，无 SQLite | ⚠️ 建议沿用文件式（上机后落 App 私有目录） |
| BYOK 直连规划/VLM API | 新建 | `BrainClient` 即 OpenAI 兼容 httpx 客户端，模型无关(架构原则) | ✅ 复用 |
| 实验对照组/指标 | M3 新建 | 信号基础设施(K2)与聚合工具(K3)已有，但 15 任务集 × 4 组 × 状态重置的 harness 需新建 | ⚠️ 真实工作量 |

**结论**：方案瞄准的缺口(RQ1–RQ3 = master spec 明文承认的「K3 V3 ablation 尚未跑出结论」)与仓库现状高度衔接，方向正确；主要风险从「Go 重写低估」转移为「Android 打包与原生桥」，均可控。

## 2. 逐项可行性评估

### 2.1 内核上机：Go 非必要，推荐 Python 嵌入 ⭐

**为什么 Go 不是门槛**：内核 workload 是 I/O 密集的（等 LLM API 回包、dump UI 树、读写 JSON），性能上 Python 与 Go 在手机端无可感知差异；Go 换来的只是包体积与运行时干净度，属产品化优化而非可行性门槛。

**完全上机 = 完全可绕开 Go 重写**。内核真正必需的手机端子集——BrainClient + ToolLoop + 知识层六模块 + 配置——**全是纯 Python**（标准库 + httpx），`requirements.txt` 里的 torch/easyocr/ultralytics/llama-server 链路在 BYOK 形态下整体不需要。四条部署路线对比：

| 路线 | 做法 | 代价 | 判定 |
|------|------|------|------|
| PC 内核 + u2/adb | 内核在开发机，手机仅执行环境 | 零移植 | ❌ 违反「完全上机」硬约束；仅保留为**开发调试通道** |
| **Python 嵌入 APK（推荐）** | Chaquopy 打包 Python 内核子集入 APK；原生壳经 JNI/接口回调 | 小：复用现有约 1 万行；主要风险是打包（见下） | ✅ 采纳 |
| Go 重写 .so | omni_core 语义移植为 Go，gomobile/JNI 嵌入 | 大：6-8k 行移植 + 手写 Agents SDK Runner 语义(1.5-3k 行)，4-6 周 | ⏸ 后置为产品化可选优化 |
| Kotlin 全原生重写 | 内核整体重写 | 最大 | ❌ 不考虑 |

**Python 嵌入的关键依赖核实（2026-09-26 spike，已定案）**：

- **pydantic-core（Rust 编译扩展）是硬阻断**：`openai-agents` 现行版本硬依赖 `pydantic>=2.12.2,<3`（PyPI 元数据核实，v2 无退路），而 pydantic-core 无预编译 Android wheel——Chaquopy 官方仓库从未上架 pydantic / pydantic_core（官方 news 的仓库新增全量记录中无此包），支持 issue [chaquopy#1017](https://github.com/chaquo/chaquopy/issues/1017)（pydantic v2）至今 open、社区按官方指引自建亦踩坑，且 [chaquopy#1409](https://github.com/chaquo/chaquopy/issues/1409) 仍在公开征集 pydantic-core wheel。maturin + NDK 交叉编译可行但非平凡（Rust 工具链 + 链接器调通，一次性成本高且随上游版本漂移）。
- **决策：上机 loop 默认自写（主路线）**——参照 `local/loop/core.py` 语义实现约 500 行极简 ReAct loop（httpx + OpenAI 兼容 `tool_calls` 协议 + 收尾门控/升级判定子集）。手机形态不需要 LangGraph 多 agent、SDK 流式/telemetry，内核价值（知识层/工具运行时/UI 桥契约）不依赖该 SDK。agents SDK 复用降级为条件项：上游落地 pydantic-core Android wheel 后再评估。
- LangGraph 桌面链路不带入手机包（`graph_runner.py` 分支不上机）。

### 2.2 执行/观察通道（上机形态定稿）

- **运行时通道（唯一）**：AccessibilityService（操作）+ MediaProjection（观察），前台/分屏执行——方案 §3 架构在完全上机约束下**原样成立**，u2 不再是可选项。
- **开发调试通道**：u2/adb（复用 `EmulatorBackend`），仅用于 bring-up 期内核逻辑调试与实验 harness 的自动化驱动（adb 是方案自认的研究正当用途）。
- **关键工程约束——双实现同接口**：Accessibility 桥必须暴露与 `EmulatorBackend` 同构的原语面（UI 树 dump / 控件点击 / 手势 / 截图 / 启动 App），延续「能力==工具」红线，内核零改动切换后端。映射关系：`dump_hierarchy` → Accessibility 节点树序列化；`tap_by_id` → `performAction(viewId)`；手势 → `dispatchGesture`；`screenshot` → 见下。
- **版本细节（方案需修正）**：`AccessibilityService.takeScreenshot` 为 **API 30+(Android 11)**；`dispatchGesture` 为 API 24+。方案「Android 8+」在 8/9/10 上截图只能走 MediaProjection 兜底——建议基线上调 **Android 11+**，8-10 列为「MediaProjection 兜底可运行」档。MediaProjection Android 14+ 每会话需确认（方案 §8 已识别）。

### 2.3 实验设计 ✅（样本量与工时有张力）

- **统计功效**：1800 次 ÷ 4 组 = 450 次/组，检测完成率 10pp 提升(如 0.35→0.45)在 α=0.05、power≈0.8 下所需样本约 380-400/组，**够用但不富裕**。建议先跑每组每任务 20 次做中期检查再决定补到 30。
- **墙钟**：单 run 平均 5-8 分钟 × 1800 ≈ 150-240 小时纯执行；建议 2 台设备并行或夜间挂机。harness 必须处理异常恢复(发热弹窗、系统升级提示、无障碍服务被系统回收)。
- **自动化通道（上机形态下依然成立）**：内核在手机上开**仅本机回环的 HTTP API**（沿用桌面版 FastAPI 语义），PC harness 经 `adb reverse tcp:<port> tcp:<port>` 建立转发——实验自动化不破坏「完全上机」（正常运行不需要 PC，只有实验 harness 用 adb）。状态重置 `pm clear`/重装同样经 adb 自动化。
- **对照组 C(人类示教)**：需一个小型轨迹录制器（u2 生态或 App 内录制），约 2-3 天。
- **注入可复现性**：`knowledge_inject.py`（K0 弱注入，默认关）即注入开关；harness 需固化「注入知识快照版本」，实验期间冻结 Curator 触发，否则 B/C 组内不可比。
- **与 K3 判据对齐**：master spec K3 判据「步数降 ≥15% 且成功率不降」vs 方案 RQ1「完成率 ≥10pp 显著提升」。建议预注册文档明写「手机域沿用 RQ1 口径，步数/耗时为次级指标」，避免口径歧义。

### 2.4 成本 ✅

1800 次 × 15 步 × 1-2 次调用 ≈ 3-6 万次规划调用(DeepSeek 级，约 ¥100-400)+ 2.7 万张 VLM 图(¥135-1350)。方案 ¥500-1400 区间**合理**。国内 API 直连无 GFW 暴露面（与 master spec §15.2 一致）。

### 2.5 合规与风控 ✅

- 只操作测试/开源 App、不碰登录支付、自有设备 sideload 不上架：AccessibilityService 的 Play 审查风险不适用。
- 完全上机后 u2/atx-agent 仅存在于开发调试阶段，运行时是官方 Accessibility API，**合规面比 v1 评审结论更干净**。
- 隐私面：截图/UI 树仅测试 App 内容；key 存 Android Keystore（方案 §8）+ 本机配置（桌面版去 key 机制语义沿用）。

## 3. 修订后的实施路线（v2 定稿）

### 3.1 技术栈决策（替代方案原文「Go 重写」）

| 层 | 选型 | 说明 |
|----|------|------|
| 原生壳（必须） | Kotlin | AccessibilityService + MediaProjection 桥、前台服务、BYOK 设置 UI、Keystore |
| 内核 | **Python 子集经 Chaquopy 嵌入** | BrainClient + ToolLoop + 知识层六模块；LangGraph/本地执行器链路不打包 |
| 上机 loop | **自写 ~500 行极简 ReAct loop（主路线，spike 已定案）** | agents SDK 因 pydantic-core 无 Android wheel 开箱不可用（§2.1）；语义参照 `local/loop/core.py`，与桌面侧保持同构 |
| UI 桥 | 双实现同接口：Accessibility（运行时）/ u2（调试） | 对照 `EmulatorBackend` 原语面，内核零改动 |
| 实验 harness | PC 侧 Python + `adb reverse` HTTP | 仅实验用；产品运行不依赖 PC |
| Go .so | ⏸ 产品化可选优化 | 仅当 Python 嵌入的包体积/启动/运维确证构成问题时启动 |

### 3.2 路线图

| 里程碑 | 内容 | 估计 |
|--------|------|------|
| M0' | 真机环境：Android 11+ 设备、无障碍/MediaProjection 授权链、测试 App 集、亮屏常驻 | 2-3 天 |
| M1' | Kotlin 壳：AccessibilityService + MediaProjection 桥（与 EmulatorBackend 同构原语面）+ 前台服务 | 2-3 周 |
| M2' | Chaquopy 嵌入内核（打包 spike 已完成：loop 走自写路线）+ BYOK 配置 + Keystore + 回环 HTTP API | 2-3 周 |
| M3' | 单任务闭环真机：截图→VLM→坐标→Accessibility 注入，前台模式跑通 | 1 周 |
| M4' | 实验 harness：4 组条件 + 状态重置 + 随机顺序 + 指标采集(复用 K2 信号) + 异常恢复 + 示教录制器 + 注入快照固化 | 1.5-2 周 |
| M5' | 中期实验：每组每任务 20 次 → 中期检查(功效复核、效应方向) | 1-1.5 周 |
| M6' | 补足 30 次/组/任务 + 统计 + 报告开源 | 1.5-2 周 |
| (远期 RQ5) | Go .so 内核替换 / 锁屏队列 / 虚拟副屏观察项 | 不入研究范围 |

**研究主线合计约 7-9 周单人**（v1 评审的 5-6 周是 PC 辅助形态；完全上机约束的真实代价约 +2-3 周，全部花在 Kotlin 桥与打包上，这也是方案 v1.1 原估 8-11 周的合理区间——**用 Python 替代 Go 后，方案原总工期反而从低估变为基本准确**）。

## 4. 风险清单（增量部分，方案 §8 之外）

| 风险 | 等级 | 缓解 |
|------|------|------|
| pydantic-core 无 Android wheel | ✅ 已消除 | spike 定案：上机 loop 走自写路线（§2.1），不再依赖 agents SDK |
| 极简 loop 与桌面侧行为漂移 | 🟡 低 | 语义从 `local/loop/core.py` 移植 + 同构对拍用例；桌面侧 loop 语义升级时同步移植 |
| Accessibility 桥与 u2 行为差异（节点树语义、时延 50-300ms） | 🟠 中 | M1' 用同构原语面 + 对拍测试（同一 App 双后端跑同一任务比对轨迹）；时延纳入指标口径 |
| 无障碍服务被系统回收（国产 ROM 激进省电） | 🟠 中 | 前台服务 + 电池白名单引导；harness 健康检查自动重授权 |
| u2 真机调试稳定性(atx-agent 断连) | 🟡 低 | 仅调试通道；自动重连 |
| 中期结果不显著导致后续白跑 | 🟡 低 | M5' 中期检查点先验证效应方向再补样 |
| 知识注入内容漂移 | 🟠 中 | 实验期冻结 Curator；B/C 组用固定知识快照 |
| 单设备故障中断长实验 | 🟡 低 | 轨迹/检查点落盘(App 私有目录)+ adb pull 备份；任务随机顺序天然抗中断 |

## 5. 结论

- **可以落地**。完全上机约束下推荐栈：**Kotlin 壳 + Chaquopy 嵌 Python 内核（自写极简 loop）**，Go 降为产品化可选。研究主线 7-9 周、¥500-1400；原最大风险（pydantic-core 打包）已经 2026-09-26 spike 定案消除，剩余主要风险为 Accessibility 桥工程量与双后端行为对拍。
- **RQ1–RQ3**：成立；运行通道为 Accessibility（u2 仅调试），与 v1 评审结论相反的修正由「完全上机」硬约束驱动。
- **RQ4**：成立（Accessibility UI 树 vs VLM 视觉，并列感知工具）。
- **RQ5**：原「Go + Accessibility 工程可行性」问题已被本评审部分前置回答（Go 非必要）；RQ5 保留为「Accessibility 通道在真实任务分布下的工程边界」验证。
- **预注册文档需修订四处**：①内核语言选型（Python 嵌入，Go 后置）；②Android 基线（11+ 推荐）；③RQ1 与 K3 判据关系说明；④实验自动化通道说明（回环 HTTP + adb reverse，产品运行不依赖 PC）。

> 本评审基于 `main@f059631` 代码事实与公开资料核实（2026-09-26 spike 证据链：PyPI `openai-agents` 元数据、Chaquopy 官方 news 与包仓库、[chaquopy#1017](https://github.com/chaquo/chaquopy/issues/1017)、[chaquopy#1409](https://github.com/chaquo/chaquopy/issues/1409)）。预注册文档修订后即可冻结开跑 M0'。
