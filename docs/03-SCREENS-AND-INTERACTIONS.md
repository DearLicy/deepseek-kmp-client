# 页面与交互规格

## 1. 页面地图

```text
Root
├── Welcome / New Chat
│   └── Chat
│       ├── Embedded Session Drawer
│       ├── Attachment Panel
│       ├── Voice Mode / Recording Overlay
│       ├── Search Sources Sheet
│       └── Session Context Menu / Delete Confirm
├── Settings Sheet
│   ├── Data Controls
│   ├── Voice Picker
│   ├── Font Size
│   └── About / Feedback
└── Full Text Search
```

## 2. 欢迎页与空状态

### 视觉

- 顶部左侧汉堡按钮；右侧音量/静音与新建对话胶囊。
- 中央蓝色鲸鱼 Logo + “你好，有什么我能帮你的吗？”
- 底部圆角输入区，包含“深度思考”“智能搜索”、附件 (+) 和语音按钮。
- 背景接近纯白，内容垂直居中，输入区固定在安全区底部。

### 状态

```text
WelcomeState
  apiKeyConfigured: Boolean
  input: String
  thinkingEnabled: Boolean
  searchEnabled: Boolean
  attachmentCount: Int
```

### 交互

- 点击输入区进入键盘态；输入非空后显示发送按钮。
- 点击附件展开上传面板。
- 点击语音切换“按住说话”模式。
- 点击“深度思考/智能搜索”只切换状态，不立即请求。
- 未配置 API Key 时发送动作转入 API Key 配置页/Sheet。

## 3. 对话页

### 布局

```text
Top bar: menu | title | sound + new chat
Message list: user bubble / assistant reply / action row
Composer: rounded shell + mode toggles + attach + voice
```

### 用户消息

- 右侧对齐。
- 浅蓝底、深色文字、最大宽度约屏幕宽度的 78%。
- 长文本可换行；链接可点击。
- 附件在气泡上方或内部按类型展示。

### 助手消息

- 左对齐，无传统气泡边框。
- 思考区位于正文之前，先显示状态行，再显示折叠内容。
- 思考完成后显示“已思考（用时 N 秒）”和箭头；展开后显示灰色思考正文与竖线。
- 搜索步骤可在思考区中显示：搜索图标、查询、结果数量、来源 Logo。
- 正文支持 Markdown；引用位置显示小号数字角标。
- 操作栏：复制、赞、踩、朗读、分享、重新生成；生成中显示停止。

### 流式状态

```text
Idle
  -> Waiting
  -> Thinking
  -> Searching / Reading
  -> Answering
  -> Completed
  -> Failed / Cancelled
```

- 新内容到达时不强制把用户滚动位置拉到底；只有用户本来在底部时自动跟随。
- `message_stop` 或显式取消后结束生成态。
- 失败可在消息下方重试，不清空已到达内容。

## 4. 嵌入式会话侧栏

### 视觉

- 从左侧推入，覆盖约 82% 屏宽；右侧保留对话页窄条。
- 顶部胶囊搜索框“搜索对话内容...”。
- 中间为空状态：“暂无对话；你与 DeepSeek 的对话将展示在这里” + “开启新对话”蓝色按钮。
- 有会话时按“置顶 / 今天 / 更早”等分组。
- 底部用户头像与名称、更多菜单。

### 会话菜单

点击会话项右侧省略号弹出大圆角浮层：

- 重命名
- 置顶 / 取消置顶
- 多选
- 删除（红色）

### 删除确认

- 居中大圆角卡片。
- 标题“删除后，该对话将不可恢复”。
- 说明“由该对话生成的分享链接也将失效”（官方截图原文；API Key 首版没有云端分享，成品应改为本地删除说明）。
- 红色实心“删除该对话”，下方灰色“取消”。

### 侧栏手势

- 从左边缘向右滑动打开。
- 侧栏打开时点击右侧对话页区域关闭。
- 侧栏内容滚动与页面消息滚动隔离。

## 5. 输入区状态机

