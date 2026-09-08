## 1. Specification
- [x] 1.1 Add OpenSpec delta for JVM login startup permission behavior.
- [x] 1.2 Update Markdown path index for the new change documents.

## 2. Implementation
- [x] 2.1 Add `PermissionIds.LoginStartup`.
- [x] 2.2 Implement JVM login startup status/request/open-settings handling for Windows, macOS, and Linux.
- [x] 2.3 Wire the JVM provider to expose the login startup permission on supported desktop OSes.

## 3. Validation
- [x] 3.1 Add JVM tests for command resolution, Windows registry parsing, macOS plist generation, and Linux autostart parsing.
- [x] 3.2 Run OpenSpec validation and JVM test tasks.
