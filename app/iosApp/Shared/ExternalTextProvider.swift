import Foundation
import UniformTypeIdentifiers

/// 文件表示优先；文本和网页 URL 复用应用的“从剪贴板打开”流程。
enum ExternalTextProvider {
    static func typeIdentifier(for provider: NSItemProvider) -> String? {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
            || provider.hasItemConformingToTypeIdentifier(UTType.folder.identifier)
            || provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return nil
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            return UTType.url.identifier
        }
        if let name = provider.suggestedName,
           let type = UTType(filenameExtension: (name as NSString).pathExtension),
           type.conforms(to: .text) {
            return nil
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            return UTType.plainText.identifier
        }
        return nil
    }

    static func load(_ provider: NSItemProvider, completion: @escaping (String?) -> Void) {
        guard let type = typeIdentifier(for: provider) else {
            completion(nil)
            return
        }
        provider.loadItem(forTypeIdentifier: type, options: nil) { item, _ in
            let text: String?
            if let url = item as? URL {
                text = url.isFileURL ? nil : url.absoluteString
            } else if let string = item as? String {
                text = string
            } else if let attributed = item as? NSAttributedString {
                text = attributed.string
            } else if let data = item as? Data {
                text = String(data: data, encoding: .utf8)
            } else {
                text = nil
            }
            completion(text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? text : nil)
        }
    }
}
