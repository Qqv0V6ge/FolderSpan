## 1. Tests First

- [x] 1.1 Add planner unit tests for conservative start values, successful additive increase, timeout/busy multiplicative decrease, memory-pressure clamping, and throughput-based timeout calculation.
- [x] 1.2 Add Raw HTTP route classification tests for control routes, data routes, reserved control admission, and data keep-alive capacity accounting.
- [x] 1.3 Add client transfer tests proving large device-direct reads select stream-file while small reads can still use read-bytes.
- [x] 1.4 Add fallback tests proving a failed stream-file range retries unfinished ranges without restarting completed checkpointed data.

## 2. Adaptive Transfer Planning

- [x] 2.1 Implement an internal adaptive HTTP transfer planner for device-direct transfers with chunk size, stream range size, read/write parallelism, queue depth, and timeout outputs.
- [x] 2.2 Feed transfer status headers, observed range duration, bytes transferred, timeout failures, busy signals, and runtime memory status into planner updates.
- [x] 2.3 Replace fixed byte-route timeout usage for device-direct range reads with planner-derived bounded timeouts.
- [x] 2.4 Keep fixed safe fallback values when planner state is unavailable or transfer status is missing/invalid.

## 3. Stream-First Large Reads

- [x] 3.1 Route large device-direct local downloads and relay reads through `/api/files/stream-file` using bounded buffers.
- [x] 3.2 Preserve `/api/files/read-bytes` for small reads and fallback ranges.
- [x] 3.3 Wire stream fallback into checkpointed copy continuation so only unfinished ranges are retried.
- [x] 3.4 Preserve pause, cancel, request tracking, progress, retry metadata, and task result reporting in adaptive transfer flows.

## 4. Raw HTTP Capacity Isolation

- [x] 4.1 Add shared route classification for device Raw HTTP control and data routes.
- [x] 4.2 Split Raw HTTP server admission into reserved control capacity and bounded data capacity on Android, JVM, and iOS implementations.
- [x] 4.3 Allow controlled data keep-alive only after data connections are accounted separately from reserved control capacity.
- [x] 4.4 Preserve existing TLS, encrypted HTTP envelope, auth, and response body behavior.

## 5. Verification

- [x] 5.1 Run targeted JVM tests for planner, Raw HTTP keep-alive/admission, FileRouteClient, PathRouteClient, and task recovery behavior.
- [x] 5.2 Run `./gradlew :shared:jvmTest`.
- [x] 5.3 Run `./gradlew :shared:compileDebugKotlinAndroid`.
- [x] 5.4 Validate the OpenSpec change with `openspec validate update-http-transfer-stability-speed --strict`.

## 6. Stream-First Large Writes and Web Fragment Coalescing

- [x] 6.1 Add Raw HTTP and client tests for `/api/files/stream-upload`.
- [x] 6.2 Implement `/api/files/stream-upload` with streaming request-body classification and existing write validation.
- [x] 6.3 Route large local-to-device copies through stream upload before falling back to `/api/files/write-bytes`.
- [x] 6.4 Coalesce JS and Wasm `writeByteStream` fragments through a shared bounded buffer helper.

## 7. Runtime Queue Small File Archive Batching

- [x] 7.1 Add runtime queue planner tests for device-to-local archive batch selection and target-root relative path safety.
- [x] 7.2 Route runtime copy queue small-file batches through existing device/local archive download and upload APIs before falling back to per-file copy.
- [x] 7.3 Preserve runtime queue ack, retry cleanup, byte metrics, pause/cancel checks, and fallback behavior for archive-completed and unfinished entries.
