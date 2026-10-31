# 完整内核上机方案（full kernel on device）

> 2026-10-31 探索定稿。目标：**hachimi 只做壳，内核 = 完整 omni（零改动）**，
> 根除「同一内核维护两份代码」的漂移税（2026-10-30 记忆对齐即一笔实证税）。
> 依据：本仓 `doc/mobile_research_feasibility_review_2026-09-26.md` spike（A2 条件项
> 「上游出 wheel 再评估」）+ 2026-10-31 依赖面复审。
>
> **踩坑与攻坚全录**：`full_kernel_hosting_pitfalls_2026-10-31.md`（症状 → 根因 →
> 解法 → 沉淀规律，按打包/依赖闭包/运行时导入链/网络 TLS/工具面分层；换机换端点
> 照其 §6 最小验收序复跑）。

## 1. 审计结论（2026-10-31 实测）

**可纯 Python 装进 Chaquopy 的主体**：`fastapi` / `uvicorn`（不取 standard extras）/
`openai-agents` / `openai` / `httpx` / `anyio` / `PyYAML` / `starlette` 全纯 Python。

**不mounted 就不进来的 PC 专属面**（手机自建 app 只挂需要的 router，全在本仓侧组
装，零上游改动）：

| 模块 | 重依赖 | 手机处置 |
|---|---|---|
| `backend/api/router_models.py`、`router_llm.py` → `backend/api/deps.py` → `model_hub/*` | psutil / numpy | 不挂（deps 仅被这两个 router 引用，已核实）|
| `backend/api/router_llm.py` → `llm_runtime` | — | 不挂 |
| `plugins/voice`、`plugins/host`、`plugins/emulator` | torch / numpy / psutil | 不装（插件根目录指向本仓自带插件）|

**手机必须的两个路由**：`routers/chat_runtime.py`（chat/stream/stop/health）+
`routers/audio_api.py`（tts）——两者自身只依赖 fastapi + omni_core，干净。
**但有一条拖尾**：chat_runtime 出于单测打桩兼容在模块级 `import router_runtime`
（全路由聚合器）→ 链上带入 `diagnostics_api` → **psutil**（其余子 router 已逐一
核实为纯 fastapi/stdlib）。处置三选一：① Chaquopy 源若有 psutil Android wheel
则直接装；② bootstrap 在 import 前注册 psutil 桩模块（diagnostics 路由手机不挂，
桩只为 import 存活）；③ 上游把 diagnostics 的 psutil 改惰性导入（1 行，可移植性
bugfix 同款）。langgraph（graph_runner/orchestration）不在 chat_runtime 链上；
其本身纯 Python，可作多 agent 保险项装或不装。

**唯一二进制 wheel**：`pydantic-core`（agents SDK 硬依赖）。2026-09-26 spike 时
Chaquopy 无 Android wheel（pip 即失败，ADR A2）；2026-10 复检：官方仓仍无，但
社区已有 aarch64 CI 产出（Termux 向，需重打 Chaquopy 平台标签）或用 Chaquopy
构建链自编一次。**这是全方案的第一道闸。**

**唯一结构性障碍**：上游 `utils/__init__.py` 在包导入期 eager import
`window_finder`（模块级 `ctypes.windll` + `import psutil`），而
`from utils import get_logger` 遍布内核——Android 上 `import utils` 即炸。
这不是手机特例：**今天在 Linux/macOS 上 `import utils` 同样会炸**，属上游可移植性
bug。处置二选一（见 §5 决策点）。

## 2. 目标架构

```plain
┌─ hachimi App ──────────────────────────────────────────┐
│  Kotlin UI（现有 ChatPage 等）                          │
│    └─ OmniAgentRemote client（既有 C1 代码，零新协议）   │
│         base_url = 127.0.0.1:<port>   ← Tier 1 本机内核 │
│         base_url = PC 局域网地址      ← Tier 2 遥控    │
│  ───────────────── 同一条 client 代码，只换 base_url ─────────────────  │
│  Chaquopy Python 进程（常驻前台服务内）                  │
│    ├─ bootstrap（本仓新代码）：HOME/数据目录注入、组装 FastAPI    │
│    │   只挂 chat_runtime + audio_api、装载 phone 插件、起 uvicorn │
│    ├─ omni_core / backend / utils / config（上游真身，构建时从上游仓  │
│    │   拷入 APK，单一真源 = 上游仓，不做手工副本）                  │
│    └─ phone-tools 插件（本仓新代码）：omni 插件格式，GUI 原语      │
│        （observe/tap/type/…）经 accessibility 桥回 Kotlin          │
└────────────────────────────────────────────────────────┘
```

要点：

- **内核零分叉**：`omni_core` 不再有任何手机副本。mini_loop / curator /
  memory_brain 移植版 / task_store / knowledge_inject 等 `hachimi_kernel` 内核侧
  模块在 Phase 2 整体退役——记忆、注入、巩固、SDK loop、lane 全部直接用上游的。
- **构建期同步而非手工拷贝**：gradle task 从上游仓工作区拷 `omni_core/ backend/
  utils/ config.py config.yaml` 进 APK python 源集。上游改了即生效，永远没有
  「忘同步」。
