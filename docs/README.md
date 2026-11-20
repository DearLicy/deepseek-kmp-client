# DeepSeek 客户端研究与开发入口

目标：**KMP 共享业务逻辑 + Android Jetpack Compose + iOS SwiftUI/UIKit 原生 UI，复刻官方客户端外观与交互，使用 API Key。** 不登录官方账号，不连接 DSH，不下载 dsh-mobile。

本轮先交付逆向资料、素材和实现规格。现有客户端代码是初始骨架，完整功能还未实现；不要把“构建通过”理解成已经完成 1:1 App。

| 文档 | 内容 |
| --- | --- |
| [00 产品范围](00-PRODUCT-SCOPE.md) | 用户已确认的目标、API Key 模式、成品边界 |
| [01 逆向证据](01-REVERSE-ENGINEERING-EVIDENCE.md) | APK 身份、组件、资源、证据等级与未知项 |
| [02 设计系统](02-DESIGN-SYSTEM.md) | 配色、字体、圆角、平台组件与动效候选 |
| [03 页面与交互](03-SCREENS-AND-INTERACTIONS.md) | 对话、侧栏、附件、语音、设置、音色、字体、搜索 |
| [04 动效与素材](04-MOTION-AND-ASSETS.md) | Lottie、Shader、图标、音效、移植方式 |
| [05 API 合同](05-CHAT-DEMO-API.md) | Messages/Files/SSE、ASR/TTS、引用、续写 |
| [06 搜索与音色差异](06-RESEARCH-AND-TTS.md) | 工具时间线、来源编号、四音色问题 |
| [07 KMP/MVI 架构](07-KMP-MVI-ARCHITECTURE.md) | 共享层与原生 UI 边界、Intent/Action/Effect |
| [08 实施路线](08-IMPLEMENTATION-ROADMAP.md) | 分阶段开发与验收 |
| [09 测试策略](09-TEST-STRATEGY.md) | 共享逻辑、假接口、平台与视觉验收 |
| [10 截图标注](10-SCREENSHOT-REFERENCE.md) | 文件与状态对应、像素到逻辑尺寸、采样色 |
| [11 分享建议核对](11-ADVICE-REVIEW.md) | 用户提供分享页建议与当前目标的差异 |
| [12 当前状态](12-STATUS-AND-NEXT-STEPS.md) | 实际已验证、未实现、下一阶段与命令 |
| [13 chat-demo v0.1.3 附件增量](13-CHAT-DEMO-V013-ATTACHMENTS.md) | 大图规范化、历史补传与跨端实现合同 |

正式素材在 `assets/`；`assets/SOURCE.md` 与 `assets/MANIFEST.json` 可追溯到 APK 字节。固定版参考源码与资源映射在 `docs/evidence/`。下载、反编译中间产物、构建输出、测试日志与缓存均放在项目 `.tmp/`。

音色结论请先读 `06`：截图四个卡片与 `stella/tide` 不是已验证的一一对应关系。还未用真实 Key 请求端点，不对套餐或账号权限作保证。
