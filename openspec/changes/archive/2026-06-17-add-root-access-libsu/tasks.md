## 1. Dependencies and Contracts

- [x] 1.1 Add libsu dependency aliases to `gradle/libs.versions.toml` and Android source set dependencies in the relevant Gradle modules.
- [x] 1.2 Add `PermissionIds.Root` to the common permission model without changing non-Android provider behavior.
- [x] 1.3 Define an Android-only `PrivilegedFileClient` contract covering the Shizuku/root shared operation surface.
- [x] 1.4 Adapt the existing Shizuku client to implement the privileged file client contract.

## 2. Root Authorization and Service

- [x] 2.1 Create Android root state/result models for granted, denied, unavailable, requesting, and failed states.
- [x] 2.2 Implement a libsu-backed root manager that checks availability without prompting and requests root only from an explicit permission action.
- [x] 2.3 Implement a root file service/client that mirrors Shizuku-backed list, metadata, mutation, space, exists, and file descriptor operations.
- [x] 2.4 Ensure root service lifecycle failures clear cached clients and return typed failures without crashing callers.

## 3. Privileged Fallback Integration

- [x] 3.1 Replace `withShizukuFallback` with a privileged fallback helper that attempts local IO first, then selects one authorized privileged backend for permission errors.
- [x] 3.2 Update Android `FileUtils` reads, writes, metadata, creation, deletion, rename, space, line-read, and append paths to use the privileged fallback helper.
- [x] 3.3 Update Android `PathUtils` directory listing/traversal/create/delete/existence paths to use the privileged fallback helper.
- [x] 3.4 Preserve existing read-only `content://` URI behavior without privileged escalation.
- [x] 3.5 Use root-backed access before normal Android file IO when Root permission is already granted.

## 4. Permission Settings Integration

- [x] 4.1 Add the Root permission item to Android `PlatformPermissionProvider.permissions()`.
- [x] 4.2 Map root manager states to `PermissionStatus` values, including `Unsupported` when root is unavailable.
- [x] 4.3 Route the Root permission request action through the libsu authorization flow and refresh status after completion.
- [x] 4.4 Verify startup permission reminder evaluation does not request root and omits Root when status is `Unsupported`.
- [x] 4.5 Add a permissions-page setting that lets users opt in to requesting Root during app startup.

## 5. Verification

- [x] 5.1 Add unit tests for privileged fallback ordering and non-permission error behavior using fake privileged clients.
- [x] 5.2 Add tests for Android root permission status/request mapping where the platform seams allow fakes.
- [x] 5.3 Run `./gradlew :shared:jvmTest` and any Android unit tests covering the new Android source set behavior.
- [x] 5.4 Run `openspec validate add-root-access-libsu --strict` after implementation updates.
- [x] 5.5 Add tests for root direct-mode backend selection and startup Root request setting persistence.
