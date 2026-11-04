# 手机伴侣（Phone Companion）Plan · 2026-10-28（2026-10-30 自上游迁移定稿）

> **状态：未立项（设计冻结）。硬前置 = OmniAgent 语音 M3「PC 语音链路」验收通过
> （已通过：CosyVoice2 引擎落地并经听感验收）。**
> **归属说明**：本 plan 原起草于 OmniAgent 仓库（`doc/plans/phone-companion-2026-10-28.md`），
> 2026-10-30 拆分——OmniAgent 面向所有客户端通用化，**不感知本项目**（其文档与代码
> 均不出现本项目名，红线规则本身也只存于上游的 agent 侧记录）；本项目侧的工程内容
> 全部迁入本文件。上游契约唯一来源：OmniAgent
> `doc/plans/remote-client-contract-2026-10-30.md`（冻结 + 向后兼容演进），本文件只
> 引用不复制契约语义。

## 0. 一句话

手机 7×24 语音应答；PC 在线时自动升级为完整 agent；**记忆只有一份（PC brain.db）**。

## 1. 目标与非目标

目标：
- 手机在 PC 关机时也能语音对话（能力降级但不哑）；
- PC 开机/可达时，同一部手机、同一个对话体验，自动获得完整能力（工具/派发/长期记忆）；
- 记忆不分裂：长期记忆只有 PC brain.db 一份。

非目标（本期不做）：
- QQ/飞书通道随手机 7×24 在线（gateway 随 PC 走）；
- 唤醒词（"喊一声就醒"）——先做助手注册/按键触发；
- iOS、多人格、手表/音箱端。

## 2. 载体决策记录（为什么不是 Termux，为什么不需要 root）

| 选项 | 判定 | 理由 |
|---|---|---|
| Termux 跑完整栈 | ✗ | 音频面只有一次性录音/fire-and-forget TTS——**流式 VAD 与 barge-in 做不出来**；Doze/OEM 杀后台/无 systemd/升级碎 venv 是长期运维税；FTS5 在 Termux 构建间不可靠（实测有 `no such module: fts5` 案例） |
| root 后的 Termux | ✗（对语音无解） | 瓶颈是**进程/会话架构**不是权限：`AudioRecord` 流 + 麦克风前台服务必须住在 App 进程里；root 只解锁 `tinycap`/ALSA 这类 device-specific hack，且 `audioserver` 的路由/并发策略绕不开 |
| **Kotlin 壳（hachimi）** | ✓ | `SpeechRecognizer`（ASR）+ `TextToSpeech`（TTS）+ `TTS.stop()`（打断）全是原生一等公民；hachimi 已有前台服务/授权门控/BYOK 骨架；root 非必需 |

内核位置（Chaquopy 本地 vs PC 遥控）**不是安装决策，是运行时模式**——见 §3。

## 3. 架构：两层 + 一条切换规则

```
Tier 1（PC 不可达）：hachimi 本地内核应答
    Chaquopy ReAct loop + 原生 ASR/TTS/打断
    能对话、有会话内上下文；无长期记忆（见 §4）
Tier 2（PC 可达）：遥控模式
    对话走 PC：POST /api/runtime/chat?mode=chat
    回复订阅：GET /api/runtime/stream?task_id=&sub=<client-id>
    打断：POST /api/runtime/stop（force）
    完整能力：工具 / dispatch / brain.db 检索注入 / 巩固
```

切换规则一句话：**PC 可达（`GET /health` 1s 超时）即升级，不可达即降级**。
对用户是同一个"她"，差别只是聪明程度随 PC 在线状态浮动。切换发生在轮次边界
（正在说的话不打断、不丢）。

## 4. 记忆规则（唯一要设计对的点）

- **PC brain.db 是长期记忆唯一真源；手机端不建记忆系统，只存会话原文（L0）。**
- 手机离线对话 = 会话 jsonl 落手机；PC 可达后回放：会话消息喂进 PC
  `capture` 缓冲（`POST /api/runtime/memory/sync`，幂等）→ 现有提炼/两段判/
  brain.db 管线 → 长期记忆。
