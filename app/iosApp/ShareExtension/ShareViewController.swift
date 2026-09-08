import UIKit
import UniformTypeIdentifiers

private enum ShareLocalization {
    private static let appGroupIdentifier = "group.com.folderspan.FolderSpan"
    private static let languageKey = "settings.appearance.language"

    private static var languageCode: String {
        let mode = UserDefaults(suiteName: appGroupIdentifier)?.string(forKey: languageKey)
        switch mode {
        case "SimplifiedChinese":
            return "zh-Hans"
        case "English":
            return "en"
        default:
            let preferred = Locale.preferredLanguages.first?.replacingOccurrences(of: "_", with: "-").lowercased() ?? ""
            let components = preferred.split(separator: "-").map(String.init)
            let isSimplified = components.first == "zh"
                && !components.contains("hant")
                && !components.contains(where: { ["tw", "hk", "mo"].contains($0) })
                && (components.count == 1
                    || components.contains("hans")
                    || components.contains(where: { ["cn", "sg"].contains($0) }))
            return isSimplified ? "zh-Hans" : "en"
        }
    }

    private static var bundle: Bundle {
        guard let path = Bundle.main.path(forResource: languageCode, ofType: "lproj"),
              let localizedBundle = Bundle(path: path) else {
            return Bundle.main
        }
        return localizedBundle
    }

    static func text(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(
            format: text(key),
            locale: Locale(identifier: languageCode),
            arguments: arguments
        )
    }
}

