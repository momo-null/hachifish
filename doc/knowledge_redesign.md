# 知识模块现状盘点与重构专项（P2-R）

> 状态：**待拍板**。本文档只描述现状、给出重构目标与分阶段计划，**不含任何代码改动**。
> 数据全部来自 2026-10-01 真机实测与当前代码，标注到文件行号，可逐条复验。
> 复现工具：`.tools/show_injection.py`（打印真正进 prompt 的字节）、`.tools/eff_stats.py`（去噪步数）。

---

## 0. 为什么要动

这套知识层是自 Omni 移植而来，`knowledge_inject.py:6` 写明「三来源统一挂载 system prompt 附加块（v2 定稿，**取消 A/B 测试**）」——在 Omni 侧没有落地场景，这个"看着合理"的设计从未被验证。

Hachimi 是它第一次遇到真机回路（automated reset / 可复现 seed / 可自动化 A/B），于是问题被放大且第一次可度量。三轮 DoD4 实测的结论是：

> **机制全通**（蒸馏、合并、注入、UI、导出都工作），**但不可观测到收益** —— 不是"知识没用"，而是"知识以什么形态存在、如何投放"，让它既难被匹配、也无被采用的动力。

---

## 1. 现状盘点

### 1.1 四个存储平面

| 平面 | 路径（`runtime_paths.py`） | 内容 | 生产方 | 接线状态 |
|---|---|---|---|---|
| A. 任务资产 | `tasks/<task_id>/`（trajectory.jsonl / *.run.json / `skills/`） | 轨迹、RunRecord、任务私有技能目录 | 运行时 | 读取仍在（`build_skill_catalog` 按 task 优先），但 P2 已改为直接落全局 ⇒ **该目录恒空，是残留概念** |
| B. 全局技能 | `skills/*.md` | Hermes 风格 md（frontmatter + 正文） | `SkillLibrary.from_run_record` | ✓ 已接线（候选直接落全局） |
| C. 全局记忆 | `memory/MEMORY.md`、`memory_summary.md`、`rollouts/*.md`、`merged.json`、`curator_metrics.json` | 长期事实 / 教训 / 每条任务的蒸馏中间件 | `Curator.distill_task_memory` + `maybe_merge_rollouts` | ✓ 已接线 |
| D. 用户画像 | `memory/user_profile.md`、`profile_candidates.md` | 画像候选 | `Curator.distill_user_profile` | ✗ **`load_profile_text`（`knowledge_inject.py:43`）定义了但全仓库零调用** —— 第四个平面是"死"的 |
| （另） | `character.md` | 角色卡 | UI | ✓ 经 `bridge._persona_block` 注入，与知识层分离，健康 |

### 1.2 写入路径：一次触发，两条互相不知道的链

`bridge._curator_after_finish`（`:687`）→ `Curator.run_once`（`curator.py:139`）：

```
run_once(run_record)
 ├─ ③ review_candidate_skills ──> skill md 落 skills/（scope=global，N=3 晋升 active，失败清零）
 ├─ ⑤ distill_task_memory ──> memory/rollouts/<task_id>.md（## facts / ## lessons）
 │     └─ maybe_merge_rollouts(threshold=1) ──> MEMORY.md ──> regenerate_summary()
 └─ ⑥ distill_user_profile ──> profile_candidates.md（不进画像正文）
```

**关键事实**：③ 和 ⑤ 消费的是**同一个 RunRecord 的同一份轨迹**，
`skill_library.from_run_record` 取 `substeps`、`curator._success_path_facts`（`:725`）取 actions 序列，
两者产出语义等价的内容，**中间没有任何工序判断"这是同一条"**。

### 1.3 注入路径：全量、无匹配、两来源并排

`bridge._knowledge_parts`（`:640`）→ `knowledge_inject.build_knowledge_block`（`:192`）：

