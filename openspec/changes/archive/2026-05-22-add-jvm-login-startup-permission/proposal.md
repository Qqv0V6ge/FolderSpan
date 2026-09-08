# Change: Add JVM login startup permission

## Why
JVM desktop currently reports no platform permissions, so users cannot enable FolderSpan to start automatically after system login from the existing permissions settings page.

## What Changes
- Add a JVM-only "开机启动" platform permission that registers FolderSpan as a login startup app after explicit user action.
- Support Windows, macOS, and Linux startup mechanisms; unsupported desktop environments report `Unsupported`.
- Keep the existing permissions settings UI and request/status flow unchanged.

## Impact
- Affected specs: `manage-permission-settings`.
- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/permission/PlatformPermissionProvider.kt`
  - `shared/src/jvmMain/kotlin/com/folderspan/permission/*`
  - `shared/src/jvmTest/kotlin/com/folderspan/permission/*`
