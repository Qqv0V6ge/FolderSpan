import Foundation
import ComposeApp

enum IosSharedImportCoordinator {
    private static let appGroupIdentifier = "group.com.folderspan.FolderSpan"
    private static let importDirectoryName = "share-import"
    private static let queue = DispatchQueue(label: "com.folderspan.shared-import", qos: .userInitiated)

    static func importPendingFiles() {
        queue.async {
            let (urls, texts) = claimPendingFiles()
            guard !urls.isEmpty || !texts.isEmpty else { return }

            DispatchQueue.main.async {
                if !urls.isEmpty {
                    IosDocumentOpenHandlerKt.handleIosShareExtensionFiles(urls: urls)
                }
                if !texts.isEmpty {
                    _ = ClipboardTextOpenBus.shared.publish(text: texts.joined(separator: "\n"))
                }
            }
        }
    }

    private static func claimPendingFiles() -> ([URL], [String]) {
        let fileManager = FileManager.default
        guard let groupURL = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            return ([], [])
        }

        let pendingRoot = groupURL
            .appendingPathComponent(importDirectoryName, isDirectory: true)
            .appendingPathComponent("pending", isDirectory: true)
        guard let batchURLs = try? fileManager.contentsOfDirectory(
            at: pendingRoot,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return ([], [])
        }

        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let claimedRoot = applicationSupport
            .appendingPathComponent("FolderSpan", isDirectory: true)
            .appendingPathComponent("Shared Imports", isDirectory: true)
        try? fileManager.createDirectory(at: claimedRoot, withIntermediateDirectories: true)

        var importedURLs: [URL] = []
        var importedTexts: [String] = []
        for batchURL in batchURLs.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let destinationBatch = uniqueBatchURL(in: claimedRoot, preferredName: batchURL.lastPathComponent)
            do {
                try fileManager.moveItem(at: batchURL, to: destinationBatch)
            } catch {
                do {
                    try fileManager.copyItem(at: batchURL, to: destinationBatch)
                    try fileManager.removeItem(at: batchURL)
                } catch {
                    NSLog("Shared import claim failed")
                    continue
                }
            }

            let textURL = destinationBatch.appendingPathComponent(".folderspan-texts.json")
            if let data = try? Data(contentsOf: textURL),
               let texts = try? JSONDecoder().decode([String].self, from: data) {
                importedTexts.append(contentsOf: texts)
                try? fileManager.removeItem(at: textURL)
            }
            let files = (try? fileManager.contentsOfDirectory(
                at: destinationBatch,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            )) ?? []
            importedURLs.append(contentsOf: files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }))
            if files.isEmpty {
                try? fileManager.removeItem(at: destinationBatch)
            }
        }
        return (importedURLs, importedTexts)
    }

    private static func uniqueBatchURL(in root: URL, preferredName: String) -> URL {
        let fileManager = FileManager.default
        var candidate = root.appendingPathComponent(preferredName, isDirectory: true)
        var suffix = 2
        while fileManager.fileExists(atPath: candidate.path) {
            candidate = root.appendingPathComponent("\(preferredName)-\(suffix)", isDirectory: true)
            suffix += 1
        }
        return candidate
    }
}