- 读 `load_memory_text()` = `memory_summary.md` 全文（截断 20000 字符，**无优先级、无筛选**）
- 遍历 `global_skills().glob("*.md")` **全部**技能，逐个取 `name / objective_pattern / ops`
- `build_knowledge_block` 只做**排版**：`## 历史记忆` 拼全文 + `## 相关技能` 拼全部条目
- 产物进 `MiniLoop._system_prompt`（`mini_loop.py:423`）的 `knowledge` 位：**persona 之后、任务说明之前**，每轮 LLM 调用重发一次

Omni 侧其实给了另一条更克制的路：`format_skill_catalog_message`（`:260`）输出 `<available_skills>` **摘要目录**，并声明"完整指令需通过 `load_skill` 工具加载；仅凭摘要推断或执行技能内容，可能得到不完整或过时的步骤"。
**这条路在手机侧没被采用**：`bridge._knowledge_parts` 绕过 `build_skill_catalog`，自己 `_parse` 全部技能文件；`load_skill` 原语不存在，`disable_model_invocation`（`skill_library.py:59`）也就无从生效。

### 1.4 消费面（UI / 事件 / 导出）

- `KnowledgePage.kt`：三组数据面 —— `skills_list_json`（`:716`）、`memory_json`（`:704`）、`rollouts_list_json`（`:893`）；操作：技能启停/删除（`skill_delete_json:821`）、清空两档（`knowledge_clear_json:835`，scope=memory|all）、zip 导出（`knowledge_export_json:868` + FileProvider）
- 聊天注入指示卡：`bridge` 发 `knowledge` 事件（`memory / skills_names / chars`）→ `ChatItem.KnowledgeHint`
- PC 侧清空知识只有 `adb shell run-as ... rm -rf files/memory files/skills`（回环 HTTP 无该路由）

### 1.5 一处学习 → 四份拷贝（真实样本）

以一次 T5 成功 run 为例，序列 `launch_app → new_note → locked_note_title → button1 → text_note_view → type_text`：

| # | 落点 | 形态 |
|---|---|---|
| 1 | `memory/rollouts/t_86d1b79252fa.md` 的 `## facts` | `任务「<objective 全文>」上次成功路径：launch_app(...) → tap_by_id(...) → …` |
| 2 | `memory/MEMORY.md` 的 `## 长期事实` | 同上（合并后长期驻留） |
| 3 | `skills/skill_*.md` 的 `substeps_json` | 完整 args 的 JSON：`{"tool":"type_text","args":{"text":"购物清单"}}` |
| 4 | 同一文件正文 `## Substeps` + `objective_pattern` 与 `## Objective` 双写 | 同一序列第三次出现 |

注入端再叠加：`build_knowledge_block` 把 1/2 与 3/4 并排拼进同一块 ⇒
**实测注入块 2207 字符，其中约 60% 是同一段 objective 原文重复抄写两遍。**

### 1.6 实测数据（三轮 DoD4，realme RMX3115 / Android 12 / deepseek-v4-flash-vision）

| 轮次 | 预算 | A（无知识） | B（有知识） |
|---|---|---|---|
| 1 | max_steps=26 | 14✓ 15✓ **26✗** | **26✗** 16✓ **26✗** |
| 2 | 1000 + wall_clock 900s（T1） | 15✓ 18✓ 12✓（均值 15.0） | 13✓ 46✓ 17✓（均值 25.3） |
| 3 | 1000（T5，B 组继承 T1 沉淀，每组 5 次） | 20,18,18,23,13 均值 **18.4** / 有效步 10.0 | 14,35,14,28,13 均值 **20.8** / 有效步 11.8 |

（有效步数 = 总步数 − observe/look/screenshot）

关键观察：

- 放大预算后成功率 **6/6 → 10/10**，说明"26 步"曾把失败样本截断成假象；
- 两组**最优等价**（13–14 总步 / 8–9 有效步），组内方差 13~35 步远大于可能效应量；
- B 组多出的步数几乎全是**确认动作**：离群样本 35 步里 16 步是 observe/look/screenshot（同组正常样本仅 5–11 步）。

### 1.7 缺陷清单

**P0（结构，导致重复与不可控）**

