# Hachimi 对话式 UI + 大肥鱼角色化改造 Plan

> 设计基准：`doc/ux_mockup_v3.html`（v0.5，12 屏）· 内核语义参照 `D:\code\AITolls\OmniAgent`（桌面版统一入口已验证）
> 日期：2026-09-30 · 状态：待评审 · rev4（K1b 扩为父项目 sdk_loop 机制移植清单；总工期 8.5 人日）

---

## 一、现状盘点（已核实）

### 1.1 前端（android/app）

| 事实 | 出处 |
|---|---|
| 9 Activity，纯代码 View，无 Compose / XML / RecyclerView | 全 ui/ 目录，`DesignSystem.kt` L14 注释 |
| 主壳 = MainActivity 3-Tab（任务/知识/设置）FrameLayout 切换 | `MainActivity.switchTab()` L108 |
| 任务输入是**表单式**（目标卡 + 模式分段），非对话式 | `TasksPage.kt` L31 |
| "继续对话"雏形已有：同 task 追加 run | `TaskDetailActivity.sendContinue()` L285 |
| 授权门控已有三级（仅本次/总是/拒绝）底部 sheet | `gate/GateActivity.kt` L21、`GateManager.kt` L15 |
| 持久化 = SharedPreferences（"hachimi_ui"），无 DataStore/Room | `TasksPage.PREFS` L506 |
| 无图片加载库（无 Coil/Glide） | build.gradle |
| 大肥鱼素材在仓库根 `assets/big_fish/`，**未进 android 模块** | — |
| 悬浮层 = NarrativePanel 深色浮条/停靠台/文字药丸 | `NarrativePanel.kt` |

### 1.2 后端（kernel/hachimi_kernel）

| 事实 | 出处 |
|---|---|
| **角色卡链路已存在**：`character.md` 随 system prompt 注入，读取+截断管控已实现 | `runtime_paths.py` L91 `character_card()`、`knowledge_inject.py` L59 |
| system prompt 拼接点：目标+判据+约束+工具+SYSTEM_RULES+注入块 | `mini_loop.py` L280 `_system_prompt()` |
| finish 事件**已带 summary**（LLM 总结） | `bridge.py` L580-582 |
| step 事件无总步数字段（step/tool/args/ok/error/screen） | `bridge.py` L538 |
| **无闲聊接口**（无任务的单轮对话） | bridge.py 接口全集 |
| 无 userName 概念；task/run 模型，`files/tasks/<id>/` 落盘 | `task_store.py` |

### 1.3 可直接复用（改造面大幅缩小）

1. **人设提示词** → 前端写 `character.md` 即可，kernel 零改动生效
2. **完成态副文案** → finish.summary 直接渲染
3. **V12 授权门控** → GateActivity 换皮 + 加 10s 超时自动拒绝
4. **对话消息流** → step/finish 事件结构直接映射 tool 卡片
5. **GroundView 红框** → 保留不动

---

## 二、总体架构决策

| 决策点 | 方案 | 理由 |
|---|---|---|
| UI 框架 | **继续纯代码 View**（引入 RecyclerView） | 项目惯例（DesignSystem 单一来源）；聊天列表必须回收复用 |
| **对话入口** | **统一入口（对齐桌面 OmniAgent `/chat`）**：一个会话 = 一个 task，闲聊与执行由大脑自决，`mini_loop` 增加 message 终态 | 双入口日后必然分裂（历史/事件/门控做两遍）；桌面版已验证该语义 |
| **执行流** | **叙事事件对齐父项目 turn_steps**（thinking/message/tool_call 三类块语义）；手机端**不输出深度思考**（reasoning_content 留 kernel 不 emit） | 与父项目行为一致是硬要求；thinking 块桌面显示、手机端明确不要 |
| **任务执行 UX** | 首个 tool_call → 浮窗 + 前台引导；run 结束 → 回主界面，会话流已有完整 tool 卡片 + 终态面板（K4 闭环） | 手机小屏：执行期看浮窗，回看进度在对话流，单一叙事不重复 |
| 对话数据模型 | **不引入 conversation 实体**：会话即 task；消息历史 = `messages.jsonl`（kernel 单一真源，前端不存副本） | 桌面 P2.2 同构；避免双端历史漂移 |
| 意图判定 | **无前端判定、无独立分类调用**——system prompt 给大脑自决空间（闲聊直接回，干活调工具） | 零误判、零额外延迟；模型自决是 agent 语义 |
| 角色配置存储 | SharedPreferences（角色五元组）+ 头像存 `filesDir` + 人设写 `character.md` | 与现有持久化惯例一致，DataStore 引入收益低 |
| 头像/插图加载 | `BitmapFactory` + `LruCache` 手写小工具 | 素材共 5 张 PNG，无必要引入图片库 |
| 称呼注入 | userName 只进 **character.md（人设）**，不进任务 system prompt 的硬模板 | 任务执行需要模型冷静，卖萌人设已含"称呼用户为X" |
| 进度百分比 | step 事件增加 `total`（= max_steps）字段，前端算 n/total | 一行改动，避免前端猜 |

