// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FolderSpanInstaller",
    platforms: [.macOS(.v11)],
    products: [.executable(name: "FolderSpanInstaller", targets: ["FolderSpanInstaller"])],
    targets: [
        .target(name: "InstallerCore"),
        .executableTarget(name: "FolderSpanInstaller", dependencies: ["InstallerCore"]),
        .testTarget(name: "InstallerCoreTests", dependencies: ["InstallerCore"])
    ]
)
