# 完整内核上机：攻坚与踩坑全录（full kernel on device）

> 2026-10-31。目标形态：手机壳（hachimi）+ 完整 omni 内核真身（零上游改动）跑在
> 机内 Chaquopy Python 上，环回 HTTP 服务，壳与远程模式共用同一 wire contract。
> 当日完成 Phase 0（可行性五道闸）+ Phase 1（kernel host v2）并真机验收通过：
> 环回 chat 全链路（BYOK LLM 真实应答）+ GUI 工具真机执行（press/observe 读到
> 真实屏幕树）。本文按层记录全部踩坑：症状 → 根因 → 解法 → 沉淀规律。
> 配套方案文档：`full_kernel_hosting_plan_2026-10-31.md`。

## 0. 结论速览

| 层 | 结论 |
|---|---|
| 解释器 | Chaquopy 17.2 + 显式 `version = "3.12"`（与 buildPython、二进制 wheel 三方配对） |
| 依赖闭包 | 纯 Python 走 PyPI；**自建 Rust wheel 5 个**；语义替身 2 个；官方 Android 仓 1 个 |
| 运行时 | HOME/CWD/SSL_CERT_FILE 三件套注入 + 导入顺序纪律 + started 标志验证 |
| 网络 | 设备可达性是环境事实（与 PC 不同）；端点需逐个探活 |
| 工具面 | `@function_tool` 直接注册（不走 loader 目录扫描）；risk/percept 声明完整生效 |

## 1. 打包与解释器层

### 1.1 buildPython 从警告变硬依赖
- **症状**：无 pip 块时只报 warning（.pyc 编译跳过）；加 pip 块后构建失败
  `is not a valid Python 3.12 command`。
- **根因**：Chaquopy 只在需要 pip/pyc 时才调 buildPython；仓内 `.tools/python312`
  junction 已失效。
- **解法**：`-PhachimiPython=<真实 Python 3.12>` 属性传入（本机路径不进仓库）。
- **沉淀**：Chaquopy 开 pip 即要求 buildPython 与目标 `version` 主次版本一致。

### 1.2 目标 Python 版本要显式钉死
- 默认目标是 3.10（APK 里 `libpython3.10.so` 可证）；二进制 wheel 按解释器版本
  配对（cp312）。显式 `version = "3.12"` 后运行库变 `libpython3.12.so`——这同时
  决定了自建 wheel 的 DT_NEEDED 能否命中（见 §2.3）。

### 1.3 AssetFinder：源码在虚拟路径
- Chaquopy 把 python 源码打进 `assets/chaquopy/app.imy`（zip），import 正常，
  **但真实文件系统不存在**：任何「扫描目录」的上游逻辑（插件 loader 的
  `plugins/` 目录扫描、`Path(__file__).parent.glob`）在设备上静默落空。
- **解法**：工具注册改走 `@function_tool` import 即注册（TOOL_REGISTRY 进程内表），
  不依赖 loader 目录扫描；数据文件走 certifi.where() 等运行时解析的真实路径。

### 1.4 pip 索引拓扑
- Chaquopy 的 pip 原生包活仓库是 `chaquo.com/pypi-13.1`（URL 名是历史遗留，内容
  持续更新，android_21/24 标签 cp312/cp313 均有）；`maven.chaquo.com` 证书不含该
  子域，已废弃。
- 换国内镜像加速必须用 `--extra-index-url`（`--index-url` 会把 Chaquopy 原生仓
  挤出解析——PyYAML 等「pypi 有纯 wheel 但 Chaquopy 有原生 wheel」的包会直接
  `from versions: none`）。

## 2. 依赖闭包攻坚（二进制 wheel）

### 2.1 闭包清点方法
逐层 `grep "^import |^from "` + AST 解析顶层 import，区分「模块级硬依赖」与
「函数内惰性导入」；每个二进制包问三问：官方 Android wheel 有吗 → Chaquopy 仓有
吗 → 社区/自建可行吗。**静态审计仍会漏**（utils/json_utils 的 numpy 是第三处
漏网）——最终以真机逐轮报错收敛为准。

### 2.2 自建 wheel 五步法（Rust/pyo3 包，全部验证通过）
1. Rust 工具链：rustup（镜像 rsproxy）+ `stable-x86_64-pc-windows-gnu` host
   工具链（**机器无 MSVC 时必须**，msvc host 链接器会撞到 Git Bash 的 coreutils
   `link.exe`）+ `rustup target add aarch64-linux-android`（**按工具链隔离**，gnu
   工具链要单独 add）。