- 好处：手机内核不需要 FTS/巩固/判重（绕开 Chaquopy sqlite FTS5 未验证的风险）；
  语义一致性继续由本项目的 harness 体系维持。
- 代价（接受的降级）：离线对话延迟入长期记忆；离线期间想不起历史细节。
- 回放**幂等**：会话带 `session_id` + 消息序号，PC 侧按 (session_id, seq) 去重。

## 5. 上游就绪情况（原「PC 侧前置」，2026-10-29 已实施）

1. **请求级鉴权** ✅：非回环来源访问 `/api/*`、`/v1/*` 必须
   `Authorization: Bearer <server.auth_token>`；loopback 豁免、`/health` 豁免、
   静态 UI 豁免。**网关定位**：公网面统一鉴权在 nginx，服务侧 token 即 nginx
   转发时携带的内部凭证，不是用户体系。逃生门 `server.allow_tokenless_lan: true`
   （默认关，启动大字警告）。
2. **绑定地址贯通** ✅：desktop 壳 spawn 参数读 `server.host`；后端 `uvicorn.run`
   本就读 `server.host`。防火墙首次放行 8000。
3. **手机测试安全上下文**（实测路径，本项目侧事项）：浏览器 `getUserMedia` 要求
   安全上下文——`http://<PC内网IP>:8000` 在手机浏览器默认禁麦克风。短期：Android
   Chrome 在 `chrome://flags/#unsafely-treat-insecure-origin-as-secure` 加该地址并
   重启；终态：走 nginx 挂证书的 https 域名（证书在网关层，与统一鉴权同层）。用
   系统浏览器（Chrome/Safari），微信内置 webview 禁 getUserMedia。

## 6. 契约

见上游 `remote-client-contract-2026-10-30.md` §1（5 端点 + 鉴权，冻结）。
本项目是它的一个客户端实现；客户端侧关注点：`sub` 用稳定 client-id、
`memory/sync` 按 (session_id, seq) 保证幂等、`stop` 对齐 Tier 1 的 TTS 打断。

## 7. 里程碑与验收

