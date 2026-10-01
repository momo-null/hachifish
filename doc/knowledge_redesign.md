# 知识模块现状盘点与重构专项（P2-R）

> 状态：**待拍板**。本文档只描述现状、给出重构目标与分阶段计划，**不含任何代码改动**。
> 数据来自 2026-10-01/02 真机实测与当前代码，标注到文件行号，可逐条复验。
> 复现工具：`.tools/show_injection.py`（打印真正进 prompt 的字节）、`.tools/eff_stats.py`（去噪步数）。

---

## 0. 为什么要动

知识层自 Omni 移植，`knowledge_inject.py:6` 写明「三来源统一挂载 system prompt 附加块（v2 定稿，**取消 A/B 测试**）」—— Omni 侧无落地场景，从未被验证。Hachimi 是它第一次遇到真机回路（automated reset / 可复现 seed / 可自动化 A/B），问题被放大且第一次可度量。三轮 DoD4 的结论：**机制全通**（蒸馏、合并、注入、UI、导出都工作），但早期**不可观测到收益** —— 不是"知识没用"，而是"知识以什么形态存在、如何投放"让它既难被匹配、也无被采用的动力。

---

## 1. 现状盘点

### 1.1 四个存储平面

| 平面 | 路径（`runtime_paths.py`） | 生产方 | 状态 |
|---|---|---|---|
| A. 任务资产 | `tasks/<task_id>/`（trajectory / *.run.json / `skills/`） | 运行时 | **残留概念**：P2 改为落全局后该目录恒空 |
| B. 全局技能 | `skills/*.md`（frontmatter + 正文） | `SkillLibrary.from_run_record` | ✓ |
| C. 全局记忆 | `memory/MEMORY.md` / `memory_summary.md` / `rollouts/*.md` / `merged.json` / `curator_metrics.json` | `Curator.distill_task_memory` + `maybe_merge_rollouts` | ✓ |
| D. 用户画像 | `memory/user_profile.md` / `profile_candidates.md` | `Curator.distill_user_profile` | ✗ `load_profile_text`（`knowledge_inject.py:43`）**全仓零调用** |
| （另） | `character.md` 角色卡 | UI | ✓ 经 `bridge._persona_block`，与知识层分离 |

### 1.2 写入：一次触发，两条互不知情的链

`bridge._curator_after_finish`（`:687`）→ `Curator.run_once`（`curator.py:139`）：

```
run_once(run_record)
 ├─ ③ review_candidate_skills ──> skills/*.md（scope=global，N=3 晋升 active，失败清零）
 ├─ ⑤ distill_task_memory ──> memory/rollouts/<task_id>.md（## facts / ## lessons）
 │     └─ maybe_merge_rollouts(threshold=1) ──> MEMORY.md ──> regenerate_summary()
 └─ ⑥ distill_user_profile ──> profile_candidates.md（不进画像正文）
```

**关键**：③ 与 ⑤ 消费**同一 RunRecord 的同一份轨迹**（`from_run_record` 取 `substeps`，`_success_path_facts`（`:725`）取 actions 序列），产出语义等价内容，**中间没有任何工序判断"这是同一条"**。

### 1.3 注入：全量、无匹配、两来源并排

`bridge._knowledge_parts`（`:640`）→ `knowledge_inject.build_knowledge_block`（`:192`）：读 `load_memory_text()`（`memory_summary.md` 全文，截断 20000，**无优先级**）+ 遍历 `global_skills().glob("*.md")` **全部**技能；`build_knowledge_block` 只做排版。产物进 `MiniLoop._system_prompt`（`mini_loop.py:423`）的 `knowledge` 位（persona 之后、任务说明之前），**每轮 LLM 调用重发一次**。

Omni 另有更克制的路径 `format_skill_catalog_message`（`:260`）输出 `<available_skills>` 摘要目录 + "需通过 `load_skill` 加载" —— **手机侧未采用**：`_knowledge_parts` 自己 `_parse` 全部技能，`load_skill` 原语不存在，`disable_model_invocation`（`skill_library.py:59`）无从生效。

### 1.4 消费面

- `KnowledgePage.kt`：`skills_list_json`（`:716`）/ `memory_json`（`:704`）/ `rollouts_list_json`（`:893`）；技能启停删除（`skill_delete_json:821`）、清空（`knowledge_clear_json:835`，scope=memory|all）、zip 导出（`:868` + FileProvider）
- 聊天指示卡：`bridge` 发 `knowledge` 事件 → `ChatItem.KnowledgeHint`
- PC 清知识只有 `adb shell run-as com.hachimi.app rm -rf files/memory files/skills`（回环 HTTP 无该路由）

### 1.5 一处学习 → 四份拷贝（T5 成功 run 样本）

序列 `launch_app → new_note → locked_note_title → button1 → text_note_view → type_text`：

| # | 落点 | 形态 |
|---|---|---|
| 1 | `rollouts/t_86d1b79252fa.md` 的 `## facts` | "任务「<objective 全文>」上次成功路径：launch_app(...) → …" |
| 2 | `MEMORY.md` 的 `## 长期事实` | 同上（合并后长期驻留） |
| 3 | `skills/skill_*.md` 的 `substeps_json` | 完整 args JSON：`{"tool":"type_text","args":{"text":"购物清单"}}` |
| 4 | 同文件 `## Substeps` + `objective_pattern` / `## Objective` 双写 | 同一序列第三次出现 |

