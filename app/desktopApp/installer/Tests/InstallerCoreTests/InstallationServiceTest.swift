import Foundation
import Darwin
import XCTest
@testable import InstallerCore

final class InstallationServiceTest: XCTestCase {
    private var root: URL!
    private var source: URL!
    private var destination: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        source = root.appendingPathComponent("payload/FolderSpan.app")
        destination = root.appendingPathComponent("Applications")
        try makeApp(source, version: "2")
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    func testInstallsCompleteBundleAndPreservesExecutablePermissions() throws {
        let installed = try InstallationService().install(source: source, directory: destination)
        XCTAssertEqual(installed, destination.appendingPathComponent("FolderSpan.app"))
        XCTAssertEqual(try version(installed), "2")
        XCTAssertTrue(FileManager.default.isExecutableFile(atPath: installed.appendingPathComponent("Contents/MacOS/FolderSpan").path))
        XCTAssertEqual(try version(source), "2")
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: destination.path).contains { $0.hasPrefix(".FolderSpan-install-") })
    }

    func testExistingVersionRequiresExplicitConfirmation() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: destination)) {
            XCTAssertEqual($0 as? InstallationFailure, .replacementRequired)
        }
        XCTAssertEqual(try version(existing), "1")
    }

    func testConfirmedReplacementInstallsNewVersion() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        _ = try InstallationService().install(source: source, directory: destination, replaceExisting: true)
        XCTAssertEqual(try version(existing), "2")
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: destination.path).contains { $0.hasPrefix(".FolderSpan-backup-") })
    }

    func testFailedPublishRestoresOldVersion() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        XCTAssertThrowsError(try InstallationService(fileManager: FailingPublishFileManager()).install(
            source: source, directory: destination, replaceExisting: true
        ))
        XCTAssertEqual(try version(existing), "1")
    }

    func testRunningApplicationIsNeverReplaced() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        XCTAssertThrowsError(try InstallationService(isApplicationRunning: { true }).install(
            source: source, directory: destination, replaceExisting: true
        )) { XCTAssertEqual($0 as? InstallationFailure, .applicationRunning) }
        XCTAssertEqual(try version(existing), "1")
    }

    func testApplicationStartedDuringCopyIsNeverReplaced() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        var checks = 0
        let service = InstallationService(isApplicationRunning: { checks += 1; return checks > 1 })
        XCTAssertThrowsError(try service.install(source: source, directory: destination, replaceExisting: true)) {
            XCTAssertEqual($0 as? InstallationFailure, .applicationRunning)
        }
        XCTAssertEqual(try version(existing), "1")
    }

    func testUnrelatedDestinationIsNeverReplaced() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1", identifier: "org.example.other")
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: destination, replaceExisting: true)) {
            XCTAssertEqual($0 as? InstallationFailure, .invalidDestination)
        }
        XCTAssertEqual(try version(existing), "1")
    }

    func testInvalidPayloadDoesNotTouchDestination() throws {
        try makeApp(source, version: "2", identifier: "org.example.other")
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: destination)) {
            XCTAssertEqual($0 as? InstallationFailure, .invalidPayload)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
    }

    func testDestinationSymlinkIncludingDanglingLinkIsRejected() throws {
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try FileManager.default.createSymbolicLink(at: existing, withDestinationURL: root.appendingPathComponent("missing.app"))
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: destination, replaceExisting: true)) {
            XCTAssertEqual($0 as? InstallationFailure, .invalidDestination)
        }
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: existing.path), root.appendingPathComponent("missing.app").path)
    }

    func testFailedCopyPreservesOldVersionAndRemovesStaging() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        XCTAssertThrowsError(try InstallationService(fileManager: FailingCopyFileManager()).install(
            source: source, directory: destination, replaceExisting: true
        ))
        XCTAssertEqual(try version(existing), "1")
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: destination.path).contains { $0.hasPrefix(".FolderSpan-install-") })
    }

    func testFailedRollbackKeepsRecoverableBackup() throws {
        let existing = destination.appendingPathComponent("FolderSpan.app")
        try makeApp(existing, version: "1")
        do {
            _ = try InstallationService(fileManager: FailingRollbackFileManager()).install(
                source: source, directory: destination, replaceExisting: true
            )
            XCTFail("Expected failed replacement")
        } catch InstallationFailure.recoveryRequired(let backup) {
            XCTAssertEqual(try version(backup), "1")
        }
    }

    func testConcurrentInstallerIsRejected() throws {
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let fd = Darwin.open(destination.appendingPathComponent(".folderspan-install.lock").path, O_WRONLY | O_CREAT, 0o600)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { close(fd) }
        XCTAssertEqual(flock(fd, LOCK_EX | LOCK_NB), 0)
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: destination)) {
            XCTAssertEqual($0 as? InstallationFailure, .installationBusy)
        }
    }

    func testSourceCannotBeOverwrittenOrInstalledInsideItself() throws {
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: source.deletingLastPathComponent())) {
            XCTAssertEqual($0 as? InstallationFailure, .sameLocation)
        }
        XCTAssertThrowsError(try InstallationService().install(source: source, directory: source.appendingPathComponent("nested"))) {
            XCTAssertEqual($0 as? InstallationFailure, .sameLocation)
        }
        XCTAssertEqual(try version(source), "2")
    }

    func testPayloadSymlinkIsRejected() throws {
        let link = root.appendingPathComponent("linked.app")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: source)
        XCTAssertThrowsError(try InstallationService().install(source: link, directory: destination)) {
            XCTAssertEqual($0 as? InstallationFailure, .invalidPayload)
        }
    }

    private func makeApp(_ url: URL, version: String, identifier: String = "com.folderspan") throws {
        let executable = url.appendingPathComponent("Contents/MacOS/FolderSpan")
        try FileManager.default.createDirectory(at: executable.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
        let plist: [String: String] = ["CFBundleIdentifier": identifier, "CFBundleExecutable": "FolderSpan", "CFBundleVersion": version]
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
            .write(to: url.appendingPathComponent("Contents/Info.plist"))
    }

    private func version(_ url: URL) throws -> String? {
        let data = try Data(contentsOf: url.appendingPathComponent("Contents/Info.plist"))
        return (try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String])?["CFBundleVersion"]
    }
}

private final class FailingPublishFileManager: FileManager, @unchecked Sendable {
    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        if srcURL.deletingLastPathComponent().lastPathComponent.hasPrefix(".FolderSpan-install-") {
            throw CocoaError(.fileWriteUnknown)
        }
        try super.moveItem(at: srcURL, to: dstURL)
    }
}

private final class FailingCopyFileManager: FileManager, @unchecked Sendable {
    override func copyItem(at srcURL: URL, to dstURL: URL) throws { throw CocoaError(.fileWriteOutOfSpace) }
}

private final class FailingRollbackFileManager: FileManager, @unchecked Sendable {
    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        if srcURL.deletingLastPathComponent().lastPathComponent.hasPrefix(".FolderSpan-install-") ||
            srcURL.lastPathComponent.hasPrefix(".FolderSpan-backup-") {
            throw CocoaError(.fileWriteUnknown)
        }
        try super.moveItem(at: srcURL, to: dstURL)
    }
}
