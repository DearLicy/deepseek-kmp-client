# 动效与资源归档

## 1. 资源归档原则

- 原始 APK、反编译源码、临时工具只放在 `.tmp`，不提交到 Git。
- 正式使用的 Lottie、Shader、SVG/PNG、音频复制到 `assets/`，每个文件记录来源资源名和 APK 文件路径。
- 原始资源与派生资源分开：
  - `assets/original/`：直接来自 APK 的字节。
  - `assets/derived/`：转换后的 SVG、Android Vector、Metal Shader 或压缩资源。
- 任何派生文件都要在 `assets/SOURCE.md` 中写清转换方式和原始哈希。
- 不把官方 App 的私有接口字段、Token 或登录态复制进素材目录。

## 2. Lottie 映射

| 语义 | APK 源文件 | 目标 | 帧率 | 帧数 | 尺寸 | 用途 |
| --- | --- | --- | --- | --- | --- | --- |
| `auto_play_off` | `res/1I.json` | `assets/original/lottie/auto_play_off.json` | 100 | 40 | 25x24 | 静音/自动播放关 |
| `auto_play_on` | `res/oM.json` | `assets/original/lottie/auto_play_on.json` | 100 | 40 | 25x24 | 自动播放开 |
| `expert` | `res/s7.json` | `assets/original/lottie/expert.json` | 60 | 30 | 16x16 | 专家模式 |
| `flash` | `res/En.json` | `assets/original/lottie/flash.json` | 60 | 30 | 16x16 | 快速模式 |
| `globe` | `res/U1.json` | `assets/original/lottie/globe.json` | 60 | 30 | 16x16 | 联网搜索 |
| `photo` | `res/HR.json` | `assets/original/lottie/photo.json` | 60 | 30 | 16x16 | 图片/相册 |
| `playing` | `res/u1.json` | `assets/original/lottie/playing.json` | 100 | 120 | 24x24 | 朗读播放中 |
| `think` | `res/b0.json` | `assets/original/lottie/think.json` | 60 | 26 | 16x16 | 深度思考 |
| `playing_bars` | `assets/ic_playing_bars_24.json` | `assets/original/lottie/playing_bars.json` | 60 | 96 | 24x24 | 朗读波形 |

实现建议：

- Android 使用 `LottieAnimation`，但不要在滚动列表中为每条消息创建无限动画；只给当前播放/生成的消息启用。
- iOS 使用 `Lottie` Swift Package 或 `lottie-ios`，同样做可见性和生命周期管理。
- 降低动效模式下将 `think`、`playing` 替换为静态图标。
- Lottie 颜色若可配置，按当前主题传入；不可配置时保留原始色并做暗色对比度检查。

## 3. Shader 映射

### 3.1 Fluid Blob（音色预览）

源文件：

```text
assets/shaders/fluid_blob.agsl
assets/shaders/fluid_blob.vert
assets/shaders/fluid_blob.frag
assets/shaders/fluid_blob_medium.frag
assets/shaders/fluid_blob_low.frag
```

核心 Uniform：

```text
color1, color2, color3
resolution, offset, time, pixelRatio
scale, rotation, colorCount, grain
proportion, softness, shape, shapeScale
distortion, swirl, swirlIterations
```

用途：

- 设置 -> 朗读音色 -> 中央流体色块。
- 音色切换时改变 color palette 与 shape 参数。
- 录音/通话页面可作为背景层。

平台实现：

- Android API 33+：`RuntimeShader` / AGSL。
- Android API < 33：优先复用包内 GLSL ES 100（`fluid_blob.frag` / medium / low）配合 GLES TextureView；只有渲染器确实不可用时才用静态渐变降级，并记录视觉差异。
- iOS：把数学逻辑移植到 Metal fragment shader；`fluid_blob.frag` 是 GLSL 参考，不是可直接编译的 Metal 源。

### 3.2 Voice Mask（录音遮罩）

源文件：

```text
res/02.agsl
```

用途：

- 录音全屏态的蓝色遮罩、波形纹理、上滑取消反馈。
- 需要 `time`、`audioLevel`、`touch`、主题色等输入。

降级：

- 低端 Android：线性渐变 + 波形 View。
- iOS：按算法移植到 Metal + 原生叠层，当前尚未实现或验证。
- 若设备不支持 Shader，仍必须保留“松手发送、上滑取消”的清晰文字反馈。

## 4. 图标资源

以下语义资源名已从 `resources-parsed.tsv` 确认存在：

```text
ic_think_outline_16
ic_search_web
ic_globe_language_outline_16
ic_globe_language_outline_20
ic_voice_outline_26
ic_send_fill_20
ic_copy_outline
ic_like_outline
ic_dislike_outline
ic_playing_fill_24
ic_new_chat_outline_24
ic_ds_new_chat_outline_20
ic_ds_search_outline_16
ic_keyboard_voice_24
ic_photo_outline_16
ic_photo_outline_20
ic_font_size_small
ic_font_size_large
chat_welcome_logo
deepseek_logo_title
```

资源提取策略：

