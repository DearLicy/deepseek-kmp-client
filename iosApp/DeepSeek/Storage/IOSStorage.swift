import Foundation
import ImageIO
import Security
import UniformTypeIdentifiers
import DeepSeekShared

/// Keychain holds the configured credential; Application Support holds conversation JSON only.
final class IOSStorage: NSObject, NativeStorage {
    private let service = "com.deepseek.client.api-key.v1"
    private let lock = NSLock()
    private var keyQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service, kSecAttrAccount as String: "default"]
    }
    func readKey() -> NativeRead {
        lock.lock(); defer { lock.unlock() }
        var query = keyQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return NativeRead(value: nil, failed: false) }
        guard status == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else { return NativeRead(value: nil, failed: true) }
        return NativeRead(value: value, failed: false)
    }
    func writeKey(value: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let data = value.data(using: .utf8), !data.isEmpty, data.count < 8192 else { return false }
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(keyQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        return SecItemAdd(keyQuery.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
    }
    func clearKey() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let status = SecItemDelete(keyQuery as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
    private func historyURL() throws -> URL {
        var directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true).appendingPathComponent("DeepSeek", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory.appendingPathComponent("history-v1.json")
    }
    func readSnapshot() -> NativeRead {
        lock.lock(); defer { lock.unlock() }
        do {
            let url = try historyURL()
            guard FileManager.default.fileExists(atPath: url.path) else { return NativeRead(value: nil, failed: false) }
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            guard (values.fileSize ?? Int.max) <= 64 * 1024 * 1024 else { return NativeRead(value: nil, failed: true) }
            return NativeRead(value: try String(contentsOf: url, encoding: .utf8), failed: false)
        } catch { return NativeRead(value: nil, failed: true) }
    }
    func writeSnapshot(value: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do {
            let data = Data(value.utf8)
            try data.write(to: historyURL(), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
}

/// Original attachment bytes live beside local history, protected and excluded from backup.
/// Image resizing produces an upload-only copy; the original file is never overwritten.
final class IOSAttachmentStorage: NSObject, NativeAttachmentStorage {
    private let lock = NSLock()
    private let maxImageEdge = 4096

    private func directory() throws -> URL {
        var directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true).appendingPathComponent("DeepSeek/Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory
    }

    private func file(_ id: String) throws -> URL {
        let pattern = try NSRegularExpression(pattern: "^attachment-[A-Za-z0-9-]{1,90}$")
        let range = NSRange(id.startIndex..<id.endIndex, in: id)
        guard pattern.firstMatch(in: id, range: range) != nil else { throw CocoaError(.fileReadInvalidFileName) }
        return try directory().appendingPathComponent(id, isDirectory: false)
    }

    func read(id: String, limit: Int32) -> NativeAttachmentBytes {
        lock.lock(); defer { lock.unlock() }
        do {
            let values = try file(id).resourceValues(forKeys: [.fileSizeKey])
            guard let size = values.fileSize, size > 0, size <= Int(limit) else {
                return NativeAttachmentBytes(value: nil, failed: false)
            }
            return NativeAttachmentBytes(value: try Data(contentsOf: file(id)).kotlinByteArray, failed: false)
        } catch { return NativeAttachmentBytes(value: nil, failed: false) }
    }

    func write(id: String, bytes: KotlinByteArray) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do {
            let data = bytes.dataValue
            guard !data.isEmpty, data.count <= 64 * 1024 * 1024 else { return false }
            try data.write(to: try file(id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }

    func delete(ids: [String]) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do {
            for id in ids { try? FileManager.default.removeItem(at: file(id)) }
            return ids.allSatisfy { !FileManager.default.fileExists(atPath: (try? file($0).path) ?? "") }
        } catch { return false }
    }

    func prepareImage(name: String, mediaType: String, bytes: KotlinByteArray) -> NativePreparedImage? {
        let data = bytes.dataValue
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else { return nil }
        guard max(width, height) > maxImageEdge else {
            return NativePreparedImage(name: name, mediaType: mediaType, bytes: bytes)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxImageEdge,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let outputType: UTType = mediaType == "image/jpeg" ? .jpeg : .png
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, outputType.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 1.0] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        let extensionName = outputType == .jpeg ? "jpg" : "png"
        let stem = name.contains(".") ? String(name[..<name.lastIndex(of: ".")!]) : name
        return NativePreparedImage(name: "\(stem).\(extensionName)", mediaType: outputType.preferredMIMEType ?? mediaType,
            bytes: (output as Data).kotlinByteArray)
    }
}

extension Data {
    var kotlinByteArray: KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { raw in
            let source = raw.bindMemory(to: UInt8.self)
            for index in source.indices { result.set(index: Int32(index), value: Int8(bitPattern: source[index])) }
        }
        return result
    }
}

private extension KotlinByteArray {
    var dataValue: Data {
        var data = Data(capacity: Int(size))
        for index in 0..<Int(size) { data.append(UInt8(bitPattern: get(index: Int32(index)))) }
        return data
    }
}