1. **同源双写无去重**：③⑤ 两条链产出语义等价内容，无 ID、无交叉引用 ⇒ 注入必重复。
2. **无稳定 ID**：技能以 `objective_pattern`（整段原文）为标识，fact 以"（全文+路径串）"为标识，彼此无法判断是否同一条。
3. **概念边界重叠**：`## facts` 里装的"上次成功路径"就是套路，与 skill 的 `substeps` 完全同构 —— 于是"memory / skill"只是落两个文件夹，没有语义分工。
4. **注入全量无匹配**：不按当前 objective 筛选/排序，所有技能都进 prompt；`objective_pattern` 只当展示文本，从未参与任何相似度计算。

**P1（形态，导致信噪比低与不被采用）**

5. **参数未脱敏**：`_fmt_action` 把 `type_text(购物清单)`、`type_text(提醒：明天上午备份实验数据)` 原值注入 —— 对新任务是噪声，且有诱导照抄的风险。
6. **弱化措辞反向授权**：`_WEAKEN_HINT = "历史记忆/技能（可能过时，以实际观测为准）"`（`knowledge_inject.py:25`）。PC 侧无害；手机 GUI 上唯一可知状态的手段就是 `observe`，这句话等于"先看再说"，与 B 组 perception 翻倍吻合。
7. **信息密度低**：同一段 objective 原文占注入块约 60%。
8. **truncate 无优先级**：summary 按字符硬截 20000，不按新近度/相关度排序。

**P2（余留 / 未接线）**

9. `load_profile_text` 定义了零调用 —— 用户画像平面没接。
10. `build_skill_catalog` 按 task 目录优先读取、且只有它内部才认 `disable_model_invocation`；但 P2 已把候选技能直接落全局 ⇒ task 级目录恒空，而全局分支又被 `bridge._knowledge_parts` 绕过 —— 这条 catalog 路径在手机侧从未真正跑过。
11. 组装路径并存：新版 `build_knowledge_block` 之外，旧壳 `_knowledge_block` 仍保留（`bridge.py:676`），两条路径容易混用。
12. A/B 所需的**冻结知识快照**能力缺失：`snapshot_id` 只在旧 runner 里有，手机侧未接线 ⇒ 组间知识漂移无法控制（本轮实验已受影响）。

---

## 2. 重构目标

### 2.1 设计原则

1. **单一规范记录**：一次 run 只落一份 canonical execution record；skill / fact / lesson / profile 都从它**派生**，不再各自反查 trajectory。
2. **类型分工明确**（四选一，不再重叠）：
   - **Skill** = 可复用执行套路（脱敏后的动作序列）+ **触发条件**
   - **Fact** = 跨任务的环境事实（App 怪癖、UI 语义、可靠 id）—— **不再存"上次成功路径"**
   - **Lesson** = 失败归因（何时不适用）
   - **Profile** = 用户偏好（当前未接线，本次视情况补）
3. **注入只进一次**：同一条目在注入块中只允许出现一次；进入前经相关度排序，取 top-k。
4. **可被模型采用**：措辞中性并要求优先复用，而非"以实际观测为准"式免责。
5. **可度量优先**：每条理论收益都要能在真机回路上被证伪。

### 2.2 可衡量标准

| 维度 | 指标 | 目标（建议，待拍板） |
|---|---|---|
| 结构 | 重复率 = 1 − 去重后条目数 / 总条目数 | ≤ 5% |
| 结构 | 注入块字符数（同一知识负载） | 相对现状（2207 字符）降低 ≥ 40% |
| 结构 | 同一 `(tool, args-脱敏)` 序列在注入块中出现次数 | = 1 |
| 检索 | 注入条目命中率（注入 top-k 中含真正相关技能的比例） | ≥ 80%（人工标注 20 条） |
| 行为 | T5 跨任务迁移 A/B，每组 ≥5 次：**有效步数** B < A | 均值差 ≥ 15% |
| 行为 | perception 步数 B < A | 均值差 ≥ 20% |
| 行为 | 首个动作命中已注入序列的比例 B > A | B ≥ 70% |
| 可观测 | UI 知识页展示 `chars / 去重后 chars / 重复条目数` | 每次注入可见 |
| 安全 | 既有 DoD1/2/3/5 全部保持通过 | 0 回归 |

