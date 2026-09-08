## Context
The permissions settings page is already driven by `PlatformPermissionProvider.permissions()`, but the JVM actual provider currently returns an empty list.
Desktop login startup is platform-specific and can be handled without adding a new dependency.

## Goals / Non-Goals
- Goals:
  - Expose login startup as a JVM platform permission.
  - Enable startup only after the user clicks the permission action.
  - Avoid writing startup entries for development commands such as `java` or `javaw`.
- Non-Goals:
  - No UI redesign for the permissions page.
  - No in-app disable action; users are sent to system startup settings after the permission is granted.
  - No system-level/all-users startup registration.

## Decisions
- Windows uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` with value name `FolderSpan`.
- macOS writes `~/Library/LaunchAgents/com.folderspan.login.plist` with `RunAtLoad = true`.
- Linux writes `~/.config/autostart/com.folderspan.desktop`.
- The current executable is resolved from `ProcessHandle.current().info().command()` and rejected when it points to a generic JVM launcher.

## Risks / Trade-offs
- Packaged desktop apps should resolve to a native launcher, while Gradle development runs usually resolve to `java`; development runs therefore show `Unsupported`.
- Some Linux desktop environments may ignore freedesktop autostart files; the permission still reports based on the file state.
