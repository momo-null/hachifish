# Hachimi · OmniAgent 手机研究载体

> **Hachimi** 是 [OmniAgent](https://github.com/momo-null/OmniAgent) 的手机端研究项目：以「手机 GUI agent」为实验场，验证知识级自升级（轨迹 → 蒸馏 → skill/记忆注入）对任务完成率的真实提升（RQ1–RQ3）。
>
> **硬约束**：完全上机运行（不依赖开发机常驻）、不 root、官方 API（AccessibilityService + MediaProjection）、BYOK 零服务器。

## 技术栈定稿（2026-09-26 评审 + spike）

| 层 | 选型 |
|----|------|
| 原生壳 | Kotlin：AccessibilityService + MediaProjection 桥、前台服务、BYOK 设置 UI |
| 内核 | Python 子集经 **Chaquopy** 嵌入 APK；**自写极简 ReAct loop**（agents SDK 因 pydantic-core 无 Android wheel 开箱不可用，见 docs 评审 §2.1） |
| UI 桥 | 双实现同接口：Accessibility（运行时）/ u2（开发调试），对照桌面版 `EmulatorBackend` 原语面 |
| 实验框架 | PC 侧 harness + `adb reverse` 回环 HTTP 驱动（产品运行本身不依赖 PC） |
| Go .so | ⏸ 产品化可选优化，不进入研究范围 |

## 目录

```
android/   Kotlin 壳（无障碍/投屏桥、前台服务、设置）
kernel/    Python 子集：极简 loop + 知识层（语义上游 = OmniAgent omni_core/local）
harness/   PC 侧实验框架（4 组对照、状态重置、指标采集、对拍用例）
docs/      预注册文档与评审档案
```

## 文档

- [docs/preregistration_v1.1_source.html](docs/preregistration_v1.1_source.html) — 预注册研究方案 v1.1 原文存档
- [docs/preregistration_v1.2_revisions.md](docs/preregistration_v1.2_revisions.md) — v1.2 修订清单（待并入正式版）
- [docs/mobile_research_feasibility_review_2026-09-26.md](docs/mobile_research_feasibility_review_2026-09-26.md) — 落地可行性评审 v2（含 spike 证据链）

## 里程碑（详见评审报告 §3.2）

M0' 真机环境 → M1' Kotlin 桥 → M2' Chaquopy 嵌内核 → M3' 单任务闭环 → M4' 实验框架 → M5'/M6' 实验（约 7-9 周单人）

## 上游与口径

- 语义上游：OmniAgent `main`（`omni_core/local/*`、K2 信号、`scripts/effectiveness.py`）；一致性靠 harness 对拍测试维持，不用 submodule。
- 决策档案：OmniAgent 仓库 `mobile-feasibility` 分支。