- **Tier 1 / Tier 2 同构**：本机内核 = 环回远程。C3 的探活切换逻辑原样复用，
  C4（memory/sync）将来对同一个 contract 做。
- **人格/记忆资产**：brain.db、character.md、config 全落 App 私有目录（HOME 注入
  指向 filesDir），与现有 `HACHIMI_DATA_DIR` 同型。

## 3. 阶段计划

**Phase 0 — 闸门 spike（~1 天）**：空分支验三件事，任一不过则方案回炉：
1. pydantic-core aarch64 wheel 重打 Chaquopy 标签后 `pip install openai-agents`
   成功；
2. Chaquopy 内 `import omni_core` + 起 uvicorn（挂 chat_runtime 假 router）+
   Kotlin 侧 HTTP 打通 `/health`；
3. `utils` 导入障碍按 §5 选定处置后消失。

**Phase 0 结果（2026-10-31，spike 分支，真机实跑）**：
✅ **全部必需闸通过、无桩运行，全程 4.4s**——`GATE_A` pydantic-core Rust 扩展真机
加载（2.13.3 / core 2.46.3）；`GATE_B1` 纯 Python 栈；`GATE_B2` agents 0.22.2 真身
导入（自编 jiter 0.18.0 生效）；`GATE_C` 完整上游导入链**真实 SDK 无桩**通过
（utils + omni_core.local.loop.core + router_runtime + chat_runtime + audio_api +
router_system）；`GATE_D` 环回 uvicorn `/health` → 200。**Phase 0 通过，Phase 1 开闸。**

**自建 wheel 管线（已验证，Phase 1 固化进构建脚本）**：cargo（GNU host 工具链，避免
MSVC 依赖）+ NDK r27c clang + `PYO3_CROSS=1 PYO3_CROSS_PYTHON_VERSION=3.12` +
`-L native=<从 APK 抽出的 libpython3.12.so>`（pyo3 对 android 目标无条件链
libpython）+ 手工组 wheel（官方 wheel 抽 python 文件与元数据，换 .so、WHEEL 标签
`cp312-cp312-android_24_arm64_v8a`、重算 RECORD）。已产出：jiter 0.18.0、
rpds-py 0.30.0。maturin 1.16 在 Windows 有解释器名解析 bug（.exe 后缀），绕过用
cargo 直编。

**SDK 闭包与 PC venv 逐版对齐**（R6：漂移 = bug）：openai-agents==0.22.2 /
openai==3.13.0 / mcp==2.2.0 / httpx2==2.12.0 / websockets==15.0.1 /
jsonschema==4.26.0 / rpds-py==0.30.0 / jiter==0.18.0（PC 钉 `>=0.22.2`，手机锁
精确版）。

真机跑出来的 **Phase 1 bootstrap 必做项**（全部零上游改动）：
1. `os.chdir(HACHIMI_DATA_DIR)`：上游 `utils/logger` import 时写 `./temp`（相对
   CWD），Android 进程 CWD=/ 只读——CWD 注入与 HOME/DATA_DIR 同型；
2. **导入顺序**：先 `backend.api.router_runtime`（聚合器）再 chat_runtime——
   chat_runtime:57 兼容 shim 反向 import router_runtime，PC 靠 server.py 先导聚合
   器保平安，手机 bootstrap 同序即可；
3. 额外依赖：`numpy==1.26.2`（utils/json_utils 模块级，官方仓有）——静态审计漏网
   的第三处（json_utils，前两处是 window_finder/logger）；
4. agents/openai SDK 桩仅用于 jiter 解锁前的导入存活；jiter 解锁后移除。

**Phase 0 构建侧结果（2026-10-31，spike 分支）**：✅ 全过——APK 已含
libpython3.12.so + pydantic_core.so + psutil + 全套上游源码 + spike 自检模块，
待真机跑 `SPIKE|GATE_X` 结论。过程中钉死的技术事实：

- Chaquopy 17.x 的 pip 原生包活仓库仍是 `chaquo.com/pypi-13.1`（URL 名是历史
  遗留，内容持续更新，android_21/24 标签 cp312/cp313 都在）；`maven.chaquo.com`
  证书不含该子域，已废弃勿用。psutil 官方仓有（7.1.3），jiter/pydantic-core 无。
- 社区 wheel（Eutalix，NDK r25b）：**文件名**须改成 android 平台标签
  （`android_24_arm64_v8a`）pip 才接受；其 WHEEL 内部标签本来就是 android 的，
  `.so` DT_NEEDED = `libpython3.12.so`，与 `version = "3.12"` 目标的运行库
  soname 逐字匹配。
- pydantic 2.13.3 ↔ pydantic-core 2.46.3 严格配对（pip 解析确认）。
- openai 3.x 硬依赖 jiter（Rust，无 marker）→ openai-agents 装不上是**唯一**剩余
  二进制缺口；预案 = maturin + NDK 交叉编译（pyo3 标准流程，Phase 1 项），或
  上游出官方 wheel 后直接装（ADR A2 条件项销案条件）。