2. NDK r27c 解压（Git Bash 的 tar 解 zip 会报假损坏，用 Python zipfile）。
3. 交叉编译：
   `PYO3_CROSS=1 PYO3_CROSS_PYTHON_VERSION=3.12 PYO3_CROSS_PYTHON_IMPLEMENTATION=CPython`
   `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=<NDK>/.../aarch64-linux-android30-clang.cmd`
   `RUSTFLAGS="-L native=<从 APK 抽出的 libpython3.12.so 目录>"`
   `cargo +stable-gnu build --release --target aarch64-linux-android`。
   **pyo3 对 android 目标无条件 `-lpython3.12`**（extension-module feature 也压不掉
   ——上游 build.rs 对 android 硬编码），必须喂 libpython；从自家 APK 抽
   `lib/arm64-v8a/libpython3.12.so` 即可，产物 DT_NEEDED 与 Chaquopy 运行库逐字匹配。
4. 手工组 wheel（**maturin 1.16 在 Windows 有解释器名解析 bug，绕行 cargo 直编**）：
   从官方 manylinux aarch64 wheel 抽全部 python 文件与 dist-info 元数据 → 换入
   自编 `.so` → WHEEL 标签改 `cp312-cp312-android_24_arm64_v8a` → 重算 RECORD 哈希。
5. **扩展文件名必须裸 `.so`**：`.cpython-312-aarch64-linux-android.so` 这种完整
   标签名 Chaquopy 的 EXTENSION_SUFFIXES 不认（import 报 No module named），改成
   `<name>.so` 后即刻可见。abi3 包用 `_name.abi3.so` 同理改 `.so`。

已产出 wheel（PC venv 逐版对齐）：
- 社区现成：pydantic-core 2.46.3（Eutalix/NDK r25b；**文件名**要从 linux_aarch64
  改成 android_24_arm64_v8a——内部 WHEEL 标签本来就是 android 的，改文件名 pip 才收）
- 自编：jiter 0.18.0、rpds-py 0.30.0、ormsgpack 1.12.2、orjson 3.11.7、
  uuid-utils 0.14.1（abi3）
- 官方 Android 仓：psutil 7.1.3、numpy 1.26.2（含 openblas/libcxx 伴生包）
- 语义替身（见 §2.4）

### 2.3 libpython 链接的边界
自编产物 DT_NEEDED=`libpython3.12.so` 与 Chaquopy 运行库 soname 一致 → dlopen 直接
命中（真机验证可加载可执行）。若 Chaquopy 改 soname，需要 patchelf 改 DT_NEEDED
或改用纯 extension-module 路线（pyo3 对 android 硬编码，改不了）。

### 2.4 语义替身（比编 C 扩展省一半力气的正当手段）
前提：消费方对 API 的**语义要求**弱于位级兼容时，纯 Python 替身 + 官方开关是合法解：
- **xxhash 3.6.0**：langgraph 只消费 `xxh3_128_hexdigest`（pregel 状态版本指纹，
  只要求确定性 128 位摘要）→ `hashlib.blake2b(digest_size=16)` 实现，py3-none-any
  wheel。与 PC 真 xxhash 摘要值不互通——无跨机语义，无影响。
- **zstandard 0.25.0 stub**：langsmith 的压缩导入是 `try/except ImportError` 守卫
  + 官方开关 `LANGSMITH_DISABLE_RUN_COMPRESSION=true`——stub wheel 满足 pip 依赖
  解析，守卫自然判 False，双保险。
- 判据：替身前先 grep 消费方真实用法（langgraph 全仓只有一处 from-import；langsmith
  有守卫+开关），并确认无跨系统兼容语义。

### 2.5 闭包的地窖有多深（实测序列）
openai-agents → openai → jiter(Rust) → 自建；
mcp → jsonschema → rpds-py(Rust) → 自建；
langgraph → langgraph-checkpoint → ormsgpack(Rust) → 自建；
langgraph-sdk → orjson(Rust) → 自建；
langchain-core → uuid-utils(Rust) → 自建 + langsmith → zstandard(C，替身)；
langgraph 本体 → xxhash(C，替身) + numpy(官方仓)。
**规律**：LLM/Agent 生态 2025-2026 年 Rust 化严重，每个「纯 Python 好像够了」的
判断都要用 pip 实测闭包验证。

## 3. 运行时导入链

