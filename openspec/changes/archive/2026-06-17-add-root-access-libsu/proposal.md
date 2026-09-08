## Why

Android file management currently relies on normal app storage permissions with Shizuku as the privileged fallback. Users with rooted devices should be able to grant FolderSpan root access directly through topjohnwu/libsu and get the same restricted-path file management behavior without requiring a running Shizuku service.

## What Changes

- Add topjohnwu/libsu as the Android root shell integration.
- Introduce a root privileged file access backend that mirrors the Shizuku-backed operations used by local file browsing, metadata, creation, deletion, rename, space queries, reads, and writes.
- Use root-backed access directly for ordinary filesystem operations when Root permission is already granted; otherwise use one authorized privileged backend after local permission failures.
- Add a Root permission entry to the permissions settings page with status, request action, startup request opt-in, and startup reminder behavior consistent with Shizuku.
- Keep content URI handling read-only and unaffected by root access.

## Capabilities

### New Capabilities
- `android-root-file-access`: Defines Android root authorization, root-backed file operations, privileged backend selection, and failure behavior for restricted filesystem paths.

### Modified Capabilities
- `manage-permission-settings`: Adds Root as an Android platform permission surfaced and requested from the existing permissions settings experience.

## Impact

- Affected code: Android source sets in `shared/src/androidMain/kotlin/com/folderspan/root`, existing Shizuku/file utility paths under `shared/src/androidMain/kotlin/com/folderspan/utils`, Android permission provider, and `composeApp/src/androidMain/kotlin/com/folderspan/MainActivity.kt`.
- Affected build: Gradle version catalog and Android dependencies for topjohnwu/libsu.
- Affected tests: Android/JVM-compatible unit coverage around permission status mapping, privileged backend selection, and root command/file operation adapters where feasible.
