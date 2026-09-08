# Change: Remember per-disk paths within a session

## Why
Switching between Local/Device/Share/Network resets the path, forcing users to navigate back each time. Remembering the last path per disk makes switching predictable and faster.

## What Changes
- Cache the last visited path for each DiskBase instance for the current app session.
- When switching disks, restore the cached path for that disk if present.
- Preserve existing behavior when no cached path exists, and keep pathOverride precedence.

## Impact
- Affected specs: remember-disk-paths
- Affected code: shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt
