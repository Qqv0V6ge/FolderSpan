import Foundation
import UIKit
import UniformTypeIdentifiers
import ComposeApp

enum IosDropImportCoordinator {
    private static let stagingDirectoryName = "ios-drop-import"
    private static let fileTypeIdentifiers = [
        "public.folder",
        "public.file-url",
    ]
    private static let fallbackTypeIdentifiers = [
        "public.content",
        "public.item",
        "public.data",
    ]

    static func handle(providers: [NSItemProvider]) -> Bool {
        let textProviders = providers.filter { ExternalTextProvider.typeIdentifier(for: $0) != nil }
        let fileProviders = providers.filter { ExternalTextProvider.typeIdentifier(for: $0) == nil }
        let filesStarted = prepare(providers: fileProviders, requireFileIdentity: false) { completedURLs, _ in
            guard !completedURLs.isEmpty else { return }
            IosDropImportHandlerKt.handleIosDroppedFiles(urls: completedURLs.map { $0 as Any })
        }
        if !textProviders.isEmpty {
            let group = DispatchGroup()
            let lock = NSLock()
            var texts = Array<String?>(repeating: nil, count: textProviders.count)
            for (index, provider) in textProviders.enumerated() {
                group.enter()
                ExternalTextProvider.load(provider) { text in
                    lock.lock()
                    texts[index] = text
                    lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                _ = ClipboardTextOpenBus.shared.publish(text: texts.compactMap { $0 }.joined(separator: "\n"))
            }
        }
        return filesStarted || !textProviders.isEmpty
    }

    static func prepareClipboardItems(
        providers: [NSItemProvider],
        fallbackImage: UIImage?,
        completion: @escaping ([URL], Int) -> Void
    ) -> Bool {
        let started = prepare(providers: providers, requireFileIdentity: true) { completedURLs, representedCount in
            if !completedURLs.isEmpty {
                completion(completedURLs, representedCount)
                return
            }
            if let stagedImageURL = stageClipboardImage(fallbackImage) {
                completion([stagedImageURL], max(representedCount, 1))
                return
            }
            completion([], representedCount)
        }
        return started || fallbackImage != nil
    }

    private static func prepare(
        providers: [NSItemProvider],
        requireFileIdentity: Bool,
        completion: @escaping ([URL], Int) -> Void
    ) -> Bool {
        let supportedProviders = providers.filter { provider in
            let exposesURL = provider.hasItemConformingToTypeIdentifier("public.folder")
                || provider.hasItemConformingToTypeIdentifier("public.file-url")
                || provider.canLoadObject(ofClass: URL.self)
            let exposesImage = provider.hasItemConformingToTypeIdentifier(UTType.image.identifier)
                || provider.canLoadObject(ofClass: UIImage.self)
            let hasNamedFileRepresentation = provider.suggestedName?.isEmpty == false
                && preferredTypeIdentifier(for: provider) != nil
            return exposesURL || (!requireFileIdentity && preferredTypeIdentifier(for: provider) != nil)
                || (requireFileIdentity && (hasNamedFileRepresentation || exposesImage))
        }
        guard !supportedProviders.isEmpty else {
            completion([], providers.count)
            return false
        }

        let group = DispatchGroup()
        let resultLock = NSLock()
        var stagedURLs = Array<URL?>(repeating: nil, count: supportedProviders.count)
        for (index, provider) in supportedProviders.enumerated() {
            group.enter()
            loadDroppedItem(from: provider) { stagedURL in
                resultLock.lock()
                stagedURLs[index] = stagedURL
                resultLock.unlock()
                group.leave()
            }
        }
        group.notify(queue: .main) {
            let completedURLs = stagedURLs.compactMap { $0 }
            completion(completedURLs, providers.count)
        }
        return true
    }

    private static func loadDroppedItem(
        from provider: NSItemProvider,
        completion: @escaping (URL?) -> Void
    ) {
        if provider.canLoadObject(ofClass: URL.self),
           !fileTypeIdentifiers.contains(where: { provider.hasItemConformingToTypeIdentifier($0) }) {
            loadURLObjectFallback(from: provider, completion: completion)
            return
        }
        if let typeIdentifier = preferredTypeIdentifier(for: provider) {
            loadDroppedURL(from: provider, typeIdentifier: typeIdentifier, completion: completion)
            return
        }

        loadURLObjectFallback(from: provider, completion: completion)
    }

    private static func preferredTypeIdentifier(for provider: NSItemProvider) -> String? {
        for typeIdentifier in fileTypeIdentifiers where provider.hasItemConformingToTypeIdentifier(typeIdentifier) {
            return typeIdentifier
        }
        if let concreteImageType = provider.registeredTypeIdentifiers.first(where: { typeIdentifier in
            guard let type = UTType(typeIdentifier) else { return false }
            return type.conforms(to: .image) && type.preferredFilenameExtension != nil
        }) {
            return concreteImageType
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return UTType.image.identifier
        }
        for typeIdentifier in fallbackTypeIdentifiers where provider.hasItemConformingToTypeIdentifier(typeIdentifier) {
            return typeIdentifier
        }
        return nil
    }

    private static func loadDroppedURL(
        from provider: NSItemProvider,
        typeIdentifier: String,
        completion: @escaping (URL?) -> Void
    ) {
        provider.loadInPlaceFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _, _ in
            if let stagedURL = stageDroppedItem(
                from: url,
                suggestedName: provider.suggestedName,
                typeIdentifier: typeIdentifier
            ) {
                completion(stagedURL)
                return
            }
            loadFileRepresentationFallback(from: provider, typeIdentifier: typeIdentifier, completion: completion)
        }
    }

