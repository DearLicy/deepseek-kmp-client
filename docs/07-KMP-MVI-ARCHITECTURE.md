# KMP + MVI 架构

## 1. 总原则

本项目不采用 Compose Multiplatform 共享 UI。共享层只承载可测试、与平台无关的逻辑；Android 和 iOS 各自实现原生 UI。

```text
┌────────────────────────────────────────────────────┐
│                    shared (KMP)                     │
│ protocol / domain / reducer / effects / persistence │
│ state projection / voice state / settings state     │
└───────────────┬──────────────────────┬──────────────┘
                │                      │
┌───────────────▼──────────┐  ┌────────▼──────────────┐
│ Android Jetpack Compose  │  │ iOS SwiftUI / UIKit    │
│ native widgets           │  │ native widgets         │
│ OkHttp/Ktor + AudioRecord│  │ URLSession + AVAudio   │
│ Keystore                 │  │ Keychain               │
└──────────────────────────┘  └────────────────────────┘
```

## 2. 推荐目录

```text
DeepSeek/
├── docs/
├── assets/
├── shared/
│   └── src/
│       ├── commonMain/kotlin/com/deepseek/client/
│       │   ├── protocol/       # API 请求/SSE/内容块
│       │   ├── domain/         # Session/Message/Block/Source
│       │   ├── mvi/            # Intent/Action/Reducer/Effect
│       │   ├── conversation/   # 事件合并/分支/历史投影
│       │   ├── settings/       # 设置状态与音色配置
│       │   ├── search/         # 来源登记与引用编号
│       │   ├── voice/          # ASR/TTS 状态机
│       │   └── platform/       # expect 接口
│       ├── androidMain/        # Android expect actual
│       └── iosMain/            # iOS expect actual
├── androidApp/
│   └── src/main/kotlin/com/deepseek/client/android/
│       ├── ui/                 # Compose screens/components/theme
│       ├── media/              # recorder/player
│       ├── security/           # Keystore
│       └── storage/            # local persistence
├── iosApp/
│   ├── DeepSeek/
│   │   ├── App/
│   │   ├── Features/
│   │   │   ├── Chat/
│   │   │   ├── Sessions/
│   │   │   ├── Settings/
│   │   │   └── Voice/
│   │   ├── DesignSystem/
│   │   ├── Media/
│   │   ├── Security/
│   │   └── Resources/
│   └── DeepSeek.xcodeproj/
├── scripts/
└── .tmp/
```

## 3. MVI 约定

### 3.1 单向数据流

```text
UI event
  -> Intent
  -> Reducer(state, intent) -> (state, [Effect])
  -> Effect executes I/O
  -> Action(result)
  -> Reducer(state, action) -> state
  -> UI recomposes/renders state
```

硬规则：

- UI 不直接改共享状态。
- Reducer 是唯一状态变更入口。
- 网络、磁盘、录音、播放、权限都通过 Effect 执行。
- Effect 返回 Action，不直接回调 UI。
- 任何异步结果都要带稳定的 request/turn/block ID，避免慢响应覆盖新状态。

### 3.2 状态分层

```text
AppState
├── bootstrap
├── settings
├── sessions
├── chat
├── composer
├── voice
├── search
└── transientUi (toast/dialog/sheet)
```

`AppState` 持久化字段和纯 UI 字段要明确区分。弹窗是否打开、输入框焦点、Sheet 动画状态不应进入导出文件。

### 3.3 Intent 示例

```kotlin
sealed interface ChatIntent {
    data class InputChanged(val text: String) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data class ToggleThinking(val enabled: Boolean) : ChatIntent
    data class ToggleSearch(val enabled: Boolean) : ChatIntent
    data class OpenSession(val id: SessionId) : ChatIntent
    data object NewSession : ChatIntent
    data class RenameSession(val id: SessionId, val title: String) : ChatIntent
    data class DeleteSession(val id: SessionId) : ChatIntent
    data class ToggleThinkingBlock(val messageId: MessageId, val blockId: BlockId) : ChatIntent
    data class PlayVoice(val messageId: MessageId) : ChatIntent
    data object StopVoice : ChatIntent
}
```

### 3.4 Action 示例

```kotlin
sealed interface ChatAction {
    data class StreamStarted(val turnId: TurnId) : ChatAction
    data class BlockStarted(val turnId: TurnId, val index: Int, val block: ContentBlock) : ChatAction
    data class TextDelta(val turnId: TurnId, val index: Int, val text: String) : ChatAction
    data class ThinkingDelta(val turnId: TurnId, val index: Int, val text: String) : ChatAction
    data class CitationsAdded(val turnId: TurnId, val index: Int, val citations: List<TextCitation>) : ChatAction
    data class StreamCompleted(val turnId: TurnId, val title: String?) : ChatAction
    data class StreamFailed(val turnId: TurnId, val error: UiError) : ChatAction
}
```

### 3.5 Effect 示例

```kotlin
sealed interface ChatEffect {
    data class SendMessage(val turnId: TurnId, val request: CreateMessageRequest) : ChatEffect
    data class CancelStream(val turnId: TurnId) : ChatEffect
    data class UploadAttachment(val draftId: DraftId, val file: PlatformFile) : ChatEffect
    data class Transcribe(val recordingId: RecordingId, val file: PlatformFile) : ChatEffect
    data class Speak(val messageId: MessageId, val text: String, val voiceId: VoiceId) : ChatEffect
    data class PersistSession(val sessionId: SessionId) : ChatEffect
}
```