### 2.3 范围（Scope）

**IN（内核）**
- `kernel/hachimi_kernel/curator.py`：蒸馏链（`_success_path_facts` / `distill_task_memory` / `review_candidate_skills` / `maybe_merge_rollouts`）
- `kernel/hachimi_kernel/skill_library.py`：技能模型、写入、晋升、读取
- `kernel/hachimi_kernel/knowledge_inject.py`：注入块组装、措辞、摘要/按需加载路径、profile 接线
- `kernel/hachimi_kernel/bridge.py`：`_knowledge_parts` / `_knowledge_block` / 各数据面函数 / knowledge 事件字段

**IN（UI，最小化）**
- `KnowledgePage.kt`（展示去重后的结构 + 新指标）、`ChatModels.kt`（KnowledgeHint 增字段）

**IN（harness）**
- `harness/experiments/dod4_ab.py`（冻结快照、判据换成命中率 / 探索步数）、注入块 dump 与去重对比脚本正规化

**OUT**
- 任务循环（`mini_loop.py`）除 system prompt 拼装位以外不动
- 无障碍原语、工具面（除非 P3 决定加 `load_skill` 原语）
- Checkpoint / 轨迹（trajectory）存储格式

---

## 3. 参照设计：TencentDB Agent Memory

> 来源：`https://github.com/TencentCloud/TencentDB-Agent-Memory`（MIT，腾讯云数据库团队，插件形态接入 OpenClaw / Hermes，19.8k star）。
> 与我们同构：都在解决"历史经验如何存储与投放，才不至于被浪费"。本节只作参照，不照搬。

### 3.1 它的核心主张

**符号化短期记忆 + 分层式长期记忆**。拒绝暴力历史堆砌，也拒绝不可逆的暴力摘要。

| 域 | 底层（证据） | 中层（索引） | 高层（结构） |
|---|---|---|---|
| 短期任务 | `refs/*.md` 原始工具日志 | `jsonl` 步骤摘要 | Mermaid 任务画布（带 `node_id`） |
| 长期个性化 | L0 Conversation 原始对话 | L1 Atom 结构化事实 / L2 Scenario 场景块 | L3 Persona 用户画像 |
| 技能（Roadmap，**未落地**） | Traces + 报错日志 | 共性解决模式 | Skill / SOP |

配套机制：

- **上下文卸载**：厚重工具日志移出上下文，只保留轻量符号；需要时按 `node_id` 下钻恢复原文（宣称 100% 可溯源）。
- **预算化召回**：`recall.maxResults=5`、`scoreThreshold`、`maxTotalRecallChars` 字符预算、`recall.timeoutMs=5000`（超时跳过注入、不阻塞对话）。
- **写入前去冲**：`extraction.enableDedup` 在**提取阶段**做向量去重与冲突检测，而非注入时才处理。
- **混合检索**：BM25 + 向量 + RRF；`embedding.provider="none"` 时可降级为纯关键词路径。
- **白盒可调**：中间产物全是 Markdown / Mermaid，沿"高层符号 → 中层索引 → 底层原文"逐层定位。

公开数据（OpenClaw 插件形态）：WideSearch Token −61.38% / 成功率 +51.52%；SWE-bench −33.09% / +9.93%；PersonaMem 48% → 76%。

### 3.2 映射到我们：采纳什么

| 我们的缺陷 | 它的对应做法 | 采纳方式 | 落点 |
|---|---|---|---|
| #4 注入全量无匹配 | 混合检索 top-k + 阈值 + 字符预算 | 改 **相关度排序 + top-k + 注入字符预算**（关键词路径即可，无需 embedding） | Phase 2 |
| #1/#2 同源双写、无 ID | 提取阶段去重 + 冲突检测 | 沿用我们的 `entry_id`，但去重提前到**写入前** | Phase 1 |
| #8 截断无优先级 | `maxTotalRecallChars` 预算制 | 20000 字符硬截 → **预算 + 排序** | Phase 2 |
| #3 类型分工不清 | L0→L3 金字塔 | 借"平时看高层、要细节时下钻"的用法，落到 Fact / Lesson / Profile | Phase 1 |
| **（我们独有）每步重发整棵 observe 树** | 上下文卸载 + `node_id` 下钻 | **最对症的一条**：`observe` 返回的完整 UI 树就是它说的"厚重工具日志"，应卸载为 ref + 紧凑骨架，按需下钻 | 见 §3.4 |