final class ShareViewController: UIViewController {
    private enum Palette {
        static let brand = UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.65, green: 0.84, blue: 0.34, alpha: 1)
                : UIColor(red: 0.24, green: 0.40, blue: 0.00, alpha: 1)
        }
        static let brandSurface = UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.13, green: 0.18, blue: 0.08, alpha: 1)
                : UIColor(red: 0.94, green: 0.98, blue: 0.88, alpha: 1)
        }
    }

    private let appIconImageView = UIImageView()
    private let brandLabel = UILabel()
    private let stateIconContainer = UIView()
    private let stateIcon = UIImageView()
    private let statusLabel = UILabel()
    private let detailLabel = UILabel()
    private let activityIndicator = UIActivityIndicatorView(style: .medium)
    private let doneButton = UIButton(type: .system)
    private let footnoteLabel = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        importAttachments()
    }

    private func configureView() {
        view.backgroundColor = UIColor { traits in
            traits.userInterfaceStyle == .dark ? .systemBackground : UIColor(red: 0.98, green: 0.99, blue: 0.96, alpha: 1)
        }

        configureBrandIcon()

        brandLabel.font = .preferredFont(forTextStyle: .title2).withWeight(.bold)
        brandLabel.textColor = Palette.brand
        brandLabel.text = "FolderSpan"
        brandLabel.textAlignment = .center
        brandLabel.adjustsFontForContentSizeCategory = true

        let brandStack = UIStackView(arrangedSubviews: [appIconImageView, brandLabel])
        brandStack.axis = .horizontal
        brandStack.alignment = .center
        brandStack.spacing = 14

        stateIconContainer.backgroundColor = Palette.brandSurface
        stateIconContainer.layer.cornerRadius = 40
        stateIconContainer.translatesAutoresizingMaskIntoConstraints = false

        stateIcon.contentMode = .scaleAspectFit
        stateIcon.tintColor = Palette.brand
        stateIcon.translatesAutoresizingMaskIntoConstraints = false
        stateIconContainer.addSubview(stateIcon)

        activityIndicator.color = Palette.brand
        activityIndicator.hidesWhenStopped = true
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        stateIconContainer.addSubview(activityIndicator)
        activityIndicator.startAnimating()

        statusLabel.font = .preferredFont(forTextStyle: .title2).withWeight(.bold)
        statusLabel.text = ShareLocalization.text("share_importing_title")
        statusLabel.textAlignment = .center
        statusLabel.adjustsFontForContentSizeCategory = true

        detailLabel.font = .preferredFont(forTextStyle: .subheadline)
        detailLabel.textColor = .secondaryLabel
        detailLabel.numberOfLines = 0
        detailLabel.textAlignment = .center
        detailLabel.text = ShareLocalization.text("share_reading_files")
        detailLabel.adjustsFontForContentSizeCategory = true

        doneButton.setTitle(ShareLocalization.text("share_done"), for: .normal)
        doneButton.backgroundColor = Palette.brand
        doneButton.tintColor = .white
        doneButton.setTitleColor(.white, for: .normal)
        doneButton.layer.cornerRadius = 16
        doneButton.layer.cornerCurve = .continuous
        doneButton.contentEdgeInsets = UIEdgeInsets(top: 15, left: 24, bottom: 15, right: 24)
        doneButton.titleLabel?.font = .preferredFont(forTextStyle: .headline).withWeight(.semibold)
        doneButton.accessibilityIdentifier = "share-extension-done-button"
        doneButton.isHidden = true
        doneButton.addTarget(self, action: #selector(finish), for: .touchUpInside)

        footnoteLabel.font = .preferredFont(forTextStyle: .footnote)
        footnoteLabel.textColor = .tertiaryLabel
        footnoteLabel.numberOfLines = 0
        footnoteLabel.textAlignment = .center
        footnoteLabel.adjustsFontForContentSizeCategory = true
        footnoteLabel.isHidden = true

        let stack = UIStackView(arrangedSubviews: [
            brandStack,
            stateIconContainer,
            statusLabel,
            detailLabel,
            doneButton,
            footnoteLabel,
        ])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.setCustomSpacing(30, after: brandStack)
        stack.setCustomSpacing(24, after: stateIconContainer)
        stack.setCustomSpacing(8, after: statusLabel)
        stack.setCustomSpacing(28, after: detailLabel)
        stack.setCustomSpacing(12, after: doneButton)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -28),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor, constant: -8),
            stack.topAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.topAnchor, constant: 32),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            appIconImageView.widthAnchor.constraint(equalToConstant: 56),
            appIconImageView.heightAnchor.constraint(equalToConstant: 56),
            detailLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            doneButton.widthAnchor.constraint(equalTo: stack.widthAnchor),
            footnoteLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            stateIconContainer.widthAnchor.constraint(equalToConstant: 80),
            stateIconContainer.heightAnchor.constraint(equalToConstant: 80),
            stateIconContainer.centerXAnchor.constraint(equalTo: stack.centerXAnchor),
            stateIcon.widthAnchor.constraint(equalToConstant: 44),
            stateIcon.heightAnchor.constraint(equalToConstant: 44),
            stateIcon.centerXAnchor.constraint(equalTo: stateIconContainer.centerXAnchor),
            stateIcon.centerYAnchor.constraint(equalTo: stateIconContainer.centerYAnchor),
            activityIndicator.centerXAnchor.constraint(equalTo: stateIconContainer.centerXAnchor),
            activityIndicator.centerYAnchor.constraint(equalTo: stateIconContainer.centerYAnchor),
            doneButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 52),
        ])
    }

    private func configureBrandIcon() {
        appIconImageView.contentMode = .scaleAspectFill
        appIconImageView.clipsToBounds = true
        appIconImageView.layer.cornerRadius = 14
        appIconImageView.layer.cornerCurve = .continuous
        appIconImageView.isAccessibilityElement = true
        appIconImageView.accessibilityLabel = ShareLocalization.text("share_app_icon")

        if let appIcon = Self.loadContainingAppIcon() {
            appIconImageView.image = appIcon
        } else {
            appIconImageView.image = UIImage(systemName: "folder.fill")
            appIconImageView.contentMode = .center
            appIconImageView.backgroundColor = Palette.brand
            appIconImageView.tintColor = .white
            appIconImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 25, weight: .semibold)
        }
    }

    private static func loadContainingAppIcon() -> UIImage? {
        let containingAppURL = Bundle.main.bundleURL
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        guard let fileNames = try? FileManager.default.contentsOfDirectory(
            at: containingAppURL,
            includingPropertiesForKeys: nil
        ) else {
            return nil
        }

        return fileNames
            .filter { $0.lastPathComponent.hasPrefix("AppIcon") && $0.pathExtension.lowercased() == "png" }
            .compactMap { UIImage(contentsOfFile: $0.path) }
            .max { lhs, rhs in
                lhs.size.width * lhs.size.height < rhs.size.width * rhs.size.height
            }
    }

    private func importAttachments() {
        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        let providers = items.flatMap { item -> [NSItemProvider] in
            let attachments = item.attachments ?? []
            if !attachments.isEmpty { return attachments }
            guard let text = item.attributedContentText?.string,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
            return [NSItemProvider(object: text as NSString)]
        }.filter {
            ExternalTextProvider.typeIdentifier(for: $0) != nil
                || ShareAttachmentLoader.typeIdentifier(for: $0) != nil
        }

        guard !providers.isEmpty else {
            showResult(
                importedCount: 0,
                failedCount: 0,
                errorMessage: ShareLocalization.text("share_no_files")
            )
            return
        }

        detailLabel.text = ShareLocalization.format("share_reading_count", providers.count)
        guard let writer = SharedImportBatchWriter() else {
            showResult(
                importedCount: 0,
                failedCount: providers.count,
                errorMessage: ShareLocalization.text("share_group_unavailable")
            )
            return
        }

        let group = DispatchGroup()
        let resultLock = NSLock()
        var importedCount = 0
        var failedCount = 0

        for (index, provider) in providers.enumerated() {
            group.enter()
            ShareAttachmentLoader.load(provider, index: index, writer: writer) { succeeded in
                resultLock.lock()
                if succeeded {
                    importedCount += 1
                } else {
                    failedCount += 1
                }
                resultLock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .global(qos: .userInitiated)) { [weak self] in
            let finalized = importedCount > 0 && writer.finish()
            if !finalized {
                writer.cancel()
            }
            DispatchQueue.main.async {
                self?.showResult(
                    importedCount: finalized ? importedCount : 0,
                    failedCount: finalized ? failedCount : providers.count,
                    errorMessage: finalized ? nil : ShareLocalization.text("share_staging_failed")
                )
            }
        }
    }

    private func showResult(importedCount: Int, failedCount: Int, errorMessage: String?) {
        activityIndicator.stopAnimating()
        stateIcon.isHidden = false
        doneButton.isHidden = false
        footnoteLabel.isHidden = false

        if importedCount > 0 {
            stateIcon.image = UIImage(systemName: failedCount > 0 ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
            stateIcon.tintColor = failedCount > 0 ? .systemOrange : Palette.brand
            statusLabel.text = ShareLocalization.format("share_received_count", importedCount)
            detailLabel.text = failedCount > 0
                ? ShareLocalization.format("share_partial_failure", failedCount)
                : ShareLocalization.text("share_saved")
            footnoteLabel.text = ShareLocalization.text("share_finish_hint")
        } else {
            stateIcon.image = UIImage(systemName: "xmark.circle.fill")
            stateIcon.tintColor = .systemRed
            statusLabel.text = ShareLocalization.text("share_import_failed")
            detailLabel.text = errorMessage ?? ShareLocalization.text("share_read_failed")
            footnoteLabel.text = ShareLocalization.text("share_retry_hint")
        }

        UIAccessibility.post(notification: .screenChanged, argument: statusLabel)
    }

    @objc private func finish() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}

private extension UIFont {
    func withWeight(_ weight: UIFont.Weight) -> UIFont {
        UIFont.systemFont(ofSize: pointSize, weight: weight)
    }
}

private enum ShareAttachmentLoader {
    private static let fallbackTypeIdentifiers = [
        UTType.folder.identifier,
        UTType.fileURL.identifier,
        UTType.content.identifier,
        UTType.item.identifier,
        UTType.data.identifier,
    ]

    static func typeIdentifier(for provider: NSItemProvider) -> String? {
        provider.registeredTypeIdentifiers.first { identifier in
            UTType(identifier)?.conforms(to: .item) == true
        } ?? fallbackTypeIdentifiers.first { provider.hasItemConformingToTypeIdentifier($0) }
    }

    static func load(
        _ provider: NSItemProvider,
        index: Int,
        writer: SharedImportBatchWriter,
        completion: @escaping (Bool) -> Void
    ) {
        if ExternalTextProvider.typeIdentifier(for: provider) != nil {
            ExternalTextProvider.load(provider) { text in
                completion(writer.write(text: text, index: index))
            }
            return
        }
        guard let typeIdentifier = typeIdentifier(for: provider) else {
            completion(false)
            return
        }

        provider.loadInPlaceFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _, _ in
            if let url, writer.copyItem(from: url, suggestedName: provider.suggestedName) {
                completion(true)
                return
            }

            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _ in
                if let url, writer.copyItem(from: url, suggestedName: provider.suggestedName) {
                    completion(true)
                    return
                }

                provider.loadDataRepresentation(forTypeIdentifier: typeIdentifier) { data, _ in
                    completion(writer.write(data: data, suggestedName: provider.suggestedName))
                }
            }
        }
    }
}

