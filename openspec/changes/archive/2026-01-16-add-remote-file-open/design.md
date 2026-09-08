## Context
FileScreen currently opens files directly, which fails for non-local desks because remote files must be downloaded first.
The new flow must keep local behavior unchanged while guiding remote opens through a download step.

## Goals / Non-Goals
- Goals:
  - Provide a clear confirmation flow for remote file opens with a configurable download directory.
  - Allow users to suppress the confirmation prompt via a persisted setting.
  - Expose the settings inside `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/FileShareSettingsScreen.kt` without adding a new settings page.
  - Reuse existing transfer and task infrastructure for downloads.
- Non-Goals:
  - Background download management or per-file default destinations.
  - Modifying remote file transfer protocols.

## Decisions
- Decision: Add a FileState openFile entry point that branches on current desk type.
  - Local desks open immediately.
  - Non-local desks trigger the confirmation flow (when enabled) and then download to the configured directory.
- Decision: Persist a boolean preference (default enabled) that controls whether the confirmation prompt is shown.
  - The confirmation dialog includes a "do not show again" checkbox that is checked by default.
  - The preference is updated when the dialog is dismissed (confirm or cancel) and the checkbox is checked.
- Decision: Implement the download using existing copy/task logic from remote to local.
  - Downloads target a configurable directory; when unset, default to PathUtils.getCachePath().
  - After a successful download, open the local file via FileUtils.openFile.

## Risks / Trade-offs
- Default checked "do not show again" could be surprising; the setting is only applied when the checkbox is checked on dismiss.
- Downloads may collide with existing files; conflict handling should follow existing copy behavior.

## Migration Plan
- Add the new setting key with a default that keeps prompts enabled.
- Roll out the UI flow without altering existing transfer protocols.

## Open Questions
- None.
