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
| **C1** 遥控模式最小闭环 | hachimi 加 ASR/TTS + 写死 PC endpoint | ✅ 已实现（分支 `feature/omniagent-remote-client`：`OmniAgentRemote.kt` 客户端 + `ChatPage` 分流 + 🎤 对讲 + TTS 播报，SSE 解析 JVM 单测）；**待真机内网验收**（首字 <2s） |
| **C2** 打断 | TTS 播报中说话 → `TTS.stop()` + force | ✅ 已实现（🎤 按下即打断播报；远程轮运行中收音 → `chat force=true` barge-in）；**待真机验收**（<500ms + PC 侧 [被打断]） |
| **C3** 自动切换 | 探活降级/升级，轮次边界切换 | ✅ 已实现（每轮 `/health` 1s 探活，不可途回 Tier 1 内核，过程不丢话）；**待真机验收**（拔网线不哑、插回自动升级） |
| **C4** 离线会话同步 | §4 回放 + 幂等（依赖上游 memory/sync 端点落地） | 🚧 **blocked**：上游 `POST /api/runtime/memory/sync` 未实现（OmniAgent 侧唯一内核待办） |
| **C5** 锁屏可用 | 助手注册（VoiceInteractionService）或耳机键/通知栏按钮 | ⏳ 后置（C1-C3 真机验收后） |

**C1-C3 落地清单（分支 `feature/omniagent-remote-client`，2026-10-30）**：
`OmniAgentRemote.kt`（HttpURLConnection 零新依赖：health/chat/stop + SSE 行解析）·
`RemoteConfigStore.kt`（Keystore 加密 base_url/token/自动切换/播报开关）·
`VoiceIo.kt`（系统 SpeechRecognizer + TextToSpeech 封装）·
`ChatPage`（☁ 配置对话框 + 路径分流 + 远程事件映射复用 ChatMapper + 🎤 对讲）·
`OmniAgentRemoteTest`（SSE 解析 JVM 单测）·
manifest（RECORD_AUDIO + 识别服务可见性 + cleartext C1 简化）·
`spec/redlines.md`（R1 豁免修订 + R2 权限清单附录，redline-change）。

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

## 9. 待拍板决策（不阻塞 C1，C2 前定）

1. ASR：系统 `SpeechRecognizer`（免费/快/联网）vs 端上 whisper（离线/准/耗电）——建议 C1 先用系统 ASR；
2. TTS：系统 TTS 引擎 vs 端上 piper（音色可控）；
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