---

## 三、后端改造（kernel）

### K0. character.md / 知识注入接线（核实后发现的真实缺口）

> 摸底勘误：`knowledge_inject.character_block()` 函数虽存在（桌面同源模块），但 **bridge/mini_loop 均未调用**——bridge 只拼实验注入与 constraints（`bridge.py:384-399`），人设从未真正进 system prompt。

- `mini_loop._system_prompt()`（`mini_loop.py:280`）拼接处调用 `knowledge_inject.character_block()` + `build_knowledge_block()`（记忆/技能弱注入），插在 SYSTEM_RULES 之前
- 注入块 run 内锁定快照（knowledge_inject 已有该机制，直接复用）
- DoD：写 character.md → 下轮任务回复带人设语气（原 P0 DoD 依赖此条，之前误判"零改动"）

### K1. 统一入口改造（核心，对齐桌面 OmniAgent `/chat` 语义）

> 参照 `D:\code\AITolls\OmniAgent\backend\api\routers\chat_runtime.py:487`：
> **「一个会话 = 一个 task，闲聊与执行同一入口，由大脑自决」**。
> 不做独立闲聊接口——两条入口日后必然分裂（历史管理、事件、门控都要做两遍）。

**K1a. `start_task_json` 语义扩展为统一对话入口**

```python
def start_task_json(body_json)  # {task_id?, message, max_steps?, wall_clock_s?, mode?}
```
- `task_id` 为空（首条消息）→ **自动建 task**，objective = 首条消息（对齐桌面"先立会话、名字后回填"）
- 有 `task_id` → 沿用既有 task 追加轮次（现有 TaskDetail 续话链路已证明 task 级追加可行）
- messages 历史不再由前端全量传：**kernel 从 task 目录加载**（见 K1b），前端只传本条消息——单一真源，避免双端历史漂移

**K1b. mini_loop 增加「对话轮次」能力（以父项目 sdk_loop.py 为规范移植）**

> rev4 摸底补充：通读父项目 `omni_core/brain/sdk_loop.py`（1714 行），其循环经长期实机调试积累了一批自愈/收尾机制，mini_loop 均缺失。
>
> **SDK 可搬性结论（2026-09-30 定论）**：openai-agents SDK 整体不可搬——①依赖链 `openai-agents → openai → pydantic v2`，pydantic-core 为 Rust 原生二进制，Chaquopy 无 Android 预构建 wheel，pip 安装即失败；②R7 红线明文 kernel 仅标准库（`android/app/build.gradle.kts:47`）；③父项目踩过的 SDK 版本坑（run_streamed await 事故、Agent.__post_init__、griffe docstring 解析）会全部继承到手机端。`graph.py`（LangGraph 双 agent 编排）手机端单 agent 不需要。
> **但 sdk_loop 的机制大部分与 SDK 解耦，可近乎原样复制**（`errors.py` 全文、`_RepeatGuard`、`_hard_keep`/`_estimate_tokens`/`_prune_tool_results`、`build_budget_hint` 均为纯 stdlib）；挂在 SDK Model/Runner 接口上的部分（StopAtTools 收尾、Model 包装链）在 mini_loop 循环内手写对应分支。即：**骨架 = mini_loop（stdlib），机制 = 从父项目复制，行为 = 对照父项目验收**。

- `files/tasks/<task_id>/messages.jsonl`：user/assistant/tool 消息逐条落盘（新 SessionStore，或并入 TaskStore）
- run 开始时从 messages.jsonl 恢复 `_messages`；历史以 **user/assistant 交替**注入（父项目教训：两条 user 连排会让模型误读为用户连说两件事）
- `_system_prompt()` 改写：从「每轮必须调用工具」改为**大脑自决**——"用户闲聊/提问时直接文字回复；需要操作手机时调用工具；完成后调用 task_done"
- **纯文本回复分支重写**（`mini_loop.py:121-129`）：纯文本回复 = 本轮答案，emit message → `status="chat"` 结束 run；下一条用户消息 = 新一轮 run（同 task_id），历史自然延续