private final class SharedImportBatchWriter {
    private static let appGroupIdentifier = "group.com.folderspan.FolderSpan"
    private static let importDirectoryName = "share-import"

    private let fileManager = FileManager.default
    private let lock = NSLock()
    private let stagingURL: URL
    private let pendingURL: URL
    private var reservedNames: Set<String> = [".folderspan-texts.json"]
    private var texts: [Int: String] = [:]

    init?() {
        guard let groupURL = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier
        ) else {
            return nil
        }

        let importRoot = groupURL.appendingPathComponent(Self.importDirectoryName, isDirectory: true)
        let stagingRoot = importRoot.appendingPathComponent("staging", isDirectory: true)
        let pendingRoot = importRoot.appendingPathComponent("pending", isDirectory: true)
        let batchID = UUID().uuidString
        stagingURL = stagingRoot.appendingPathComponent(batchID, isDirectory: true)
        pendingURL = pendingRoot.appendingPathComponent(batchID, isDirectory: true)

        do {
            try fileManager.createDirectory(at: stagingRoot, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: pendingRoot, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: stagingURL, withIntermediateDirectories: true)
        } catch {
            NSLog("Share extension staging setup failed")
            return nil
        }
    }

    func copyItem(from sourceURL: URL, suggestedName: String?) -> Bool {
        let destinationURL = reserveDestination(
            sourceName: sourceURL.lastPathComponent,
            suggestedName: suggestedName
        )
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        do {
            try fileManager.copyItem(at: sourceURL, to: destinationURL)
            return true
        } catch {
            NSLog("Share extension copy failed")
            return false
        }
    }

    func write(data: Data?, suggestedName: String?) -> Bool {
        guard let data else { return false }
        let destinationURL = reserveDestination(sourceName: nil, suggestedName: suggestedName)
        do {
            try data.write(to: destinationURL, options: .atomic)
            return true
        } catch {
            NSLog("Share extension data write failed")
            return false
        }
    }

    func write(text: String?, index: Int) -> Bool {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }
        texts[index] = text
        return true
    }

    func finish() -> Bool {
        do {
            if !texts.isEmpty {
                let orderedTexts = texts.keys.sorted().compactMap { texts[$0] }
                let data = try JSONEncoder().encode(orderedTexts)
                try data.write(to: stagingURL.appendingPathComponent(".folderspan-texts.json"), options: .atomic)
            }
            try fileManager.moveItem(at: stagingURL, to: pendingURL)
            return true
        } catch {
            NSLog("Share extension finalize failed")
            return false
        }
    }

    func cancel() {
        try? fileManager.removeItem(at: stagingURL)
    }

    private func reserveDestination(sourceName: String?, suggestedName: String?) -> URL {
        lock.lock()
        defer { lock.unlock() }

        let requestedName = sanitize(sourceName) ?? sanitize(suggestedName) ?? "shared-file"
        let requestedURL = URL(fileURLWithPath: requestedName)
        let baseName = requestedURL.deletingPathExtension().lastPathComponent
        let pathExtension = requestedURL.pathExtension
        var candidate = requestedName
        var suffix = 2

        while reservedNames.contains(candidate) || fileManager.fileExists(atPath: stagingURL.appendingPathComponent(candidate).path) {
            candidate = pathExtension.isEmpty
                ? "\(baseName)-\(suffix)"
                : "\(baseName)-\(suffix).\(pathExtension)"
            suffix += 1
        }

        reservedNames.insert(candidate)
        return stagingURL.appendingPathComponent(candidate)
    }

    private func sanitize(_ name: String?) -> String? {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { return nil }
        let invalid = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/:"))
        let cleaned = trimmed.unicodeScalars.map { invalid.contains($0) ? "-" : String($0) }.joined()
        return cleaned.isEmpty ? nil : cleaned
    }
}
