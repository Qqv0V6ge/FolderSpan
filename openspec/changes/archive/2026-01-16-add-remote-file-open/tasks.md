## 1. Implementation
- [x] 1.1 Add a persisted setting and SettingsState flow for the remote open confirmation preference.
- [x] 1.2 Add a remote open settings section to `FileShareSettingsScreen`.
- [x] 1.3 Implement FileState.openFile with dialog state for confirmation and download handling (apply checkbox preference on confirm or cancel).
- [x] 1.4 Update FileScreen to use FileState.openFile for file clicks.
- [x] 1.5 Add a remote open download directory setting and remove download location dialogs.
- [x] 1.6 Wire download actions to existing copy/task logic and open the downloaded file on success.

## 2. Validation
- [x] 2.1 Manual: local file clicks open immediately without prompts.
- [x] 2.2 Manual: remote file clicks show confirmation (when enabled) and download to the configured directory before opening.
- [x] 2.3 Manual: disabling the confirmation in settings skips the confirmation dialog and uses the configured directory.
