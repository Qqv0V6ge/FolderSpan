import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    private let acceptedDropTypes = [
        "public.plain-text",
        "public.url",
        "public.folder",
        "public.file-url",
        "public.content",
        "public.item",
        "public.data",
    ]

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .onDrop(of: acceptedDropTypes, delegate: FileCopyDropDelegate(types: acceptedDropTypes))
    }
}

private struct FileCopyDropDelegate: DropDelegate {
    let types: [String]

    private func hasFiles(_ info: DropInfo) -> Bool {
        info.itemProviders(for: types).contains { ExternalTextProvider.typeIdentifier(for: $0) == nil }
    }

    private func updateLocation(_ info: DropInfo) {
        guard hasFiles(info) else { return }
        IosDropImportHandlerKt.moveIosFileDrop(
            horizontal: Float(info.location.x * UIScreen.main.scale),
            vertical: Float(info.location.y * UIScreen.main.scale)
        )
    }

    func validateDrop(info: DropInfo) -> Bool {
        info.hasItemsConforming(to: types)
    }

    func dropEntered(info: DropInfo) {
        updateLocation(info)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        updateLocation(info)
        return DropProposal(operation: .copy)
    }

    func dropExited(info: DropInfo) {
        IosDropImportHandlerKt.endIosFileDrop()
    }

    func performDrop(info: DropInfo) -> Bool {
        let requestId = hasFiles(info) ? IosDropImportHandlerKt.beginIosFileDrop(
            horizontal: Float(info.location.x * UIScreen.main.scale),
            vertical: Float(info.location.y * UIScreen.main.scale)
        ) : nil
        IosDropImportHandlerKt.endIosFileDrop()
        return IosDropImportCoordinator.handle(providers: info.itemProviders(for: types), dropRequestId: requestId)
    }
}
