# chat-demo API 接入规范

> 证据版本：`deepseek-ai/chat-demo` commit `429af5a4558469496b231631e850fbccc0c30c87`（v0.1.2）。
> 本文是该固定源码版本的接入合同，不是“所有 API Key 都已实测可用”的结论；没有真实 Key 联调。
> 对应源码与 MIT LICENSE 保存在 `docs/evidence/chat-demo/`，哈希见该目录 `SOURCE.json`。
>
> v0.1.3 增量：图片上传前若长边超过 4096 像素，生成等比缩小的上传副本；历史图片缺少当前尺寸检查标记时按需补传。未改变 Messages/Files 的线上协议。见 `13-CHAT-DEMO-V013-ATTACHMENTS.md`。

## 1. 数据面定位

本客户端的数据面只使用 `deepseek-ai/chat-demo` 中已经公开的 API Key 接入方式。默认 API Origin：

```text
https://api.deepseek.com
```

聊天协议为 **Anthropic-compatible Messages API**，不是 OpenAI Chat Completions 的 `choices[].delta.reasoning_content` 结构。实现时不得混用两套协议。

## 2. 鉴权

### 2.1 API Key 校验

```http
GET /models
Authorization: Bearer <API_KEY>
```

- 不产生聊天费用。
- 200 只证明当前模型列表请求通过，不能保证该 Key 具备所有工具、文件和语音能力。
- 失败时映射为本地错误，不把 Key 写日志。

### 2.2 聊天鉴权

推荐的 Chat Header：

```http
x-api-key: <API_KEY>
Content-Type: application/json
Accept: text/event-stream
```

`chat-demo` 同时说明也可使用 `Authorization: Bearer <API_KEY>`。实现优先使用 `x-api-key`，文件上传/引用时按需增加：

```http
anthropic-beta: files-api-2025-04-14
```

## 3. 模型与请求

`chat-demo` 当前声明：

```text
deepseek-flash
deepseek-v4-pro
```

默认使用 `deepseek-flash`。首版 UI 可以使用官方截图中的“快速/专家”视觉，但实际模型选择必须由服务端支持情况和产品配置决定，不能仅靠切换图标就假定模型存在。

### 3.1 最小请求

```http
POST /anthropic/v1/messages
```

```json
{
  "model": "deepseek-flash",
  "system": "You are a helpful assistant.",
  "messages": [
    {
      "role": "user",
      "content": "你好"
    }
  ],
  "thinking": { "type": "disabled" },
  "stream": true,
  "tools": [{ "type": "deepseek_read_url", "name": "deepseek_read_url" }],
  "safety_check": { "level": "high" },
  "title": { "enabled": true }
}
```

### 3.2 开启深度思考

```json
{
  "thinking": { "type": "enabled" }
}
```

### 3.3 开启联网搜索

```json
{
  "tools": [
    { "type": "deepseek_web_search", "name": "deepseek_web_search" },
    { "type": "deepseek_read_url", "name": "deepseek_read_url" }
  ]
}
```

固定版 `chat/store.ts:46-56` 始终声明 `deepseek_read_url`，只按搜索开关增减 `deepseek_web_search`；UI 中是否把它们合并成一个“智能搜索”按钮属于产品映射，协议层要保留独立工具声明。

### 3.4 `latest_reminder`

`chat-demo` 使用一个扩展角色，用于在上下文前插入时间/位置等最新提醒：

```json
{
  "role": "latest_reminder",
  "content": "Current date: 2026-11-19; Location: ..."
}
```

该角色只在服务端支持时使用。若服务端返回协议错误，应关闭提醒能力并继续基础聊天，不应阻塞所有请求。

### 3.5 继续生成

末尾附带 assistant 内容会触发续写。对于本轮已经产生的 assistant 内容，需要按 `chat-demo` 的规则构造：

- 保留本轮非空 thinking block 及其 signature。
- 历史轮次的思考通常省略，具体以服务端要求为准。
- 成功读取链接的 `web_fetch_tool_result` 必须保留 signature，否则不要回传该调用。
- 搜索结果块通常不回传。

实现上把“重新生成”和“续写”拆成两个 Intent，避免覆盖原有消息。

## 4. SSE 协议

### 4.1 帧格式

响应是标准 `text/event-stream`：

```text
event: message_start
data: {"type":"message_start",...}

event: content_block_delta
data: {"type":"content_block_delta",...}

```

- 注释行 `: keep-alive` 忽略。
- 多行 `data` 用换行拼接。
- 事件以空行结束。
- 不依赖 `[DONE]`；以 `message_stop` 为结束标志。
- 连接在 `message_stop` 前断开要视为 `interrupted`，不是正常完成。

### 4.2 事件表

| 事件 | 关键字段 | 客户端动作 |
| --- | --- | --- |
| `message_start` | `message.id`、`message.model`、usage | 创建消息稳定 ID，进入生成态 |
| `content_block_start` | `index`、`content_block` | 创建 text/thinking/tool block |
| `content_block_delta` | `index`、`delta` | 追加正文、思考、签名或引用 |
| `content_block_stop` | `index` | 标记 block 完成 |
| `message_delta` | `stop_reason`、`usage`、可选 `title` | 更新标题/用量/结束原因 |
| `message_stop` | 无 | 标记整轮完成 |
| `ping` | 无 | 保活，不进入 UI |

### 4.3 Delta 类型

| Delta | 处理 |
| --- | --- |
| `text_delta` | 追加正文 Markdown |
| `thinking_delta` | 追加思考内容 |
| `signature_delta` | 更新思考/读链接签名 |
| `citations_delta` | 登记来源并把角标插入对应文本位置 |
| `input_json_delta` | 工具参数增量；首版不在 UI 展示客户端工具调用 |