### 3.1 CWD/HOME 三件套（顺序敏感）
- `HOME` → `Path.home()`/`~/.omniagent` 落 App 私有目录；
- **CWD** → 上游 `utils/logger` import 时 `makedirs('./temp')`（相对 CWD），Android
  进程 CWD=`/` 只读 → `OSError: Read-only file system`。chdir 必须发生在**任何
  omni/utils 导入之前**——Kotlin 侧序列：`prepare()`（零依赖，只动环境）→ bind
  工具模块 → start。工具模块顶部再自保一份（import 即 chdir）。
- `SSL_CERT_FILE=certifi.where()` → 见 §4。

### 3.2 eager import 的桩
上游 `utils/__init__` 包导入期 eager import `window_finder`（模块级
`ctypes.windll.user32` + psutil）——Android 上 `import utils` 即炸。桩：
`ctypes.windll = SimpleNamespace(user32=None)`（psutil 官方仓有真装）。**桩必须
在第一条会触发 utils 的 import 前生效**——含壳侧工具模块自己的 import 链
（phone_tools → omni_core.tools.base → async_bridge → utils）。正解是上游 3 行
惰性化（这是全平台可移植性 bug，Linux/macOS 同炸），待拍板后撤桩。

### 3.3 循环导入：导入顺序即契约
`chat_runtime` 出于单测打桩兼容在模块级 `import router_runtime`（聚合器），而
聚合器又聚合 chat_runtime——**先导 chat_runtime 必撞循环**。PC 靠 server.py 先导
聚合器保平安；手机 bootstrap 同序（`import router_runtime` → 再取 chat_runtime）。
沉淀：重构拆分文件时留下的「兼容 shim import」是隐性的导入顺序契约，移植方必须
复刻宿主的导入序。

### 3.4 服务就绪验证不能靠探活冒名
- **症状**：omni_host 报 `kernel up`，实际端口上没东西。
- **根因**：bind 失败（端口被残留监听占用）后 uvicorn 静默退出，而 `/health` 探活
  打到了**还活着的旧监听者**上——冒名顶替。
- **解法**：就绪判断用 `uvicorn Server.started` 标志 + 线程存活检查，探活只作
  第二确认。
- **沉淀**：同一端口上先后两个服务交接时，「探活通过」无法区分新旧监听者。

### 3.5 测试挂钩与常驻服务抢端口
Phase 0 的 spike 自检服务器（18471）与正式 omni_host 同端口：spike 的
`should_exit` 是异步生效的，正式服务立刻 bind 会 `EADDRINUSE`，随后探活又打到
spike 上假成功。验收完成后**摘除测试挂钩**（spike 模块文件保留供诊断复用）。

## 4. 网络与 TLS

### 4.1 SSL_CERT_FILE（本次最深的一坑）
- **症状**：agent 循环跑通后 LLM 调用 3 连败 `APIConnectionError`（流式退同步的
  日志可见），而裸 `ssl.create_default_context()` 直连同一端点 TLS 握手成功、
  httpx 也成功（401=HTTP 正常）。
- **根因**：openai SDK 走 httpx2，httpx2 自建 ssl context 走「默认验证路径」——
  设备上 `openssl_cafile=/usr/local/ssl/cert.pem` 不存在 → `unable to get local
  issuer certificate`。Chaquopy 的 CA（cacert.pem）不在默认路径上，httpx2 也不
  自动用 certifi。
- **解法**：环境变量 `SSL_CERT_FILE=certifi.where()`（certifi 在 AssetFinder 有
  真实文件）——prepare() 里设置，先于任何 context 创建。
- **诊断法**：环回诊断路由 `/debug-net` 三层自检（裸 ssl / httpx / httpx2 +
  显式 CA 对照），一次出全部结论。该路由保留（仅 127.0.0.1 可达）。

### 4.2 设备网络可达性是独立环境事实
同一 BYOK 端点：PC 可达（走代理链）≠ 手机可达。实测某端点手机 ICMP ping 通但
TCP 443 全超时。**验收前对每个候选端点做 `toybox nc -w 5 <host> 443` 探活**
（部分 OEM 的 `settings put secure` 也被禁——无障碍等开关需手动拨）。切到设备
可达的 provider 后全链路即刻通。

### 4.3 BYOK → 内核配置流
壳 Keystore 配置 → JSON 注入 `omni_host.start` → `config.save_models_config`
（providers/defaults，模型路由真源）+ `config.save_settings`（security.mode、
memory 门）→ `reload_config`。**不要手写 YAML**——走内核自身 API 才有 deepMerge
与缓存失效语义。

### 4.4 端点响应形状要按流/非流两种模式分别验证（gitclaw 包裹坑）
- **症状**：手机设置页「测试连通」报「响应不是 OpenAI 兼容格式
  (unexpected response shape)」，但内核 agent 循环（同样走该端点）真实出活——
  测试失败与链路可用并存，极具迷惑性。