| 里程碑 | 内容 | 验收 |
|---|---|---|
| ~~**PC-0**（前置，随 M3）~~ | ~~内网鉴权 + 绑定贯通 + 防火墙~~ | ✅ 已随 M3 实施验收（另一台内网设备带 token 完成对话） |
| **C1** 遥控模式最小闭环 | hachimi 加 ASR/TTS + 写死 PC endpoint | ✅ 已实现（分支 `feature/omniagent-remote-client`：`OmniAgentRemote.kt` 客户端 + `ChatPage` 分流 + 语音输入 + TTS 播报，SSE 解析 JVM 单测）；真机内网已验收（首字 <2s）。**语音规则改版（2026-11-04）**：播报开关从 ☁ 对话框删除，改由输入形态决定（语音轮念、打字轮只上屏）；ASR·TTS 端点只取 设置 · 语音供应商，未配置在语音入口直接报错。**同日二次改版**：对讲（按住 🎤）整段下线——它本质就是"语音转文字再发送"，与 通话 重叠，删除后输入行只剩 发送 + `+`；`+` 菜单承载「对话取向」与「语音通话」两件事。**同日三次改版（控件归位）**：运行中的停止钮从头部搬进输入行、与 发送 同位互换并按本轮实际 Tier 接到内核 `/api/runtime/stop`；头部的模型 chip 与 ☁ 远程内核一并收进 `+` 菜单「内核与模型」；左上任务列表入口改自绘「两横」 |
| **C1+** 对话取向（chat / task） | 手机与 PC 同一个 mode 开关 | ✅ 已实现（2026-11-04）：`ChatRequest.mode` 上游早有，手机侧 `OmniAgentRemote.chat(..., mode)` 透传；`+` 菜单两档——**工作 lane（task）**=可干活 + 思考出「深度思考」卡、**对话纪律（chat）**=只回话、不出思考卡且本地按 `max_steps=0` 发。缺省 task（与 PC 同口径），全局选择进 prefs（`chat_mode`）、按会话覆盖进内存 `modeByTask`（与 PC 的 `modeByTask[currentTaskId]` 同构，手机不做跨启动持久化会话级选择） |
| **C2** 打断 | TTS 播报中说话 → 停播 + force | ✅ 已实现（**对讲档随对讲下线删除**，2026-11-04）：**通话档 = 真全双工插话**（播报期间麦克风常开，`Segmenter` 用高于起句线的插话线判出"人在插话"→ `AudioPlayer.stop()` 闭嘴 + 这句话收完照常 `force=true` 打断在途轮）。余 echo 消不干净时由 `EchoGuard` 按文本重合判掉，不发消息；**没有平台 AEC 的机器上的替代打断 = 点正在念的那条气泡**（`AudioPlayer.stop()`，不另起第二路采集）或 `+` 里的「结束通话」；**待真机验收**（<500ms + PC 侧 [被打断]） |
| **C2+** 通话形态 | 自由对话（连续听 + 念回复 + 插话） | ✅ 已实现（2026-11-04）：`DuplexListener` = `AudioRecord(VOICE_COMMUNICATION)` + `AcousticEchoCanceler/NoiseSuppressor/AGC`（session 挂载）+ 能量 VAD 分段（替掉旧的"8s 自动收口 + 15s 看门狗"）+ 串行上传。**设备无平台 AEC 时按能力降级**：播报期间整段不收音（半双工），入口 toast 如实说明并给出可用的替代打断方式（点气泡 / 结束通话）。入口从「免提按钮」改为 `+` 菜单的「语音通话 / 结束通话」，标题栏状态改「通话中 · 说话即发送」 |
| **C3** 自动切换 | 探活降级/升级，轮次边界切换 | ✅ 已实现（每轮 `/health` 1s 探活，不可达回 Tier 1 内核，过程不丢话）；**开关默认关**（2026-11-04 改：配了 base_url 也要在远程配置里勾选才走 PC，☁ 亮起＝这轮真会走远程）；**待真机验收**（拔网线不哑、插回自动升级） |
| **C4** 离线会话同步 | §4 回放 + 幂等（依赖上游 memory/sync 端点落地） | 🚧 **blocked**：上游 `POST /api/runtime/memory/sync` 未实现（OmniAgent 侧唯一内核待办） |
| **C5** 锁屏可用 | 助手注册（VoiceInteractionService）或耳机键/通知栏按钮 | ⏳ 后置（C1-C3 真机验收后） |

**C1-C3 落地清单（分支 `feature/omniagent-remote-client`，2026-10-30）**：
`OmniAgentRemote.kt`（HttpURLConnection 零新依赖：health/chat/stop + SSE 行解析）·
`RemoteConfigStore.kt`（Keystore 加密 base_url/token/自动切换）·
`VoiceIo.kt`（~~系统 SpeechRecognizer + TextToSpeech 封装~~ → **2026-11-04 只留 `AudioPlayer`**：识别/播报实现全部收进 `VoiceProvider.kt` 的供应商通路；同日全双工改造：`AudioPlayer` 从 MediaPlayer 换成 `AudioTrack + USAGE_VOICE_COMMUNICATION`——AEC 的参考信号必须在通话链路上；**对讲下线**：`SpeechController` 接口与 `VoiceProvider.kt` 里的 `ProviderSpeechInput` 实现整段删除，该文件只剩 `ProviderStt` + `voiceProviderTts`）·
`Wav.kt`（PCM↔WAV 一份头实现，通话收音与播报共用）·
`VoiceVad.kt`（`Segmenter` 能量 VAD + 挂起端点 + 插话判定、`EchoGuard` 余 echo 文本判定；纯逻辑，JVM 单测 `VoiceDuplexTest`）·
`HandsFree.kt`（`CommsAudio` 通话模式与外放路由、`SessionEffects` AEC/NS/AGC 实测可用性、`DuplexListener` 连续收音分段串行转写）·
`ChatPage`（路径分流 + 远程事件映射复用 ChatMapper + 通话接线 + **`+` 菜单**：对话取向两档 /
内核与模型（远程内核配置对话框 · 本会话模型选择器）/ 语音通话·结束通话；输入行按钮序 =
输入框 · **发送 ↔ 停止同位互换** · `+`；头部只留「两横」会话入口（自绘两条横杠，不再用
🗂 emoji），🎤 与 ☁ 与模型 chip 均已从头部下线）·
`ChatModels.kt`（`ChatItem.ThinkingCard` + `ChatMapper.splitThinkTag/stripThinkTag`：上游流式把成对思考段投在 message 块原文里，客户端按同口径拆——正文进气泡、思考段进卡）·
`OmniAgentRemoteTest`（SSE 解析 JVM 单测）·
manifest（RECORD_AUDIO + MODIFY_AUDIO_SETTINGS + cleartext C1 简化；系统识别服务可见性查询随端侧 ASR 删除）·
`spec/redlines.md`（R1 豁免修订 + R2 权限清单附录两次，redline-change）。

