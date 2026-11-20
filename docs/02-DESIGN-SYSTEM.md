# 设计系统

## 1. 设计原则

- 内容优先：对话正文是视觉中心，容器、分割线和按钮保持低噪声。
- 柔和层叠：侧栏、设置 Sheet、音色 Sheet、危险确认都使用大圆角和轻微阴影，而不是硬边界。
- 状态可读：思考、搜索、录音、上传、失败各有明确状态，不用同一套颜色表达不同语义。
- 原生触感：iOS 与 Android 使用各自的安全区、键盘、录音权限和触觉反馈；共享的是 Token、状态和业务规则，不强行共享 Widget。
- 可访问性：动态字体、VoiceOver/TalkBack、对比度、减少动效模式都必须进入验收。

> 本文是目标设计规格。字号、圆角、动画曲线为重建起点；除明确列出的 APK 资源色和截图采样色外，不能视为从官方源码还原的精确 Token。静态截图无法证实动画耗时。

## 2. 色彩 Token

以下颜色中，带“APK”的值来自 APK 资源或官方主题证据；其余为截图采样/设计推算值，统一作为设计 Token 使用。

| Token | 浅色 | 深色 | 来源/用途 |
| --- | --- | --- | --- |
| `primary` | `#426EFE` | `#507BF2` | APK `color/primary` 默认/夜间资源色 |
| `primaryAction` | `#4C6DF5` | `#507BF2` | 截图 Logo 区采样/按钮候选色 |
| `primarySoft` | `#EEF3FD` | `#1A2744` | 浅色气泡为截图采样；深色为重建候选 |
| `background` | `#FFFFFF` | `#0F0F0F` | 页面背景 |
| `surface` | `#FFFFFF` | `#171717` | 输入框、卡片 |
| `surfaceMuted` | `#F8F8F8` | `#111111` | 设置页洗底 |
| `textPrimary` | `#0F0F0F` | `#F8F8F8` | 主文字 |
| `textSecondary` | `#7D7F84` | `#9A9DA4` | 说明、时间、占位符 |
| `border` | `#ECEDF0` | `#2A2B2F` | 输入框、卡片描边 |
| `danger` | `#FF3B30` | `#FF453A` | 删除、危险确认 |
| `success` | `#34C759` | `#30D158` | 开关开启 |
| `toastBackground` | `#2F2F31` | `#3A3A3C` | 中央 Toast 胶囊 |

颜色不得直接散落在页面代码中；Android 使用 Material 3 `ColorScheme` 扩展，iOS 使用 `Color` Asset 或统一定义文件。

## 3. 字体与字号

- 不打包独立品牌字体；正文使用平台系统中文字体，优先保证字形和可读性。
- Android：`sans-serif` / `FontFamily.Default`。
- iOS：`SF Pro Text` + 系统中文字体。
- 字体大小设置影响正文、输入框和预览，不影响状态栏与系统 UI。

建议 Token：

| Token | 字号/行高 | 用途 |
| --- | --- | --- |
| `display` | 28 / 36 | 欢迎语、空状态标题 |
| `title` | 22 / 30 | Sheet 标题、页面标题 |
| `body` | 17 / 27 | 对话正文 |
| `bodyCompact` | 15 / 23 | 次要正文、来源摘要 |
| `caption` | 13 / 19 | 时间、说明、来源元信息 |
| `button` | 17 / 22 | 主按钮 |

字体缩放使用平台的动态字体/字体缩放 API；共享状态只保存用户偏好的档位和是否跟随系统。

## 4. 圆角与间距

| Token | 值 | 用途 |
| --- | --- | --- |
| `radiusSmall` | 10 | 小图标容器、来源角标 |
| `radiusMedium` | 16 | 输入框内部块、来源卡片 |
| `radiusLarge` | 24 | 用户气泡、输入框外壳 |
| `radiusSheet` | 28 | 设置/音色/附件 Sheet |
| `radiusPill` | 999 | 胶囊按钮、搜索框 |
| `space1` | 4 | 图标与文字 |
| `space2` | 8 | 小间距 |
| `space3` | 12 | 卡片内边距 |
| `space4` | 16 | 页面水平边距 |
| `space5` | 20 | 卡片分组间距 |
| `space6` | 24 | 页面区块间距 |

截图中的用户气泡约 18–24pt 圆角，输入区外壳约 24–28pt 圆角，设置卡片约 20–24pt 圆角。实现时以 Token 统一，不逐页手写魔法数。

## 5. 阴影与描边

- 浅色页面背景纯白，输入区使用极轻的 `shadow + 1px border` 形成浮起感。
- 侧栏与底部 Sheet 使用柔和投影，不使用强烈黑色边框。
- 深色模式主要依靠背景层级和描边，不照搬浅色阴影。
- 危险确认卡与普通弹窗使用同一圆角体系，但危险按钮为实心红色。

## 6. 图标

- 基础线性图标 20–26pt，思考/工具微型图标 16pt。
- 选中态可使用品牌蓝填充版或 Lottie。
- 图标容器优先使用圆形描边按钮；输入区附件和语音使用圆形图标按钮。
- 所有图标必须有 TalkBack/VoiceOver 文案；纯装饰图标标记为不读。

## 7. 动效

| 场景 | 时长/曲线 | 说明 |
| --- | --- | --- |
| 侧栏推入 | 280–360ms，ease-out | 对话页缩放/轻微平移，侧栏覆盖约 82% |
| Sheet 弹出 | 300–360ms，spring | 从底部滑入，背景变暗 |
| 思考折叠 | 180–240ms | 内容高度与箭头旋转同步 |
| 搜索步骤出现 | 160–220ms，fade/slide | 新步骤从底部淡入 |
| 语音长按 | 180–260ms | 输入框切换为“按住说话”，继续长按进入全屏录音层 |
| 波形 | 与音频能量/时间绑定 | 避免静态假波形 |
| Toast | 180ms 淡入，2s 保持，160ms 淡出 | 不阻断交互 |
| 按钮按压 | 80–120ms | scale 0.96–1.0，触觉反馈可配置 |

## 8. 深浅色与系统偏好

默认跟随系统。手动切换保留在本地设置：

- `system`
- `light`
- `dark`

主题切换不重建会话；正在流式输出的消息不得因主题切换丢失。录音层、Shader 和 Lottie 需要分别提供浅色/深色参数。

## 9. 原生实现映射

| 设计能力 | Android | iOS |
| --- | --- | --- |
| 页面容器 | Compose `Scaffold` + `WindowInsets` | SwiftUI `NavigationStack` / `View` |
| 侧栏 | Compose `Box` + `AnimatedVisibility` | SwiftUI overlay + matched geometry |
| 输入区 | Compose `BasicTextField` + custom action bar | SwiftUI `TextField` / `TextEditor` + custom bar |
| 设置 Sheet | Compose `ModalBottomSheet` 或自定义 Sheet | SwiftUI `.sheet` / custom detent |
| 流体音色 | Android `RuntimeShader`（API 33+）并提供低版本降级 | Metal + SwiftUI `MetalView` |
| 录音 | `AudioRecord` / `MediaRecorder` | `AVAudioRecorder` / `AVAudioEngine` |
| 触觉 | `HapticFeedback` | `UIImpactFeedbackGenerator` |

