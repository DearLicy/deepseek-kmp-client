# 逆向证据与证据边界

## 1. 输入产物

| 产物 | 路径 | 用途 |
| --- | --- | --- |
| 官方 APK | `.tmp/input/deepseek.apk` | UI、资源、字符串、私有接口痕迹 |
| APK 资源解包 | `.tmp/extracted/apk` | Lottie、Shader、PNG/Vector 资源 |
| JADX 源码 | `.tmp/extracted/jadx/sources` | 静态类名、字符串、接口证据 |
| 资源映射 | `.tmp/extracted/resources-parsed.tsv` | 语义资源名到 APK 文件名的映射 |
| 中文字符串 | `.tmp/extracted/strings-zh-Hans.tsv` | 页面文案、Toast、弹窗文案 |
| 类名/文件映射 | `.tmp/extracted/source-symbols.tsv` | Kotlin 源文件名、类名、行号证据 |
| chat-demo | `.tmp/reference/chat-demo` | API Key 数据面、SSE 与工具协议 |

## 2. APK 身份

```text
package:      com.deepseek.chat
versionName:  2.8.0
versionCode:  297
minSdk:       24
targetSdk:    36
ABI:          arm64-v8a
SHA-256:      489ff75c6debfce94fcb247595969b71314522c736b11548eb27c845a5f4dce4
```

## 3. 证据等级

本项目采用以下证据等级，文档和实现不得混用：

| 等级 | 含义 | 典型来源 |
| --- | --- | --- |
| E1 | APK 中可直接观察到的资源、字符串、资源 ID | 资源表、Lottie、Shader、字符串表 |
| E2 | 反编译源码中的类名、方法名、调用点、协议字符串 | `source-symbols.tsv`、JADX 源码 |
| E3 | 截图可见的视觉结果与交互结果 | 用户提供的 iOS/Android 截图 |
| E4 | 对截图尺寸、颜色、圆角的推算 | 设计标注与截图采样 |
| E5 | 对运行时行为的推断 | 动态服务端返回、未验证的音频/网络行为 |

`E5` 不得被写成“已确认”；涉及服务端能力的结论必须回落到 `chat-demo` 源码或实际请求验证。

## 4. 已确认的 UI 组件证据

`source-symbols.tsv` 中可见以下 Kotlin 源文件与组件：

| 语义组件 | 源文件证据 |
| --- | --- |
| 设置页 | `SettingsPage.kt` |
| 字体大小 | `FontSizePreferencePage.kt` |
| 音色选择 Sheet | `VoiceSelectBottomSheet.kt`、`VoiceSelectContent.kt` |
| 音色流体预览 | `VoiceBlobPreview.kt`、`CallVoiceBlobPreview.kt` |
| 语音输入按钮 | `VoiceInputButton.kt` |
| 语音手势 | `VoiceInputGestureModifier.kt` |
| 录音全屏反馈 | `VoiceInputOverlay.kt` |
| 语音遮罩 | `VoiceMaskInputContent.kt`、`ShaderVoiceInputContent.kt` |
| 录音波形 | `VoiceWaveform.kt` |
| 输入框 | `ChatInputField.kt` |
| 输入动作栏 | `ChatInputActionBar.kt` |
| 会话列表 | `ChatSessionList.kt`、`ChatSessionItem.kt` |
| 全文搜索 | `FullTextSearchBar.kt` |
| 数据管理 | `DataControlsSettingsPage.kt` |
| 删除确认 | `DeleteSessionConfirmDialog.kt` |
| 重命名弹窗 | `SessionTitleEditDialog.kt` |
| 上传面板 | `UploadPanel*.kt` |

注意：JADX 输出的是反编译表示及编译器保留的符号信息，并未恢复原始 Kotlin 源码；部分 `.java` 输出仍是寄存器式伪代码。源文件行号来自编译产物调试/符号信息，不能把 `source-symbols.tsv` 当作可直接编译的源码。

## 5. 页面证据

用户提供的 iOS 截图覆盖以下页面：

- 欢迎页：鲸鱼 Logo、问候语、输入区。
- 对话页：用户气泡、助手正文、思考折叠/展开、复制/点赞/点踩/朗读/分享/重试操作栏。
- 联网搜索：搜索过程、来源角标、来源列表底部弹出。
- 嵌入式侧栏：约 82% 屏宽、搜索框、空状态、底部用户区、会话菜单。
- 输入区：深度思考、智能搜索、附加按钮、语音按钮。
- 附件面板：拍照、相册、文件；顶部最近的图片预览条。
- 语音模式：按住说话、长按录音全屏、波形、松手发送/上滑取消。
- 设置：分组卡片、账号、数据、语言、语音、字体、关于、退出登录。
- 数据管理：数据优化开关、分享链接、导出、删除。
- 音色选择：底部 Sheet、流体色块、分页点、确认按钮。
- 字体大小：预览卡片、跟随系统开关、滑块。
- 删除确认：危险红色按钮、取消、说明文字。
- 全文搜索：返回箭头、搜索框、键盘搜索态。

