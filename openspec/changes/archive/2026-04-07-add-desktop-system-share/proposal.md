# Change: Add desktop system share support

## Why
System sharing works on mobile and web but is not supported on JVM desktop targets, preventing Windows/macOS/Linux users from sharing selected files and folders via the OS share UI.

## What Changes
- Add JVM desktop implementations for system sharing on Windows 10+, macOS 11+, and Linux with xdg-desktop-portal.
- Introduce OS-specific share adapters for JVM and wire them into the existing `shareSystemItems` API.
- Provide clear fallback behavior (return false so the UI shows a snackbar) when desktop sharing is unavailable.
- Add required native/bridge dependencies for desktop share integration.

## Impact
- Affected specs: share-files-system (modified)
- Affected code: JVM system share implementation, desktop OS detection, build dependencies
- Related changes: add-system-share-action (depends on base system share flow)