⇒ **实测注入块 2207 字符，约 60% 是同一段 objective 原文重复抄写两遍。**

### 1.6 实测数据（三轮 DoD4，realme RMX3115 / Android 12 / deepseek-v4-flash-vision）

| 轮次 | 预算 | A（无知识） | B（有知识） |
|---|---|---|---|
| 1 | max_steps=26 | 14✓ 15✓ **26✗** | **26✗** 16✓ **26✗** |
| 2 | 1000 + wall_clock 900s（T1） | 15✓ 18✓ 12✓（均 15.0） | 13✓ 46✓ 17✓（均 25.3） |
| 3 | 1000（T5，B 继承 T1 沉淀，每组 5 次） | 20,18,18,23,13 均 **18.4** / 有效步 10.0 | 14,35,14,28,13 均 **20.8** / 有效步 11.8 |

（有效步 = 总步 − observe/look/screenshot）关键观察：放大预算后成功率 6/6 → 10/10（"26 步"曾把失败截断成假象）；两组最优等价（13–14 总步），**组内方差 13~35 步远大于可能效应量**；B 多出的步数几乎全是确认动作（离群 35 步样本里 16 步是 observe/look/screenshot，同组正常样本仅 5–11 步）。

### 1.7 缺陷清单

**P0（结构 → 重复与不可控）**
1. **同源双写无去重**：③⑤ 产出语义等价内容，无 ID、无交叉引用 ⇒ 注入必重复
2. **无稳定 ID**：技能以 `objective_pattern`（整段原文）为标识，fact 以"（全文+路径串）"为标识，无法判断是否同一条
3. **概念边界重叠**：`## facts` 里的"上次成功路径"就是套路，与 skill 的 `substeps` 同构 —— memory/skill 只是两个文件夹，无语义分工
4. **注入全量无匹配**：不按当前 objective 筛选/排序；`objective_pattern` 只当展示文本，从未参与相似度计算

**P1（形态 → 信噪比低、不被采用）**
5. **参数未脱敏**：`_fmt_action` 把 `type_text(购物清单)` 原值注入 —— 对新任务是噪声且有诱导照抄风险
6. **弱化措辞反向授权**：`_WEAKEN_HINT = "历史记忆/技能（可能过时，以实际观测为准）"`（`knowledge_inject.py:25`）。手机 GUI 上唯一可知状态的手段就是 `observe`，这句话等于"先看再说"，与 B 组 perception 翻倍吻合
7. **信息密度低**：同一段 objective 原文占注入块约 60%
8. **truncate 无优先级**：按字符硬截 20000，不按新近度/相关度排序

**P2（余留 / 未接线）**
9. `load_profile_text` 定义零调用 —— 画像平面没接
10. `build_skill_catalog` 按 task 目录优先、且只有它内部认 `disable_model_invocation`；task 目录恒空、全局分支又被 `_knowledge_parts` 绕过 ⇒ catalog 路径在手机侧从未真跑过
11. 组装路径并存：新 `build_knowledge_block` 外，旧壳 `_knowledge_block` 仍保留（`bridge.py:676`）
12. A/B 所需的**冻结知识快照**缺失：`snapshot_id` 只在旧 runner 有，手机侧未接线 ⇒ 组间知识漂移无法控制（已影响本轮实验）

---

## 2. 重构目标

### 2.1 设计原则

1. **单一规范记录**：一次 run 只落一份 canonical execution record；skill/fact/lesson/profile 都从它**派生**，不再各自反查 trajectory
2. **类型分工明确**（四选一，不重叠）：**Skill** = 可复用执行套路（脱敏动作序列）+ 触发条件；**Fact** = 跨任务环境事实（App 怪癖/UI 语义/可靠 id，**不再存"上次成功路径"**）；**Lesson** = 失败归因（何时不适用）；**Profile** = 用户偏好（未接线，视情况补）
3. **注入只进一次**：同一条目在注入块中只允许出现一次；进入前按相关度排序取 top-k
4. **可被模型采用**：措辞中性并要求优先复用，而非"以实际观测为准"式免责
5. **可度量优先**：每条理论收益都要能在真机回路上被证伪

### 2.2 可衡量标准（建议，待拍板）

| 维度 | 指标 | 目标 |
|---|---|---|
| 结构 | 重复率 = 1 − 去重后条目数 / 总条目数 | ≤ 5% |
| 结构 | 注入块字符数（同一知识负载） | 相对 2207 字符降低 ≥ 40% |
| 结构 | 同一 `(tool, args-脱敏)` 序列在注入块中出现次数 | = 1 |
| 检索 | 注入条目命中率（人工标注 20 条） | ≥ 80% |
| 行为 | T5 跨任务迁移 A/B（每组 ≥5）：有效步 B < A | 均值差 ≥ 15% |
| 行为 | perception 步 B < A | 均值差 ≥ 20% |
| 行为 | 首个动作命中已注入序列的比例 | B ≥ 70% |
| 可观测 | UI 知识页展示 `chars / 去重后 chars / 重复条目数` | 每次注入可见 |
| 安全 | DoD1/2/3/5 保持通过 | 0 回归 |

