## 1. Implementation
- [x] 1.1 Add common clipboard entry models and parsing utilities (path vs URL).
- [x] 1.2 Implement platform clipboard readers (Android, iOS, JVM, JS, WASM) with best-effort support for text and file lists.
- [x] 1.3 Wire "Open from Clipboard" into AppDrawer MoreOptionsDropdown and route parsed paths into FileState navigation.
- [x] 1.4 Add a selection dialog for multiple valid path entries and direct open for a single entry.
- [x] 1.5 Log clipboard entries and URL entries with LogKit and skip non-existent paths.
- [x] 1.6 Add parser-focused tests in common tests (composeApp/src/commonTest or shared/src/commonTest).
- [x] 1.7 Document manual validation steps for desktop, Android, and iOS clipboard behavior.

## 2. Manual Validation Steps
- Desktop (JVM): copy a local file path and a directory path, then use AppDrawer > More > 从剪贴板打开 to verify direct open and dialog for multiple.
- Android: copy a file path or file:// URL, then use AppDrawer > More > 从剪贴板打开 to verify it opens the parent directory or the directory itself.
- iOS: copy a file URL or text path, then use AppDrawer > More > 从剪贴板打开 to verify open and URL logging in LogKit.