## 5. 内容块与来源

### 5.1 思维块

```json
{
  "type": "thinking",
  "thinking": "用户说...",
  "signature": "..."
}
```

界面必须把思考块与正文块分开存储，不能把 thinking 拼进 Markdown 正文。折叠状态属于 UI 状态，不属于服务端内容块。

### 5.2 搜索调用

`server_tool_use` 可能包含：

```text
search
deep_search
read_link
image_search
deep_image_search
```

工具结果块：

```text
web_search_tool_result
web_fetch_tool_result
```

UI 映射：

- `search` / `deep_search` -> “正在搜索 / 搜索到 N 个网页”
- `read_link` -> “阅读搜索结果”
- `find_in_page` -> “在网页中查找”
- `web_search_result` -> 来源卡片
- `web_fetch_result` -> 已阅读网页

### 5.3 引用

`citations_delta` 的引用包含：

```text
type: web_search_result_location
url
title
cited_text
encrypted_index
```

客户端负责：

1. 按 URL 去重。
2. 按首次出现顺序编号。
3. 在正文对应位置插入 `[citation:N]`。
4. 底部“N 个网页”展示来源列表。
5. 点击角标跳到来源卡片或详情。

不能尝试解密 `encrypted_content` 或把服务端加密内容当明文展示。

## 6. Files API

### 6.1 上传

```http
POST /anthropic/v1/files
anthropic-beta: files-api-2025-04-14
```

multipart 字段：

```text
purpose: rich_layout_recognition | user_data
file: <bytes>
```

multipart 如有 `purpose`，必须先写 `purpose` 再写 `file`。图片不传 `purpose`；PDF、Word、PPT 需要版面解析时使用 `rich_layout_recognition`；纯文本、代码、表格、电子书使用 `user_data`。

### 6.2 引用文件

图片：

```json
{
  "type": "image",
  "source": { "type": "file", "file_id": "file_..." }
}
```

文档：

```json
{
  "type": "document",
  "source": { "type": "file", "file_id": "file_..." }
}
```

Files API 与使用 `file_id` 引用图片必须携带 Files beta header；示例实现对含 image/document 的请求统一添加。固定版 `types.ts` 提示扩展模式不支持 document，而 `store.ts` 又固定 `safety_check.level = high`，因此“关闭搜索就一定可发 document”不能当作已解决方案。普通文档上传、引用与模式组合需以真实返回单独验证；不以关闭安全检查规避。

### 6.3 限制

`chat-demo` 的客户端限制值：

```text
单文件最大 64 MiB
user_data 最大 32 MiB
单次最多 50 个文件
```

服务端可能比客户端更严格；以服务端 4xx 返回为准。

### 6.4 图片大小处理

- 上传副本长边上限为 4096 像素，原始本地附件不覆盖。
- 浏览器 Canvas 对大动图只绘制一帧；原生端也采用首帧策略，必须向用户说明。
- 历史图片复用 `file_id` 前检查本地原件的尺寸策略版本；策略过期或原图超限时重新生成上传副本。
- 服务端明确报告 `file_id` 失效时，仅重置被点名的附件；无法定位具体 ID 时才重试本次请求涉及的附件集合，最多 2 次。

## 7. ASR

```http
POST /v1/tools/asr
Authorization: Bearer <API_KEY>
Content-Type: multipart/form-data
```

```text
file=recording.wav
```

音频要求：

- WAV：单声道、16-bit PCM。
- 或 Ogg Opus。
- 最长 120 秒。
- 最大 8 MiB。

响应：

```json
{ "text": "识别后的文字" }
```

客户端录音建议输出 16 kHz 或 24 kHz 单声道 16-bit PCM WAV；以服务端兼容性和识别效果为准。

## 8. TTS

```http
POST /v1/tools/tts
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

```json
{
  "text": "要朗读的正文",
  "voice_id": "stella",
  "format": "pcm",
  "filter_markdown": true
}
```

响应是裸 PCM 字节流：

```text
24 kHz
单声道
16-bit little-endian
```

实现要点：

- 把 PCM 分块送入音频队列，支持暂停/停止。
- 播放结束后释放 AudioTrack/AVAudioEngine。
- 不把朗读全文写日志。
- `filter_markdown: true` 时服务端会过滤代码块、表格、引用标记和长公式；客户端仍需处理失败和空结果。

## 9. 错误映射

| 类型 | 触发 | UI 行为 |
| --- | --- | --- |
| `http` | 非 2xx | 展示服务端错误摘要；401/403 引导检查 Key |
| `stream` | SSE 内含 error 帧 | 保留已到内容，标记本轮失败 |
| `network` | 断网、DNS、TLS | 可重试，不清空草稿 |
| `interrupted` | 无 `message_stop` 提前结束 | 标记“连接中断”，提供续写/重试 |
| `protocol` | 非 SSE、帧不合法 | 记录本地诊断（无 Key），提示协议错误 |

不要把 `error.message` 原样无限长展示；截断到合理长度并提供复制诊断（不包含敏感 Header）。

## 10. 严禁项

- 不将 API Key 放进 URL、WebView、日志、分析事件或导出历史。
- 不使用官方 App 私有登录 Header。
- 不调用 `/api/v0/chat/*` 作为产品依赖。
- 不把 `Authorization`、`x-api-key`、`anthropic-beta` 等 Header 写入错误报告。
- 不把服务端加密的 citation/signature 当成可解析明文。