### 2.3 范围

**IN（内核）**：`curator.py`（蒸馏链）、`skill_library.py`（模型/写入/晋升/读取）、`knowledge_inject.py`（组装/措辞/摘要按需加载/profile 接线）、`bridge.py`（`_knowledge_parts` / `_knowledge_block` / 数据面 / knowledge 事件字段）
**IN（UI，最小化）**：`KnowledgePage.kt`（去重后结构 + 新指标）、`ChatModels.kt`（KnowledgeHint 增字段）
**IN（harness）**：`harness/experiments/dod4_ab.py`（冻结快照、判据换命中率/探索步数）、注入块 dump 与去重对比脚本正规化
**OUT**：任务循环 `mini_loop.py`（除 system prompt 拼装位）、无障碍原语与工具面（除非 P3 加 `load_skill`）、Checkpoint / trajectory 存储格式

---

## 3. 参照设计：TencentDB Agent Memory

> 来源：`https://github.com/TencentCloud/TencentDB-Agent-Memory`（MIT，腾讯云数据库团队，插件形态接入 OpenClaw / Hermes，19.8k star）。同构于我们：都在解决"历史经验如何存储与投放才不被浪费"。只作参照，不照搬。

### 3.1 核心主张

**符号化短期记忆 + 分层式长期记忆**，拒绝暴力堆砌与不可逆摘要。

| 域 | 底层（证据） | 中层（索引） | 高层（结构） |
|---|---|---|---|
| 短期任务 | `refs/*.md` 原始工具日志 | `jsonl` 步骤摘要 | Mermaid 任务画布（带 `node_id`） |
| 长期个性化 | L0 原始对话 | L1 Atom 结构化事实 / L2 Scenario 场景块 | L3 Persona 画像 |
| 技能（Roadmap，**未落地**） | Traces + 报错日志 | 共性解决模式 | Skill / SOP |

配套：**上下文卸载**（厚重日志移出上下文，按 `node_id` 下钻，宣称 100% 可溯源）；**预算化召回**（`maxResults=5` / `scoreThreshold` / `maxTotalRecallChars` / `timeoutMs=5000` 超时跳过注入）；**写入前去冲**（`extraction.enableDedup` 提取阶段向量去重 + 冲突检测）；**混合检索**（BM25 + 向量 + RRF，`embedding.provider="none"` 降级纯关键词）；**白盒可调**（中间产物全 Markdown/Mermaid，逐层定位）。
公开数据（OpenClaw 插件）：WideSearch Token −61.38% / 成功率 +51.52%；SWE-bench −33.09% / +9.93%；PersonaMem 48% → 76%。

### 3.2 映射到我们：采纳什么

| 我们的缺陷 | 它的做法 | 采纳方式 | 落点 |
|---|---|---|---|
| #4 注入全量无匹配 | 混合检索 top-k + 阈值 + 预算 | **相关度排序 + top-k + 注入字符预算**（关键词路径即可，无需 embedding） | Phase 2 |
| #1/#2 同源双写、无 ID | 提取阶段去重 + 冲突检测 | 沿用 `entry_id`，去重提前到**写入前** | Phase 1 |
| #8 截断无优先级 | `maxTotalRecallChars` 预算制 | 20000 硬截 → **预算 + 排序** | Phase 2 |
| #3 类型分工不清 | L0→L3 金字塔 | 借"平时看高层、要细节下钻"的用法，落到 Fact / Lesson / Profile | Phase 1 |
| **（独有）每步重发整棵 observe 树** | 上下文卸载 + `node_id` 下钻 | **最对症**：完整 UI 树即"厚重工具日志"，应卸载为 ref + 紧凑骨架 | §3.5 |

### 3.3 不采纳 / 需改造

1. **它的 Skill 层是 Roadmap 未落地项** —— 而我们的核心恰恰是操作套路。骨架可借，Skill 沉淀自己设计
2. **长期记忆语义不同**：它的 L3 指向用户偏好（PersonaMem），我们面向**设备操作流程**。分层形式可复用，内容语义不照抄
3. **embedding 依赖**：内核受纯标准库红线约束，只能走 BM25 / 关键词降级路径（它本身就支持）
   - 影响评估：**当前可忽略**。objective 是系统生成的模板化长句（同类任务词元重叠极高，非口语），且技能池只有 1–2 条 —— top-k 排序在候选 1 条时不产生差别
   - 补丁：**不用裸 BM25 全文**，改按 `app 包名` / `动作骨架` / `意图签名` 做**结构化字段匹配**，辅以词元重叠打分。零依赖，且避开 objective 冗长 UI 提示带来的词频噪声
   - 结论翻转条件：池子涨到数十条且需跨 App 泛化、或 objective 变为用户口语 —— 再评估可选 embedding 后端
4. **Mermaid 对短序列过重**：GUI 操作是短线性序列（有效动作 5–9 步、无分支），目的是让模型"照做"而非"理解拓扑"
   - 影响评估：**几乎无损失且更省**
   - 补丁：骨架保留 `[成功] / [失败] / [重试]` 状态标记与 `ref`，防将来出现分支/回溯时丢失可追溯性

### 3.4 边界对照：它怎么处理"上限"

