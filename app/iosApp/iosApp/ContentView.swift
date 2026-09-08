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
            .onDrop(of: acceptedDropTypes, isTargeted: nil, perform: IosDropImportCoordinator.handle)
    }
}