**设备侧验收（2026-11-04，MuMu 模拟器 x86_64 / Android 12，语音端点与模型端点临时指向
`.tools/mock_voice_server.py`——上游 BYOK 403、本机 8000 只回空串，拿不到真回复，故用
mock 把"assistant 确实回了话"这一环补齐；验证完配置已原样还原）**：`files/chat_remote.log`
留证——🎤 对讲轮 `ev=chat → tts ok: 48044B → play: 24000Hz 1ch 1000ms`（`Wav.parse` +
`AudioTrack` 通话声道出声），mock 侧同轮对上 `/v1/audio/transcriptions`（31KB PCM）与
`/v1/audio/speech`；纯打字轮 `metaSuccess=true` 但**无 tts/play 行**（播不播看输入形态这条
成立）；免提开 = `duplex start: aec=false ns=false agc=false fullDuplex=false` 且
`dumpsys audio` 实到 `MODE_IN_COMMUNICATION`，关 = `duplex stop` + 回 `MODE_NORMAL`（引用计
数不漏）；免提中按 🎤 只闭嘴、不另起第二路采集。**模拟器拿不到的两条**（真机项）：① 平台
AEC 生效与插话时延（MuMu 无 AEC 且宿主麦克风不进 guest，VAD 永不出句）；② 播报期间真实
收音的余 echo 表现（`EchoGuard` 仅 JVM 单测覆盖）。复现口径：`python .tools/mock_voice_server.py`
+ `adb reverse tcp:18470 tcp:18470`，语音供应商与内核模型端点都填 `http://127.0.0.1:18470`。
（该轮留证里的「🎤 对讲」入口已于同日下线，此处按当时的表面形态如实记录，不复现。）

**设备侧验收第二轮（2026-11-04 同日，同一台 MuMu x86_64 / Android 12，样本 = 同一会话内
连发 5 轮，其中 task 取向 3 轮走真实 link provider grok-4.7、2 轮走 `.tools/mock_voice_server.py`
的 `MOCK_STYLE=thinktag` 回复；chat 取向 1 轮）**：三条各有正面证据——① **第二轮发送不再顶出
第一轮的回复气泡**（上一轮的用户泡 / 思考卡 / 结果泡原位不动，新轮从下方追加；上游侧靠
`helpers._outbox_turn_floor` 把旁路游标钉在本轮起点，见 OmniAgent `tests/test_mode_lane.py`）；
② **思考不再当答案显示**：task 取向每段思考独立成「深度思考」卡（灰底 + 模型 chip + 折叠箭头，
流式期自动展开、`toolcall`/终态自动收起，PC `ThinkBlock` 同构），气泡里只剩正文；think-tag
那一路（上游把成对思考段投在 message 块原文里）用 mock 实机跑通——卡内容 = `先想清楚这一步
要什么，再决定怎么说。`、气泡 = 剥标签后的正文，且**同块终态覆盖同一气泡、不另起第二条**；
③ **取向按预期分叉**：chat 取向那轮 `hadTools=false metaSteps=1` 且**无思考卡**、直接出结果，
task 取向轮 `hadTools=true metaSteps=3/5` 且出卡。`+` 菜单本身：勾在当前取向行、切换即时
toast、通话行在通话态自动变「结束通话」，标题栏「通话中 · 说话即发送」↔「待命中」互切，
`dumpsys window` 实测弹层 `mFrame=[400,1557][1400,2158]`（点行命中弹层自身，不穿透到列表）。
**本轮新增的已知瑕疵（已改）**：卡头图标先用 🧠（Unicode 11），该 ROM emoji 字库没有 → 豆腐块，
换 💭（Unicode 6.0，与 🔧 同批）后实机正常。**验证后设备侧已还原**：`models.json` 去掉 mock
provider、main/worker 槽位选择清空（回到验证前的 `source=none`）、会话模型 chip 复原、
`adb reverse/forward` 与 mock 进程全部撤掉。