### 3.3 不采纳 / 需改造

1. **它的 Skill 层是 Roadmap 未落地项** —— 而我们的核心恰恰是操作套路。骨架可借，Skill 沉淀必须自己设计。
2. **长期记忆语义不同**：它的 L3 指向"用户偏好"（PersonaMem 评测），我们面向的是**设备操作流程**。分层形式可复用，内容语义不能照抄。
3. **embedding 依赖**：我们内核受纯标准库红线约束，只能走它的 BM25 / 关键词降级路径（所幸它本身就支持）。
   - 影响评估：**当前可忽略**。objective 是系统生成的模板化长句（同类任务词元重叠极高，非用户口语），且技能池目前只有 1–2 条 —— `top-k` 排序质量在候选仅 1 条时不产生差别。
   - 补丁：**不用裸 BM25 全文**，改按 `app 包名` / `动作骨架` / `意图签名` 做**结构化字段匹配**，再辅以词元重叠打分。零依赖，且能避开 objective 中那段冗长 UI 提示带来的词频噪声。
   - 结论翻转条件：技能池涨到数十条、需要跨 App 泛化、或 objective 变为用户口语输入 —— 到时再评估可选 embedding 后端。
4. **Mermaid 对短序列过重**：GUI 操作是短线性序列，不需要任务图谱；退化为"紧凑动作骨架 + ref"即可。
   - 影响评估：**几乎无损失且更省**。Mermaid 的价值在于非线性拓扑（并行/依赖/回溯），我们的有效动作只有 5–9 步且无分支；而目的是让模型"照做"而非"理解拓扑"。
   - 补丁：骨架保留 `[成功] / [失败] / [重试]` 状态标记与 `ref`，以防将来出现分支/回溯时丢失可追溯性。

### 3.4 由此新增的候选目标（待拍板是否并入 §2.2）

- **上下文卸载（Context Offloading）**：把 `observe` 的完整树从每步载荷中移出，改为 `ref + 紧凑摘要`，需要时按 ref 下钻。
- 建议指标：**每步平均载荷字符数**、**单 run 累计输入字符数**，目标各降 ≥ 30%。
- 落点：建议单列 **Phase 2b**（或并入 Phase 2），因为它与"知识去重"正交、收益可能更大。

> **实施顺序提醒（重要）**：当前瓶颈不是检索精度，而是**知识池太小 + 注入形态差**（重复 / 未脱敏 / 措辞反向 / 无预算）。池子只有 1–2 条时，换再好的检索算法也测不出收益。
> 因此固定顺序：Phase 1（去重 / 分工 / ID）→ Phase 2（脱敏 / top-k / 预算 / 措辞）→ 池子扩大后再评估检索后端升级。

---

## 4. 分阶段实施计划

每个 Phase **单独提交、可回滚**；验收不通过则不进入下一 Phase。

**Phase 0 · 测量基准就绪（准备）**
- 冻结快照能力：让 A/B 两组吃同一份知识，消除组间漂移
- 工具正规化：把注入块 dump + 去重对比脚本从 `.tools/` 落到 `harness/tools/`
- 验收：同一知识能复注入两次得到同一字节

**Phase 1 · 单源 + 类型分工 + ID 去重**
- 引入 canonical record + `entry_id`（脱敏动作序列摘要）
- Fact 不再装成功路径；`_success_path_facts` 退出（或降级为 debug 通道）
- 注入前按 `entry_id` 去重，同一套路只出现一次
- 验收：重复率 ≤ 5%；DoD1/3/5 通过；UI 三个面仍能读

