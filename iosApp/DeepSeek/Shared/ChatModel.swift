import Foundation
import DeepSeekShared

struct ViewState: Decodable {
    var ready = false, configured = false, keyChecking = false
    var keyMasked: String?, keyError: String?, storageError: String?
    var draft = "", thinking = false, search = false, theme = "system", fontScale: Float = 1
    var selectedId: String?, activeId: String?, notice: String?
    var attachments: [AttachmentView] = []
    var preparingAttachments = false
    var sessions: [SessionView] = []
    var selected: SessionView? { sessions.first { $0.id == selectedId } }
}
struct SessionView: Decodable, Identifiable {
    let id: String, title: String, pinned: Bool, updatedAt: Int64
    let messages: [MessageView]
}
struct MessageView: Decodable, Identifiable {
    let id: String, role: String, status: String, error: String?
    let blocks: [BlockView], sources: [SourceView]
    var createdAt: Int64 = 0
    var attachments: [AttachmentView] = []
    var text: String { blocks.filter { $0.kind == "text" }.map(\.text).joined(separator: "\n\n") }
}
struct BlockView: Decodable, Identifiable {
    let id: String, kind: String, text: String, collapsed: Bool
    let startedAt: Int64?, endedAt: Int64?, status: String?
    let citations: [CitationView]
}
struct AttachmentView: Decodable, Identifiable { let id: String, name: String, mediaType: String, sizeBytes: Int }
struct CitationView: Decodable { let url: String, title: String?, citedText: String }
struct SourceView: Decodable, Identifiable {
    let url: String, title: String, siteName: String, publishedAt: String?, snippet: String?, siteIcon: String?, index: Int
    var id: String { url }
    var link: URL? { guard let value = URL(string: url), ["https", "http"].contains(value.scheme?.lowercased() ?? "") else { return nil }; return value }
}

@MainActor
final class ChatModel: ObservableObject {
    @Published private(set) var state = ViewState()
    @Published private(set) var bridgeError: String?
    let client: NativeClient
    private let decoder = JSONDecoder()
    init() {
        client = NativeClient(storage: IOSStorage(), files: IOSAttachmentStorage())
        client.start { [weak self] json in
            // NativeClient emits on Dispatchers.Main; do not reorder snapshots in independent Tasks.
            MainActor.assumeIsolated {
                guard let self else { return }
                do { self.state = try self.decoder.decode(ViewState.self, from: Data(json.utf8)); self.bridgeError = nil }
                catch { self.bridgeError = "共享状态无法读取，请检查客户端版本" }
            }
        }
    }
}
