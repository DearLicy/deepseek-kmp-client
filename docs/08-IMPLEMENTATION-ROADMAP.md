# 实施路线图

## 0. 当前状态

截至 2026-11-19：

- APK 已静态解包，JADX 已输出类/方法与反编译结果；部分方法保留寄存器式回退表示，不代表恢复了可编译的官方源码。
- chat-demo 已克隆并确认 API Key 数据面。
- 页面、设计、资源映射、Lottie/Shader、私有接口证据已整理。
- 逆向、页面、素材、接口和架构文档已归档。
- KMP 与 Android 工程骨架已建立，SwiftUI 只有源文件骨架，尚无完整 Xcode 工程；当前环境没有 Xcode。各项实际验证见 `12-STATUS-AND-NEXT-STEPS.md`。

## 1. Phase P0：证据与范围冻结

### 任务

- 固定 APK 哈希与版本。
- 固定 chat-demo commit。
- 确认不接 DSH、不接 dsh-mobile、不接官方私有登录。
- 建立能力矩阵与未知项。

### 验收

- `docs/00`、`01`、`05`、`06` 与实际证据一致。
- 不存在“四音色已支持”的错误声明。

## 2. Phase P1：设计与素材归档

### 任务

- 复制 Lottie、Shader、音频、Logo、图标。
- 生成 `assets/SOURCE.md` 与 SHA-256 清单。
- 把二进制 Vector XML 转成 Android Vector 和 iOS SVG/PDF。
- 对截图关键页面做尺寸与颜色标注。

### 验收

- 每个正式素材都能追回 APK 资源名和原文件。
- 不把 `.tmp` 纳入 Git。
- Android/iOS 资源命名一致，尺寸和视觉对比可复现。

## 3. Phase P2：KMP 共享核心

### 任务

- 初始化 Gradle/KMP 工程。
- 定义协议模型、SSE parser、Reducer、Effect、来源投影。
- 实现 API client 抽象（不绑定平台 HTTP 实现）。
- 实现设置、会话、草稿和 MVI store。
- 写 reducer/SSE/URL 去重/audio state 单测。

### 验收

- 在 Linux 上执行 `./gradlew :shared:jvmTest :shared:testDebugUnitTest`；Apple 测试留到 macOS 执行。
- 假 SSE 流输入能生成正确 UI state。
- 不带 API Key 的日志扫描通过。

## 4. Phase P3：Android 原生 UI

### 任务

- Compose 主题、字体缩放、浅/深色。
- 欢迎页、对话页、侧栏、消息列表。
- 输入区、附件、语音、录音遮罩。
- 设置、数据管理、音色、字体大小、全文搜索。
- 本地持久化与安全存储。
- 当前会话流式输出与停止/重试。

### 验收

- `assembleDebug` 通过。
- 在 API 24/33+ 至少各做一次手工冒烟。
- 无权限、断网、401、流中断都有可见状态。
- 关键页面与截图对照通过。

## 5. Phase P4：iOS 原生 UI

### 任务

- SwiftUI 页面壳、Navigation、Sheet、键盘和安全区。
- 共享 store 的 Swift 包装。
- URLSession SSE、Keychain、AVAudioRecorder/AVAudioEngine。
- Metal fluid blob / voice mask；不支持时降级。
- iOS 资源与系统分享/朗读。

### 验收

- 在 macOS/Xcode 上 `xcodebuild` 通过。
- 真机/模拟器验证录音权限、键盘、Sheet、深浅色。
- 同一组 reducer 测试向量与 Android 结果一致。

## 6. Phase P5：端到端与发布准备

### 任务

- 假 API Server 集成测试。
- 真实 API Key 手工冒烟（只在运行时使用）。
- 历史导出/导入。
- 性能：长会话、长思考、长来源列表、低端设备。
- 无障碍、隐私、崩溃恢复。
- 版本号、更新说明、非官方声明、许可证。

### 验收

- 发布构建可安装。
- 关键崩溃路径有恢复策略。
- 不在 APK/IPA 中包含测试 Key 或开发环境私有信息。
- 不把未验证的 iOS/真实接口结果写成已验证。

## 7. 首个可运行切片

为了尽快形成可验证闭环，首个切片只做：

1. API Key 配置与 `/models` 校验。
2. 新建会话、输入消息、SSE 流式正文。
3. 深度思考开关与折叠。
4. 本地保存会话。

搜索、附件、ASR/TTS 在第二个切片接入。这样可以在没有完整 UI 的情况下先验证协议、状态机和 reducer。

## 8. 关键风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 官方私有接口不可用 | 四音色/账号能力无法复刻 | 只做 chat-demo 公开能力，UI 数据驱动 |
| iOS 环境缺失 | 无法本地构建 iOS | 先写共享测试与 Swift 结构，macOS 上再编译 |
| Shader 移植差异 | 音色预览视觉不一致 | 做参考截图 + Metal 单测/像素对比，保留低版本降级 |
| 长会话内存 | 流式 Markdown/来源列表膨胀 | block 分片、可见性更新、持久化分页 |
| 录音权限/格式 | ASR 失败 | 明确权限态、WAV 校验、8 MiB/120s 限制 |
| 素材版权 | 公开发布风险 | 保留非官方声明，发布前确认授权与替换方案 |

