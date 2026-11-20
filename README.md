# DeepSeek KMP 原生客户端

目标：Kotlin Multiplatform 共享业务逻辑与 MVI 状态，Android 使用 Jetpack Compose，iOS 使用 SwiftUI/UIKit。Web 直接复用 `deepseek-ai/chat-demo` 的 MIT UI，只替换为同一套 KMP 业务逻辑。对话、思考、搜索、附件、录音、侧栏和设置按用户提供的官方客户端外观复刻；数据面参考 chat-demo，用自己的 API Key，不需要登录。

**当前阶段是可构建的跨端原型，不是已通过双端真机验收的 1:1 成品。** 不下载 dsh-mobile，不连接 DSH。

- 研究与开发文档入口：`docs/README.md`
- 真实完成度与验证结果：`docs/12-STATUS-AND-NEXT-STEPS.md`
- 正式资源及出处：`assets/SOURCE.md`、`assets/MANIFEST.json`
- 固定版接口参考与 MIT 许可：`docs/evidence/chat-demo/`
- 素材提取/验证：`scripts/extract_assets.py`、`scripts/verify_assets.py`
- 初始共享层/Android/SwiftUI 源文件：`shared/`、`androidApp/`、`iosApp/`

Web 的页面、侧栏、输入框、附件卡片、消息、思考、搜索、Markdown 与设置界面来自 chat-demo v0.1.2（MIT），不再维护一套自写 Web UI；`web/src/services/` 和 `web/src/runtime/` 只负责把原组件接到 KMP `BrowserClient`。Android/Web 已接安全存储、持久化、SSE 聊天和附件上传；iOS 已接同一共享控制器，但需要在 macOS/Xcode 或 GitHub Actions 上完成首次编译验收。附件支持本地原件保留、4096 像素上传副本、`file_id` 补传和失效 ID 重试。公开示例只枚举 `stella/tide`；截图四音色与它们的映射尚未确认。

Web UI 适配说明见 `docs/14-WEB-CHAT-DEMO-UI-INTEGRATION.md`。

## 当前 Linux 验证命令

```bash
export JAVA_HOME=<JDK17_HOME>
export ANDROID_HOME=<ANDROID_SDK>
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --no-daemon
npm --prefix web run check
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify_assets.py
```

当前主机复用项目目录外已安装 JDK/Android SDK。项目新增的 Gradle 下载、缓存、临时目录、编译产物及验证日志均放在 `.tmp/`；`gradlew` 会设置相应路径。

Debug APK 在 `.tmp/build/androidApp/outputs/apk/debug/`。本地已完成编译、lint、共享层网络切片和 Web 运行检查；Web 还通过本地 mock 验证了“设置 Key → chat-demo 原版输入框发送 → KMP SSE → 思考/搜索/正文回显”。仍不能替代 Android 模拟器/真机交互验收。

iOS 工程由 `iosApp/project.yml` 生成，SwiftUI 通过 `NativeClient` 调用共享业务层，并使用 Keychain、Application Support 和 ImageIO。`.github/workflows/ci.yml` 可在 GitHub 的 macOS runner 上执行无签名模拟器构建；该构建仍不等于真机、签名或商店发布验收。


## 许可

按项目权利人指示，本仓库代码及随仓库分发的派生素材统一采用 MIT License。`docs/evidence/chat-demo/` 保留上游 MIT 声明，`assets/SOURCE.md` 与 `assets/MANIFEST.json` 继续如实记录素材来源。DeepSeek 名称及相关商标归其权利人所有，本项目不是官方客户端。
