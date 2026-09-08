## 1. Session frame bed

- [x] 1.1 Add `core/src/commonMain/kotlin/com/folderspan/service/session/` with frame types, little-endian codec, and 64 KiB payload cap
- [x] 1.2 Add in-memory credit window that blocks data at zero credit and resumes on window update
- [x] 1.3 Add multiplexed loopback session covering interleaved streams, ping/pong, RST, GOAWAY, and the 256-stream cap
- [x] 1.4 Add `commonTest` coverage for oversized frames, truncated frames, unknown types, and low-memory window clamp

## 2. TLS session handshake

- [x] 2.1 Advertise and require ALPN `folderspan/1` in `DeviceTlsIdentity` on JVM, Android, and iOS
- [x] 2.2 Change the native device listener to enter the session loop after ALPN and close HTTP or missing-ALPN connections
- [x] 2.3 Implement session `Connect` using existing identity proof and bind the token to the TLS connection
- [x] 2.4 Replace `/api/devices/heartbeat` with control-stream ping/pong that updates last-seen
- [x] 2.5 Point native `DeviceState.connect` at the session client and stop creating device `HttpRouteClientManager` instances
- [x] 2.6 Configure ALPN on every accepted Android TLS socket, retain API 26–28 Conscrypt compatibility, and verify Linux-to-Android negotiation on a real device

## 3. Streaming file transfer

- [x] 3.1 Add `writeStream` / `readStream` on `DeviceFileClient` and implement them on the session client
- [x] 3.2 Stream server writes through `DeviceFileService.prepareWriteBytes` plus platform range writes (no whole-file `ByteArray`)
- [x] 3.3 Switch `PathRouteTransferClient` / `PathRouteWriteTransferClient` native device copies to session streams
- [x] 3.4 Keep same-target writes serial and allow concurrent streams for distinct files within the session window
- [x] 3.5 Delete native device HTTP file routes: read-bytes, write-bytes, stream-file, stream-upload

## 4. Directory copy and remaining device HTTP

- [x] 4.1 Send batched directory manifests and create empty directories over control RPCs
- [x] 4.2 Copy each file on its own stream; skip symbolic links and record them as failures
- [x] 4.3 Move remaining path, bookmark, and copy-control HTTP routes to control RPCs
- [x] 4.4 Remove `/api/webrtc/signaling/` from the device listener so port 12040 never parses HTTP
- [x] 4.5 Delete leftover device HTTP routes including archive-download/upload on the device port

## 5. LAN beacon discovery

- [x] 5.1 Broadcast a signed UDP beacon on 12041 from native listeners (link/site local only)
- [x] 5.2 Replace native `DeviceState.scanner` HTTPS ping with continuous beacon receive
- [x] 5.3 Keep manual IP connect on the session port; do not fall back to HTTPS ping
- [x] 5.4 Keep same-account auto-connect, but evaluate beacons and session `Connect` instead of HTTP ping/connect
- [x] 5.5 Bootstrap unknown manual IP endpoints with ALPN-only TLS `Identify`, then reconnect with the captured certificate fingerprint pinned
- [x] 5.6 Keep the remote beacon source out of the local-address fallback set and cover peer discovery with a regression test
- [x] 5.7 Preserve a pending native session `Loading` state across repeated beacon refreshes and add regression coverage

## 6. Cleanup

- [x] 6.1 Change `DeviceTransportType` to `Session | WebRtc` and update `SocketDevice` connection checks
- [x] 6.2 Remove native device usage of `HttpRouteClientManager`, dual HTTP clients, and `CloseOnUnauthorized` on that path
- [x] 6.3 Leave LinkShare HTTP, `HttpShareRouteClientManager`, WebRTC, and `NetworkClient` unchanged
- [x] 6.4 Update `service/http` AGENTS notes so device API is documented as the TLS session, not HTTP/1.1
- [x] 6.5 Run `openspec validate device-tls-session-transport --strict` and `:core:jvmTest` for session plus remaining device tests

