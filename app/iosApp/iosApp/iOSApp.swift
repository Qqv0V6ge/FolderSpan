import SwiftUI
import UIKit
import ComposeApp

private enum AppNativeLocalization {
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
            let parts = preferred.split(separator: "-").map(String.init)
            let isSimplified = parts.first == "zh"
                && !parts.contains("hant")
                && !parts.contains(where: { ["tw", "hk", "mo"].contains($0) })
                && (parts.count == 1
                    || parts.contains("hans")
                    || parts.contains(where: { ["cn", "sg"].contains($0) }))
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

    static func installQuickActions(on application: UIApplication = .shared) {
        application.shortcutItems = [
            UIApplicationShortcutItem(
                type: "com.folderspan.openFileShare",
                localizedTitle: text("quick_share"),
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(type: .share),
                userInfo: nil
            )
        ]
    }
}

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        KMPNotifier.shared.initialize(
            configuration: NotificationPlatformConfigurationIos(
                showPushNotification: true,
                askNotificationPermissionOnStart: false,
                notificationSoundName: nil
            ),
            extensions: [LocalNotifications.shared]
        )
        IosNotificationBootstrap.shared.initializeEventBridge()
        IosClipboardFilePasteBridge.shared.start()
        AppNativeLocalization.installQuickActions(on: application)
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: "Default Configuration",
            sessionRole: connectingSceneSession.role
        )
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        let handled = QuickActionHandlerKt.handleQuickAction(type: shortcutItem.type)
        completionHandler(handled)
    }

    func applicationWillTerminate(_ application: UIApplication) {
        IosDropImportHandlerKt.releaseIosDropImportResources()
    }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UIHostingController(rootView: ContentView())
        self.window = window
        window.makeKeyAndVisible()

        handle(connectionOptions.urlContexts)
        IosSharedImportCoordinator.importPendingFiles()
        if let shortcutItem = connectionOptions.shortcutItem {
            _ = QuickActionHandlerKt.handleQuickAction(type: shortcutItem.type)
        }
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        IosSharedImportCoordinator.importPendingFiles()
        AppNativeLocalization.installQuickActions()
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        handle(URLContexts)
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        let handled = QuickActionHandlerKt.handleQuickAction(type: shortcutItem.type)
        completionHandler(handled)
    }

    private func handle(_ urlContexts: Set<UIOpenURLContext>) {
        IosDocumentOpenCoordinator.handle(urlContexts.map(\.url))
    }
}

enum IosDocumentOpenCoordinator {
    static func handle(_ urls: [URL]) {
        let fileURLs = urls.filter(\.isFileURL)
        guard !fileURLs.isEmpty else { return }

        IosDocumentOpenHandlerKt.handleIosDocumentOpenUrls(
            urls: fileURLs.map { $0 as Any }
        )
    }
}
