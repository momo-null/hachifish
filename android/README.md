# android/ · Kotlin 原生壳（M1'）

Kotlin 原生壳是唯一不可替代的部分：AccessibilityService 与 MediaProjection 只能以 Android 原生组件存在。

## 职责

1. **AccessibilityService 桥（操作通道）**
   - UI 树序列化（对应桌面 `dump_hierarchy`）、`performAction` 控件点击（对应 `tap_by_id`）、`dispatchGesture` 手势、`takeScreenshot`（API 30+）。
   - **原语面必须与桌面版 `environments/emulator/backend.py` 同构**——内核零改动切换后端（评审报告 §2.2）。
2. **MediaProjection 桥（观察通道）**：录屏式一次授权连续取帧；Android 14+ 每会话确认的降级处理。
3. **前台服务 + 电池白名单引导**（对抗国产 ROM 无障碍回收）。
4. **BYOK 设置 UI**：规划/VLM 通道 base_url + key，key 存 Android Keystore。
5. **回环 HTTP API**：仅 `127.0.0.1` 监听，供 PC harness 经 `adb reverse` 驱动实验（产品运行不依赖 PC）。

## 待办

- [ ] 空工程（Chaquopy 15 依赖接入）
- [ ] AccessibilityService 骨架 + 授权引导
- [ ] 原语面接口定义（与 kernel 对拍）