**移植清单（父项目踩坑机制 → mini_loop 对应实现）**：

| 父项目机制 | 出处 | mini_loop 移植方案 | 优先级 |
|---|---|---|---|
| 方案 B：纯文本收尾即答案（无验证条件信任大脑；有条件走 verify，拒绝则注入继续） | sdk_loop L1462-1485 | 纯文本 → message 事件 + `status="chat"`；有 done_when 时纯文本收尾先走 `_verify`，通过=done、不通过=注入"完成条件未满足请继续" | **P0（统一入口核心）** |
| verify 中途调用不误判收尾 | L1274-1279 | task_done/verify 被拒后置 `verify_stopped`，该轮纯文本不触发 chat 终态 | P0 |
| RepeatGuard：同名工具连续失败 N 次注入"换方案"提醒（只注一次防刷屏） | L437-509 | stuck 检测分级：3 次同工具失败→提醒换方案 → 4 次相同动作才终止（现有 stuck 直接终止太粗暴） | P0 |
| 崩溃自愈三分类：上下文溢出（硬截重试 1 次）/ 输出 length 截断（截历史+纠正提示，重试≤3）/ 工具不存在（回喂自纠，run 不崩） | L1406-1451（2026-09-21 修复） | brain.chat 异常分类（`errors.py` 判定函数 stdlib 化移植）；各类限次计数器防死循环 | P0 |
| 步数记账防停摆/防虚报 | T4.6/B1 | 纯文本回合也计 1 步；步数以实际工具调用数为准 | P0 |
| _hard_keep 滑窗 + _estimate_tokens 四字段（content/name/args/output——曾漏算致压缩永不触发） | L1575/L1636 | 超阈值（字符/4 估算）保留最近 N 轮；粘性压缩后置 | P1（长会话必需） |
| 预算提示（陈述性，禁"请加速"式诱导） | L66-76 | 可选：接近 max_steps 注入"已用 x/y 步，无法达成可调 task_done 交回已完成部分" | P2 |
| stop mid-chunk cancel / MCP 重连 / dispatch / 流式 / escalate / todo | — | 手机端无场景或步边界够用 | 裁剪 |

- run 语义变为：**一轮 = 0..N 工具步 + 一条文本回复（或任务完成/失败）**——与 `/chat` turn 语义一致

**K1c. task.json 元数据轻扩展**

- `objective` 首条消息自动回填后，允许后续轮次更新显示名（桌面同语义：名字后回填）
- `state` 增加 `chat`（对话过/等待下一轮），与 `running/done/failed` 并列

**DoD**（以父项目行为为验收基准）：
1. 首条消息"你好" → 自动建 task → 大肥鱼直接人设语气回复，不启动工具循环，悬浮层不出现
2. 同 task 第二条"帮我把桌面整理一下" → 进入工具循环 → 完成后回复
3. App 杀进程重启 → 同 task 历史完整恢复
4. 任务列表里闲聊会话与任务会话统一呈现（都是 task）
5. **对照父项目自愈行为**：done_when 未达成时纯文本收尾被拒并继续（不假绿）；同一工具错参数连败 3 次收到"换方案"提醒而非直接终止；弱模型输出截断/调不存在工具 run 不崩且能自纠；5+ 轮混合闲聊/任务对话不串台

### K2. step 事件增加 total 字段

`bridge.py:538` 的 `_emit({... "step": ...})` 增加 `"total": loop_max_steps`。一行改动。

### K3. 叙事事件模型对齐父项目 turn_steps 语义

桌面版（`helpers.py` L404-418）每轮产出三类块：`thinking` / `message` / `tool_call`。hachimi 叙事事件对齐为：

| 父项目块 | hachimi 事件（bridge `_emit`） | 手机端消费 |
|---|---|---|
| `{type:"thinking", content}` | **不 emit**（见 K3a） | ✗ 无深度思考输出 |
| `{type:"message", content}` | `{"event":"message","content"}` → assistant 气泡 | ✓ |
| `{type:"tool_call", name, arguments, result}` | `{"event":"step", tool, args, ok, error, total}` → tool 卡片 | ✓ |