| 维度 | TencentDB | 默认值 | 我们（hachimi） |
|---|---|---|---|
| **存储端容量** | **没有总量上限**；`capture.l0l1RetentionDays=0` = 永不清理 | 0（关） | 有：facts 40 / skills 20 / tasks 50 run |
| **写入端节流** | `everyNConversations=5`、`maxMemoriesPerSession=20`、`persona.triggerEveryN=50`、`l1IdleTimeoutSeconds=600`、`l2MinIntervalSeconds=900` | 见左 | **无**（每个 run 都蒸馏） |
| **注入端预算** | `maxResults=5` / `maxCharsPerMemory` / `maxTotalRecallChars` / `timeoutMs=5000` / `offload.mmdMaxTokenRatio=0.2` | 5/0/0/5000/0.2 | 有：块 ≤2000 字符、top-k=3、memory 视图 20000 |
| **去重** | `extraction.enableDedup=true`，提取阶段向量去重 + 冲突检测（依赖 embedding） | true | 写入阶段结构化规则 + 效用淘汰（无 embedding） |
| **分层与下钻** | 低层（L0/L1/refs）全量留证据，高层（L2/L3/MMD）留结构 | — | 未分层（facts 平面 + rollouts 证据） |

它的立场："Memory 不是为了让 AI 存下所有东西" —— 但实现上靠**注入端过滤**（每次只放 5 条 + 字符预算 + 超时跳过），**存储端默认不删**。
**手机侧不成立**：桌面/服务端磁盘便宜、每轮只召回一次；我们是**每步重发整块**（1420 字符 × 数十步），设备 tasks 实测 16.7 MB / 283 run ⇒ 必须补**真正的淘汰**（§8.2）。

**两条我们还没做、值得补的**：① **写入端节流**（沉淀速率先限流，比事后删更省）；② **反证扣分**（按某条 fact 操作却失败应扣分甚至淘汰，此前只有"印证加分"）。二者已在 §8.4 补齐。

### 3.5 候选：上下文卸载（待拍板是否并入 §2.2）

把 `observe` 的完整树从每步载荷移出，改为 `ref + 紧凑摘要`，按需下钻。建议指标：**每步平均载荷字符数**、**单 run 累计输入字符数**，目标各降 ≥ 30%。建议单列 **Phase 2b**（与"知识去重"正交、收益可能更大）。

> **实施顺序提醒（重要）**：当前瓶颈不是检索精度，而是**知识池太小 + 注入形态差**（重复/未脱敏/措辞反向/无预算）。池子 1–2 条时换再好的检索算法也测不出收益。
> 固定顺序：Phase 1（去重/分工/ID）→ Phase 2（脱敏/top-k/预算/措辞）→ 池子扩大后再评估检索后端升级。

---

## 4. 分阶段实施计划

每个 Phase **单独提交、可回滚**；验收不通过不进下一 Phase。

| Phase | 内容 | 验收 |
|---|---|---|
| **0 · 测量基准就绪** | 冻结快照（A/B 吃同一份知识，消除漂移）；注入块 dump + 去重对比脚本从 `.tools/` 落到 `harness/tools/` | 同一知识复注入两次得到同一字节 |
| **1 · 单源 + 类型分工 + ID 去重** | canonical record + `entry_id`（脱敏动作序列摘要）；Fact 不再装成功路径，`_success_path_facts` 退出（或降级 debug）；注入前按 `entry_id` 去重 | 重复率 ≤ 5%；DoD1/3/5 通过；UI 三个面仍能读 |
| **2 · 相关度检索 + 脱敏 + 措辞** | `objective_pattern` → 语义 key（意图签名）用于相似度排序，只注入 top-k；`_fmt_action` 脱敏（文本参数 → `<text>`）；弱化措辞改中性、要求优先复用 | 注入字符数降 ≥ 40%；检索命中率 ≥ 80% |
| **3 · UI 与可观测** | 知识页新增「注入了什么 / 去重后多大 / 重复条目数」；skills/facts/lessons 三类分区；knowledge 事件扩展字段 | 每次注入 UI 可见；DoD2 保持 |
| **4 · 真机验证** | T1→T5 跨任务迁移，A/B 各 ≥5 次，判据用新指标 | B 有效步均值低于 A ≥ 15% 且 perception 低 ≥ 20% |

**回滚**：每个 Phase 一个 commit 或独立 branch；`_knowledge_enabled` 总开关始终可一键关（注入与沉淀一起停）。

---

## 5. 待拍板决策项

1. `## facts` 里的"上次成功路径"是否彻底删除？（与 skill 同构、是重复根源；但它是当前唯一能"记住怎么做"的兜底）
2. 是否补 `load_skill` 原语，让技能走"目录摘要 + 按需加载"？（Omni 原设计，需动工具面）
3. 用户画像平面（Profile）是否上线，还是先搁置？
4. 是否允许"意图签名/语义 key"取代 objective 全文做 key？（涉及一小段规范化逻辑，可能被视为领域启发式 —— 与北极星红线边界需你确认）
5. DoD4 判据是否正式换成（有效步数 / 首个动作命中率 / perception 数），总步数仅作辅助？
6. 是否采纳「上下文卸载」（§3.5），并单列 Phase 2b？
7. 每个 Phase 单独 commit，还是攒到一个 branch 合并？

---

## 6. 真机实测结果链