## 7. Continuous native discovery cleanup

- [x] 7.1 Add regression coverage for native continuous Beacon discovery versus triggered WebRTC browser discovery
- [x] 7.2 Restrict `DeviceState.scanner` and its loading/pause state to JS/Wasm WebRTC discovery
- [x] 7.3 Remove native startup/resume, drawer, file-share, and desktop-tray scan actions while retaining manual IP connection
- [x] 7.4 Run focused discovery tests, relevant module tests/compilation, and strict OpenSpec validation
- [x] 7.5 Remove normal per-packet Beacon discovery logs while retaining lifecycle and failure diagnostics, then validate affected targets

## 8. Session transfer throughput regression

- [x] 8.1 Add regression coverage proving one stream can pipeline multiple 64 KiB frames and Session downloads write chunks incrementally
- [x] 8.2 Decouple per-stream credit from the 64 KiB frame cap while preserving the memory-bounded aggregate session window and critical-memory clamp
- [x] 8.3 Write Session download chunks directly to their local target range instead of buffering each recovery range before disk I/O
- [x] 8.4 Collapse JVM and Android frame write-plus-flush into one I/O dispatcher hop
- [x] 8.5 Run focused Session/file tests, relevant platform compilation, and strict OpenSpec validation

## 9. Stable single-file session throughput

- [x] 9.1 Add regression coverage for sequential same-file recovery ranges and bounded persistent range writes
- [x] 9.2 Serialize recovery ranges for one file when either device endpoint requires ordered range transfer, while preserving concurrency across distinct files
- [x] 9.3 Bridge Session download frames into one `FileUtils.writeByteStream` call with a bounded 256 KiB coalescing buffer
- [x] 9.4 Run focused recovery/file/session tests, relevant native compilation, and strict OpenSpec validation

## 10. Continuous Session throughput

- [x] 10.1 Add regression coverage proving a resumed Session download uses one direct stream from the committed offset through EOF while advancing checkpoints from committed local writes
- [x] 10.2 Replace per-4 MiB Session range OPEN/TRAILER cycles with one long-lived download stream and publish byte progress inside that stream
- [x] 10.3 Add regression coverage and implementation for bounded batching of already queued frames into fewer TLS write-and-flush operations
- [x] 10.4 Read Session source files in bounded multi-frame blocks and emit completion diagnostics for bytes, duration, effective rate, and cumulative send time
- [x] 10.5 Run focused recovery/file/session tests, relevant native compilation, strict OpenSpec validation, and diff checks

## 11. Stable receive hot path and rate reporting

- [x] 11.1 Add a regression test proving a high-rate continuous download coalesces hundreds of recovery boundaries into bounded checkpoint writes while persisting EOF
- [x] 11.2 Coalesce durable checkpoints by committed bytes or elapsed time without delaying local progress, receive credit, cancellation, or pause checks
- [x] 11.3 Add a regression test for short-window transfer-rate smoothing after the first complete sample
- [x] 11.4 Smooth displayed runtime transfer rates without changing exact byte progress or checkpoint offsets
- [x] 11.5 Run focused recovery/rate/session tests, full core JVM tests, relevant native compilation, strict OpenSpec validation, and diff checks

## 12. Checkpoint write-behind and late-transfer diagnostics

- [x] 12.1 Add a regression test proving blocked checkpoint persistence does not stop continuous committed-byte flow
- [x] 12.2 Move continuous Session checkpoint replacement to one bounded latest-wins writer, drain EOF before success, and keep cancellation non-blocking
- [x] 12.3 Emit one receive-completion diagnostic with bytes, duration, checkpoint write count/time, and maximum committed-write gap
- [x] 12.4 Run focused recovery tests, full core JVM tests, relevant native compilation, strict OpenSpec validation, and diff checks
