# 第三方来源说明

- `docs/evidence/chat-demo/` 是 `deepseek-ai/chat-demo` 固定 commit `429af5a4558469496b231631e850fbccc0c30c87` 的选定参考文件。MIT 许可原文保存在该目录 `LICENSE`；发布时保留适用版权与许可。
- `assets/original/` 与 `assets/derived/` 来自用户提供的 APK `489ff75c6debfce94fcb247595969b71314522c736b11548eb27c845a5f4dce4`。APK 的图形、音频、品牌资源不因 chat-demo 开源而自动成为 MIT 素材。每个正式文件来源见 `assets/MANIFEST.json`。
- v0.1.3 仅做增量附件行为审计，引用文件级 blob 哈希保存在 `docs/evidence/chat-demo/SUPPLEMENTAL-v0.1.3.json`；没有把该版本整包当作固定源码快照。
- 用户截图仅用于设计参考，不自动复制到 App 安装包；其中个人资料与会话不作为成品示例数据。
- Gradle Wrapper 属于 Gradle 启动组件（Apache-2.0）。当前 wrapper JAR 使用本机已有副本引导 Gradle 8.14.6；校验与升级需保留上游许可。

当前没有发布、上传仓库、请求发布凭证或连接任何用户会话服务。

- 本项目原创代码采用根目录 `LICENSE` 的 MIT License；官方 App 提取素材不在该授权范围内。