**设备侧验收第三轮（2026-11-04 同日，控件归位 + 停止接线，同一台 MuMu x86_64 / Android 12，
模型走真实 link provider grok-4.7）**：表面形态截图留证——头部只剩 头像 + 角色名/相位 +
「两横」入口（点它实开「会话」面板，含 `+ 新建会话`）；输入行 = 输入框 · **发送 ↔ 停止同位
互换**（运行中那颗是红底 ■，轮次边界回到 ↑）· `+`；`+` 菜单三组 = 对话取向（✓ 当前行）/
内核与模型 / 语音通话，其中「远程内核 · 未启用（话走本机内核）」实开配置对话框（读到存档
base_url/token/自动切换，取消不改设备）、「本会话模型 · grok-4.7」实开单选目录（当前项选中）。
新增两行后弹层 `dumpsys window` 实测 `mFrame=[360,1146][1400,2158]`（向上扩到 1012px，未被
屏幕顶裁）。**停止真接到内核**（`files/chat_remote.log` 两轮）：① `stop: tier=本机内核
task=t_b08ae5c3b456` → 330ms 后 `ev=chat ×2 → status → terminal: hadTools=true
metaSuccess=false` → `stream closed`；② `send 19:07:47.004 → stop 19:07:54.560 → terminal
19:07:54.709`（150ms）→ `stream closed 19:07:54.814`。内核侧本来就是硬取消（`AgentCore.
request_stop()` 同时 cancel 编排图 Task 与当前 chunk 的 SDK Task，`sdk_bridge` 把
`should_stop=_is_stop_requested` 交给 tool loop），手机侧缺的是**按下之后的三件本地事**：
先闭嘴（`speakGen++` + `AudioPlayer.stop()`）、本轮 Tier 记不住时按本机内核寻址（旧实现
`?: RemoteConfigStore.load(act) ?: return`——没配 PC 就是白按）、读流加 `!remoteRunning`
退出条件（内核 `/api/runtime/stream` 是 `while True` 常驻流，本地收口了不退读就留一条死
连接）。**停止的语义也改了**：用户停掉的轮原先走失败出口（「遇到点麻烦…」+ 重试/手动接管
面板 + 失败弹窗）＝把用户的动作说成故障；现按 `stopRequested` 分叉成「已停止」，不出面板不
弹窗，流里保留内核那句「用户主动停止」。**未覆盖**：语音通话行代码路径未变（上一轮已实机验
过，本机无 AEC 且宿主麦克风不进 guest，只到起/停与模式切换这一层）；chat 取向的单大脑调用
在途时按停止要等本次模型请求返回才落停（内核按 chunk 边界检查）。46 个 JVM 单测全绿。

