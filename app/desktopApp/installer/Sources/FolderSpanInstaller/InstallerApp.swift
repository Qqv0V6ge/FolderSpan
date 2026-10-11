import InstallerCore
import AppKit
import SwiftUI

@main
struct InstallerApp {
    static func main() {
        let app = NSApplication.shared
        let delegate = InstallerAppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.regular)
        withExtendedLifetime(delegate) { app.run() }
    }
}

@MainActor
final class InstallerAppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var window: NSWindow?
    private var model: InstallerModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let model = InstallerModel()
        self.model = model
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 680, height: 520),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable],
                              backing: .buffered, defer: false)
        window.title = model.text("windowTitle")
        window.appearance = NSAppearance(named: .aqua)
        let hostingView = NSHostingView(rootView: InstallerView(model: model))
        // Keep the native window size stable when localized text changes its ideal size.
        // https://developer.apple.com/documentation/swiftui/nshostingview/sizingoptions
        if #available(macOS 13.0, *) { hostingView.sizingOptions = [] }
        window.contentView = hostingView
        window.setContentSize(NSSize(width: 680, height: 520))
        window.contentMinSize = NSSize(width: 680, height: 520)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        window.makeKeyAndOrderFront(nil)
        self.window = window
        let menu = NSMenu()
        let appItem = NSMenuItem()
        menu.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: model.text("quit"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        NSApplication.shared.mainMenu = menu
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool { model?.isInstalling != true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        model?.isInstalling == true ? .terminateCancel : .terminateNow
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
