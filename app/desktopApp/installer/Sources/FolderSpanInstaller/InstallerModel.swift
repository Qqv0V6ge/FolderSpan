import AppKit
import Foundation
import InstallerCore

@MainActor
final class InstallerModel: ObservableObject {
    @Published private(set) var isInstalling = false
    @Published private(set) var installedApplication: URL?
    @Published private(set) var message: String?
    @Published private(set) var hasError = false
    @Published private(set) var directory = URL(fileURLWithPath: "/Applications", isDirectory: true)

    let source = Bundle.main.resourceURL?.appendingPathComponent("Payload/FolderSpan.app")
    private let strings: Bundle
    private let preview: Bool

    init() {
        let arguments = ProcessInfo.processInfo.arguments
        preview = arguments.contains("--preview") || Bundle.main.object(forInfoDictionaryKey: "FolderSpanInstallerPreview") as? Bool == true
        var language = InstallerLanguage()
        if preview, let code = Bundle.main.object(forInfoDictionaryKey: "FolderSpanInstallerPreviewLanguage") as? String {
            language = InstallerLanguage(preferredLanguages: [code])
        }
        if preview, let index = arguments.firstIndex(of: "--language"), arguments.indices.contains(index + 1) {
            language = InstallerLanguage(preferredLanguages: [arguments[index + 1]])
        }
        let resourceURL = Bundle.main.resourceURL ?? Bundle.main.bundleURL
        strings = Bundle(url: resourceURL.appendingPathComponent("\(language.rawValue).lproj")) ?? Bundle.main
    }

    func text(_ key: String) -> String { strings.localizedString(forKey: key, value: nil, table: nil) }
    var canInstall: Bool { !preview && source.map { FileManager.default.fileExists(atPath: $0.path) } == true }
    var folderLabel: String { directory.path == "/Applications" ? text("applications") : directory.lastPathComponent }
    var statusText: String {
        message ?? (preview ? text("preview") : canInstall ? text("footer") : text("invalidPayload"))
    }

    func selectDirectory() {
        let panel = NSOpenPanel()
        panel.title = text("chooseLocation")
        panel.prompt = text("choose")
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.directoryURL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications")
        if panel.runModal() == .OK, let url = panel.url {
            directory = url
            message = nil
            hasError = false
        }
    }

    func install() {
        guard !isInstalling, installedApplication == nil, canInstall, let source else { return }
        let target = directory.appendingPathComponent("FolderSpan.app")
        let replacing = FileManager.default.fileExists(atPath: target.path)
        if replacing {
            let alert = NSAlert()
            alert.messageText = text("replaceTitle")
            alert.informativeText = text("replaceBody")
            alert.addButton(withTitle: text("replace"))
            alert.addButton(withTitle: text("cancel"))
            guard alert.runModal() == .alertFirstButtonReturn else { return }
        }
        isInstalling = true
        hasError = false
        message = text("installing")
        let directory = self.directory
        DispatchQueue.global(qos: .userInitiated).async {
            let service = InstallationService(isApplicationRunning: {
                !NSRunningApplication.runningApplications(withBundleIdentifier: InstallationService.bundleIdentifier).isEmpty
            })
            let result = Result { try service.install(source: source, directory: directory, replaceExisting: replacing) }
            DispatchQueue.main.async {
                self.isInstalling = false
                switch result {
                case .success(let app):
                    self.installedApplication = app
                    self.message = self.text("installed")
                case .failure(let error):
                    self.hasError = true
                    self.message = self.describe(error)
                }
            }
        }
    }

    // https://developer.apple.com/documentation/appkit/nsworkspace/openapplication(at:configuration:completionhandler:)
    func openApplication() {
        guard let app = installedApplication else { return }
        NSWorkspace.shared.openApplication(at: app, configuration: .init()) { _, error in
            DispatchQueue.main.async {
                if error != nil {
                    self.hasError = true
                    self.message = self.text("openFailed")
                } else {
                    NSApplication.shared.terminate(nil)
                }
            }
        }
    }

    private func describe(_ error: Error) -> String {
        if let failure = error as? InstallationFailure {
            switch failure {
            case .invalidPayload: return text("invalidPayload")
            case .invalidDestination: return text("invalidDestination")
            case .replacementRequired: return text("replaceTitle")
            case .applicationRunning: return text("applicationRunning")
            case .installationBusy: return text("installationBusy")
            case .sameLocation: return text("sameLocation")
            case .recoveryRequired(let backup): return text("recoveryRequired") + "\n" + backup.path
            }
        }
        let error = error as NSError
        if (error.domain == NSCocoaErrorDomain && error.code == NSFileWriteNoPermissionError) ||
            (error.domain == NSPOSIXErrorDomain && [EACCES, EPERM].contains(Int32(error.code))) {
            return text("permissionDenied")
        }
        if (error.domain == NSCocoaErrorDomain && error.code == NSFileWriteOutOfSpaceError) ||
            (error.domain == NSPOSIXErrorDomain && error.code == Int(ENOSPC)) {
            return text("outOfSpace")
        }
        return text("installFailed")
    }
}
