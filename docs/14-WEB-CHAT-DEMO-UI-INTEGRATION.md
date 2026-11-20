# Web 直接复用 chat-demo UI

更新日期：2026-11-20。

## 结论

Web 不再维护项目自写的页面和设计系统。`web/src/app/`、`web/src/components/`、`web/src/i18n/`、`web/src/utils/` 来自 `deepseek-ai/chat-demo` v0.1.2，保留其 MIT License、React 组件结构、CSS Modules、Markdown、思考块、联网搜索、侧栏、输入框和设置弹窗。

业务状态仍只有一份：chat-demo 组件通过适配后的 Zustand store 读取 KMP `BrowserClient` 的 JSON 投影；用户操作再转成 KMP `ChatIntent`。因此 Web、Android 和 iOS 共享同一套 MVI reducer、协议解析、附件状态和会话快照语义。

## 数据流

```text
chat-demo React UI
  -> useChatStore / useSettings / useDrafts 适配层
  -> BrowserClient.start() 的 NativeViewState JSON
  -> KMP ChatController (MVI Intent -> Action -> State -> Effect)
  -> DeepSeek Messages / Files / SSE
```

发送链路：

```text
原版 ChatInput
  -> setDraftText/addDraftFiles
  -> BrowserClient.input/addFiles
  -> MVI SendMessage
  -> DeepSeekApi.streamMessage
  -> NativeProjection.encode
  -> 原版 AssistantMessage / ReplyArea / 搜索来源组件
```

## 适配边界

| chat-demo 接口 | 本项目实现 |
| --- | --- |
| `useChatStore` | 把 KMP 会话、消息、块、来源和流式状态映射成原组件需要的 `Session` / `Message` |
| `useSettings` | 主题、思考、搜索、API Key 状态转给 KMP；语言、位置和朗读音色仍保存在 Web 本地设置 |
| `useDrafts` | `drafts` 与 `draftAttachments` 双向映射到 KMP 快照，保证刷新与切换会话后仍是同一状态 |
| 文件选择/移除 | 原版按钮和附件卡片直接调用 KMP `addFiles` / `removeAttachment` |
| API Key 校验 | `BrowserClient.validateAndSaveKey()` 使用同一 `/models` 校验逻辑，Key 只存当前标签页 `sessionStorage` |
| SSE 正文 | KMP `ContentBlock.Text` 映射为原版 `TextBlock`，引用转为 `web_search_result_location` |
| 思考 | KMP `ContentBlock.Thinking` 映射为原版 thinking part，并由原版折叠组件显示 |
| 联网搜索 | KMP `ContentBlock.Search` / `ReadLink` 与 `Source` 映射为原版工具时间线、来源卡片和引用角标 |

## 已验证

以下验证均在本机 Linux 和 Chromium 完成：

1. `npm --prefix web run check`：Node 测试、TypeScript、Vite 生产构建通过。
2. 1440×1000 与 390×844：chat-demo 欢迎页、桌面侧栏、移动端顶栏、输入框和两个功能开关正常显示。
3. 通过原版隐藏文件输入添加 `qa-note.txt`：附件卡片出现，删除后 DOM 与 KMP 草稿状态均清空。
4. 用 CDP 拦截 DeepSeek 网络为 mock：设置页输入 Key、`/models` 校验、原版输入框发送、KMP SSE、正文回显整条链路通过。
5. 同一 mock 链路加入 thinking、`server_tool_use`、`web_search_tool_result`、citation：原版“已思考”“已阅读 1 个网页”、引用角标和来源汇总均正常渲染。

验证截图位于 `.tmp/web-qa/`；临时脚本、Chromium profile、构建与日志均不进入正式源码。

## 当前限制

- chat-demo 的 IndexedDB ZIP 历史导入尚未迁移到 KMP 快照格式，设置页会明确提示暂不支持，不写入无法恢复的数据。
- Web 朗读与语音转写仍是占位适配；尚未接入共享语音能力和四个音色。
- 上述 E2E 使用本地 mock，不证明真实 API Key 的账号权限、Files 配额、TTS/ASR 或服务端搜索能力。
- UI 源码是 v0.1.2；v0.1.3 的附件增量已落实到 KMP 逻辑和 Web 文件适配层，没有另行修改原版视觉。
