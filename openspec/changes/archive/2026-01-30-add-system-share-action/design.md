## Context
`FileShareScreen` currently supports link sharing and device-to-device sharing, both using the share list bottom sheet for multi-select. There is no way to invoke the platform system share sheet from this screen.

## Goals / Non-Goals
- Goals:
  - Add a system share entry to `FileShareScreen`.
  - Reuse the existing multi-select share list to choose files/folders.
  - Delegate system share to platform-specific implementations via an expect API.
  - Use Web Share API when available on web targets.
- Non-Goals:
  - Automatic compression/archiving of folders before sharing.
  - New sharing backends beyond the system share sheet.

## Decisions
- Introduce a common `SystemShareItem` data structure (path, display name, mime type, isDirectory) and an expect API such as `shareSystemItems(items): Boolean`.
  - The boolean indicates whether the share sheet was successfully launched (false for unsupported platforms).
- Add a "system share mode" in `FileShareScreen` so the bottom sheet shows multi-select controls without requiring a link/device selection.
  - On confirm, the app shares `checkedFiles` when non-empty; otherwise it shares the full `files` list.
- Platform behavior (best-effort):
  - Android: Build content URIs using `FileProvider`, use ACTION_SEND/ACTION_SEND_MULTIPLE with read permission.
  - iOS: Use `UIActivityViewController` with file URLs for files and folders.
  - JVM: If no native share API is available, return false.
  - JS/Wasm/Web: Use `navigator.share` when available; otherwise return false.
- When the platform returns false, the common UI shows a snackbar indicating system sharing is unavailable.

## Risks / Trade-offs
- Some apps/platforms do not accept folder shares; we will pass folders through without compression as requested.
- Web targets may not be able to share local file paths; sharing may fail or be unsupported.

## Migration Plan
No data migration required.

## Open Questions
None.
