# 用户提供的分享页建议核对

## 来源性质

用户提供 `https://chat.deepseek.com/share/e5qy9ztc48rqkhle9m`，本地采集内容保存在 `.tmp/extracted/official-advice.txt`，对应采集证据在 `.tmp/rea/official-advice.json`。这是 DeepSeek 分享的一段 AI 生成对话，页面本身有 AI 内容提示；不是 SDK 文档或具有执行优先级的工程指令。

## 采用与调整

| 分享页建议 | 本项目决定 | 原因 |
| --- | --- | --- |
| KMP + Compose Multiplatform 共享 UI | KMP 共享逻辑，Android Compose，iOS SwiftUI/UIKit | 用户明确要求双端原生 UI |
| MVI 单向数据流 | 采用 | 将消息、工具、录音、上传和错误归约到共享状态 |
| `choices[].delta.reasoningContent` | 不作为本项目主协议 | 固定版 chat-demo 用 Anthropic-compatible 内容块事件 |
| 根据开关改 system prompt 实现搜索/思考 | 使用实际 `thinking`、`tools` 字段 | 提示词不是搜索服务调用 |
| `prompt-bar`、`llm-typewriter` | 不直接安装 | 分享页未给出可复核坐标/版本；也不符合共享逻辑、原生 UI 的边界 |
| Ktor、SQLDelight、Koin | 作为技术候选 | 共享层先隔离 transport/storage；避免未实现前宣称依赖已落地 |
| 大圆角、思考折叠、按住录音、侧栏 | 采用截图语义 | 由截图、资源及 APK 组件信息交叉约束 |
| 固定颜色与时长 | 按证据与测量分别记录 | Android 资源色与 iOS 截图色不同；截图无法证明时长 |

没有下载 Whaleness 或 dsh-mobile，没有连接 DSH。原文中与用户后续范围冲突的建议不覆盖用户要求。

## 网络与音色

固定版 chat-demo 可作为请求格式参考，但并未使用真实 API Key 做线上对话/文件/语音验证。

官方 APK 静态模型提供 `voice_id`、多语言名称和描述、试听 URL、默认标记及色板；截图有四个分页点。当前示例枚举 `stella/tide`。这不足以推出第三、第四个可用公开 ID，也不足以证明官方四项中的任意一项与示例两项相同。后续只有拿到公开能力说明或真实公开端点返回才能补齐映射，不调用依赖用户登录的私有会话来填补。
