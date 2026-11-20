# 测试策略

## 1. 测试层级

```text
纯单元测试
  -> 协议/Reducer/投影
集成测试
  -> SSE 假服务、文件上传、ASR/TTS
平台 UI 测试
  -> Android Compose / iOS XCUITest
手工视觉验收
  -> 截图对照、动画、录音、主题
安全与隐私
  -> Key 扫描、日志、导出
```

## 2. 共享层单元测试

### 2.1 SSE parser

必须覆盖：

- LF、CRLF、CR 换行。
- 多行 `data`。
- `event` 缺省为 `message`。
- 注释行和 `ping`。
- 跨 chunk 的半行、半 UTF-8 字符。
- 空行派发。
- 没有 `message_stop` 的尾流。
- 超长事件/畸形 JSON 的拒绝与错误映射。

### 2.2 Reducer

使用表驱动测试向量：

```text
初始 state + event[] -> 期望 state
```

覆盖：

- message_start -> content_block_start -> deltas -> message_stop。
- thinking 与 text 交错。
- 重复 block index。
- 迟到 delta 被丢弃。
- citations 去重、编号稳定。
- 停止、失败、中断。
- 重新生成不覆盖旧分支。
- 切换会话不串流。

### 2.3 来源与引用

- 同一 URL 多次出现只编号一次。
- 先 open 后 search 时元数据补齐。
- 非法 URL 不展示。
- 标题/站点名/日期缺失时回退。
- `encrypted_content` 永远不作为正文。

### 2.4 设置与 API Key

- 初次启动无 Key。
- 校验成功/失败。
- Key 清除后不可发送。
- Key 不进入 `AppState.export`。
- 日志/错误对象中不包含 Key。

### 2.5 语音状态机

- 文本态 -> 按住说话 -> 录音 -> 松手 -> 识别。
- 上滑取消不调用 ASR。
- 权限拒绝时回到文本态。
- 120 秒/8 MiB 限制。
- TTS 播放/暂停/停止/切换消息。

## 3. 假 API Server

建立本地测试服务器（只绑定 loopback），可配置返回：

- `/models` 200/401/500。
- `/anthropic/v1/messages` 固定 SSE 事件序列。
- 延迟 chunk，验证增量 UI。
- 在 `message_start` 后断开，验证中断。
- `thinking_delta`、`text_delta`、`citations_delta` 组合。
- `server_tool_use` + `web_search_tool_result` + `web_fetch_tool_result`。
- 含 `tool_use` 的未知块。
- `/v1/tools/asr` 成功、空文本、超限。
- `/v1/tools/tts` 返回固定 PCM。

假 Server 不接入真实 API，不需要真实 Key，适合 CI。

## 4. Android 验证

### 构建

```bash
export JAVA_HOME=<JDK17_HOME>
./gradlew :androidApp:assembleDebug
```

### UI

- 欢迎页：空输入、输入中、键盘态。
- 侧栏：空/有会话、打开/关闭、会话菜单。
- 对话：思考展开/收起、正文流式、来源列表。
- 输入：文本/语音/录音/附件面板。
- 设置：Sheet、数据管理、音色、字体大小、删除确认。

### 设备

至少覆盖 API 24 模拟器、API 33+ 真机或模拟器；验证系统返回键、IME Insets、暗色模式、录音权限、文件选择器。

## 5. iOS 验证（macOS）

> 当前 Linux 运行环境没有 Xcode，以下为交付到 macOS 后的验收要求，不代表当前已执行。

```bash
# 先创建完整 Xcode 工程并确认实际 scheme / 模拟器名称，下列仅是未来验收命令示意
xcodebuild -scheme DeepSeek -destination 'platform=iOS Simulator,name=iPhone 15' build
xcodebuild -scheme DeepSeek -destination 'platform=iOS Simulator,name=iPhone 15' test
```

检查：

- `NavigationStack` 返回路径。
- `.sheet` 高度、拖拽、背景遮罩。
- 键盘安全区与输入框跟随。
- Keychain 读写与清除。
- AVAudioRecorder 权限、录音切换、后台/来电中断。
- AVAudioEngine 播放 PCM。
- Metal shader 在真机/模拟器的降级。
- VoiceOver 标签与动态字体。

## 6. 视觉验收矩阵

| 页面/状态 | 浅色 | 深色 | 窄屏 | 大屏/横屏 |
| --- | --- | --- | --- | --- |
| 欢迎页 | ✓ | ✓ | ✓ | ✓ |
| 对话正文 | ✓ | ✓ | ✓ | ✓ |
| 思考展开 | ✓ | ✓ | ✓ | ✓ |
| 搜索来源 | ✓ | ✓ | ✓ | ✓ |
| 侧栏 | ✓ | ✓ | ✓ | ✓ |
| 会话菜单 | ✓ | ✓ | ✓ | ✓ |
| 删除确认 | ✓ | ✓ | ✓ | ✓ |
| 输入文本态 | ✓ | ✓ | ✓ | ✓ |
| 按住说话 | ✓ | ✓ | ✓ | ✓ |
| 录音全屏 | ✓ | ✓ | ✓ | ✓ |
| 附件面板 | ✓ | ✓ | ✓ | ✓ |
| 设置 Sheet | ✓ | ✓ | ✓ | ✓ |
| 音色 Sheet | ✓ | ✓ | ✓ | ✓ |
| 字体大小 | ✓ | ✓ | ✓ | ✓ |
| 全文搜索 | ✓ | ✓ | ✓ | ✓ |

`✓` 表示未来需要截图/录屏留档；本轮只做提取图标接触表 QA，尚未执行 App 页面视觉验收。

## 7. 性能测试

- 200 条消息、每轮 10 个搜索来源的长会话滚动。
- 单轮 30 分钟流式输出（用假流模拟）。
- 附件上传 50 个文件的上限。
- 100 个会话的侧栏分页/搜索。
- Shader 在低端设备持续 30 秒的帧率和电量。
- 录音 120 秒的临时文件占用。

## 8. 安全测试

- `rg -n '(sk-|api[_-]?key|Bearer )'` 扫描源码和资源；排除测试假字面量并人工复核。
- 检查崩溃日志、Toast、错误详情、导出文件。
- 确认 Key 只在安全存储读取点出现。
- 确认 HTTP 请求不允许明文或非预期 Origin。
- 确认文件上传路径没有目录穿越。
- 确认 TTS/ASR 临时文件在取消/失败后清理。

## 9. 验收证据格式

每个阶段交付都要分开写：

```text
已完成：
- 实际改动
- 实际执行命令与结果

未验证：
- 未运行的平台
- 未连接的真实 API
- 未完成的视觉/性能检查

风险：
- 可能导致失败的外部条件
- 需要用户/设备/Key 才能继续的事项
```

不得把“代码已写”“预计可编译”“截图看起来像”表述成“已完成验证”。


## 10. 本轮执行记录

实际执行、计数和路径以 `12-STATUS-AND-NEXT-STEPS.md` 为准。共享测试只是 pure unit tests + fake transport，不是完整 HTTP 假服务或真实 API 集成测试。代码没有发送实际 Key 请求，也没有运行用户提供的 APK。
