## Context

Large-file HTTP transfer already uses adaptive chunks and long streams. Small-file folders still perform poorly because thousands of files create thousands of route calls and local/remote file operations. Standard ZIP would reduce requests, but it would add cross-platform ZIP dependencies, require temporary archives or central-directory buffering, and make item-level retry harder.

## Goals / Non-Goals

**Goals:**

- Reduce batch small-file transfer time by grouping eligible entries into archive streams.
- Avoid temporary archive files and double disk usage.
- Preserve path validation, permissions, pause/cancel, retry, progress, and fallback semantics.
- Keep current large-file stream/range behavior unchanged.

**Non-Goals:**

- No user-visible `.zip` export/import feature.
- No compression in the first version; the archive stream is a framing format, not a compressed file.
- No device-to-device archive relay in v1; device-to-device continues using the existing bounded read/write pipeline.
- No change to WebRTC transfer semantics.

## Decisions

### Use a FolderSpan archive stream v1

Create a common archive-stream codec with this wire shape:

- magic bytes: `FSAR1`;
- repeated frames:
  - 4-byte big-endian protobuf header length;
  - protobuf `ArchiveEntryHeader`;
  - `size` raw bytes for file frames;
- end marker: 4-byte zero header length.

`ArchiveEntryHeader` stores a normalized relative path, entry kind (`file` or `directory`), byte size, modified time if available, and display metadata already present on `FileSimpleInfo` when useful. Directory frames have size `0`. File frame bytes follow immediately after the header and are copied with bounded buffers.

The codec rejects absolute paths, empty file paths, `..` segments, backslashes, duplicate file entries, negative sizes, sizes over the batch limit, and streams that end before the declared bytes are consumed.

### Add archive routes without replacing byte routes

Add these routes:

- `/api/files/archive-download` streams a requested set of small Device file entries.
- `/api/files/archive-upload` accepts an archive stream and extracts it under a validated destination root.
- `/api/share/archive-download` streams a requested set of small Share file entries.

Download requests are protobuf metadata. Upload uses protobuf metadata in a header, matching the existing `write-bytes` streaming pattern, and raw archive bytes in the request body. Native TLS carries raw bytes; JS/Wasm encrypted HTTP falls back to existing per-file paths unless encrypted streaming support already exists for the route.

Existing `/api/files/read-bytes`, `/api/files/write-bytes`, `/api/files/stream-file`, `/api/share/read-bytes`, and `/api/share/stream-file` remain the fallback for ineligible entries and older peers.

### Select small-file archive batches conservatively

During folder copy, after traversal has produced normalized entries:

- group files at or below a small-file threshold, including zero-byte files;
- include directory frames needed for the grouped files;
- keep each archive batch under a bounded entry count and total bytes;
- exclude files that need per-file checkpointing, files that changed size during local upload preparation, and entries already failed permission/path validation;
- copy large files and skipped entries through the existing path.

Recommended initial constants:

- small file threshold: `1 MiB`;
- target archive entries per batch: `64`;
- target archive payload bytes per batch: `8 MiB`;
- max archive entries per batch: `512`;
- max archive payload bytes per batch: `32 MiB`;
- archive stream buffer: reuse the existing raw/stream transfer buffer size.

These values reduce request count without creating huge all-or-nothing batches.

### Preserve task semantics

Archive transfer is an execution optimization beneath file tasks:

- pause/cancel checks run before starting each batch and between decoded file entries;
- task progress advances per completed archive entry, not only per archive response;
- byte metrics update as file payload bytes are written;
- archive download and upload extraction writes file payloads through bounded streaming buffers instead of materializing each file payload as a whole `ByteArray`;
- if an archive batch fails, the task records retry metadata for entries not confirmed complete and falls back to existing per-file transfer for remaining entries when the failure is recoverable;
- successful sibling entries are not retried.

### Keep control routes responsive

Archive download/upload routes are Raw HTTP data routes. Upload request bodies are streamed only for the archive upload route and existing write-bytes route. Control routes keep separate capacity and keep-alive accounting.

## Risks / Trade-offs

- Archive batch failure can lose more work than one-file requests -> keep batches bounded and record confirmed entries as they complete.
- Path traversal bugs would be high impact -> normalize and validate every frame on both sender and receiver.
- Local-to-device upload streaming needs Raw HTTP request-body streaming support -> explicitly whitelist `/api/files/archive-upload` and test it on JVM/Android/iOS server code.
- No compression means less bandwidth reduction -> the target bottleneck is request overhead for small files, not byte volume.

## Migration Plan

1. Add archive frame models, codec helpers, and validation tests.
2. Add Raw HTTP route classification and body streaming tests.
3. Implement archive download routes for Device and Share.
4. Implement Device archive upload extraction.
5. Add client helpers and route-selection tests.
6. Wire folder copy batches for Share-to-local, Device-to-local, and local-to-Device.
7. Verify fallback keeps existing behavior for large files, unsupported peers, and failed batches.

Rollback can disable archive route selection while leaving the new routes unused; existing per-file routes continue to work.
