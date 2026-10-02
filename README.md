# Hachifish · 手机端 GUI Agent

> **Hachifish** 是 [OmniAgent](https://github.com/momo-null/OmniAgent) 的手机端实现：说一句话，agent 通过官方无障碍通道在真机上把事办完——真实 App、真实环境，不 root、零自有服务器（BYOK）。

## 它能做什么

在手机上输入一个自然语言目标（例如"打开 B 站搜索你感兴趣的视频并播放"，已多次真机验证成功），agent 会自主观察屏幕、规划步骤、执行点击/输入/手势，并在声称完成前强制校验屏幕状态。执行中随时可暂停/停止，敏感操作（输入、删除等）先过授权门控，支持"总是允许"白名单。

## 技术架构

| 层 | 选型 |
|----|------|
| 原生壳 | Kotlin：AccessibilityService（操作通道）+ MediaProjection（观察通道）、前台服务、授权门控、BYOK 设置 UI |
| 内核 | Python 子集经 **Chaquopy** 嵌入 APK：自写极简 ReAct loop + 模型客户端 + 知识层（skill / 记忆 / 环境模型），仅标准库 |
| 模型 | 任意 OpenAI 兼容端点（规划 + 视觉），用户自备 key，存 Android Keystore |
| 视觉兜底 | 自绘/无控件 id 界面走 VLM；无障碍树可用时以树为主 |
| 实验框架 | PC 侧 harness + `adb reverse` 回环 HTTP 驱动（产品运行本身不依赖 PC） |

## 目录

```
android/   Kotlin 壳（无障碍/投屏桥、前台服务、设置、任务 UI）
kernel/    Python 子集：极简 loop + 知识层（语义上游 = OmniAgent omni_core/local）
harness/   PC 侧实验框架（对照组注入、状态重置、指标采集、对拍用例）
doc/       架构、里程碑、预注册、交互原型
spec/      红线与工程规范
```

## 文档

| 文档 | 角色 |
|------|------|
| [doc/architecture.md](doc/architecture.md) | 架构决策权威（组件/契约/ADR/上游移植地图） |
| [doc/preregistration_v1.3.md](doc/preregistration_v1.3.md) | 预注册研究方案（tag `prereg-v1.3`） |
| [doc/product_milestones.md](doc/product_milestones.md) | 产品里程碑（P0–P6） |
| [doc/milestones.md](doc/milestones.md) | 研究里程碑（M0'–M6'，已暂停冻结） |
| [spec/redlines.md](spec/redlines.md) | 红线（R1–R12，含检查方式与变更流程） |
| [doc/ux_mockup.html](doc/ux_mockup.html) | 交互原型（8 界面示意） |

## 约束与原则

- 官方 API only：操作走 AccessibilityService，观察走 MediaProjection / takeScreenshot；禁 root、私有 hook
- BYOK：模型端点与密钥完全由用户自备，App 只与用户配置的端点通信
- 本地优先：轨迹、知识、配置全部落 App 私有目录，无遥测
- 随时可中止：主界面、悬浮面板、通知栏三处均可停止
- 语义上游：[OmniAgent](https://github.com/momo-null/OmniAgent) `omni_core/local`；一致性靠 harness 对拍测试维持

## 构建

```bash
cd android
gradle assembleDebug -PhachimiPython=<python3.12 路径> --no-daemon
```

首次运行需在系统设置中开启 Hachifish 无障碍服务，并在 App 内配置模型端点（`harness/config.example.json` 是 PC 侧 harness 的配置模板）。

## 许可

研究数据与脚本随结果开源；研究方案见预注册文档。