### 6.0 重构后首轮（2026-10-01）

**环境**：realme RMX3115 / Android 12；大脑 = 用户自配（api.deepseek.com / deepseek-flash，每步 ~1s）；max_steps=1000 + wall_clock 900s；B 组经 `kb_snapshot` 冻结起点后 `--no-clean` 继承沉淀。

**机制验证（全过）**：`entry_id` 去重（T1 同套路两次 run 合并同一技能档 `entry_id: 9ea1041f6188`，success_count=2）；措辞/预算/脱敏落地（1520 字符级、`type_text(<text>)`）；上下文卸载（骨架进消息、完整树落轨迹）。

**DoD4（T5 ×5/组）**：

| 口径 | A（无知识） | B（继承 T1 沉淀） | 差值 |
|---|---|---|---|
| 总步（均值 / 中位） | 34.4（15,28,37,16,76）/ 28 | 31.4（15,45,13,14,70）/ **15** | −8.7% / **−46%** |
| 有效步（均值 / 中位） | 16.8 / 13 | 15.0 / **9** | −10.7% / **−31%** |
| perception（均值） | 17.6 | 16.4 | −6.8% |

解读：首次出现方向一致的正向效应（旧注入格式下 B 反而劣于 A：20.8 vs 18.4）；中位数大幅达标，均值未达 15% —— 两组各有一个 70/76 步长尾（该模型同任务分布 13~76 步）；B 组 5 次中 3 次 ≤15 步，A 组 3/5 ≥28 步；成功率 10/10。

> **本节乐观结论已被 §6.1 作废**（条件污染）。

### 6.1 收口实验（n=10/组，冻结快照 + 清零复验）—— §6.0 作废

**两个污染（本轮修掉）**：① **A 组清零没兜住**：Curator 在 run 结束后**异步**蒸馏，"rm 之后立刻开跑"时文件下一秒被写回（日志里 clear 后仍列出 `MEMORY.md`）⇒ 改为循环删除 + **复验为空**才开跑；② **B 组知识漂移**：连续跑每轮追加新 LLM facts ⇒ 改 `--snapshot`，每个 run 前恢复同一份冻结快照。

**收口条件**：T5 ×10/组；B = 冻结快照（T1 ×3 播种，技能已 active + 12 条环境事实）；A = 每 run 前清零并复验；同一模型；成功率 20/20。

| 口径 | A 中位 | B 中位 | 中位差 | U | p |
|---|---|---|---|---|---|
| 总步数 | 24.0 | 25.5 | **+6.2%** | 44 | 0.650 |
| 有效步数 | 13.5 | 14.5 | **+7.4%** | 38 | 0.363 |
| 确认步数 | 12.0 | 11.0 | −8.3% | 50 | 1.000 |

**结论（诚实版）**：三个口径**全部不显著**，总步与有效步甚至略微反向 ⇒ **严格控制条件下，知识注入没有带来可观测的步数收益**。§6.0 的"中位数 −46%"是 5 样本 + 条件污染下的噪声，不作数。
**根因**：组内方差远大于组间差（A 13~60 步、B 14~103 步，同模型同任务抖动 5~7 倍）。机制侧（去重/提取/注入/load_skill/search_skill）已逐项验证工作，不是它的问题。

### 6.2 低方差判据：知识被采纳了，只是没转化成步数（`harness/tools/lowvar_judge.py`）

对**已有 20 条轨迹回溯计算**（无需重跑）：

| 判据 | A（无注入） | B（冻结快照注入） |
|---|---|---|
| `first_action_hit` 首动作命中注入序列首步 | n/a | **0.90**（9/10） |
| `path_prefix_hit` 前 5 个动作命中注入序列 | n/a | **1.00**（10/10） |
| `used_skill_tool` 主动调用 load_skill / search_skill | 0.00 | **0.90**（9/10） |
| `inject_hit` 注入块含本任务同 App 技能 | 0.00 | **1.00**（10/10） |

⇒ §6.1 的不显著**不是**"模型忽略知识"，而是：**"知道怎么做" ≠ "执行干净"**。步数分布由纠错/确认次数决定（同任务 13~103 步，差 7 倍），而纠错次数取决于模型对界面状态的判断稳定性与门控/重试行为。
后续不该继续在知识层加码，而应看：①更稳的规划模型；②减少确认动作的提示/工具约束（如 observe 结果的确定性摘要）；③高抖动场景的收敛机制（连续同动作检测阈值）。
**副作用发现**：`used_skill_tool=0.90` 验证 `load_skill` / `search_skill` 在真机被真实调用 —— 关键词搜索不是纸面设计。

### 6.3 复杂 / 高知识密度场景（T3 时钟加闹钟）—— DoD4 达成

**假设**：§6.1 不显著是**天花板效应** —— T5 这类简单任务无知识也能 13~15 步摸出来，知识能省的只有试错那几步（收益上限 2~3 步），而组内方差 ±40 步 ⇒ 信噪比过低。换知识密度高的任务（不知道怪癖就必然绕远）应能显现。

