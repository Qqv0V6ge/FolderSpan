## 1. OpenSpec
- [x] 1.1 Create the device direct throughput proposal, design, and `http-file-transfer-status` delta.

## 2. Implementation
- [x] 2.1 Decouple `DeviceTransportPipeline` read parallelism, write parallelism, and queue depth while preserving existing call sites.
- [x] 2.2 Raise device direct HTTP in-flight budget to 128MB and allow deeper bounded request concurrency without increasing 8MB chunk size.
- [x] 2.3 Increase and unify raw byte read buffers for device and Share HTTP clients.
- [x] 2.4 Adjust `HttpFileTransferStatusProvider` recommendations to stay aggressive under low pressure and retain busy retry under high pressure.
- [x] 2.5 Stream device downloads to local range writes without unnecessary per-range `ByteArray` merge copies.
- [x] 2.6 Reuse Raw TLS HTTP/1.1 connections across sequential range requests without changing byte-route payloads.
- [x] 2.7 Pipeline device-to-device large file copy so source reads and destination writes overlap under bounded queue depth.
- [x] 2.8 Raise only the device direct byte-route chunk ceiling to 16MB while keeping the 128MB in-flight budget and generic Share/WebRTC limits.
- [x] 2.9 Preserve the 16MB device-direct chunk limit in relay/recovery schedulers, remove upload polling gaps, and allow single-file device-to-device duplex request permits.
- [x] 2.10 Stream device-to-local HTTP response bodies directly into local file ranges with reusable buffers.
- [x] 2.11 Replace device-to-local hot-path fragment writes with bounded range coalescing and single-block local writes.
- [x] 2.12 Tune Path copy runtime chunks to 10MB so 128MB in-flight budget can fill 12 client requests.
- [x] 2.13 Add a device-direct long range stream download fast path with byte-route fallback for unfinished ranges.
- [x] 2.14 Retune the long stream client to keep 12 parallel connections with 32MB target ranges after 6x64MB proved stable but slower.

## 3. Verification
- [x] 3.1 Update pipeline and transfer status tests for the new queue/concurrency and recommendation behavior.
- [x] 3.2 Run OpenSpec validation and shared JVM tests.
- [x] 3.3 Add keep-alive semantics coverage and run Android debug Kotlin compilation for Raw TLS changes.
- [x] 3.4 Add coverage for device-direct 16MB status/range compatibility and rerun OpenSpec/JVM/Android verification.
- [x] 3.5 Rerun OpenSpec validation, shared JVM tests, and Android debug Kotlin compilation after scheduler/relay updates.
- [x] 3.6 Add direct stream writer coverage and rerun OpenSpec/JVM/Android verification.
- [x] 3.7 Add regression coverage for coalesced device-to-local downloads and rerun JVM/Android/OpenSpec verification.
- [x] 3.8 Add chunk-plan coverage for 12-request startup fill and rerun JVM/Android/OpenSpec verification.
- [x] 3.9 Add long stream endpoint/client coverage and rerun JVM/Android/OpenSpec verification.
- [x] 3.10 Add long stream width coverage and rerun JVM/Android/OpenSpec verification.
