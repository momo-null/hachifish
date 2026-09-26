# kernel/ · Python 内核子集（M2'）

经 Chaquopy 打包进 APK 的 Python 内核。**语义上游 = OmniAgent `omni_core/local/*`**，本目录是刻意分叉的移植（App 私有目录、自写 loop），与上游的一致性由 harness 对拍测试维持。

## 计划内容

| 模块 | 来源 | 说明 |
|------|------|------|
| `mini_loop.py` | 参照 `omni_core/local/loop/core.py` 语义新写 | ~500 行极简 ReAct loop：httpx + OpenAI 兼容 `tool_calls` + 收尾门控/升级判定子集。**不引入 agents SDK / LangGraph / pydantic**（spike 定案，评审 §2.1） |
| `trajectory.py` / `curator.py` / `skill_library.py` / `world_model.py` / `knowledge_inject.py` / `task_store.py` | 移植 `omni_core/local/*` | 仅标准库依赖；落点改为 App 私有目录（替换 `~/.omniagent/`） |
| `runtime_paths.py` | 改写 | Android Context.getFilesDir() 注入根路径 |
| `brain_client.py` | 移植 `omni_core/brain/llm.py` 客户端子集 | httpx，OpenAI 兼容，BYOK 双通道（规划 + VLM） |

## 硬规则

- 禁止 torch / easyocr / ultralytics / llama-server 链路进入本包（BYOK 形态全部走 API）。
- 知识层为 JSON/Markdown 文件存储（沿用上游 Plan C 模型），不引入 SQLite。
- loop 与桌面侧行为漂移 = bug：语义变更须上游先行。
