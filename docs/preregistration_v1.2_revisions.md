# 预注册文档 v1.2 修订清单

> 基于《手机版 OmniAgent 研究方案 v1.1》（见 `preregistration_v1.1_source.html`）与
> 《落地可行性评审 v2》（`mobile_research_feasibility_review_2026-09-26.md`，2026-09-26）。
> 本清单为 v1.1 → v1.2 的**增量修订**，逐条落实后冻结开跑 M0'。每条修订均给出对 v1.1 原文的具体落点。

## 修订 ①　内核语言选型：Go 重写 → Python 嵌入（自写 loop）

**落点**：v1.1 §3 总体架构（"OmniAgent 内核（Go，编译为 .so 嵌入 APK）"）、§4 技术选型表（"内核/知识：Go 重写 omni_core + SQLite"行）、§6 路线图 M1。

**修订为**：

- 原生壳 = Kotlin（AccessibilityService + MediaProjection 桥、前台服务、BYOK 设置 UI、Keystore）。
- 内核 = Python 子集经 **Chaquopy** 嵌入 APK；上机 loop 为**自写极简 ReAct loop**（~500 行，httpx + OpenAI 兼容 tool_calls + 收尾门控/升级判定子集），语义参照桌面 `omni_core/local/loop/core.py`。
- **定案依据（2026-09-26 spike）**：`openai-agents` 硬依赖 `pydantic>=2.12.2,<3`；pydantic-core 无 Android wheel（Chaquopy 官方仓库从未上架；issue #1017 open、#1409 征集中）。agents SDK 复用降级为条件项。
- Go .so 降为**产品化可选优化**，移出研究范围（与 v1.1 §9 "研究优先原则"一致）。
- 存储沿用 JSON/Markdown 文件式（v1.1 所述 SQLite 取消）；落点改为 App 私有目录。

## 修订 ②　Android 基线：8+ → 11+

**落点**：v1.1 §3（"Android 8+（无障碍完整可用）"）、§4 选型表（"执行环境：真机（Android 8+）"）。

**修订为**：目标基线 **Android 11+**（`AccessibilityService.takeScreenshot` 为 API 30+；`dispatchGesture` 为 API 24+）。Android 8–10 列为「MediaProjection 兜底可运行」档位，不进入主实验。

## 修订 ③　RQ1 口径与桌面 K3 判据的关系

**落点**：v1.1 §2 成功标准（"RQ1 在 α=0.05 下完成率显著提升（预期 ≥10pp）"）。

**修订为**：明写「手机域主判据沿用 RQ1 口径（完成率 ≥10pp 显著提升，α=0.05）；步数/耗时为次级指标」。桌面 master spec K3 判据（步数降 ≥15% 且成功率不降）为**长任务桌面域口径**，与手机域并列、互不替代——避免口径混用质疑。

## 修订 ④　实验自动化通道说明

**落点**：v1.1 §4 选型表（"开发调试通道 = 开发机 adb"）、§5.3 实验执行。

**修订为**：区分两个通道——

- **运行时通道（产品形态）**：Accessibility + MediaProjection，完全上机，零 PC 依赖。
- **实验通道（仅 harness）**：手机内核开**仅回环（127.0.0.1）HTTP API**，PC harness 经 `adb reverse tcp:<port> tcp:<port>` 驱动任务派发与指标回收；状态重置经 adb（`pm clear`/重装）。产品运行不依赖该通道。

## 修订 ⑤（随①联动）　执行通道与 UI 桥表述

**落点**：v1.1 §1.2 执行形态决策、§4 选型表「UI 桥」行。

**修订为**：运行时通道唯一 = Accessibility；u2/adb（桌面版 `EmulatorBackend`，已真机验证）降为**开发调试通道**。UI 桥双实现须原语面同构（UI 树 dump / 控件点击 / 手势 / 截图 / 启动 App），内核零改动切换。

---

**冻结流程**：本清单并入 v1.1 正文形成 v1.2 → 预注册冻结（打 tag `prereg-v1.2`）→ 开跑 M0'。实验数据与脚本随结果开源。
