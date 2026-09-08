## Why

Ignore file support had split semantics: browsing could mark ignored entries, device/share serving could hide or reject ignored paths, and copy/move tasks still transferred ignored content. Users need a simpler model: ignore files are not access control, but enabled source-side ignore rules should keep generated/build/cache files out of file-operation tasks.

## What Changes

- Keep ignored entries visible and remotely accessible when normal device/share permissions allow access.
- Remove ignore-based filtering and direct-access denial from device/share serving paths.
- Apply enabled ignore file rules while building copy and move task manifests for `Local`, `Share`, `Device`, and `Network` source protocols.
- Skip ignored source entries, ignored directory subtrees, explicitly selected ignored roots, and enabled ignore files themselves.
- For copy tasks, skipped entries SHALL NOT be copied or added to retry/continue manifests.
- For move tasks, skipped entries SHALL NOT be copied or deleted; only entries that entered the move manifest may be deleted from the source after copy succeeds.
- Destination-side ignore preferences SHALL NOT block writes; only source-side ignore preferences decide what enters the operation.
- **BREAKING**: Remote clients will be able to list and access paths that match this device's configured ignore files, subject to normal sharing and authorization rules.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `filter-directory-list`: ignore files no longer filter remote serving responses, but enabled source-side ignore rules apply to file-operation traversal.
- `manage-file-operation-tasks`: copy and move task manifests exclude ignored source entries and persist only executable entries.
- `move-files`: move semantics leave skipped ignored source entries in place and avoid deleting parent directories that still contain ignored content.

## Impact

- Affected specs: `filter-directory-list`, `manage-file-operation-tasks`, `move-files`
- Affected code: device path/file services, server routes, raw HTTP dispatcher, ignore matcher/loading helpers, `FileState` copy/move traversal and manifest creation, move delete-source stage, task retry/continue metadata
- Affected behavior: ignored paths remain visible/accessibile under normal share/device permissions; copy/move totals and results exclude skipped ignored entries; moving a directory with ignored content may leave the source directory behind