**设备侧验收第四轮（2026-11-04 同日，「结果都出来了这一轮还不结束」）**：现象是 chat 取向
气泡已经落定、输入行那颗红底 ■ 还占着发送位十几到几十秒。先把「手机在等什么」排掉——
手机按**终态 chat 事件**收口，不等 `status.running=false`（`terminal → stream closed` 稳定
100ms），所以尾巴不在客户端。用 `.tools/tail_probe.py`（`adb forward tcp:18471` 直连本机
内核，同一句 12 字闲聊，`mode=chat`、`gitclaw2/grok-4.7`）配内核自己的账本定死数字：轨迹
`llm_timing` 里 call 1 的 `ts_end`（＝用户看完答案的时刻）与 `*.run.json` 的 `end_ts` 相差
**34.29s**；手机侧同口径两轮（`files/chat_remote.log`）口播完成 → 终态分别是 **11.17s**
（19:45:05.620 → 19:45:16.791）与 **38.79s**（19:43:13.506 → 19:43:52.296），中间一段流上
只有 0.3s 一次的 status 心跳，没有任何事件。尾巴的来源是**收尾意图门**：纯文本收尾走
「无校验条件，信任大脑」路径（`run.json reason` 原话），判成功之前 `_plain_finish_judge`
要再发一次 LLM 调用做语义复核——它不计入 `brain_calls`、`timeout=90s`、对用户完全不可见，
而它防的是「oneshot 任务把没派发的畸形调用当结论、静默假成功」（上游
`memory-architecture.md` §6），chat 取向的纯文本收尾本身就是交付物，没有可被误判的「任务
成功」。**A/B**：设备 `files/.omniagent/config.yaml` 临时加 `runtime.long_task.
finish_intent_check: false` 并重启应用，同一句尾巴塌到 **0.04s**（`end_ts` 与 `ts_end` 差
40ms）；测完配置原样还原（md5 `4918f8537a86eda5f659eb3f1288f94f`）。**同日补记**：这把总闸
已进 PC 设置页「通用 → 收尾意图门」（同键进 `config.CONTEXT_DEFAULTS`，GET 下发生效值、
PUT 即时写回并 `reload_config`），做 A/B 不必再手改配置文件；它的**作用域只有 `task`/`auto`**
——chat 取向在读本键之前就已返回 None，因此不给 chat 另设二级开关，手机侧也没有按取向的
门控旋钮。**改法在上游内核，
手机侧零改动**（收口口径不动，不留双路径）：`instructions.py` 的 `_plain_finish_judge`
对 `mode=chat` 直接返回 None（取向门），`core.py` 的 mode 参数清单补上「收尾意图门」，
`sdk_bridge.py` 注明对话取向自行退出；`task`/`auto` 语义不变——回归实测同一句走 task 取向
尾巴仍是 **17.9s**，门还装着。上游新增 `tests/test_mode_lane.py::
test_chat_mode_skips_finish_intent_gate`（chat 不装门、task/auto 装），`test_mode_lane.py`
+ `test_tools_gate.py` 30 passed。**顺带修一条被误报掩盖的内核缺陷**：`_dispatch_chat`
里 `result = None` 原先写在 `run_task` 前一行，而 finally 要读它判 paused——run_task 之前
任何一步抛（19:42:30 那轮 `model=默认` 在模型槽解析处抛）都会让 finally 撞
`UnboundLocalError`，线程死在 `_finish_task` 之后：本轮既没有终态记录推到前端、running 又
已经置假，手机表现为「遇到点麻烦」而不是那条真实异常。现在初始化搬到函数头，try 之前的
异常走正常失败面。装机复测（同一台 MuMu，UI 真发一轮）：`send 20:14:09.678 mode=chat →
最后一次口播增量 15.554 → 终态 15.555 → terminal 15.574 → stream closed 15.966`，截图里
输入行已回到 ↑、头部「待命中」；46 个 JVM 单测全绿。**顺带一条内核观测（未改）**：全局主
订阅（`/api/runtime/stream` 不带 `task_id`）靠 `_running_task_id()` 跟随本轮 task 的专属
outbox，若终态推送正好落在「最后一次 running=True 的 drain」与「drain 切回 `_global`」之
间，那条事件就留在 task outbox 里没人取——手机不受影响（它显式带 `task_id`），本轮所有
尾巴数字取自内核 `run.json` / 轨迹，也不依赖这条流。

## 8. 耦合纪律（2026-10-30 讨论，与上游拆分时确立）

- **接口耦合是唯一合法耦合**：本项目对上游的全部依赖 = 5 端点契约；Chaquopy
  内核不 import 上游任何代码（跨语言天然隔离）。
