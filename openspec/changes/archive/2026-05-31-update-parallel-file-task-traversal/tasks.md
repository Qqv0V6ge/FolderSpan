## 1. OpenSpec
- [x] 1.1 Add `manage-file-operation-tasks` delta for endpoint-adaptive parallel manifest traversal.
- [x] 1.2 Validate the OpenSpec change with `openspec validate update-parallel-file-task-traversal --strict`.

## 2. Implementation
- [x] 2.1 Add internal traversal parallelism config and endpoint-adaptive resolver.
- [x] 2.2 Add bounded parallel directory traversal helper with de-duplication, fail-fast errors, pause/cancel checks, and scan progress callbacks.
- [x] 2.3 Replace `FileState.collectDirectoryEntries` serial `traverse()` use with one-level list calls through the new helper.
- [x] 2.4 Publish scan speed and current/max traversal concurrency while copy, move, and delete manifests are being built.
- [x] 2.5 Isolate device/share/WebRTC heartbeat and control monitors from bulk workers, and dynamically limit device/share/link-share directory enumeration concurrency.

## 3. Tests
- [x] 3.1 Add common tests for concurrent traversal, de-duplication, cancellation, fail-fast behavior, and adaptive parallelism.
- [x] 3.2 Run targeted and regression test commands from the implementation plan.
- [x] 3.3 Cover scan result messages and task metrics that expose speed and traversal concurrency.
- [x] 3.4 Cover device-side and dynamic list concurrency limiting so traversal cannot monopolize path enumeration.
- [x] 3.5 Audit remaining heartbeat, keep-alive, pause/cancel monitor, and direct-copy traversal call sites for the same starvation pattern.
