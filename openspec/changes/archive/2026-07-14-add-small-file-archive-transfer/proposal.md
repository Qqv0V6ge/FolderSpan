# Change: Add Small File Archive Transfer

## Why

Batch transfers with many small files are slow because each file currently pays separate HTTP request, scheduling, file-open, progress, and task-state overhead. Existing stream/range optimizations improve large files, but do not reduce the fixed per-file cost that dominates small-file folders.

## What Changes

- Add a first-party archive stream format for small-file batches. The stream carries directory/file entry metadata plus file bytes in one HTTP response or request body.
- Add archive download support for Share and Device sources so Share-to-local and Device-to-local folder copies can transfer eligible small files through one bounded stream per batch.
- Add archive upload support for local-to-Device folder copies so eligible local small files can be streamed to the device and unpacked server-side.
- Keep large files, unsupported sources, unsupported targets, failed archive batches, and older peers on existing per-file range/stream paths.
- Classify archive stream routes as Raw HTTP data routes so control requests keep reserved capacity during bulk archive transfer.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `http-file-transfer-status`: Extend HTTP transfer behavior with small-file archive stream routes and fallback to existing byte/stream routes.
- `http-socket-tls-transport`: Add archive stream routes to data-route capacity classification and request-body streaming admission.
- `manage-file-operation-tasks`: Preserve task progress, pause/cancel, retry metadata, and item-level fallback while transferring small-file batches through archive streams.

## Impact

- Affected code:
  - Raw HTTP routes and models under `shared/src/serverRouteMain/kotlin/com/folderspan/routes/`
  - HTTP clients under `shared/src/commonMain/kotlin/com/folderspan/service/http/client/`
  - File operation copy execution in `PathRouteClient` and `HttpShareRouteClientManager`
  - Platform Raw HTTP servers that decide whether request bodies are streamed
- Affected tests:
  - Archive frame codec tests, Raw HTTP route classification tests, client route selection tests, and copy-task fallback/progress tests.
- No breaking change is intended for existing request/response payloads. Existing byte and stream endpoints remain supported.
