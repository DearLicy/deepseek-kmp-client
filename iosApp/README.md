# DeepSeek iOS 原生客户端

SwiftUI 界面已从静态预览改为调用 `DeepSeekShared.NativeClient`。会话、消息流、思考折叠、主题、字体和 Key 校验都走同一个 KMP 控制器；Swift 只投影显示并提交操作。平台层用 Keychain 保存 Key，用 Application Support 原子写入本地会话，目录排除备份。

**Linux 仅验证了共享代码及 JSON 投影，未编译 Swift / Kotlin Native，未执行 Xcode 或 iPhone 验收。以下工程配置是待 macOS 验证的构建入口。**

在 macOS 安装 JDK 17、Xcode 和 XcodeGen 后，从项目根目录执行：

```bash
./scripts/prepare_ios.sh
xcodebuild -project .tmp/xcode/DeepSeek.xcodeproj \
  -scheme DeepSeek -sdk iphonesimulator -configuration Debug \
  -derivedDataPath .tmp/xcode-derived CODE_SIGNING_ALLOWED=NO build
```

- iOS 17+；Swift 5 语言模式，SwiftUI/UIKit 原生 UI。
- XcodeGen 配置：`iosApp/project.yml`。生成的工程在 `.tmp/xcode/`。
- Framework 构建阶段调用 `:shared:embedAndSignAppleFrameworkForXcode`；搜索目录随项目 `buildDirectory` 落在 `.tmp/build/shared/`。
- 所有新增下载、生成工程、Gradle/Kotlin Native 缓存、DerivedData 均使用项目 `.tmp/`。不要把 root 的 Linux `local.properties` 原样带到 Mac；根据当地 Android SDK 配置或暂时移走该文件。
- 如 Xcode 不继承 shell 的 JDK 环境，在 Scheme Build 环境中设置本机 `JAVA_HOME`；真机需选择自己的开发团队。
- `NativeStorage` 返回成功/失败，Swift 错误和 Key 不进入 UI 日志。SwiftUI 的状态 DTO 与共享 `NativeProjection` 的自动化测试共同约束字段。

当前范围：Key 配置、文本/思考流、搜索状态和来源列表、本地会话列表/搜索/改名/置顶/删除、基础设置。附件、录音、朗读、完整 Markdown/内联引用、精确动效与逐像素还原尚未完成。没有创建假的登录、上传或语音成功状态。