    private static func loadFileRepresentationFallback(
        from provider: NSItemProvider,
        typeIdentifier: String,
        completion: @escaping (URL?) -> Void
    ) {
        provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _ in
            if let stagedURL = stageDroppedItem(
                from: url,
                suggestedName: provider.suggestedName,
                typeIdentifier: typeIdentifier
            ) {
                completion(stagedURL)
                return
            }
            loadDataRepresentationFallback(from: provider, typeIdentifier: typeIdentifier, completion: completion)
        }
    }

    private static func loadDataRepresentationFallback(
        from provider: NSItemProvider,
        typeIdentifier: String,
        completion: @escaping (URL?) -> Void
    ) {
        provider.loadDataRepresentation(forTypeIdentifier: typeIdentifier) { data, _ in
            let resolvedName = dataRepresentationName(
                suggestedName: provider.suggestedName,
                typeIdentifier: typeIdentifier
            )
            if let stagedURL = stageDroppedData(data, suggestedName: resolvedName) {
                completion(stagedURL)
                return
            }
            loadURLObjectFallback(from: provider, completion: completion)
        }
    }

    private static func loadURLObjectFallback(
        from provider: NSItemProvider,
        completion: @escaping (URL?) -> Void
    ) {
        guard provider.canLoadObject(ofClass: URL.self) else {
            loadImageObjectFallback(from: provider, completion: completion)
            return
        }

        _ = provider.loadObject(ofClass: URL.self) { object, _ in
            if let stagedURL = stageDroppedItem(from: object, suggestedName: provider.suggestedName) {
                completion(stagedURL)
                return
            }
            loadImageObjectFallback(from: provider, completion: completion)
        }
    }

    private static func loadImageObjectFallback(
        from provider: NSItemProvider,
        completion: @escaping (URL?) -> Void
    ) {
        guard provider.canLoadObject(ofClass: UIImage.self) else {
            completion(nil)
            return
        }

        _ = provider.loadObject(ofClass: UIImage.self) { object, _ in
            completion(stageClipboardImage(object as? UIImage))
        }
    }

    private static func dataRepresentationName(suggestedName: String?, typeIdentifier: String) -> String? {
        if let sanitizedName = sanitize(name: suggestedName),
           !URL(fileURLWithPath: sanitizedName).pathExtension.isEmpty {
            return sanitizedName
        }
        return clipboardImageName(typeIdentifier: typeIdentifier) ?? sanitize(name: suggestedName)
    }

    private static func clipboardImageName(typeIdentifier: String) -> String? {
        guard let type = UTType(typeIdentifier), type.conforms(to: .image) else {
            return nil
        }
        let extensionName = sanitizeFilenameExtension(type.preferredFilenameExtension) ?? "png"
        let epochMillis = Int(Date().timeIntervalSince1970 * 1_000)
        return "clipboard-image-\(epochMillis).\(extensionName)"
    }

    private static func sanitizeFilenameExtension(_ value: String?) -> String? {
        let normalized = value?.lowercased() ?? ""
        guard !normalized.isEmpty,
              normalized.count <= 8,
              normalized.allSatisfy({ $0.isLetter || $0.isNumber }) else {
            return nil
        }
        return normalized == "jpeg" ? "jpg" : normalized
    }

    private static func stageClipboardImage(_ image: UIImage?) -> URL? {
        guard let data = image?.pngData() else {
            return nil
        }
        return stageDroppedData(data, suggestedName: clipboardImageName(typeIdentifier: UTType.png.identifier))
    }

    private static func stageDroppedItem(
        from sourceURL: URL?,
        suggestedName: String?,
        typeIdentifier: String? = nil
    ) -> URL? {
        guard let sourceURL else {
            return nil
        }
        let itemType = typeIdentifier.flatMap { UTType($0) }
        let isImageRepresentation = itemType?.conforms(to: .image) == true
        let sourceName = sourceURL.pathExtension.isEmpty && isImageRepresentation
            ? nil
            : sourceURL.lastPathComponent
        let resolvedSuggestedName = typeIdentifier.map {
            dataRepresentationName(suggestedName: suggestedName, typeIdentifier: $0)
        } ?? suggestedName
        guard let targetURL = makeStagingURL(
            sourceName: sourceName,
            suggestedName: resolvedSuggestedName
        ) else {
            return nil
        }

        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        do {
            try FileManager.default.copyItem(at: sourceURL, to: targetURL)
            return targetURL
        } catch {
            NSLog("iOS drop import staging copy failed: %@", error.localizedDescription)
            return nil
        }
    }

    private static func stageDroppedData(_ data: Data?, suggestedName: String?) -> URL? {
        guard let data, let targetURL = makeStagingURL(sourceName: nil, suggestedName: suggestedName) else {
            return nil
        }

        do {
            try data.write(to: targetURL, options: .atomic)
            return targetURL
        } catch {
            NSLog("iOS drop import staging write failed: %@", error.localizedDescription)
            return nil
        }
    }

    private static func makeStagingURL(sourceName: String?, suggestedName: String?) -> URL? {
        let fileManager = FileManager.default
        let stagingRoot = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent(stagingDirectoryName, isDirectory: true)
        let containerURL = stagingRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let resolvedName = sanitize(name: sourceName) ?? sanitize(name: suggestedName) ?? "dropped"

        do {
            try fileManager.createDirectory(at: containerURL, withIntermediateDirectories: true, attributes: nil)
            return containerURL.appendingPathComponent(resolvedName)
        } catch {
            NSLog("iOS drop import staging directory creation failed: %@", error.localizedDescription)
            return nil
        }
    }

    private static func sanitize(name: String?) -> String? {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else {
            return nil
        }
        return trimmed.replacingOccurrences(of: "/", with: "-")
    }
}

final class IosClipboardFilePasteBridge {
    static let shared = IosClipboardFilePasteBridge()

    private var observer: NSObjectProtocol?

    func start() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: Notification.Name("com.folderspan.clipboard.file-paste-request"),
            object: nil,
            queue: .main
        ) { _ in
            let pasteboard = UIPasteboard.general
            _ = IosDropImportCoordinator.prepareClipboardItems(
                providers: pasteboard.itemProviders,
                fallbackImage: pasteboard.image
            ) { urls, representedCount in
                IosClipboardFilePasteBridgeKt.completeIosClipboardFileProviderRequest(
                    urls: urls.map { $0 as Any },
                    representedItemCount: Int32(representedCount)
                )
            }
        }
    }
}
