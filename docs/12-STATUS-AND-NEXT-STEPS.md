# 当前状态与下一步

更新日期：2026-11-21。本文只记录当前工作区可复核的事实；设计规格中的“必须实现”不等于已经完成。

## 当前可运行范围

- KMP 共享层包含 MVI Store、Reducer、SSE 协议、会话快照、搜索来源投影、Files API 附件状态与上传编排。
- Android 原生 Compose 已接安全 API Key、会话持久化、系统文件选择器、本地附件原件、图片长边规范化、发送前上传和历史附件补传。
- Web 已接同一 KMP 控制器，使用 IndexedDB 保存附件原件、Canvas 生成上传副本，并提供附件选择、移除和发送前状态。
- iOS SwiftUI 已接文件选择器、Application Support 本地原件、ImageIO 图片缩放和同一 `NativeClient` 桥；工程由 XcodeGen 生成。
- 思考折叠、联网来源、会话侧栏、设置、字体、主题和 API Key 配置已有实现。音色、ASR、录音与完整视觉动效仍未完成。

## 已实际验证

2026-11-21 在本机 Linux 执行并通过：

```bash
JAVA_HOME=<JDK17_HOME> ANDROID_HOME=<ANDROID_SDK> \
  ./gradlew :shared:jvmTest :shared:testDebugUnitTest \
  :androidApp:lintDebug :androidApp:assembleDebug \
  :shared:jsBrowserProductionLibraryDistribution --no-daemon --console=plain

JAVA_HOME=<JDK17_HOME> ANDROID_HOME=<ANDROID_SDK> \
  npm --prefix web run check
```

| 检查 | 结果 |
| --- | --- |
| 共享层 JVM 单测 | 通过；覆盖附件协议、快照、MVI 和 SSE |
| 附件网络切片 | 通过；MockWebServer 覆盖 multipart 字段顺序、图片补传、原件保留、失效 `file_id` 重试 |
| Android 单测 / lint / Debug APK | 通过 |
| Web 单测 / Vite 生产构建 | 通过 |
| Web 运行时 | Chromium 实际加载通过；文件选择后出现附件卡片，移除后状态归零，无页面异常 |
| iOS | 已准备 XcodeGen 工程与 GitHub Actions macOS 任务；尚未在本工作区实际跑过 macOS/Xcode，不能声称编译通过 |

产物路径：

- Android Debug APK：`.tmp/build/androidApp/outputs/apk/debug/androidApp-debug.apk`
- Web 构建：`.tmp/web-dist/`
- Web QA 截图：`.tmp/web-qa/desktop2.png`、`mobile2.png`
- JVM 测试报告：`.tmp/build/shared/reports/tests/jvmTest/`

## chat-demo v0.1.3

官方 Release 的唯一功能性说明是“修复超大图片导致聊天请求失败”：长边大于 4096 像素时先缩小上传副本，历史旧图再次引用时按需补传。增量源码审计与实现合同见 [13-CHAT-DEMO-V013-ATTACHMENTS.md](13-CHAT-DEMO-V013-ATTACHMENTS.md)。

## GitHub Actions

`.github/workflows/ci.yml` 分为两个任务：

1. Ubuntu：共享层测试、Android lint/APK、Web 测试/生产构建，并上传 APK 与 Web 静态产物。
2. macOS：安装 XcodeGen、生成 iOS 工程，并以无签名模拟器构建上传 `.app`。

GitHub Actions 能替代“手边必须有 Mac”的编译步骤，但仍有边界：

- 模拟器构建证明 Swift、KMP framework 与 Xcode 工程可编译；不证明真机运行、签名、TestFlight 或 App Store 审核。
- 真机签名需要 Apple Developer 证书和 provisioning profile，不能把私钥提交到仓库。
- 当前工作区没有 Git remote，也没有提交；只有推送并触发 Actions 后才能取得真实 CI 结论。

## 仍未完成

- iOS CI 的首次真实结果；Swift/Kotlin Native 之间的 ByteArray 与协议实现需要在 macOS runner 上验收。
- Android/iOS 录音、ASR、TTS 四音色、音频播放与权限流程。
- 搜索时间线、引用位置和完整 Markdown 在不同原生文本组件中的一致呈现。
- Lottie/AGSL/Metal 等精确动效；当前工程只保留 APK 提取素材和静态规格。
- 真机视觉、深色模式、动态字体、VoiceOver/TalkBack 和性能验收。
- 使用真实 API Key 对 Files/文档能力、搜索工具和语音能力的联调；现有网络测试使用合成数据。

## 存储与发布边界

- 所有临时下载、反编译结果、缓存、QA 日志和构建输出均在 `.tmp/`。
- API Key 不进入会话快照：Android 使用 Keystore，iOS 使用 Keychain，Web 使用当前标签页 `sessionStorage`。
- 项目权利人已指示仓库统一采用 MIT License；`assets/SOURCE.md` 与 `assets/MANIFEST.json` 继续记录 APK 来源。DeepSeek 名称及商标权利不因代码许可发生转移，项目不声称是官方客户端。