## 6. 资源证据

Lottie 资源由 `resources-parsed.tsv` 中的 `raw` 条目确认：

| 语义名 | APK 文件 | 帧率/帧数/尺寸 |
| --- | --- | --- |
| `auto_play_off` | `res/1I.json` | 100fps / 40f / 25x24 |
| `auto_play_on` | `res/oM.json` | 100fps / 40f / 25x24 |
| `expert` | `res/s7.json` | 60fps / 30f / 16x16 |
| `flash` | `res/En.json` | 60fps / 30f / 16x16 |
| `globe` | `res/U1.json` | 60fps / 30f / 16x16 |
| `photo` | `res/HR.json` | 60fps / 30f / 16x16 |
| `playing` | `res/u1.json` | 100fps / 120f / 24x24 |
| `think` | `res/b0.json` | 60fps / 26f / 16x16 |
| `voice_mask` | `res/02.agsl` | AGSL Shader |
| `ic_playing_bars_24` | `assets/ic_playing_bars_24.json` | 60fps / 96f / 24x24 |

Fluid blob Shader 资源：

```text
assets/shaders/fluid_blob.agsl
assets/shaders/fluid_blob.vert
assets/shaders/fluid_blob.frag
assets/shaders/fluid_blob_medium.frag
assets/shaders/fluid_blob_low.frag
assets/shaders/fluid_blob.example.frag
assets/shaders/fluid_blob_medium.example.frag
assets/shaders/fluid_blob_low.example.frag
```

## 7. 官方 App 私有接口痕迹

静态分析可见以下私有接口字符串：

```text
/api/v0/chat/completion
/api/v0/chat/continue
/api/v0/chat/resume_stream
/api/v0/chat/stop_stream
/api/v0/chat/history_messages
/api/v0/chat/tts/voices
/api/v0/chat/tts/voice
/api/v0/chat/tts/
/api/v0/asr/ws
/api/v0/file/upload_file
```

未认证访问 `/api/v0/chat/tts/voices` 返回的示例错误：

```json
{"code":40003,"msg":"INVALID_TOKEN","data":null}
```

这只能证明官方 App 使用了该接口，不能证明 API Key 客户端有权调用它，也不能证明其返回的音色 ID 可被 `/v1/tools/tts` 接受。因此这些接口不进入本项目的数据面。

## 8. 当前未知项

- 截图四音色与 API Key 音色的逐项映射未确认；不能把两组音色简单视为同一个列表里少两个。
- 官方 App 录音的上传编码、降噪、VAD 与 ASR 会话细节未做动态验证。
- 官方 App 的精确字体、Spacing Token、阴影参数只能从截图采样，不能把估计值冒充官方值。
- iOS 端 `fluid_blob` 的 Metal 移植需要单独做像素级比对；AGSL 不能直接作为 Metal Shader 编译。
- 当前 Linux 环境没有 Xcode，iOS 只能做源码与接口审查，不能声称已完成 iOS 构建。


## 9. 本轮证据登记（2026-11-19）

| 判断 | 可复核来源 | 状态 |
| --- | --- | --- |
| APK 包名、版本、SDK | `inspect_android_package`：`ev_5df8ad1779f44d1de941ddfb378dc564665cb2b950edcd946f48e44827ea2029`，本地包见 `.tmp/rea/android-package.json` | 静态观察 |
| APK v2 签名记录与内容自洽 | `inspect_apk_signature` 父 Evidence：`ev_e72c6e612bc9fdf602292ac2052e5b8f201f219c08e3cfaf8458d9027d6d8cde`；`.tmp/rea/apk-signature-evidence.json` | 不等于已验证签名真实性或分发来源 |
| 音色为动态对象 | `docs/evidence/apk-serialization-models.tsv`；JADX `defpackage/xv9.java`、`zv9.java` | 字段观察，无四项实测响应 |
| 主题主色 | 原资源表 `color/primary` 默认/夜间配置 | Android 资源观察，不能直接等同 iOS |
| 页面符号 | `docs/evidence/apk-ui-source-map.tsv` | 符号/文件名观察，部分代码回退伪代码 |
| 接口路由与 SSE 字段 | 固定版 `docs/evidence/chat-demo/src/services/api/` | 源码合同，未做线上 Key 联调 |
| 资源迁移完整性 | `scripts/verify_assets.py` 与 `.tmp/qa/assets-verification.json` | 182 个正式条目哈希、20 对路径集合已核验 |
| 用户截图 | `assets/reference/screenshots/MANIFEST.json` | 11 张有文件的原图；其他内嵌图只作视觉观察 |

“官方 APK”是本次用户提供产物的称呼；其哈希固定了当前样本，不凭包名、证书声明或内容自洽自动断言其发行者身份。没有运行该 App 或读取用户账号数据。
