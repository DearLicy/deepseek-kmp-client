# 联网搜索、引用与语音能力

## 1. 研究过程的产品表达

官方 App 的研究过程不是普通“加载圈”，而是一条可展开的时间线：

```text
思考
  -> 搜索 "query"
  -> 搜索到 N 个网页
  -> 阅读/打开网页
  -> 继续思考
  -> 正文回答
  -> 底部来源汇总
```

首版实现要保留这条时间线，不把所有工具事件压成一句“正在搜索”。

## 2. 搜索请求

搜索由 Anthropic-compatible 的 server tool 表达：

```json
{
  "tools": [
    { "type": "deepseek_web_search", "name": "deepseek_web_search" },
    { "type": "deepseek_read_url", "name": "deepseek_read_url" }
  ]
}
```

### 搜索类型

| kind | UI 文案建议 | 说明 |
| --- | --- | --- |
| `search` | 搜索 `query` | 普通网页搜索 |
| `deep_search` | 深度搜索 | 多轮搜索/阅读 |
| `read_link` | 阅读链接 | 读取用户问题里的 URL |
| `image_search` | 搜索图片 | 结果可显示来源 |
| `deep_image_search` | 深度图片搜索 | 结果可显示来源 |

### 查询解析

`server_tool_use.input` 可能是：

```json
{ "action": "search", "query": "...", "queries": ["..."] }
{ "action": "open_page", "url": "..." }
{ "action": "find_in_page", "url": "...", "pattern": "..." }
{ "action": "read_link", "url": "..." }
```

空查询允许出现在图片搜索中；UI 要能显示“图片搜索”而不是空字符串。

## 3. 来源模型

来源统一转换成：

```text
Source
  url: String
  title: String
  siteName: String
  publishedAt: String?
  snippet: String?
  siteIcon: String?
  index: Int
```

规则：

- 按 URL 去重，首次出现分配编号。
- 标题优先使用服务端标题；缺失时显示 URL。
- 站点名优先服务端 `site_name`，否则使用域名去掉 `www.`。
- 发布时间可能是 RFC 3339，也可能是网页原始日期字符串；不要强转成统一日期格式后丢失原文。
- 站点图标加载失败时显示通用地球图标。
- 正文引用角标与来源卡片编号必须一致。

## 4. 来源列表 UI

### 底部汇总

- 正文结束后显示一个圆角胶囊：`[站点图标组] N 个网页`。
- 点击从底部弹出来源列表 Sheet。
- Sheet 顶部可显示“搜索结果”标题和关闭按钮。
- 每条来源显示站点图标、站点名、日期、标题、摘要、序号。

### 单条来源

- 点击打开系统浏览器或内置 Safari/Chrome。
- 首版不抓取网页正文；抓取由 `deepseek_read_url` 服务端完成。
- 不缓存服务端加密内容；只缓存可展示的标题/URL/摘要元数据。

### 引用角标

- 小号浅灰圆角，不抢正文视觉。
- 点击可滚动/弹出对应来源。
- 同一 URL 不重复编号。

## 5. 搜索失败与取消

| 状态 | UI |
| --- | --- |
| 搜索中 | 旋转搜索图标 + “正在搜索” |
| 搜索失败 | 灰色/红色错误图标 + “联网搜索暂不可用” |
| 无结果 | “未搜索到合适的内容” |
| 用户停止 | “搜索已停止” |
| 搜索开启且有文件 | 发送前提示“请先删除文件再开启联网搜索” |

取消生成时同时取消网络请求；已经返回的思考、来源和正文保留在可恢复状态中。

## 6. 语音能力总览

语音分为三个独立能力，不能混成一个状态：

```text
ASR：录音 -> 文本
TTS：文本 -> 音频
Call：实时通话（首版不接）
```

### 6.1 录音输入

- 用户长按语音按钮进入录音态。
- 采集单声道 16-bit PCM；可选 WAV 包装。
- 录满 120 秒自动停止。
- 超过 8 MiB 前停止并提示。
- 上滑取消时丢弃本地临时文件，不调用 ASR。
- 松手后先展示“识别中”，成功后填入输入框或按用户设置发送。

### 6.2 ASR 响应

```text
POST /v1/tools/asr
Authorization: Bearer <API_KEY>
```

响应只有 `{ "text": "..." }`；没有分段时间戳或置信度，UI 不要伪造。

### 6.3 TTS 播放

```text
POST /v1/tools/tts
voice_id: stella | tide
format: pcm
```

播放层需要：

- 24kHz、单声道、16-bit LE。
- 流式 PCM 队列。
- 暂停、继续、停止、拖动进度（若把 PCM 缓存到本地）。
- 朗读按钮具有 loading / playing / paused / error 状态。
- 页面离开时停止播放或把播放器归属到消息，避免多个消息同时朗读。

## 7. 音色数量问题

### 已确认

`chat-demo` 本次固定版本 `speech.ts` 中的 TTS 类型枚举为：

```text
stella：海星，女声
tide：暗潮，男声
```

### 官方 App

官方 App 的音色来自私有动态接口 `/api/v0/chat/tts/voices`，DTO 包含：

```text
voice_id
name_i18n
description_i18n
gender
languages
demo_urls
is_default
color_palette(c1, c2, c3)
```

静态模型说明客户端能够接收动态名称、描述、试听地址和色块；截图显示四个分页点，但尚无已认证的四项列表响应，但不能证明这些 `voice_id` 在 `/v1/tools/tts` 的 API Key 路线可用。

### 尚未确认的映射

截图里可见“贝壳 / 百变活泼”，而示例名称为“海星 / 暗潮”。不能把 `stella` 标成“贝壳”，也不能把某个色块、性别、语言、介绍套到未验证的 ID 上。公开示例枚举两个值不等于服务端绝对只支持两个；目前结论仅限源码覆盖范围。

### 首版决策

- UI 组件数据驱动，支持 0..N 个音色。
- 首版只展示 `stella` 和 `tide`。
- 不伪造其余 ID，不显示“即将上线”的假音色。
- 如果未来服务端公开更多 ID，只改配置和资源，不改页面结构。
- 不为了凑四音色而调用官方私有接口。

### 配置结构

```text
VoiceOption
  id: String
  name: LocalizedText
  description: LocalizedText
  gender: VoiceGender?
  languages: List<String>
  palette: List<Color>
  isDefault: Boolean
```

## 8. 朗读内容策略

- 只朗读助手正文，不朗读思考、工具参数、加密字段和页面按钮。
- Markdown 过滤由 `filter_markdown: true` 完成。
- 代码块、表格、长公式默认可跳过；提供用户开关时再扩展。
- 正文包含引用角标时，朗读前去掉 `[citation:N]` 标记。
- 流式生成中可选“生成完再朗读”，避免反复打断音频。

## 9. 隐私

- 录音临时文件保存到平台 cache/临时目录，发送后尽快删除。
- ASR/TTS 请求不得记录原始音频或完整正文到分析系统。
- 用户删除会话时同时删除本地音频缓存。
- 麦克风权限、相册权限、文件权限分别按需申请，不在首次启动时一次性索要。

