# Change: add file move operations

## Why
Move is exposed in the UI but has no implementation, so users cannot complete move workflows.

## What Changes
- Add move behavior in FileState by mirroring copy flow and deleting sources after successful copy.
- Reuse conflict-resolution handling from copy operations for move.

## Impact
- Affected specs: move-files (new)
- Affected code: shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt
