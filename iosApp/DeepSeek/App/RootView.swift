import SwiftUI
import DeepSeekShared
import UIKit
import UniformTypeIdentifiers

struct RootView: View {
    @StateObject private var model = ChatModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var drawer = false
    @State private var settings = false
    @State private var keySheet = false
    @State private var search = ""
    @State private var rename = ""
    @State private var renaming: SessionView?
    @State private var deleting: SessionView?
    @State private var showRename = false
    @State private var showDelete = false
    @State private var deleteAll = false
    @State private var sources: [SourceView] = []
    @State private var showSources = false
    @State private var showImporter = false
    private var state: ViewState { model.state }
    private var client: NativeClient { model.client }

    var body: some View {
        GeometryReader { geometry in
            let width = min(geometry.size.width * 0.82, 360)
            ZStack(alignment: .leading) {
                sidebar.frame(width: width)
                chat
                    .background(Color(.systemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: drawer ? 30 : 0))
                    .shadow(color: .black.opacity(drawer ? 0.1 : 0), radius: 24, x: -8)
                    .offset(x: drawer ? width : 0)
                    .overlay(alignment: .leading) {
                        if drawer {
                            Color.clear.contentShape(Rectangle()).onTapGesture { withAnimation { drawer = false } }
                                .offset(x: width)
                        }
                    }
                    .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { value in
                        guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.9)) {
                            if value.translation.width > 80 { drawer = true; hideKeyboard() }
                            if value.translation.width < -80 { drawer = false }
                        }
                    })
                if let notice = state.notice {
                    Text(notice).foregroundStyle(.white).padding(.horizontal, 20).padding(.vertical, 14)
                        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 20))
                        .frame(maxWidth: .infinity).allowsHitTesting(false)
                        .task(id: notice) { try? await Task.sleep(for: .seconds(2)); client.dismissNotice() }
                }
            }
            .clipped()
        }
        .tint(DSColor.primary)
        .preferredColorScheme(state.theme == "system" ? nil : state.theme == "dark" ? .dark : .light)
        .sheet(isPresented: $settings) { settingsView }
        .sheet(isPresented: $keySheet) { keyView }
        .sheet(isPresented: $showSources) { sourcesView }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): importFiles(urls)
            case .failure: client.showNotice(message: "无法打开所选附件")
            }
        }
        .alert("重命名对话", isPresented: $showRename) {
            TextField("对话名称", text: $rename)
            Button("保存") { if let item = renaming { client.renameSession(id: item.id, title: rename) } }
            Button("取消", role: .cancel) {}
        }
        .alert("删除后，该对话将不可恢复", isPresented: $showDelete) {
            Button("删除该对话", role: .destructive) { if let item = deleting { client.deleteSession(id: item.id) } }
            Button("取消", role: .cancel) {}
        } message: { Text("此操作删除本机保存的对话。") }
        .alert("删除所有本地对话？", isPresented: $deleteAll) {
            Button("删除所有历史对话", role: .destructive) { client.deleteAll() }
            Button("取消", role: .cancel) {}
        }
        .onChange(of: scenePhase) { _, phase in if phase != .active { client.flush() } }
    }

    private var sidebar: some View {
        VStack(spacing: 16) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("搜索对话内容…", text: $search).textInputAutocapitalization(.never)
            }.padding(16).background(Color(.secondarySystemBackground), in: Capsule())
            if state.sessions.isEmpty {
                Spacer()
                Text("暂无对话").font(.title3.bold())
                Text("你与 DeepSeek 的对话\n将展示在这里").foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("开启新对话") { client.newSession(); withAnimation { drawer = false } }.buttonStyle(.borderedProminent).clipShape(Capsule())
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        Text("对话").font(.subheadline).foregroundStyle(.secondary).padding(.leading, 12)
                        ForEach(state.sessions.filter { item in
                            search.isEmpty || item.title.localizedCaseInsensitiveContains(search) || item.messages.contains { $0.text.localizedCaseInsensitiveContains(search) }
                        }.sorted { $0.pinned != $1.pinned ? $0.pinned : $0.updatedAt > $1.updatedAt }) { item in
                            HStack {
                                Button { client.selectSession(id: item.id); withAnimation { drawer = false }; hideKeyboard() } label: {
                                    HStack { if item.pinned { Image(systemName: "pin.fill").font(.caption) }; Text(item.title).lineLimit(1); Spacer() }
                                        .foregroundStyle(state.selectedId == item.id ? DSColor.primary : Color.primary)
                                }
                                Menu {
                                    Button("重命名", systemImage: "pencil") { renaming = item; rename = item.title; showRename = true }
                                    Button(item.pinned ? "取消置顶" : "置顶", systemImage: "pin") { client.pinSession(id: item.id) }
                                    Button("删除", systemImage: "trash", role: .destructive) { deleting = item; showDelete = true }
                                } label: { Image(systemName: "ellipsis").foregroundStyle(.secondary).frame(width: 30, height: 36) }
                            }.padding(.horizontal, 12).padding(.vertical, 7)
                                .background(state.selectedId == item.id ? DSColor.primary.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 18))
                        }
                    }
                }
            }
            HStack {
                Image(systemName: "person.crop.circle").font(.title)
                Text("本地客户端")
                Spacer()
                Button { settings = true } label: { Image(systemName: "ellipsis").padding(10) }.accessibilityLabel("设置")
            }.padding(.vertical, 14)
        }.padding(.horizontal, 16).padding(.top, 12).background(Color(.systemBackground))
    }

    private var chat: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                circleButton("line.3.horizontal.decrease", "对话列表") { hideKeyboard(); withAnimation { drawer.toggle() } }
                Text(state.selected?.title ?? "").font(.headline).lineLimit(1)
                Spacer()
                circleButton("plus.message", "新对话") { client.newSession() }
            }.padding(.horizontal, 16).padding(.vertical, 10)
            if let error = state.storageError ?? model.bridgeError { Text(error).font(.footnote).foregroundStyle(.red).padding(8) }
            if !state.ready { Spacer(); ProgressView("正在载入本地记录"); Spacer() }
            else if let messages = state.selected?.messages, !messages.isEmpty { messageList(messages) }
            else {
                Spacer()
                Image("chat_welcome_logo").resizable().scaledToFit().frame(width: 48, height: 48)
                Text("你好，有什么我能帮你的吗？").font(.system(size: 20, weight: .semibold)).padding(.top, 22)
                Spacer()
            }
            if !state.configured && state.ready {
                Button("配置 API Key 后开始对话") { keySheet = true }.font(.footnote).padding(.bottom, 8)
            }
            composer.padding(.horizontal, 12).padding(.bottom, 12)
        }
    }

    private func messageList(_ messages: [MessageView]) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 30) {
                    ForEach(messages) { message in
                        if message.role == "User" {
                            HStack {
                                Spacer(minLength: 35)
                                VStack(alignment: .trailing, spacing: 8) {
                                    ForEach(message.attachments) { file in
                                        Label(file.name, systemImage: file.mediaType.hasPrefix("image/") ? "photo" : "doc")
                                            .font(.caption).lineLimit(1).padding(.horizontal, 11).padding(.vertical, 7)
                                            .background(.regularMaterial, in: Capsule())
                                    }
                                    if !message.text.isEmpty { Text(message.text).textSelection(.enabled) }
                                }.padding(.horizontal, 18).padding(.vertical, 12)
                                    .background(DSColor.primary.opacity(0.09), in: UnevenRoundedRectangle(topLeadingRadius: 24, bottomLeadingRadius: 24, bottomTrailingRadius: 6, topTrailingRadius: 24))
                            }
                        } else { assistant(message) }
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }.font(.system(size: 17 * CGFloat(state.fontScale))).padding(.horizontal, 24).padding(.top, 30).padding(.bottom, 20)
            }
            .id(state.selectedId)
            .onChange(of: state.selectedId, initial: true) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .onChange(of: messages.count) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .overlay(alignment: .bottomTrailing) {
                Button { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } label: {
                    Image(systemName: "chevron.down").padding(12).background(.regularMaterial, in: Circle()).overlay(Circle().stroke(.gray.opacity(0.2)))
                }.padding(14).accessibilityLabel("滚动到底部")
            }
        }
    }
    private func assistant(_ message: MessageView) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(message.blocks) { block in
                switch block.kind {
                case "thinking":
                    VStack(alignment: .leading, spacing: 12) {
                        Button { client.toggleThinkingBlock(id: block.id) } label: {
                            TimelineView(.periodic(from: .now, by: 1)) { context in
                                let end = block.endedAt ?? Int64(context.date.timeIntervalSince1970 * 1000)
                                let seconds = max(0, (end - (block.startedAt ?? end)) / 1000)
                                HStack { Text(block.endedAt == nil ? "思考中…" : "已思考（用时 \(seconds) 秒）"); Image(systemName: block.collapsed ? "chevron.right" : "chevron.down").font(.caption) }
                            }
                        }.foregroundStyle(.secondary)
                        if !block.collapsed && !block.text.isEmpty {
                            HStack(alignment: .top, spacing: 12) { Rectangle().fill(.gray.opacity(0.25)).frame(width: 1); Text(block.text).foregroundStyle(.secondary).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                case "text": Text(.init(block.text)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                default:
                    Label(block.status == "Wip" ? "正在\(block.kind == "readLink" ? "阅读网页" : "搜索网页")…" : block.status == "Failed" ? "网页处理未完成" : "已获取网页结果", systemImage: "magnifyingglass")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if message.status == "Streaming" && message.blocks.isEmpty { ProgressView() }
            if let error = message.error { Text(error).foregroundStyle(.secondary).font(.footnote) }
            if !message.sources.isEmpty {
                Button { sources = message.sources; showSources = true } label: { Label("\(message.sources.count) 个网页", systemImage: "globe").font(.subheadline).foregroundStyle(.secondary).padding(.horizontal, 14).padding(.vertical, 8).overlay(Capsule().stroke(.gray.opacity(0.2))) }
            }
            if message.status != "Streaming" {
                HStack(spacing: 24) {
                    Button { UIPasteboard.general.string = message.text } label: { Image(systemName: "square.on.square") }.accessibilityLabel("复制回答")
                    Spacer()
                    Button { client.editForResend(id: message.id) } label: { Image(systemName: "pencil") }.accessibilityLabel("编辑问题后重新发送")
                }.foregroundStyle(.secondary).padding(.top, 6)
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 8) {
            if !state.attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 7) {
                        ForEach(state.attachments) { file in
                            HStack(spacing: 6) {
                                Image(systemName: file.mediaType.hasPrefix("image/") ? "photo" : "doc").font(.caption)
                                Text(file.name).lineLimit(1).font(.caption)
                                if state.activeId == nil {
                                    Button { client.removeAttachment(id: file.id) } label: { Image(systemName: "xmark.circle.fill") }
                                        .buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel("移除附件")
                                }
                            }.padding(.horizontal, 10).padding(.vertical, 7).background(.regularMaterial, in: Capsule())
                        }
                    }
                }
            }
            TextField("发消息或添加文件", text: Binding(get: { state.draft }, set: { client.input(text: $0) }), axis: .vertical)
                .lineLimit(1...6).padding(.horizontal, 18).padding(.top, 14).padding(.bottom, 10)
            HStack(spacing: 8) {
                chip("深度思考", "atom", selected: state.thinking) { client.toggleThinking() }
                chip("智能搜索", "globe", selected: state.search) { client.toggleSearch() }
                Spacer(minLength: 4)
                Button { showImporter = true } label: { Image(systemName: "plus").font(.system(size: 18, weight: .medium)).frame(width: 36, height: 36).overlay(Circle().stroke(.gray.opacity(0.24))) }
                    .disabled(state.activeId != nil || state.attachments.count >= 50).accessibilityLabel("添加附件")
                Button {
                    if state.activeId != nil { client.stop() }
                    else if !state.configured { keySheet = true }
                    else { hideKeyboard(); client.send() }
                } label: {
                    Image(systemName: state.activeId == nil ? "arrow.up" : "stop.fill").font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white).frame(width: 36, height: 36).background(DSColor.primary, in: Circle())
                }.disabled(!state.ready || (state.activeId == nil && state.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && state.attachments.isEmpty))
                    .accessibilityLabel(state.activeId == nil ? "发送" : "停止生成")
            }.padding(.horizontal, 12).padding(.bottom, 12)
            if state.preparingAttachments { ProgressView("正在上传附件").font(.caption2).foregroundStyle(.secondary) }
        }.background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28)).overlay(RoundedRectangle(cornerRadius: 28).stroke(.gray.opacity(0.2)))
            .shadow(color: .black.opacity(0.06), radius: 16, y: 8)
    }
    private func importFiles(_ urls: [URL]) {
        Task {
            for url in urls { await importFile(url) }
        }
    }
    private func importFile(_ url: URL) async {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try await Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }.value
            client.stageAttachment(name: url.lastPathComponent, bytes: data.kotlinByteArray)
        } catch { client.showNotice(message: "无法读取附件：\(url.lastPathComponent)") }
    }
    private func chip(_ text: String, _ symbol: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(text, systemImage: symbol).font(.system(size: 13, weight: .medium)).padding(.horizontal, 10).padding(.vertical, 8)
            .foregroundStyle(selected ? DSColor.primary : .secondary).background(selected ? DSColor.primary.opacity(0.08) : .clear, in: Capsule())
            .overlay(Capsule().stroke(selected ? DSColor.primary.opacity(0.2) : .gray.opacity(0.15))) }
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
    private func circleButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 20)).foregroundStyle(Color.primary).frame(width: 44, height: 44)
            .background(.regularMaterial, in: Circle()).overlay(Circle().stroke(.gray.opacity(0.15))) }.accessibilityLabel(label)
    }
    private var keyView: some View { KeySettings(model: model) }
    private var settingsView: some View {
        NavigationStack {
            Form {
                Section("API Key") {
                    Text(state.keyMasked ?? "尚未配置").foregroundStyle(.secondary)
                    NavigationLink("配置 API Key") { KeySettings(model: model) }
                    if state.configured { Button("移除本机 API Key", role: .destructive) { client.clearKey() } }
                }
                Section("应用") {
                    Picker("外观", selection: Binding(get: { state.theme }, set: { client.setTheme(value: $0) })) {
                        Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark")
                    }
                    Text("字体大小 · \(Int(state.fontScale * 100))%")
                    Slider(value: Binding(get: { Double(state.fontScale) }, set: { client.setFontScale(value: Float($0)) }), in: 0.8...1.4, step: 0.1)
                    Text("预览文字大小").font(.system(size: 17 * CGFloat(state.fontScale)))
                }
                Section("本地数据") { Button("删除所有历史对话", role: .destructive) { deleteAll = true } }
                Section { Text("附件会保存在本机，发送时才上传。录音与朗读还未接入；此版本不需要官方账号登录。").font(.footnote).foregroundStyle(.secondary) }
            }.navigationTitle("设置").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { settings = false } } }
        }.presentationCornerRadius(32)
    }
    private var sourcesView: some View {
        NavigationStack {
            List(sources) { source in
                if let url = source.link {
                    Link(destination: url) {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack { Text(source.siteName); if let date = source.publishedAt { Text(date).foregroundStyle(.secondary) }; Spacer(); Text("\(source.index)").foregroundStyle(.secondary) }.font(.caption)
                            Text(source.title).font(.headline).foregroundStyle(Color.primary)
                            if let snippet = source.snippet { Text(snippet).lineLimit(3).font(.subheadline).foregroundStyle(.secondary) }
                        }.padding(.vertical, 12)
                    }
                }
            }.listStyle(.plain).navigationTitle("参考网页").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("完成") { showSources = false } } }
        }.presentationDetents([.large]).presentationDragIndicator(.visible).presentationCornerRadius(32)
    }
    private func hideKeyboard() { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
}

private struct KeySettings: View {
    @ObservedObject var model: ChatModel
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var submitted = false
    @State private var sawChecking = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("API Key", text: $key).textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if let error = model.state.keyError { Text(error).foregroundStyle(.red) }
                    Button { submitted = true; sawChecking = false; model.client.saveKey(value: key) } label: {
                        if model.state.keyChecking { ProgressView() } else { Text("校验并安全保存") }
                    }.disabled(key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.state.keyChecking)
                } footer: { Text("校验会请求 /models。Key 保存在本机 Keychain，不写入会话 JSON。聊天能力仍需实际请求确认。") }
            }.navigationTitle("API Key").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("关闭") { key = ""; dismiss() }.disabled(model.state.keyChecking) } }
        }.interactiveDismissDisabled(model.state.keyChecking)
            .onChange(of: model.state.keyChecking) { _, checking in
                if checking && submitted { sawChecking = true }
                if submitted && sawChecking && !checking && model.state.keyError == nil && model.state.configured { key = ""; dismiss() }
            }
    }
}
