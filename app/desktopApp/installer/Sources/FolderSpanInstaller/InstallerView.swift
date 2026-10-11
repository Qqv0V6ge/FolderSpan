import AppKit
import SwiftUI

private enum InstallerStyle {
    static let surface = Color(red: 247 / 255, green: 251 / 255, blue: 234 / 255)
    static let primary = Color(red: 62 / 255, green: 103 / 255, blue: 0)
    static let text = Color(red: 25 / 255, green: 29 / 255, blue: 19 / 255)
    static let secondary = Color(red: 66 / 255, green: 73 / 255, blue: 55 / 255)
    static let divider = Color(red: 194 / 255, green: 202 / 255, blue: 178 / 255)
}

struct InstallerView: View {
    @ObservedObject var model: InstallerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(model.text("heading"))
                .font(.system(size: 28, weight: .semibold))
                .accessibilityAddTraits(.isHeader)
            Text(model.text("subtitle"))
                .font(.system(size: 14))
                .foregroundColor(InstallerStyle.secondary)
                .padding(.top, 10)
            Spacer(minLength: 24)
            HStack(alignment: .center, spacing: 0) {
                Spacer()
                icon(image: appIcon, label: "FolderSpan")
                VStack(spacing: 16) {
                    Image(systemName: model.installedApplication == nil ? "arrow.right" : "checkmark")
                        .font(.system(size: 38, weight: .light))
                        .accessibilityHidden(true)
                    Text(model.text(model.installedApplication == nil ? "installTo" : "complete"))
                        .font(.system(size: 12))
                }
                .foregroundColor(InstallerStyle.primary)
                .frame(width: 160)
                .padding(.bottom, 28)
                icon(image: NSWorkspace.shared.icon(forFile: directoryIconPath), label: model.folderLabel)
                Spacer()
            }
            Spacer(minLength: 24)
            HStack(spacing: 8) {
                Text(model.text("location") + " " + model.directory.path)
                    .font(.system(size: 12))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .help(model.directory.path)
                Spacer()
                if model.installedApplication == nil {
                    Button(model.text("changeLocation"), action: model.selectDirectory)
                        .disabled(model.isInstalling)
                }
            }
            .foregroundColor(InstallerStyle.secondary)
            Rectangle().fill(InstallerStyle.divider).frame(height: 1).padding(.vertical, 16)
            HStack(spacing: 16) {
                HStack(alignment: .top, spacing: 8) {
                    if model.isInstalling {
                        ProgressView().scaleEffect(0.65).frame(width: 18, height: 18)
                    } else {
                        Image(systemName: model.hasError ? "exclamationmark.circle" : "info.circle")
                            .accessibilityHidden(true)
                    }
                    Text(model.statusText)
                        .font(.system(size: 12))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(model.hasError ? .red : InstallerStyle.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                if model.installedApplication != nil {
                    Button(model.text("done")) { NSApplication.shared.terminate(nil) }
                }
                Button(action: model.installedApplication == nil ? model.install : model.openApplication) {
                    Text(model.text(model.installedApplication == nil ? "install" : "open"))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, 22)
                        .frame(height: 40)
                        .background(InstallerStyle.primary)
                        .clipShape(Capsule())
                }
                .buttonStyle(PlainButtonStyle())
                .keyboardShortcut(.defaultAction)
                .disabled(model.isInstalling || (model.installedApplication == nil && !model.canInstall))
                .opacity(model.isInstalling || (model.installedApplication == nil && !model.canInstall) ? 0.5 : 1)
            }
        }
        .foregroundColor(InstallerStyle.text)
        .padding(.horizontal, 48)
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(InstallerStyle.surface)
        .preferredColorScheme(.light)
    }

    private var appIcon: NSImage {
        if let url = Bundle.main.resourceURL?.appendingPathComponent("app-icon.png"), let image = NSImage(contentsOf: url) {
            return image
        }
        return NSWorkspace.shared.icon(forFile: model.source?.path ?? Bundle.main.bundlePath)
    }
    private var directoryIconPath: String {
        FileManager.default.fileExists(atPath: model.directory.path) ? model.directory.path : "/Applications"
    }

    private func icon(image: NSImage, label: String) -> some View {
        VStack(spacing: 12) {
            Image(nsImage: image).resizable().interpolation(.high).scaledToFit()
                .frame(width: 120, height: 120).accessibilityHidden(true)
            Text(label).font(.system(size: 14)).lineLimit(1)
        }
        .frame(width: 140)
    }
}