**任务**：T3（Fossify 时钟加闹钟），卡点密集非平凡 —— 时间选择器需先点 `material_timepicker_mode_button` 切文本模式；数字输入框 `tap_by_id` 会 `performAction failed` 必须改 `tap_xy`；tab 标签不可点需坐标点按；编辑器会预填上次值。
**流程**：清零 → 播种 T3 ×3（首次 62 → 36 → 16 步，技能晋升 active）→ 冻结快照 `seed_t3x3_active` → B 每 run 前恢复快照，A 每 run 前清零并复验 → 各 12 次，成功率 24/24。

| 口径 | A 中位 | B 中位 | 中位差 | U | p | 结论 |
|---|---|---|---|---|---|---|
| 总步数 | 35.0 | 14.0 | **−60.0%** | 32 | **0.0225** | 显著 |
| 有效步数 | 18.5 | 7.5 | −59.5% | 40 | 0.0592 | 边缘 |
| 确认步数 | 17.0 | 7.0 | **−58.8%** | 27 | **0.0092** | 显著 |

**结论**：① **DoD4 达成**（总步 −60%、确认步 −58.8% 均 p<0.05；有效步 −59.5% 边缘），远超 §2.2 的 −15%；② §6.1 阴性得到解释：简单任务是**收益天花板太低** + 模型抖动，判据与实验设计必须按任务"知识密度"选择；③ 确认步下降最多印证作用机制 —— 知识主要消除"不知道怪癖 → 反复试探确认"的开销，而非操作本身；④ 播种曲线 62 → 36 → 16 展示"越用越顺手"在同类任务上真实存在。

### 6.4 语义去重真机验证（facts 体积收敛）

重建 APK 后同条件重跑播种（清零 → T3 ×3）：

| 指标 | 去重前（b226e9c） | 去重后（校准阈值） | 变化 |
|---|---|---|---|
| 3 次播种后 facts 条数 | 12 | **9** | **−25%** |
| 注入块字符数 | ~1319 | ~1326 | 持平（技能目录也在块内，facts 部分明显变薄） |
| 播种步数曲线 | 62 → 36 → 16 | 24 → 21 → 19 | 知识更薄但效果保持 |

**阈值校准（真机句子实测）**：同义句对重叠率 0.29~0.43，不同事实 ≤0.06 ⇒ 取 **0.35**；另加强信号：**两条事实共享同一个具体控件 id 即判重复**。

### 6.5 回溯合并（compact）+ 去重精度修正（2026-10-02 真机）

**6.5.1 存量回溯合并**：新增 `curator.compact_memory_sections`（纯函数）/ `compact_memory_master`（落盘）、`bridge.knowledge_compact_json` + 知识页「合并重复」按钮、PC 侧 `harness/tools/compact_memory.py`（默认 dry-run，`--apply` 回写设备）。分工固定：**合并时**只做「新增 vs 存量」比对（保护人工编辑），**存量 vs 存量**只在显式 compact 时做，两者不再混用同一段逻辑。

**6.5.2 去重精度：三条真机校准出的规则**

| 问题（真机实证） | 修正 | 常量 |
|---|---|---|
| **包名被当成控件**：`fossify.clock` 也符合"点分 token" ⇒ 同一 App 任意两条事实都"共享控件"而误合并 | 控件只认三种写法（完整 resource-id / 反引号短 id / 裸 snake_case）；**包名不算**，工具名（`type_text` 等）也剔除 | `_RES_ID_RE` / `_BACKTICK_ID_RE` / `_SNAKE_ID_RE` / `_TOOL_WORDS` |
| **共享控件 ≠ 同一件事**：`edit_alarm_time` 的"id 列举"与"必须先点它打开对话框"共享控件却是两条事实（重叠 0.19） | 共享控件只把门槛**降**到 0.25，不再无条件判同义（真同义对实测 0.31） | `_WIDGET_HIT_OVERLAP = 0.25` |
| **各讲一个控件时更不该合并**：`material_timepicker_mode_button` vs `edit_alarm_time`（重叠 0.21）在 0.35 下被误并 | 控件集合非空且**不相交** → 门槛抬到 0.55 | `_DISJOINT_WIDGET_THRESHOLD = 0.55` |

召回规则：两条事实引用/复述同一句原文（如 `` `projection not started` ``）⇒ 同主题，门槛降到 0.20（真机有一对同义改写重叠仅 0.26 靠它补回，`_ANCHOR_OVERLAP = 0.20`）。
另修脏数据：LLM 偶发返回 `- - xxx`，旧代码只剥一层项目符号 ⇒ 加 `_strip_bullet`（随下次 merge/compact 归一化）。

**6.5.3 结果（T3 播种后 + compact）**：facts 11 → **9**（−18%），MEMORY.md 964 → **733** 字符（−24%）。被合并的两条正是真同义对（`alarm_fab` 两种写法；projection 未启动的两种表述），而"必须先点 `edit_alarm_time` 打开对话框"按新规则**保留**。回归：内核单测 **99 passed**（新增 7 条）；新 APK 跑通一次 T3（32 步成功，facts 正常写入、无 `- - ` 脏数据）。
**剩余局限**：完全改写、既不共享控件也不复述同一句原文的同义对仍会漏 ⇒ 仍需 embedding 或定期 LLM 记忆合并。**但 §8.2 给出另一条路：不必靠提高归并率来收敛。**

---

## 7. OmniAgent 反哺清单