```text
TextInput
  -- longPressVoice--> HoldToTalk
  -- tapAttach-------> AttachmentPanel
  -- tapVoice--------> HoldToTalk

HoldToTalk
  -- tapKeyboard-----> TextInput
  -- longPress-------> Recording

Recording
  -- release--------> Transcribing -> TextInput / Send
  -- swipeUp---------> Cancelled -> HoldToTalk
```

### 文本态

- 外壳圆角、白底、细描边、柔和阴影。
- 第一行占位符“发消息或按住说话”。
- 第二行左侧深度思考/智能搜索胶囊，右侧附件和语音。
- 输入后右侧变为蓝色发送按钮；生成中变为停止按钮。

### 长按说话态

- 输入框内容替换为“按住说话”按钮。
- 底部工具行仍保留，以方便切换键盘或附件。

### 录音全屏态

- 背景为蓝色到浅蓝的柔和渐变/Shader。
- 顶部状态栏显示录音中；中央有鲸鱼或流体视觉。
- 文案“松手发送，上滑取消”。
- 底部动态波形，随音频能量变化。
- 上滑超过阈值切换为取消视觉（红色/降低透明度）；松手取消不上传。

## 6. 附件面板

- 点击附件后，输入区下方展开白色面板，背景轻微变暗。
- 顶部为最近图片横向缩略图条，可左右滚动。
- 下方三个大卡片：拍照、相册、文件。
- 选择后附件进入草稿区，显示文件名、类型、大小、上传状态。
- 上传中显示进度；失败提供重试/删除；发送前可以取消。

## 7. 设置 Sheet

### 页面结构

- 顶部标题“设置”，右侧圆形关闭按钮。
- 分组标题：账户、应用、语音、关于。
- 每个分组为白色圆角卡片，组内使用细分割线。

### 必须页面

1. 账号管理（首版以 API Key 配置/清除替代）
2. 数据管理
3. 语言
4. 朗读音色
5. 字体大小
6. 检查更新
7. 服务协议
8. 帮助与反馈
9. 清除 API Key（本地）

### 数据管理

- API Key 模式不映射官方“数据用于优化体验”账号开关，也不自动上传训练数据；该行先隐藏，不做含义不同的占位开关。
- “我共享的链接”在首版可以只显示空状态或隐藏，因为我们不做官方分享服务。
- “导出所有历史对话”导出本地会话。
- “删除所有历史对话”红色入口，二次确认。

## 8. 朗读音色 Sheet

- 底部大圆角 Sheet，标题“朗读音色”。
- 中央为流体色块预览（AGSL/Metal），带缓慢流动。
- 下方音色名称与描述。
- 分页圆点、横向滑动切换。
- 底部蓝色胶囊“确认”。
- 首版数据仅提供两个示例明确枚举的音色：
  - `stella`：海星，女声
  - `tide`：暗潮，男声
- 第 3/4 个官方私有音色不伪造、不显示；组件保留任意数量数据的扩展能力。

## 9. 字体大小页

- 顶部返回箭头与“字体大小”。
- 大预览卡片：用户气泡“预览文字大小” + 两段说明文字。
- “跟随系统”开关。
- 底部两侧 A 图标、当前档位“标准”、分段滑块。
- 调整时预览实时变化，但不重建会话。

## 10. 全文搜索

- 独立搜索态，顶部返回箭头 + 胶囊输入框。
- 键盘弹出后，输入框固定在安全区。
- 空查询显示最近会话/提示；有查询时显示匹配会话标题与片段。
- 点击结果关闭搜索并打开会话。

## 11. Android / iOS 差异化

共享的业务状态必须一致，但以下交互分别使用平台原生实现：

- 返回手势：Android 系统返回键/预测性返回，iOS 边缘返回。
- 键盘：Android IME insets 与 iOS keyboard safe area。
- 录音权限：各自权限文案与授权流程。
- 分享：Android `Intent.ACTION_SEND`，iOS `UIActivityViewController`。
- 朗读音频：Android `AudioTrack`/`MediaPlayer`，iOS `AVAudioEngine`/`AVAudioPlayer`。
- 触觉反馈：平台 Haptic API。