- 事件结构（键名/语义）以父项目为准，hachimi 现有 start/mode/finish/paused/error 保留为生命周期事件（父项目 SSE 也有 task 级状态流，语义等价）
- finish 补 `"objective"`；step 补 `"total"`（= K2）
- **口播穿插输出（用户确认的硬要求）**：任务执行中 `reply` 同时含 content + tool_calls 时（口播+动作），content 同样 emit 为 message 事件——对齐桌面 `_merge_message_step` 语义。手机端执行流完整呈现：

```
[user 气泡] → [大肥鱼口播"收到！先看看桌面有啥"] → [tool 卡 list_directory ✓]
           → [口播"找到 23 个文件，开始分类"] → [tool 卡 move_files ▶]
           → ... → [✓办好了~ 面板]
```

口播气泡让执行过程可读（用户不是只看一串工具名），这是"chat 为主助手形态"在任务态的延续——执行中的每一步都有大肥鱼的话在讲。

**K3a. 手机端不输出深度思考；「思考中」为前端状态机**

- kernel 收到 `reasoning_content` 仅留在 `_messages`（继续回传模型，思维链连续性不受影响），**不 emit 任何 narrative 事件**——手机端对话流从头到尾无 thinking 块（与桌面前端差异点：桌面显示 thinking，手机端不要）
- **「思考中」不需要内核流式**（brain_client 是 urllib 同步阻塞，无 delta）：前端发送消息后立即置"大肥鱼思考中…"（V6 三点动画），**首个事件到达即切换**——message 事件→气泡；step 事件→tool 卡片+浮窗。纯前端状态机，零内核改动
- 真流式口播（逐字输出）为后置增强项（brain_client 加 stream 参数对齐桌面 _on_llm_delta），不阻塞本 plan

### K4. 浮窗-主界面进度闭环（UX 主流程，对应 V3/V10）

一图流（执行流与父项目一致，前端呈现为手机形态）：

```
主界面 ChatPage（对话模式）
  ├─ 用户发消息 → 统一入口（K1）
  ├─ 闲聊：kernel 直接 message 回复 → 主界面气泡，浮窗全程不出现
  └─ 任务：首个 tool_call（step 事件）出现
        ├─ ChatPage 消息流实时滚动 tool 卡片（后台也在收）
        ├─ 触发 NarrativePanel 浮窗 show（V10 面板/V11 球）+ 前台引导去目标 App
        └─ run 结束（finish/error）
              ├─ 浮窗按现有 scheduleHide 自动退场
              └─ 用户回到主界面 → 会话流已有完整任务进度：
                  [user 气泡] → [tool 卡×N] → [✓办好了~ summary 面板 + fish_done 彩蛋]
                  （或 [fish_fail 失败面板] → 重试/接管）
```

实现要点：
- **事件分发器**：`bridge.register_narrative` 仍是单 sink → `NarrativePanel.push()`；NarrativePanel 改为**转发器**——解析后按事件类型分发给 `ChatPage`（message/step/finish/error）+ 自身浮窗消费（step/finish），App 在前台时双 UI 同步更新，后台时只有浮窗
- **ChatPage 冷恢复**：App 被杀/用户中途回来 → `task_trace_json` + messages.jsonl 回放本轮全部 tool 卡与终态（K1b 落盘已含）
- **浮窗触发时机**：`show()` 从「任务 start」改为「首个 step 事件」（闲聊不弹浮窗的机制保证）

### K5. 其余后端不动

- `character.md` 读取链路、截断管控、缓存友好（run 内锁定）已就绪
- 暂不做：多角色、多 worker 槽位（桌面有 main/worker 槽位路由，hachimi 单大脑够用）、token 记账细化

---

## 四、前端改造（android/app）

### A1. 资产接入（地基）

- `assets/big_fish/{01_guide,02_fail,03_empty,04_done,avatar}.png` → 拷入 `res/drawable-nodpi/`，命名 `fish_guide / fish_fail / fish_empty / fish_done / fish_avatar`
- 新增 `FishAssets.kt`：Drawable id 表 + `loadCircle(drawableId, sizeDp)` 圆形裁剪（BitmapShader）+ LruCache
- DoD：任意 Activity 一行代码显示圆形大肥鱼头像

### A2. 角色数据层 `RoleStore.kt`（新增）

SharedPreferences `"hachimi_role"`：

| key | 默认 | 说明 |
|---|---|---|
| `role_name` | 大肥鱼 | 聊天头部 / 消息发送者 |
| `user_name` | 主人 | 称呼，进 character.md |
| `user_avatar_path` | null | filesDir 相对路径；null 时取 user_name 首字 |
| `persona_prompt` | 内置默认文案 | 500 字截断 |

