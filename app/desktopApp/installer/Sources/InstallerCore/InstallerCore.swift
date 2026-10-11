import Foundation
import Darwin

public enum InstallerLanguage: String {
    case english = "en"
    case chinese = "zh-Hans"

    // https://developer.apple.com/documentation/foundation/nslocale/preferredlanguages
    public init(preferredLanguages: [String] = Locale.preferredLanguages) {
        for code in preferredLanguages {
            switch code.lowercased().replacingOccurrences(of: "_", with: "-").split(separator: "-").first {
            case "zh": self = .chinese; return
            case "en": self = .english; return
            default: continue
            }
        }
        self = .english
    }
}

public enum InstallationFailure: Error, Equatable {
    case invalidPayload
    case invalidDestination
    case replacementRequired
    case applicationRunning
    case installationBusy
    case sameLocation
    case recoveryRequired(URL)
}

public struct InstallationService {
    public static let bundleIdentifier = "com.folderspan"
    private let fileManager: FileManager
    private let isApplicationRunning: () -> Bool

    public init(fileManager: FileManager = .default, isApplicationRunning: @escaping () -> Bool = { false }) {
        self.fileManager = fileManager
        self.isApplicationRunning = isApplicationRunning
    }

    public func install(source: URL, directory: URL, replaceExisting: Bool = false) throws -> URL {
        guard isValidApplication(source) else { throw InstallationFailure.invalidPayload }
        guard directory.isFileURL else { throw InstallationFailure.invalidDestination }
        let directory = directory.resolvingSymlinksInPath().standardizedFileURL
        let target = directory.appendingPathComponent("FolderSpan.app", isDirectory: true)
        let sourcePath = source.resolvingSymlinksInPath().standardizedFileURL.path
        guard target.path != sourcePath, !directory.path.hasPrefix(sourcePath + "/") else {
            throw InstallationFailure.sameLocation
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let lock = try InstallationLock(directory: directory)
        defer { lock.release() }

        let replacing = try checkDestination(target)
        if replacing && !replaceExisting { throw InstallationFailure.replacementRequired }
        if isApplicationRunning() { throw InstallationFailure.applicationRunning }

        let staging = directory.appendingPathComponent(".FolderSpan-install-\(UUID().uuidString)")
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? fileManager.removeItem(at: staging) }
        let stagedApp = staging.appendingPathComponent("FolderSpan.app")
        // Copy first, on the destination volume. Never remove the installed app before this succeeds.
        // https://developer.apple.com/documentation/foundation/filemanager/copyitem(at:to:)
        try fileManager.copyItem(at: source, to: stagedApp)
        guard isValidApplication(stagedApp) else { throw InstallationFailure.invalidPayload }
        if isApplicationRunning() { throw InstallationFailure.applicationRunning }
        guard try checkDestination(target) == replacing else { throw InstallationFailure.invalidDestination }

        if replacing {
            let backup = directory.appendingPathComponent(".FolderSpan-backup-\(UUID().uuidString).app")
            try fileManager.moveItem(at: target, to: backup)
            do {
                try fileManager.moveItem(at: stagedApp, to: target)
            } catch {
                do { try fileManager.moveItem(at: backup, to: target) }
                catch { throw InstallationFailure.recoveryRequired(backup) }
                throw error
            }
            // A failed backup cleanup must not turn a successful installation into a failed one.
            try? fileManager.removeItem(at: backup)
        } else {
            try fileManager.moveItem(at: stagedApp, to: target)
        }
        return target
    }

    public func isValidApplication(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true, values.isSymbolicLink != true,
              let data = try? Data(contentsOf: url.appendingPathComponent("Contents/Info.plist")),
              let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any],
              plist["CFBundleIdentifier"] as? String == Self.bundleIdentifier,
              let executable = plist["CFBundleExecutable"] as? String,
              !executable.isEmpty, executable != ".", executable != "..", !executable.contains("/"),
              fileManager.isExecutableFile(atPath: url.appendingPathComponent("Contents/MacOS/\(executable)").path)
        else { return false }
        return true
    }

    private func checkDestination(_ url: URL) throws -> Bool {
        // lstat also detects dangling symlinks; fileExists alone would follow them.
        var info = stat()
        if lstat(url.path, &info) == -1 {
            if errno == ENOENT { return false }
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
        guard isValidApplication(url) else { throw InstallationFailure.invalidDestination }
        return true
    }
}

private final class InstallationLock {
    private let descriptor: Int32

    init(directory: URL) throws {
        descriptor = Darwin.open(directory.appendingPathComponent(".folderspan-install.lock").path,
                                 O_WRONLY | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard descriptor >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            let code = errno
            close(descriptor)
            if code == EWOULDBLOCK { throw InstallationFailure.installationBusy }
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(code))
        }
    }

    func release() {
        flock(descriptor, LOCK_UN)
        close(descriptor)
    }
}
