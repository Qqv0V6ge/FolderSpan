## Why

Large JVM-to-Android and Android-to-JVM HTTP file copies can time out or temporarily starve heartbeat/control traffic when byte-range requests use fixed chunk sizes, fixed timeouts, and aggressive data concurrency. The transfer layer needs to keep control routes responsive while still increasing throughput automatically on stable links.

## What Changes

- Prefer `/api/files/stream-file` for large device-to-local and device-to-device read ranges, using `/api/files/read-bytes` as a fallback for unfinished ranges or smaller transfers.
- Add adaptive HTTP transfer planning for device-direct transfers:
  - dynamically choose chunk size, stream range size, parallelism, queue depth, and request timeout from observed throughput and transfer status;
  - grow throughput after successful low-latency ranges;
  - reduce pressure quickly after timeouts, slow ranges, busy signals, low memory, or write backpressure.
- Split Raw HTTP server capacity into control and data classes so heartbeat, connect, pause, resume, cancel, and disconnect requests have reserved service capacity even during bulk data transfer.
- Re-enable controlled data keep-alive where safe after control/data capacity isolation is in place.
- Preserve existing request cancellation, pause/resume, retry, checkpoint, permission, and TLS behavior.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `http-file-transfer-status`: Extend HTTP transfer tuning from static response recommendations to adaptive client-side planning, large-range stream preference, timeout calculation, and safe fallback.
- `http-socket-tls-transport`: Add control/data request capacity isolation and controlled keep-alive behavior for first-party Raw HTTP device routes.
- `manage-file-operation-tasks`: Clarify that file operation transfer execution must keep pause/cancel/checkpoint semantics while using adaptive stream and byte-range transfer plans.

## Impact

- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/client/FileRouteClient.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/client/PathRouteClient.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/client/HttpRangeTransfer.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/operation/*`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt`
  - platform Raw HTTP servers under `shared/src/{androidMain,jvmMain,iosMain}/kotlin/com/folderspan/service/http/server/raw/`
- Affected tests:
  - HTTP transfer tuning, range copy, cancellation, keep-alive, and task recovery tests.
- No API-breaking change is intended for existing request/response payloads.
