## 1. Tests First

- [x] 1.1 Add common archive codec tests for magic bytes, frame round trip, end marker, invalid path rejection, declared-size mismatch, duplicate file rejection, and truncated payload failure.
- [x] 1.2 Add Raw HTTP route tests proving `/api/files/archive-download`, `/api/files/archive-upload`, and `/api/share/archive-download` are data routes and archive upload bodies are streamed.
- [x] 1.3 Add client route-selection tests proving eligible small-file folder batches use archive transfer while large files and unsupported peers stay on existing byte/stream routes.
- [x] 1.4 Add task behavior tests proving pause/cancel checks, per-entry progress, retry metadata, and recoverable fallback remain item-based.

## 2. Archive Stream Protocol

- [x] 2.1 Add common request/header models and archive frame header models with appended ProtoNumber fields only.
- [x] 2.2 Implement bounded archive stream encoder/decoder helpers with path normalization, size limits, entry count limits, and duplicate detection.
- [x] 2.3 Add shared constants for small-file threshold, max archive entries, max archive payload bytes, and archive stream buffer size.

## 3. Raw HTTP Server Support

- [x] 3.1 Add `/api/files/archive-download` and `/api/share/archive-download` handlers that validate requested entries and stream archive frames from source files.
- [x] 3.2 Add `/api/files/archive-upload` handler that validates destination metadata, streams request body frames, creates directories, and writes file bytes with bounded buffers.
- [x] 3.3 Extend Raw HTTP route classification and platform request parsing so archive data routes use data capacity and archive upload can receive a streaming body.
- [x] 3.4 Preserve TLS/encrypted HTTP behavior by using raw archive bodies on native TLS and falling back for JS/Wasm encrypted HTTP when streaming upload is unavailable.

## 4. Client Integration

- [x] 4.1 Add `FileRouteClient` helpers for archive download and archive upload with request tracking, transfer status parsing, and bounded timeouts.
- [x] 4.2 Add `HttpShareRouteClientManager` helper for Share archive download plus fallback to existing share read/stream paths.
- [x] 4.3 Add folder-copy batching that groups eligible small files after traversal and excludes large, zero-byte, checkpointed, or validation-sensitive entries.
- [x] 4.4 Wire archive batches into Share-to-local, Device-to-local, and local-to-Device copy flows while leaving device-to-device on the existing pipeline.

## 5. Verification

- [x] 5.1 Run targeted JVM tests for archive codec, Raw HTTP route classification, FileRouteClient, PathRouteClient, and HttpShareRouteClientManager.
- [x] 5.2 Run `./gradlew :shared:jvmTest`.
- [x] 5.3 Run `./gradlew :shared:compileDebugKotlinAndroid`.
- [x] 5.4 Validate the OpenSpec change with `openspec validate add-small-file-archive-transfer --strict` when the OpenSpec CLI is available.