- **Tier 1 保持"哑"**：persona 卡片 + 会话 jsonl 落盘；无工具、无记忆检索、无写链
  判断、无 mode；复杂需求明确说"等电脑开机"。漂移代价 = Tier 1 语义面 × 离线
  时长，把前者压小。
- **persona 卡片是 PC 侧导出的静态数据**（构建产物），不在本项目实现人格逻辑；
  上游改人格 → 重新导出数据即可，两仓之间没有逻辑要同步。
- **契约测试替代行为对拍**：以对拍 harness 发现/消灭漂移的思路不再采用；改为
  以上游契约 fixture 做断言（漂移检测点在契约，不在行为）。
- **止损红线**：Tier 1 不接任何"变聪明"的需求——想让手机离线调工具/查记忆，
  正确动作永远是"等 Tier 2"。
- **技术红利传导（远程表面的红利来源）**：远程表面只消费 `text + audio` 契约——
  PC 侧语音/人格升级（换引擎、情感、音色）自动传导至手机，手机零改动、零独立
  适配（播的是字节流，不需要懂声音怎么变的）。唯一适配税 = 契约演进（如将来的
  流式语音），一次适配、全表面受益；端上装引擎 = 主动放弃红利（升级即掉队），
  仅 Tier 1 离线场景独立评估。

## 9. 待拍板决策（不阻塞 C1，C2 前定）

1. ASR：系统 `SpeechRecognizer`（免费/快/联网）vs 端上 whisper（离线/准/耗电）—— ~~建议 C1 先用系统 ASR~~ **已作废（2026-11-04）**：识别端点唯一来源 = 设置 · 语音供应商（`/v1/audio/transcriptions`）；系统 ASR 整段删除，没配就在 🎤/免提入口报错（不录音、不发送、不半截轮）；
2. TTS：~~系统 TTS 引擎 vs 端上 piper~~ **已拍板（2026-10-30）**：Tier 2 播报 = **PC CosyVoice2 合成**（`GET /api/audio/tts` 拉 WAV 播放）；**已改写（2026-11-04）**：播报端点同样只认 设置 · 语音供应商（`/v1/audio/speech`，指向 PC 自建 engine_service 也走这一种接法），`/api/audio/tts` 与系统 TTS 回退全部删除——远程 base_url 不再兼作语音端点。**播不播由输入形态决定**（2026-11-04 二次改）：打字轮只上屏（"我在看屏幕"，念出来是打扰）；🎤 对讲轮念、按住 🎤 即打断；**免提 = 语音自由对话**——念回复且念的过程中能插话（旧口径"免提不念，念出去会被自己听回来"是拿半双工的限制当理由，现由通话链路平台 AEC + `EchoGuard` 文本判掉解决，见 C2/C2+）；
3. PC 发现：mDNS vs 静态 IP 配置 vs Tailscale（出门场景，二期）——C1 用静态 IP 配置；
4. 同步触发：上线即同步 vs 空闲批量——建议上线后台空闲批量；
5. persona：手机端与 PC 端是否同一 persona（建议同一份，来源=PC 导出数据）。

## 10. 测试矩阵

- 内网往返延迟（首字 <2s）、打断时延（<500ms）；
- 降级/升级往返：切换过程不丢话、不重复回答；
- 同步：幂等（重放不重复入库）、乱序容错；
- 锁屏存活 30min、飞行模式降级、PC 关机降级；
- 安全：非回环无 token 全 401、loopback 豁免不受影响。

## 11. 风险

- OEM 杀后台：前台服务 + 电池优化豁免 + 引导用户加白名单（hachimi 已有前台服务）；
- Android 音频焦点抢占（来电/其他 App 放歌）：让路并挂起，不做复杂策略；
- Chaquopy 内核能力上限：Tier 1 只承诺"陪聊 + 简单指令"，复杂活明确说"等电脑开机"；
- 局域网安全：token + 内网 + 无端口映射；出内网（Tailscale）是二期且沿用同一 token 机制。