- **根因**：gitclaw 网关**非流式**响应把 OpenAI 载荷整个包在 `{"data": {...}}`
  里（`{"data":{"choices":[...]}}`）；**流式**则是标准 SSE `data: {choices...}`
  无包裹。内核走 openai SDK 流式 → 不受影响；设置页测试（BrainClient 非流式
  urllib）→ `data["choices"]` 查不到 → 形状报错。PC 上用 curl/脚本非流式测
  同样复现（与网络无关，两端点行为一致）。
- **解法**：壳侧 `brain_client._unwrap_gateway` / `vision_client._unwrap_gateway`
  ——解析后剥 `data` 壳再按标准形状取（有 `choices` 才剥，不误伤正常端点）。
- **沉淀**：判断「端点是否 OpenAI 兼容」必须**流式与非流式各测一次**；网关类
  服务（new-api 系及自研网关）常只对非流式加壳/改形。测试桩与正式链路若传输
  方式不同（流 vs 非流），测试结果不能外推到正式链路——本例两者结论相反。

## 5. agent 循环与工具面（验收实测行为）

- 工作台版系统提示原样生效（纪律 #0「先判断是否需要工具」在手机同款生效——
  闲聊零工具调用）。
- 工具面 20 个：9 个手机 GUI 原语（observe/wait/tap_by_id/tap_xy/type_text/
  gesture/screenshot/launch_app/press）+ 上游 core 工具。PC 专属工具（如
  shell_exec）在手机上会注册但执行失败——失败是结构化的（回 agent 自主换路），
  不炸循环；后续按「工具面=注册∩通道门」纪律收窄。
- 真机实测：`press(back)` 真实按键；`observe(compact)` 读到真实 UI 树（含列表
  内容、开关状态）；agent 基于 observe 结果准确描述屏幕并自主决定收尾。
- 上游增强全吃到：世界模型、轨迹落盘、LLM 计时打点、provider 自愈（3 连败升级
  暂停的语义与 PC 一致）。
- **执行链浮窗的接入缝隙**（真机实录）：NarrativePanel 悬浮执行链（球/浮条/控制台/
  红框）只由本地 bridge 事件转发器（`NarrativePanel.push`）驱动，远程环回轮的 SSE
  事件从未接入——表现为「聊天流正常、执行期全程无链路显示」。修法：`pushRemote`
  新入口（只驱动浮窗层、不分发消息流订阅者，避免与 handleRemoteEvent 双渲染），
  ChatPage 把远程事件翻译成 start/step/message/finish/error 五种浮窗事件喂入；
  首个 tool 步沿用 K4 语义（HOME 让位 + show）。SSE 侧旁路订阅者（sub 非空）的
  游标语义、流/非流响应形状差异（§4.4）都与本节无关，各自独立成坑。

## 6. 可复现最小验收序（换机/换端点照此跑）

1. 装包 → 启动 App → logcat 确认 `omni_host: kernel up on 127.0.0.1:18471`；
2. `adb forward tcp:18471 tcp:18471` → `GET /health` = `{"status":"ok"}`；
3. `GET /debug-net`：ssl/httpx/httpx2 三层全 ok（httpx 401 即为通）；
4. `POST /api/runtime/chat`（messages 一条闲聊）→ `GET /api/runtime/stream` 收到
   agent 真实文本（final=true）；
5. `POST /chat`（mode=task，含 GUI 指令）→ 流里出现 tool_call 且 result ok=true；
6. 设备手动拨无障碍（OEM 禁 adb 写时）后重跑第 5 步。

## 7. 遗留与后续

- **Phase 2**：退役 mini_loop 与 hachimi_kernel 内核侧移植模块（memory_brain 移植
  版/curator 蒸馏线/knowledge_inject 旧注入），kernel/ 只剩壳侧（bridge/omni_host/
  phone_tools/runtime_paths/task_store 数据面）；ChatPage kernelSend 分支删除。
- **GateUI on phone**：Phase 1 以 `security.mode=full_access` 顶（手机 GateUI
  缺位，显式全权）；审批面后续单独做。
- **上游 3 行惰性化待拍板**：utils/__init__ eager import window_finder（全平台
  可移植性 bug，Linux/macOS 同炸）落地后撤 ctypes.windll 桩。
- **工具面收窄**：shell_exec 等 PC 专属工具按「注册∩通道门」纪律对手机通道裁剪。
- xxhash/zstandard 替身若将来需要位级兼容，走 Chaquopy target 头文件 + NDK 编真包。