- 优先保留 SVG/Vector XML 的原始语义，不直接把低分辨率 PNG 当作所有密度资源。
- 使用 APK 中各 density 的 PNG 作为视觉比对基准。
- 图标统一转为 Android Vector Drawable 与 iOS SVG/PDF Asset；转换后必须比对 1x/2x/3x 轮廓。
- 如果某个资源是二进制 XML，使用 Android 资源解码工具生成可编辑 XML，不手写猜测路径。

### 品牌 Logo

`chat_welcome_logo` 对应 `res/Oc.xml`，`deepseek_logo_title` 对应 `res/Hk.xml`。首版可以使用原始资源做视觉验收，但正式发布前需要确认商标/品牌使用边界；如果需要公开发布，应保留“非官方客户端”说明，不冒充官方产品。

## 5. 音频资源

证据中可见：

```text
call_connected    -> res/mT.m4a
call_disconnected -> res/sN.m4a
```

资源处理规则：

- 只用于通话连接的 UI 音效，不进入聊天 SSE 协议。
- 复制到 `assets/original/audio/`，记录采样率、声道、编码和来源。
- 用户关闭声音时不得播放。
- 首版通话功能若不做，可暂不启用这两段音频，避免误导为已接入实时通话。

## 6. 动效验收

### 侧栏

- 从左侧 82% 宽度滑入，右侧页面轻微缩放/平移。
- 动画期间消息滚动保持位置。
- 关闭后焦点回到原按钮。

### 思考折叠

- 折叠/展开不改变助手消息的稳定 ID。
- 流式输出期间默认展开；完成后按设置自动折叠。
- 箭头旋转与内容高度同步。

### 搜索

- 搜索开始显示搜索图标旋转/脉冲。
- 结果返回后显示网页数量。
- 引用角标出现不造成正文横向跳动。

### 录音

- 长按 180–260ms 后进入录音态。
- 全屏层 fade/scale 进入，不阻塞音频采集。
- 波形随真实音频能量变化；无权限时显示明确错误，不进入假录音。

### Sheet / Toast / 按钮

- Sheet 使用弹性曲线；背景遮罩逐渐变暗。
- Toast 为中央黑色胶囊，短文本优先，长文本换行。
- 重要操作按钮有触觉反馈。

## 7. 资源风险

- 官方 APK 中的图片、Lottie、音频可能受商标和版权保护；本项目是本地开发/学习复刻，不应把它包装成官方发布版。
- 任何用于公开发布的素材都要重新确认许可。
- 不从 APK 复制用户头像、账号信息、设备信息或私有服务端令牌。
- 资源转换后要保留原始来源哈希，方便追溯和替换。


## 8. 本轮完成的归档与验证

- `assets/MANIFEST.json`：182 条正式派生/原始文件记录，每项都有 APK 哈希、原始条目路径、原始条目哈希与输出哈希。
- 9 个 Lottie JSON（含 1 个 `assets/` 下的 playing bars）；时长、帧率、画布可从 JSON 复核。
- 9 个 Shader 文件：2 个 AGSL、1 个 vertex、6 个 fragment/preview 文件。
- 2 个 M4A 音效；音频未做实际试听或平台播放验证。
- 20 个图标/Logo：compiled XML 原字节、aapt2 投影、Android Vector、SVG、iOS imageset；42 个对应 PNG 变体保留原文件名，未猜测缺失的 density qualifier。
- 验证脚本逐项对照 APK ZIP 条目哈希，检查所有 drawable path/clip-path 路径多重集合一致，检查 SVG 的 ARGB→RGB/opacity 转换。
- 20 个派生 Android Vector XML 均通过 `aapt2 compile`，验证输出在 `.tmp/qa/verified-vector-res.zip`。
- 浏览器离线渲染接触表已查看：`.tmp/icon-preview.png`。这是图标提取 QA，不是 App 深色模式验收；源图标未统一 tint，因此部分在深色底上仍是黑色。
- `assets/reference/screenshots/` 另存 11 张用户提供原图与哈希，作为设计参考，不进入成品包。

复核命令：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/extract_assets.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify_assets.py
```

## 9. 录音遮罩移植的实际算法线索

`voice_mask.agsl` 明确声明了如下输入，而不是一张普通蓝色渐变图：

| Uniform | 重建职责 |
| --- | --- |
| `resolution`, `pixelScale` | 像素坐标与平台缩放 |
| `time` | 随时间变化的焦散纹理 |
| `touch`, `glow` | 手指附近的高光和流场扭曲 |
| `audioLevel`, `breathLevel`, `vmAudioLift` | 音量纹理与弧面呼吸位移 |
| `cancelMix`, `cancelColor`, `cancelAlphaScale` | 正常/取消之间的颜色和透明度插值 |
| `focusColor`, `darkAppearance` | 主题适配 |
| `arcRadius`, `baselineOffset`, `entranceScale` | 底部弧形遮罩与进入动画 |
| `spacingPx`, `flowOffsetPx` | 纹理间距和流动位移 |

`main` 返回预乘 alpha；iOS 混合模式也必须按预乘颜色处理，避免白边。源码有指向 iOS 实现的注释，但注释不是已取得 Metal 源码的证据。已存在 Android 的 `FluidBlobTextureView` 与 GLES20 调用；low shader 在代码中将循环上限压到 5 并跳过 grain，高/medium 上限为 10。这些是静态算法线索，仍需目标设备实际渲染比对。
