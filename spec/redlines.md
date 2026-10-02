# Hachimi 红线与工程规范（v1.0）

> **状态**：红线为**不可违背约束**，违反即停下修架构，不允许「先跑通再补」。
> 每条红线标注**检查方式**（人工评审 / CI 脚本 / 对拍测试），可检查的优先自动化。
> 语义上游 = OmniAgent `main`（其 master spec 红线在本仓库的投影见 R4/R6）。

## A. 架构红线

| # | 红线 | 检查方式 |
|---|------|----------|
| R1 | **完全上机**：运行时零 PC 依赖；adb/u2 仅出现在调试与 harness 通道，产品代码路径不得引用 | 评审 + 依赖扫描（`adb`/`uiautomator2` 仅许出现在 harness/ 与调试标记代码） |
| R2 | **官方 API only**：操作通道 = AccessibilityService；观察通道 = MediaProjection/takeScreenshot。禁 root、KernelSU、私有 hook、反射绕授权 | 评审（权限声明清单核对：仅 `ACCESSIBILITY_*`/`MEDIA_PROJECTION`/`INTERNET`） |
| R3 | **原语面同构**：内核只认 `doc/architecture.md` §4.1 的原语名；新增原语必须 u2/Accessibility 双实现同进，单实现 = 评审不通过 | 对拍测试（同一 App 同任务双后端轨迹语义一致率 ≥90%） |
| R4 | **能力==工具，内核零场景硬编码**：场景知识（某 App 的按钮在哪）只进知识层/工具参数，禁止写入 loop/桥代码 | lint：kernel/ 与 android/ 源码禁出现具体 App 包名/界面名词 |
| R5 | **能力默认关、需显式开启**：弱注入、Curator 蒸馏、实验模式出厂默认关闭；开关落在设置 UI | 单测：全新安装首次运行断言无注入行为 |

## B. 工程红线

| # | 红线 | 检查方式 |
|---|------|----------|
| R6 | **知识语义上游先行**：`mini_loop` 与知识层语义变更必须先在桌面仓库生效，本仓库跟随移植；**漂移 = bug** | 对拍测试（同输入 → 同蒸馏/晋升/注入决策）；变更记录须引用上游 commit |
| R7 | **依赖白名单**：`kernel/` 仅允许标准库 + httpx + PyYAML。禁入包：torch / easyocr / ultralytics / opencv / langgraph / openai-agents / pydantic / llama-server 链 | CI lint：import 黑名单扫描（`scripts/check_redlines.py`，M2' 交付） |
| R8 | **BYOK 与隐私**：key 仅存 Android Keystore；HTTPS 直连；只操作测试/开源 App；禁自动化登录/支付/实名类页面 | 评审 + 任务集审计（15 任务清单勾选） |
| R9 | **实验效度**：注入快照锁定（SHA 校验）；实验期 Curator 冻结；`trajectory.jsonl` 只读（信号层不写原始数据）；预注册冻结（tag `prereg-v1.2`）前不得开跑正式实验 | harness 断言 + 运行日志审计 |

## C. 数据与口径红线

| # | 红线 | 检查方式 |
|---|------|----------|
| R10 | **指标口径以预注册 v1.2 为准**：主判据 = 完成率 ≥10pp 显著提升（α=0.05）；步数/耗时/LLM 调用为次级。中途改判据须在报告中显式声明为偏差 | 中期检查（M5'）人工评审 |
| R11 | **范围冻结**：Go .so、虚拟副屏、锁屏队列执行不入研究范围；相关冲动一律记入「远期选项」不立项 | 里程碑评审（每 green-light 核对范围） |
| R12 | **诚实边界**：报告与 README 只可声称「链路已打通/已验证」的事实；未跑出的结论（含 RQ1 效应）不得写成已证明 | 发布前人工评审（沿用上游「诚实边界」惯例） |

## D. 工程规范

- **Python（kernel/ harness/）**：语义风格跟随上游 `spec/coding_standard.md`；类型标注齐全；结构化错误 dict 优先于抛异常打断 loop（沿用上游 tool-loop 约定）。
- **Kotlin（android/）**：官方 lint 零 error；前台服务声明 type；最小权限原则。
- **测试先行**：每个里程碑的 DoD 验证脚本随代码交付（评审报告 §3.2）；对拍用例属于 M1'/M2' 交付物，不是「以后补」。
- **里程碑纪律**：每里程碑独立可验证、逐个人工 green-light（沿用上游 master spec 惯例）；DoD 见 `doc/milestones.md`。
- **文档即决策**：架构变更改 `doc/architecture.md` ADR 表并注明日期；红线变更需在 commit message 中标注 `redline-change`。

## E. 红线变更流程

1. 提出书面理由（什么约束挡住了什么正事）；
2. 评估对预注册结论效度的影响（R9/R10 关联项一律不动）；
3. 修改本文并 commit（消息含 `redline-change`），同步更新 `doc/architecture.md` 受影响章节；
4. 历史红线永不静默修改。
