## 1. Serving Behavior

- [x] 1.1 Remove ignore-based list filtering from device/share directory list responses.
- [x] 1.2 Remove ignore-based direct-access denial from served path lookup/read flows.
- [x] 1.3 Remove `LocalIgnoreFileFilter` injection and imports from server route/service code paths that no longer need it.
- [x] 1.4 Keep ignore preference loading, parsing, matching, and local UI ignored-state marking unchanged.

## 2. Ignore Resolution

- [x] 2.1 Add a shared helper to resolve operation ignore matchers by source `protocol`, `protocolId`, path, and separator.
- [x] 2.2 Add protocol-specific ignore file readers for `Local`, `Share`, `Device`, and `Network` sources.
- [x] 2.3 Reuse existing ignore parsing, negation, nearest-preference, and matching behavior without changing local UI marking.

## 3. Copy/Move Manifest Filtering

- [x] 3.1 Filter explicitly selected ignored source entries before creating copy or move task manifests.
- [x] 3.2 Filter ignored descendants while collecting directory entries for copy and move manifests.
- [x] 3.3 Skip ignored directory subtrees so descendants are not copied or enqueued.
- [x] 3.4 Skip enabled ignore files themselves, such as `.gitignore`, from copy and move manifests.
- [x] 3.5 Ensure skipped entries do not affect byte totals, item totals, continue queues, or failed retry metadata.

## 4. Move Source Cleanup

- [x] 4.1 Build move delete-source queues only from source entries that were eligible for copy.
- [x] 4.2 Protect source directories that still contain skipped ignored descendants from deletion.
- [x] 4.3 Complete all-skipped copy and move tasks without copying, deleting, or reporting retryable failures.

## 5. Tests

- [x] 5.1 Update device path service tests so ignored children are returned when normal permissions allow listing.
- [x] 5.2 Update device file service and route tests so ignored files are accessible when normal permissions allow access.
- [x] 5.3 Add focused ignore-operation tests for local copy and move manifests.
- [x] 5.4 Add tests for selected ignored roots, enabled ignore files themselves, and ignored directory subtree skipping.
- [x] 5.5 Add protocol coverage or fakes for `Share`, `Device`, and `Network` source ignore resolution.
- [x] 5.6 Add move tests proving ignored source entries remain in place and parent directories with ignored content are not deleted.
- [x] 5.7 Keep existing ignore parser, browsing display, copy/move retry, continue-task, and serving behavior tests passing.

## 6. Validation

- [x] 6.1 Run `openspec validate skip-ignored-files-in-file-operations --strict`.
- [x] 6.2 Run relevant Gradle tests for shared file operation, ignore behavior, and serving code.
- [x] 6.3 Mark this checklist complete only after implementation and validation finish.
