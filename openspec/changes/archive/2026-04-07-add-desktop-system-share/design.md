## Context
The common system share flow is already wired via `shareSystemItems`, but JVM targets currently return unsupported. We need OS-native share on desktop across Windows, macOS, and Linux.

## Goals / Non-Goals
- Goals:
  - Support Windows 10+, macOS 11+, and Linux (with xdg-desktop-portal) from the existing `shareSystemItems` API.
  - Preserve multi-select semantics and folder sharing without compression.
  - Provide a deterministic fallback (return false) when desktop sharing cannot be invoked.
- Non-Goals:
  - Implement custom share UIs inside the app.
  - Archive/zip folder contents before sharing.

## Decisions
- Introduce a JVM-only `DesktopShareAdapter` with per-OS implementations selected by `os.name`.
- Windows 10+:
  - Use WinRT Share UI via COM/WinRT interop (JNA + jna-platform).
  - Create a hidden/active AWT window handle for `IDataTransferManagerInterop.ShowShareUIForWindow`.
  - Build a `DataPackage` with `StorageFile` items for multiple files/folders.
- macOS 11+:
  - Use AppKit `NSSharingServicePicker` with `NSURL` items.
  - Bridge to Objective-C runtime via JNA and anchor the picker to the app window.
- Linux:
  - Use xdg-desktop-portal over DBus (dbus-java) to invoke a portal share/open flow for the selected files.
  - If the portal is unavailable, return false so the UI shows a snackbar.

## Risks / Trade-offs
- Desktop native integrations are OS-specific and may require careful window-handle bridging.
- Linux portals vary by desktop environment; fallbacks are necessary.
- Additional native/bridge dependencies increase packaging complexity on JVM targets.

## Migration Plan
- Add dependencies in `composeApp` JVM source set.
- Implement adapters incrementally per OS with guarded runtime checks.
- Keep unsupported paths returning false to preserve existing behavior.

## Open Questions
- None (default assumptions: Windows 10+, macOS 11+, Linux requires xdg-desktop-portal; otherwise snackbar fallback).