方法：`read() / write(...) / writeCharacterMd(prompt, userName)` —— **写 `character.md`**（模板：人设 prompt + 系统追加行"称呼用户为「userName」"），首次启动与每次保存都同步写。

- DoD：改人设 → 下一次任务/闲聊的 system prompt 生效（kernel 零改动）

### A3. 对话式主界面 `ChatPage.kt`（替代 TasksPage 主体）

结构（对应 V2/V3/V5）：

```
ChatPage (LinearLayout)
├── chat-header        头像 + 角色名 + 状态（idle/干活中/思考中/完成）+ 会话切换
├── RecyclerView      消息流
│   ├── 消息项 user / assistant（头像+气泡+时间）
│   ├── tool 卡片项（icon+name+badge+args+进度）→ step 事件
│   ├── 完成面板项（✓办好了~ + summary + 再来一单 + fish_done 彩蛋）→ finish 事件
│   └── 失败面板项（fish_fail + 人话错误 + 重试/手动接管）→ error/finish(status=incomplete)
├── empty-state       fish_empty + "主人，给大肥鱼派个活？" + 建议 chips
└── input-area        EditText + 发送
```

关键接线（**统一入口，无闲聊/办事切换**）：
- 发送 → 一律 `start_task_json({task_id?: 当前会话, message})`：首条消息不带 task_id（kernel 自动建），之后带当前会话 task_id
- **意图由大脑自决**（K1b）：闲聊 → message 事件（assistant 气泡），浮窗不出现；干活 → step 事件流（tool 卡片）+ 浮窗（K4），finish 后主界面呈现完整任务进度
- **对话流无深度思考**（K3a）：ChatPage 只渲染 message / tool_call / 生命周期面板，thinking 类内容手机端全程不存在
- 消息流持久化：**前端不存消息副本**——冷启动从 kernel `task_trace_json` + messages.jsonl 恢复（K1b 单一真源）；前端只存「当前会话 task_id」（SharedPreferences 一行）
- 任务进行中：暂停/停止按钮内联在最后一条 tool 卡片（悬浮层之外 App 内也有控制点）
- 保留：TasksPage 的 BYOK 检查、错误人话化（`MainActivity.readableErrorStatic`）
- 会话列表（增强项，可后置）：头部点名称 → 近期 task 会话列表（复用 list_tasks_json）→ 切换/新建

### A4. 悬浮层三态改造（NarrativePanel.kt）

| 现有 | 改造为 | 实现要点 |
|---|---|---|
| 深色浮条 | V10 米白面板 | 圆角白卡（`Ds.round` 改色）；头像呼吸（RadialGradient pulse）+ 当前动作 + 进度条（total-K2）+ 最近 3 步染色（复用 colored()） |
| 停靠控制台 | 保留（高级模式） | 仅换底色为米白系 |
| 文字药丸 | V11 大肥鱼球 | 54dp 圆 + fish_avatar + **CustomDrawable 进度环**（SweepGradient）+ 步数角标；点击 expand；**可拖动贴边**（ onTouchEvent 更新 x/y，松手吸附左右边缘） |
| — | V12 不新建 | 授权门控继续用 GateActivity（本来就是底部 sheet 三按钮），仅换皮（fish_empty + 文案）+ **10s 超时自动拒绝**（Handler.postDelayed → gate_result=12） |
| GroundView 红框 | 保留 | 不动 |
| **push 单消费** | **转发器**（K4） | `NarrativePanel.push` 解析后分发：message/step/finish/error → ChatPage 回调；浮窗自身消费 step/finish；**show 时机改到首个 step**（闲聊不弹，主界面纯对话） |

### A5. 角色设置页（SettingsPage「角色」组 + RoleSettingsActivity）

- 设置首页新增「角色」分组置顶：头像卡（fish_avatar + roleName + "称呼你为「user_name」"）→ 点击进 RoleSettingsActivity
- RoleSettingsActivity（对应 V9）：
  - 大肥鱼区：角色名输入、人设 prompt 输入（500 字计数）、「恢复默认」
  - 我区：头像预览（圆形裁剪）、[从相册选择]（`ACTION_GET_CONTENT`）/[拍照]（`MediaStore.ACTION_IMAGE_CAPTURE`）、恢复默认；称呼输入
  - 保存 → `RoleStore.write()` → 同步 `character.md` → Toast "下次对话生效"