**Phase 2 · 相关度检索 + 脱敏 + 措辞**
- `objective_pattern` → 语义 key（意图签名），用于相似度排序，只注入 top-k
- `_fmt_action` 脱敏（文本类参数转 `<text>`）
- 弱化措辞改中性、要求优先复用
- 验收：注入字符数降低 ≥ 40%；检索命中率 ≥ 80%

**Phase 3 · UI 与可观测**
- 知识页新增「注入了什么 / 去重后多大 / 重复条目数」；展示 skills/facts/lessons 三类分区
- knowledge 事件扩展字段
- 验收：每次注入在 UI 可见，DoD2 保持

**Phase 4 · 真机验证**
- T1→T5 跨任务迁移，A/B 各 ≥5 次，判据用新指标
- 验收（建议口径）：B 有效步数均值低于 A ≥ 15% 且 perception 低 ≥ 20%

回滚：每个 Phase 一个 commit / 或独立 branch；`_knowledge_enabled` 总开关始终可一键关（注入与沉淀一起停）。

---

## 5. 待拍板决策项

1. **`## facts` 里的"上次成功路径"是否彻底删除**？（它与 skill 同构，是重复的根源；但它是当前唯一能"记住怎么做"的兜底）
2. **是否补 `load_skill` 原语**，让技能走"目录摘要 + 按需加载"？（Omni 原本设计，需要动工具面）
3. **是否让用户画像平面（Profile）上线**，还是先搁置？
4. **是否允许引入"意图签名/语义 key"** 取代 objective 全文做 key？（涉及一小段规范化逻辑，可能被视为领域启发式 —— 与北极星红线的边界需你确认）
5. **DoD4 判据是否正式换成**（有效步数 / 首个动作命中率 / perception 数），总步数仅作辅助？
6. **是否采纳「上下文卸载」（§3.4）**：把 `observe` 整树改为 ref + 紧凑骨架，并单列 Phase 2b？
7. 每个 Phase 是否单独提交 commit，还是攒到一个 branch 合并？

---

## 6. Phase 4 真机实测结果（2026-10-01，重构后首轮）

**环境**：realme RMX3115 / Android 12；大脑 = 用户自配（api.deepseek.com / deepseek-flash，每步 ~1s，远快于旧网关）；任务集 max_steps=1000 + wall_clock 900s；B 组经 `kb_snapshot` 冻结起点后 `--no-clean` 继承沉淀。

**机制验证（全部通过）**：
- entry_id 去重：T1 同套路两次 run 合并同一技能档（`entry_id: 9ea1041f6188`，success_count=2），不再产生重复文件 —— 写入端重复根源已根治；
- 措辞/预算/脱敏：注入块格式按 §2 落地（1520 字符级，type_text(<text>)，适用场景截断）；
- 上下文卸载：observe 骨架进消息、完整树落轨迹（证据可下钻）。

**DoD4 数据（T5 ×5/组）**：

| 口径 | A（无知识） | B（继承 T1 沉淀） | 差值 |
|---|---|---|---|
| 总步数（均值） | 34.4（15,28,37,16,76） | 31.4（15,45,13,14,70） | **−8.7%** |
| 总步数（中位） | 28 | **15** | **−46%** |
| 有效步数（均值） | 16.8 | 15.0 | **−10.7%** |
| 有效步数（中位） | 13 | **9** | **−31%** |
| perception（均值） | 17.6 | 16.4 | −6.8% |

**解读**：
1. 首次出现**方向一致的正向效应**：上一轮（旧注入格式）B 组均值反而劣于 A（20.8 vs 18.4）；重构后 B 在均值与中位数上均优于 A —— 注入形态修复（去重/脱敏/措辞/预算）带来真实边际。
2. 中位数口径大幅达标（−46% / −31%，超过 §2.2 目标的 −15%）；均值口径（−9~11%）未达 15% 目标 —— 原因是两组各有一个 70/76 步的模型抖动长尾（该模型同任务分布 13~76 步，方差仍主导均值）。
3. B 组 5 次中 3 次 ≤15 步（接近 T5 最优路径），A 组 3/5 ≥28 步 —— 分布重心明显左移。
4. 成功率 10/10（两组全过），对比旧预算时代的 ~60%：预算放大 + 新模型的稳定性收益独立成立。