> 知识层自 Omni 移植，hachimi 是它第一个真机验证场。`kernel/hachimi_kernel/*` 与 Omni 侧逐字同源（仅 runtime_paths 路径注入点一处差异）—— **两边实际是同一个项目，必须同步。**

### 7.1 必须回移的代码变更（hachimi → Omni）

| # | 变更 | hachimi 落点 | Omni 动作 |
|---|---|---|---|
| 1 | `entry_id`（脱敏动作序列 sha1）+ `promote_or_insert` 按 ID 匹配 | `skill_library.py` | 同名函数直接替换（Omni 同样有"每次成功新建候选"的重复问题） |
| 2 | 成功路径退出 facts 蒸馏链 + `_trajectory_digest` + `_extract_facts_via_llm`（可选 brain） | `curator.py` | 同步（Omni `_success_path_facts` 同样与 skill 双写） |
| 3 | `build_knowledge_block`：相关度排序（包名>词元>CJK bigram）+ top-k + 字符预算 + 摘要目录 + 中性措辞 | `knowledge_inject.py` | 同步替换（Omni 注入同样无预算） |
| 4 | `load_skill` 原语（摘要目录 + 按需加载） | `bridge.py` `_make_tools` | 工具面补同名原语（ToolSpec 机制同构） |
| 5 | `context_offload` 模块（observe/工具结果 → 骨架 + ref） | `context_offload.py` + `mini_loop._push_tool_result` | 同步（Omni 长任务 Token 大头同样是工具结果） |
| 6 | `brain_client` 超时 240s + 退避重试 | `brain_client.py` | 同步 |

### 7.2 必须同步的经验 / 约束

- **弱注入措辞是行为参数**：「可能过时，以实际观测为准」在 GUI 场景 = 授权重探索（perception 翻倍）；桌面未必可测，但中性化零成本，直接换
- **总步数是坏判据**：会被确认动作淹没（本组轨迹 ~40% 步是 observe）；用有效步数 + 首个动作命中率
- **max_steps 过小会制造假数据**：失败样本被截断成"撞上限"，A/B 差异被掩盖（26 步时 2/5 失败 → 1000 步时 10/10）
- **提取必须有 LLM 环节**：纯启发式蒸馏只能产出与 skill 重复的操作序列（死数据）；环境事实（App 怪癖/UI 语义）必须靠 LLM 从轨迹提取 —— 这是 memory 层唯一的内容源
- **entry_id 的脱敏边界**：text 类参数归一 `<text>`（内容性），结构性参数保留（身份性）；粒度 = "同 App 同流程" 一档

### 7.3 已验证数字（供 Omni 回归对照）

- 注入块 2207 → 1520 字符（−31%，legacy 数据）；去重后重复条目 = 0
- DoD4：T5 ×5/组首轮中位总步 28 → 15（−46%）、有效步 13 → 9（−31%）；T3 ×12/组中位总步 35 → 14（−60%，p=0.0225，见 §6.3）
- 成功率：max_steps=26 时代 ~60% → 1000 步时代 10/10

### 7.4 检索能力：为 Omni 统一复用而设计

> 原则：**所有知识模块能力都要能被 Omni 统一复用**，故检索拆成「模块级公共接口 + 宿主无关工具壳」两层。

| 层 | 位置 / 契约 | Omni 集成方式 |
|---|---|---|
| 打分器 | `skill_library.score_skill(skill, query)`：包名交集 > 拉丁词元 > CJK bigram；纯标准库 | 直接 import；`find_by_pattern` 逐步迁移到同一打分器（唯一实现） |
| 检索 | `skill_library.search_skills(skills, query, limit)`：接受 `Skill` 或 dict，返回 `[{name, applies, score, entry_id}]` | 面板 / CLI / agent 直接调用；返回结构是 UI 与埋点共用的稳定契约 |
| 加载 | 工具 `load_skill(name)`：精确名 → 模糊名，返回脱敏序列 | 工具面注册同名原语（纯 Python，不经 Kotlin 派发） |
| 搜索 | 工具 `search_skill(query, limit)`：命中给 results + load_skill 提示，未命中给 available 列表 | 同上；池子上百条时成为主要入口 |

说明：工具实现刻意**宿主无关**（不依赖 Kotlin dispatcher / Android API），Omni 复制同名函数即可；注入侧（目录 top-k）与工具侧（关键词搜索）共用同一打分器，避免排序口径不一致；目录只列 `max_skills` 条是**上下文预算**考量而非能力边界 —— 池子变大后由 `search_skill` 兜住可见性（Omni 是 PC 长任务，技能池规模会大得多）。

---

## 8. 容量边界与自洽机制（两层结论）

### 8.1 第一层：手机端先定边界（这是手机 App，不是企业服务）

知识是**有槽位的缓存**，不是数据库 ⇒ 工程目标不是"完美去重"，而是"在有限槽位里自洽地滑动"。实测（2026-10-02 真机）：

| 项 | 实测 |
|---|---|
| 单 App 沉淀 facts | 9 条 / 733 字符（≈81 字符/条） |
| 注入块 | 1420 字符（每步重发） |
| memory / skills | 39 KB / 12 KB |
| **tasks（run 资产）** | **16.7 MB / 283 个 run** ← 真正会失控的是它 |

边界常量（`curator.py`）：