- 构建命令须带 `-PhachimiPython=<本机 Python 3.12>`（仓内 .tools junction 已断，
  pip 块使 buildPython 从警告变硬依赖）。

**Phase 1 — kernel host v2（2026-10-31 实装，真机验收通过）**：

- `hachimi_kernel/omni_host.py`：环回内核宿主——HOME/CWD 注入、`SSL_CERT_FILE=certifi`
  （httpx2 自建 context 不吃 Chaquopy CA，真机实证）、config.save_settings
  （security.mode=full_access + memory 开）、BYOK → models.json、导入序
  （聚合器先于 chat_runtime）、uvicorn 127.0.0.1:18471、`uvicorn started` 标志验证
  就绪（防残留监听冒名）；`prepare()` 零依赖先行（Kotlin 序：prepare → bind → start）。
- `hachimi_kernel/phone_tools.py`：9 个 GUI 原语注册 omni 工具面
  （@function_tool，unit=hachimi_phone，risk=actuate/percept=state），经
  accessibility 桥调真机；不走 loader 目录扫描（AssetFinder 虚拟路径，直接 import 注册）。
- `ChatPage`：Tier 决策 = PC 可达 → Tier 2；否则环回内核（Tier 1，同一 wire
  contract）；皆不可达 → mini_loop 兜底（Phase 2 退役）。
- **真机验收记录**：环回 chat 全链路通——task 建立 → 工作台版系统提示（纪律 #0
  同款）→ BYOK LLM 真实应答（deepseek-v4.1-flash）→ SSE 推流 → task 模式下
  agent 循环（thinking/tool_call/重试决策）真实运行，工具面 20 个
  （9 手机原语 + 上游 core）。
- 闭包增补（相对 Phase 0 清单）：langgraph==1.1.2（run_task 必经）+
  langgraph-checkpoint==4.0.1 / prebuilt==1.0.8 / sdk==0.3.11 / langchain-core==1.2.19
  （PC 同版）；自编 Rust wheel 增至 4 个：+ormsgpack 1.12.2（checkpoint）、
  +orjson 3.11.7（sdk）；语义替身 2 个：xxhash 3.6.0（blake2b-128 实现
  xxh3_128_hexdigest——langgraph 仅用于状态版本指纹）、zstandard 0.25.0 stub
  （langsmith 压缩守卫关闭 + LANGSMITH_DISABLE_RUN_COMPRESSION 双保险）；
  numpy==1.26.2（utils/json_utils，静态审计漏网第三处）。
- 设备环境事实：api.gitclaw.asia 手机 TCP 443 间歇超时（网络抖动，重试可达；
  复测与 PC 解析同 IP）——但端点本身手机完全可用；真正的坑是 gitclaw 网关
  非流式响应包 `{"data":{...}}`（流式标准 SSE），见 pitfalls §4.4；无障碍开关
  OEM 禁止 adb 写，需手动拨（重装 APK 会重置）。
- debug 路由 `/debug-net`（环回 only）：ssl/httpx/httpx2 三层连通性自检，留作诊断。

**Phase 2 — 退役第二份内核（1-2 天）**：真机验收后删 `hachimi_kernel` 内核侧模块
与其测试（红线：整段删除零痕迹），`kernel/` 只剩 bootstrap + phone 插件。

**Phase 3 — 红线与文档收尾**：R7 依赖白名单改写（新增 fastapi/uvicorn/
openai-agents/pydantic，redline-change 提交）；architecture.md 数据流与 ADR 更新
（A2 条件项销案：wheel 已解，SDK 上机）；omniagent_remote_client_plan 增补
「Tier 1 = 环回远程」语义。

## 4. 风险表

| 风险 | 等级 | 缓解 |
|---|---|---|
| pydantic-core wheel 重打标签不兼容（so 名/abi） | 中 | 退路：Chaquopy 构建链自编一次（一次性，钉版本） |
| SDK 版本坑继承（run_streamed / __post_init__ 等） | 低 | 上游已踩平并钉版本；跟随上游 pin |
| Chaquopy target Python 与上游代码语法要求不匹配 | 低 | 显式 pin 目标版本 ≥ 上游开发版（3.12） |
| 启动导入耗时（fastapi+SDK 数秒级） | 低 | 常驻服务 + 现有「内核就绪」轮询机制本就容忍 ~10s |
| approvals/GateUI 手机面缺位 | 低 | contract 的 full_access 显式语义先顶，GateUI 后续独立做 |
| uvicorn/asyncio 在 Chaquopy 后台线程的行为 | 低 | spike 第 2 项直接覆盖 |

## 5. 决策点（开工前拍板）

1. **utils 导入障碍处置**：推荐 A——上游 `utils/__init__` 改惰性导入（3 行，
   非特性耦合、是全平台可移植性 bugfix，上游仓库本来就该收）；备选 B——hachimi
   bootstrap 在 import 前伪造 `ctypes.windll`（零上游改动，但是赃活且脆）。
2. phone 插件首版工具面口径：与现 mini_loop 的 11 工具对齐（tap/type/observe/
   launch/…），GateUI 授权语义沿用 contract full_access。
