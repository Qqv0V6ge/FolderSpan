## Why

Folder copy tasks currently create directories first, but 0-byte files are still scheduled with normal file-copy entries. In large folder trees this makes empty file handling pay the same transfer scheduling overhead as content-bearing files and obscures the intended execution order.

## What Changes

- Copy and move-copy manifests pre-create all directories, including empty directories, before any file entries run.
- 0-byte file entries are separated into their own copy-stage queue and batch-created after directories but before non-empty file copies.
- Task progress, retry metadata, pause/cancel handling, and continue-task behavior remain item-level.
- No user-facing setting, public transfer API, or non-empty file transfer behavior changes.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `manage-file-operation-tasks`: Copy-stage manifest execution order now distinguishes directory creation, 0-byte file creation, and non-empty file copy.

## Impact

- Affected code: file-operation manifest building/execution, task runtime persistence queue categories, task status display text, and focused tests under shared file task state.
- Affected behavior: copy and move tasks create empty files earlier; empty directories remain part of the directory pre-create batch.
- Dependencies: none.
