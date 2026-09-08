## Why

Bulk copy and move tasks may need to create many destination directories before file transfer begins, but directory creation currently runs one item at a time. Remote device `createFolders` requests also process each path serially, which underuses available I/O capacity while the project already has adaptive operation concurrency for file work.

## What Changes

- Execute folder creation entries in file operation copy queues with bounded adaptive parallelism.
- Execute remote device batch `createFolders` requests concurrently while preserving per-path result order.
- Dynamically lower or raise folder creation concurrency using the existing operation runtime strategy, including local runtime pressure and remote device transfer status when a Device endpoint is involved.
- Preserve existing retry and failure semantics: individual folder failures are recorded and other eligible folder creations continue.
- No wire-format or public route changes.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `manage-file-operation-tasks`: directory creation execution in copy/move task manifests uses adaptive operation concurrency.
- `webrtc-device-file-rpc`: batch create-folder RPC keeps HTTP-compatible result semantics while allowing service-side parallel execution.

## Impact

- Affected code: `FileState` task queue execution, `DeviceFileService.createFolders`, and focused JVM/common tests.
- Affected APIs: no signature, route, protobuf, or response-shape changes.
- Dependencies: reuse existing coroutine and `service.operation` adaptive parallelism utilities.