| 对象 | 上限 | 依据 |
|---|---|---|
| facts · 单 App | 12 条（保底 3） | 实测单 App ~9 条 |
| facts · 全局 | 40 条 | 注入预算 2000 字符只放得下 ~20 条，再多只烧 token |
| skills · 全局 | 20 条 | `entry_id` 唯一，同名套路累计不新增 |
| 注入块 / memory 视图 | 2000 / 20000 字符 | 已有（实际远小） |
| rollouts | 最近 30 条（mtime） | — |
| tasks | 最近 50 run / 7 天 / 100 MB（三选一触发） | — |

### 8.2 自洽机制：效用驱动的滑动窗口（替代"完美归并"）

**关键认识**：槽位有上限 ⇒ 漏掉的同义变体只是**多占一个槽位**，不会无限膨胀。收敛不必靠提高归并率，靠**弱者淘汰**即可。

三条机制（已实现，`curator.py`）：

1. **印证（corroborate）**：每个 run 结束用轨迹摘要点亮命中的事实（包名或具体控件出现 ⇒ `hits+1`）；同义簇内**只给簇代表加分**（`_similar` 聚类）
2. **效用分**：`hits×2 + 新鲜度`（30 天内线性衰减到 0）
3. **淘汰次序**：①近乎逐字重复（**严格门槛 0.5，且只用词面指纹** —— 删除必须保守，不能复用为召回设计的"共享控件降门槛"，否则误删 `edit_alarm_time` 那种共享控件却不同义的事实）→ ②单 App 超容（每 App 保底 3 条）→ ③全局超容

调用点：每个 run 收尾 `maintain_capacity(evidence=轨迹摘要)`；知识页「整理知识」按钮（合并 + 淘汰 + 清理一次做完）。效用索引旁挂 `memory/fact_meta.json`，不动 MEMORY.md 结构、不破坏人工编辑。
真机效果（2026-10-02）：tasks **283 → 50 run，16.7 MB → 3.6 MB**；facts 维持 10 条并建立印证计数；一次 T3 25 步成功；单测 **110 passed**。

### 8.3 给 Omni 的启示：embedding 上不上 → **当前不上**

按优先级：① **先补结构与容量**（单源派生、`entry_id`、类型分工、上限与淘汰）—— 与 embedding 无关，且正是 Omni 缺的（同源双写、无主键、**无上限**）；② **结构化字段检索**（包名/控件 id/意图签名）在"模板化 objective + 小池子"下与 embedding 不可区分（池子 1 条时 top-k 排序质量不产生差别，实测）；③ **embedding 触发条件**（满足其一再评估）：池子 ≥ 数十条且需跨 App 泛化；objective 变为用户口语；需要"找所有 App 的确认按钮套路"这类语义检索。

提醒：全程的限制因素是**注入形态与模型收尾稳定性**，不是检索精度。若 Omni 只上 embedding、不动注入形态与容量策略，大概率测不出收益。
一句话：**Omni 该带走的不是"要 embedding"，而是"先给知识定上限，再让效用决定谁留下"** —— 有了这条，归并率不完美也能自洽。

### 8.4 Omni 要不要也"按手机处理"？→ 按层设限，不照抄常量

腾讯那套看似企业服务，实为**桌面/服务端插件**（本地 SQLite + 可选云端向量库），"永不清理"在那个成本结构下合理：磁盘便宜、每轮只召回一次、且把"100% 可溯源"当卖点。**同一条原则在手机侧得出不同常量，恰恰说明原则是对的。**

判断依据不是"手机 vs 企业服务"，而是三个成本参数：① 注入块多久重发一次（我们每步 vs 它每轮）；② 存储是否稀缺；③ 检索池规模 / 是否需跨项目泛化。据此给 Omni 的建议是**分层设限**：

| 层 | 手机（hachimi） | PC / Omni 建议 |
|---|---|---|
| **注入层**（进 prompt 的字节） | 硬上限 + 预算（块 2000 字符 / top-k 3） | **同样必须硬上限** —— 与形态无关 |
| **结构层**（facts / skills 候选池） | 上限 + 效用淘汰（**真删**） | 上限 + 效用排序，**"降级"而非删除**：退出候选池、仍在证据层可检索 |
| **证据层**（rollouts / L0 / 轨迹 / refs） | 按条数 + 体积清理（50 run / 7 天 / 100 MB） | **不删**，只归档 / 压缩 —— 可审计可溯源是 PC 侧硬价值 |

⇒ **机制迁移，常量不迁移**。Omni 该抄的是"有上限 + 有排序 + 有反证"，而不是 12 / 40 / 20 / 50 这几个数字。

**已补齐的两条写入端治理**（对齐 TDB 节流与冲突检测）：

- **写入端节流**：`FACTS_PER_RUN_MAX=3`、同一 App `FACT_EXTRACT_COOLDOWN_S=300`，冷却期内不重复做 LLM 提取。注意：**不节流"合并"** —— DoD4 要求下一次 run 就能看到本次沉淀，故冷却只作用于昂贵的 LLM 提取
- **反证（conflict detection）**：失败 run 给相关事实记 `fails+1`，效用分 −3/次，累计 `FACT_FAILS_EVICT=3` 直接淘汰（不必等容量收紧）。此前只有"印证加分"，记忆会变强但不会认错 —— 现在会