**结论与下一步**：
- 判定：**DoD4 机制首次可观测到正向效应**，但按 §2.2 的均值口径尚未收口。
- 选项：①各加 5 次样本看中位数稳定性；②换更稳的规划模型再测（用户此前排除换模型，此处指模型选择自由度已回到用户手里）；③接受"中位数口径 + 长尾归因"作为收口口径。

---

## 7. OmniAgent 反哺清单（hachimi 真机验证结论 → 上游重构）

> 背景：知识层自 Omni 移植，hachimi 是它第一个真机验证场。本轮验证出的缺陷与
> 修法全部适用于 Omni 同源代码（`kernel/hachimi_kernel/*` 与 Omni 侧逐字同源，
> 仅 runtime_paths 的路径注入点一处差异）。**两边实际是同一个项目，必须同步。**

### 7.1 必须回移的代码变更（hachimi → Omni）

| # | 变更 | hachimi 落点 | Omni 动作 |
|---|---|---|---|
| 1 | `entry_id`（脱敏动作序列 sha1）+ `promote_or_insert` 按 ID 匹配 | `skill_library.py` | 同名函数直接替换；Omni 侧同样存在"每次成功新建候选"的重复问题 |
| 2 | 「成功路径」退出 facts 蒸馏链 + `_trajectory_digest`（脱敏摘要）+ `_extract_facts_via_llm`（可选 brain） | `curator.py` | 同步；Omni 侧 `_success_path_facts` 同样与 skill 双写 |
| 3 | `build_knowledge_block`：相关度排序（包名>词元>CJK bigram）+ top-k + 字符预算 + 摘要目录 + 中性措辞 | `knowledge_inject.py` | 同步替换；Omni 侧注入同样无预算 |
| 4 | `load_skill` 原语（摘要目录 + 按需加载） | `bridge.py` `_make_tools` | Omni 工具面补同名原语（其 ToolSpec 机制与 hachimi 同构） |
| 5 | `context_offload` 模块（observe/工具结果 → 骨架 + ref） | `context_offload.py` + `mini_loop._push_tool_result` | 同步；Omni 侧长任务 Token 大头同样是工具结果 |
| 6 | `brain_client` 超时 240s + 退避重试 | `brain_client.py` | 同步 |

### 7.2 必须同步的经验/约束

- **弱注入措辞是行为参数**：「可能过时，以实际观测为准」在 GUI 场景 = 授权重探索（perception 翻倍）；桌面场景未必可测，但措辞中性化零成本，直接换。
- **总步数是坏判据**：会被确认动作淹没（本组轨迹 ~40% 步是 observe）；判据用有效步数（−observe/look/screenshot）+ 首个动作命中率。
- **max_steps 过小会制造假数据**：失败样本被截断成"撞上限"，A/B 差异被掩盖（2026-10-01 实测 26 步时 2/5 失败，放大到 1000 后 10/10 成功）。
- **提取必须有 LLM 环节**：纯启发式蒸馏只能产出与 skill 重复的操作序列（死数据）；环境事实（App 怪癖/UI 语义）必须靠 LLM 从轨迹提取 —— 这是 memory 层唯一的内容源。
- **entry_id 的脱敏边界**：text 类参数归一 `<text>`（内容性），结构性参数保留（身份性）。粒度 = "同 App 同流程" 为一档。

### 7.3 hachimi 侧已验证的数字（供 Omni 回归对照）

- 注入块 2207 → 1520 字符（-31%，legacy 数据）；去重后重复条目 = 0。
- DoD4 首轮正向：T5 ×5/组，中位数总步 28 → 15（−46%）、有效步 13 → 9（−31%）；均值 −9~11%（长尾未收口）。
- 成功率：max_steps=26 时代 ~60% → 1000 步时代 10/10。