- 头像处理：选图 → `ContentResolver` 读 → 中心裁方 → 圆形 BitmapShader → 压至 256px → 存 `filesDir/user_avatar.png`

### A6. 引导页改造（OnboardingActivity）

- 新第一屏：fish_guide 大图 + 「才不是吃白饭的好吗！」气泡 + 「开始使用」→ 进入原四步授权流（无障碍→投屏→白名单→BYOK）
- 原四步流程保留（授权是硬需求），仅视觉换米白风

### A7. 完成态 / 失败态文案（ChatPage 内）

- 完成：finish.summary → "办好了~" 面板副文案；fish_done 右下角彩蛋
- 失败：`readableErrorStatic` → fish_fail 面板 + 「重试一次」（重发同 objective）/「手动接管」（跳 TaskDetail 看轨迹）
- 文案红线（沿用产品原则）：不写"已修复/保证成功"承诺

---

## 五、分阶段里程碑（每阶段可独立验收）

| Phase | 内容 | DoD |
|---|---|---|
| **P0 地基**（0.5d） | A1 素材 + A2 RoleStore + 写 character.md | 手动改 character.md → 任务回复带人设语气 |
| **P1 对话壳**（2d） | A3 ChatPage 消息流 + 空状态 + 头部；消息渲染先适配旧事件（start/step/finish/error） | 从聊天页发任务 → tool 卡片流实时滚动 → 完成面板带 summary |
| **P2 统一入口**（3d） | K1a/K1b/K1c（含父项目机制移植清单 P0 项）+ K2/K3 + ChatPage 接线（去表单化，全部消息走统一入口） | 闲聊直接回（不弹悬浮层）→ 同会话接"帮我整理桌面" → 工具循环 → 完成；杀进程重启历史完整恢复；父项目自愈行为验收 5 条全过 |
| **P3 悬浮层**（1.5d） | A4 三态（V10 面板/V11 球/total 进度）+ Gate 换皮超时；**首个 tool 步才 show**（闲聊不弹） | 收起成球→拖动→点开回面板；门控 10s 自动拒绝 |
| **P4 角色设置**（1d） | A5 全部（含头像上传） | 改称呼/人设/头像即时落盘，下次对话生效 |
| **P5 引导+收尾**（0.5d） | A6 引导页第一屏 + 旧 TasksPage 移除/知识页接线（顺延 P2 里程碑） | 新装用户旅程：引导→空状态→闲聊/派活→完成 闭环 |

总计约 7.5 人日。P2（统一入口）是本 plan 的架构核心，P1 可与 P2 并行开发（事件结构稳定后前端先行）。

---

## 六、风险与待确认

| # | 风险/决策点 | 倾向 |
|---|---|---|
| 1 | RecyclerView 引入 vs 手写回收 | 引入 androidx.recyclerview（模板默认有，不算破坏惯例） |
| 2 | **统一入口的防幻觉边界**：模型闲聊语义误判（把任务当闲聊直接回）或把闲聊当任务乱调工具 | system prompt 写清自决边界；连续 2 次纯文本注入一次提醒（K1b）；真机跑 5+ 轮混合对话验收 |
| 3 | messages.jsonl 无限增长（长会话 token 失控） | 沿用桌面做法：截最近 N 条进上下文 + 历史压缩后置（kernel 已有压缩引擎参数概念） |
| 4 | character.md 截断上限（kernel 现有管控）与 500 字输入冲突？ | 实现时核对 `knowledge_inject.py` 截断阈值，取 min |
| 5 | 旧 TaskDetailActivity「继续对话」链路与统一入口合并后的回归 | sendContinue 改走统一入口（同 task_id 追加），保留轨迹回放功能 |
| 6 | 大肥鱼 PNG 在深色模式边缘白边 | 米白底为主视觉；P5 加 `outline` 描边兜底（仅暗色主题） |
| 7 | GateActivity 超时自动拒绝的边界（用户正在读长文） | 10s + 倒计时可见；沿用现有 gate=12 语义（拒绝=停止），文档明示 |
| 8 | 旧 TasksPage 去留 | P5 移除表单卡，但 BYOK 检查/错误人话化逻辑迁入 ChatPage |

---

## 七、不在本 plan 范围

- 知识 Tab 数据接线（沿用 P2 原里程碑）
- Lottie 微动效（设计 v0.5 可选项，后置）
- 多角色卡 / 云同步
- token 记账页