## 4. 领域模型

```text
Session
  id, title, createdAt, updatedAt, pinned, messages, model, mode

Message
  id, role, createdAt, status, blocks, sources, usage

ContentBlock
  TextBlock(index, text, citations)
  ThinkingBlock(index, text, signature, startedAt, endedAt)
  ServerToolUseBlock(index, id, name, input, kind)
  WebSearchToolResultBlock(index, toolUseId, kind, results)
  WebFetchToolResultBlock(index, toolUseId, kind, result, signature)

Source
  url, title, siteName, publishedAt, snippet, siteIcon, index
```

UI 从 `Message.blocks` 投影出 `ReplyView`：

```text
ReplyView
  steps: [thinking/search/read]
  answerMarkdown: String?
  sources: List<Source>
  searchResults: List<Source>
```

投影必须是纯函数，便于 Android/iOS 使用同一套测试向量。

## 5. 流式事件合并

每个请求生成稳定 `turnId`。Reducer 收到事件时按以下键合并：

```text
turnId + blockIndex
```

规则：

- `message_start` 创建 turn，不创建可见正文。
- `content_block_start` 以 index 建立 block；重复 index 只能幂等更新，不能重复追加。
- `thinking_delta` 只追加到 thinking block。
- `text_delta` 只追加到 text block。
- `citations_delta` 按 URL 去重并保存引用位置。
- `message_stop` 后任何迟到 delta 丢弃，并记录本地诊断。
- 网络中断时保存已到达内容，状态为 `Interrupted`。

## 6. 平台边界

使用 `expect/actual` 或依赖注入接口抽象：

```kotlin
interface SecureKeyStore {
    suspend fun readApiKey(): String?
    suspend fun saveApiKey(value: String)
    suspend fun clearApiKey()
}

interface SessionStore {
    suspend fun loadSessions(): List<Session>
    suspend fun upsertSession(session: Session)
    suspend fun deleteSession(id: SessionId)
    suspend fun exportHistory(): ExportArchive
}

interface AudioRecorder {
    suspend fun start(): RecordingHandle
    suspend fun stop(): PlatformFile
    suspend fun cancel()
}

interface AudioPlayer {
    suspend fun playPcm(stream: AudioStream)
    fun pause()
    fun stop()
}
```

Android/iOS 各自实现。共享层不依赖 Android `Context`、`MediaRecorder`、`AVAudioRecorder` 或平台 UI 类型。

## 7. API Key 安全

- Android：Android Keystore 管理密钥，应用私有目录保存经认证加密的 API Key；具体实现后单独验证。
- iOS：Keychain，`kSecClassGenericPassword`，访问策略按设备解锁。
- API Key 不进入 `AppState` 的可导出字段；共享状态只保存 `apiKeyConfigured: Boolean` 和显示末 4 位的脱敏信息。
- 请求构造在平台安全存储读取后直接注入 Header；不得把 Key 复制到日志。
- 清除 Key 时同时取消进行中的请求和 TTS/ASR。

## 8. 本地持久化

首版建议 SQLDelight + 文件系统：

- `sessions`、`messages`、`blocks`、`sources` 表。
- 附件原文件按 session/message 目录保存；只保留用户明确发送的文件。
- 草稿按 session 存储，应用退出可恢复。
- 数据库迁移要版本化，禁止启动时静默删库。
- 导出历史使用自带格式；不包含 API Key、录音缓存、认证 Header。

如果首版为了降低复杂度使用 JSON 文件，也必须通过 `SessionStore` 接口隔离，后续可无损迁移到 SQLDelight。

## 9. 并发与生命周期

- Reducer 在主线程/单一 actor 上下文中串行执行。
- 网络流在后台调度，Action 回主上下文。
- 每个页面有 `screenId`，离开页面不自动销毁正在发送的会话；由会话级 Effect 管理。
- 用户切换会话时，旧会话的流仍可继续但不可覆盖当前 UI；返回旧会话时状态恢复。
- 停止生成要在 API 请求层、流解析层、状态层三处都有明确状态。

## 10. iOS 桥接

- 共享模块产出 `DeepSeekShared.framework`。
- SwiftUI 使用 ObservableObject/`@Observable` 包装共享 store，不把 Kotlin `StateFlow` 直接泄漏到 SwiftUI 视图层级。
- 回调转换为 `AsyncSequence` 或 publisher，并保留取消语义。
- 共享 reducer 的错误类型转换为 Swift `Error` 的可展示结构。
- 不要在 SwiftUI body 中执行网络或读取 Keychain。

## 11. Android 原生 UI

- 单 Activity + Compose Navigation（或自建状态路由）。
- 每个 Screen 只消费 `State`、发送 `Intent`。
- 主题、字体缩放、系统栏、IME Insets 从 Compose 层读取。
- 使用 `Lifecycle` 管理录音和播放，不在 Composable 重建时重复启动。
- 列表使用 `LazyColumn` + 稳定 key；流式更新只重绘变更 block。

## 12. 不可接受的实现

- 让 UI 直接调用 API。
- 让 UI 直接改 `mutableStateOf` 中的共享 Model。
- 用一个全局可变 `ChatMessage` 对象跨平台原地修改。
- 把思考内容拼接到正文后保存。
- 用同一个序号同时表示网络事件和 UI 列表 index。
- 在 iOS 端复制一套不同的 SSE/统计逻辑。

