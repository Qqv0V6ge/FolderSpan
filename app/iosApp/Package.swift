// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FolderSpanExternalText",
    platforms: [.macOS(.v13), .iOS(.v16)],
    targets: [
        .target(name: "ExternalTextImport", path: "Shared"),
        .testTarget(name: "ExternalTextImportTests", dependencies: ["ExternalTextImport"], path: "tests"),
    ]
)
