## Context

See proposal.md for why the HTTP/1.1 device API is being replaced. Native desktop, Android, and iOS already share `RawTlsHttpServer`, `DeviceTlsIdentity` pinning, and `DeviceFileService` / `DevicePathService` business logic. This change keeps those identities and services, and replaces only the application protocol on port `12040`. Development has no shipped clients, so there is no HTTP dual-stack.

## Goals / Non-Goals

**Goals:**

- One pinned TLS connection per device pair, ALPN `folderspan/1` only.
- Multiplex control RPCs and credit-limited file streams so heartbeat cannot be starved.
- Stream files in ≤64 KiB frames while allowing multiple frames in flight within a memory-bounded stream/session credit window.
- Continuously discover native LAN devices with a signed UDP beacon instead of triggered /24 HTTPS ping.
- Keep LinkShare HTTP, WebRTC, and network-drive protocols unchanged.

**Non-Goals:**

- HTTP/1.1 fallback, ALPN `http/1.1`, or old-client compatibility.
- QUIC / HTTP/3 (no KMP server library covering iOS).
- Bridging session frames onto WebRTC DataChannel in this change.
- Making JS/Wasm a device session server or a `12040` client.
- Rewriting `DeviceFileService` permission / path policy.

## Decisions

### 1. Custom TLS session instead of HTTP/2 or QUIC

HTTP/2 still has a request/response model and is not available on the current raw server or Ktor CIO. QUIC would remove TCP HOL, but there is no production KMP QUIC server for JVM + Android + iOS. A length-prefixed multiplexed session on the existing TLS stack is implementable on all three natives and matches the “one connection, many streams, credit windows” need.

### 2. Same port, ALPN-only dispatch

Reuse `12040` and the current device certificate. After TLS, require ALPN `folderspan/1` and close anything else. Do not parse HTTP request lines on that listener. Link-share keeps its own HTTP/HTTPS port.

Android TLS providers do not consistently propagate application protocols configured on `SSLServerSocket` to each accepted `SSLSocket`. Configure `folderspan/1` again on every accepted socket before `startHandshake()`. API 29+ uses `SSLParameters.applicationProtocols`; API 26–28 uses the platform Conscrypt socket ALPN methods so the minimum supported Android version keeps the same protocol requirement.

WebRTC HTTP signaling currently lives on the same device dispatcher (`/api/webrtc/signaling/`). Move native-hosted browser signaling off that port in this change so `12040` never speaks HTTP. Browsers continue to connect over the existing WebRTC path, not `folderspan/1`.

### 3. Frame format is independent of WebRTC chunks

Do not reuse `WebRtcChunkCodec`. That codec is an SCTP message layout without credit, OPEN, or session ping. The session frame is:

```
u8 type | u32 streamId | u32 length | payload[length]
```

Little-endian, `length <= 65536`. Stream 0 is control. Clients allocate odd ids, servers even ids. Hard cap 256 active streams.

Types: `WINDOW_UPDATE`, `OPEN`, `DATA`, `TRAILER`, `RST`, `PING`, `PONG`, `GOAWAY`.

### 4. Control RPCs keep existing protobuf messages

Map current dispatcher paths to RPC names (`Connect`, `ListPath`, `CreateFolders`, …) and reuse the existing `@Serializable` protobuf DTOs. After `Connect`, bind the token to the TLS session object; later frames do not send `Authorization`. Revocation sends `GOAWAY`.

### 5. Streaming DeviceFileClient for Session, ByteArray only for WebRTC

Add `writeStream` / `readStream` to `DeviceFileClient`. Session implementations MUST use them. WebRTC may keep `writeBytes` / `readBytes` as an adapter. Server writes go through existing `DeviceFileService.prepareWriteBytes` plus a streaming `FileUtils` range write. Do not feed session transfers through `runDeviceTransportPipeline`’s `ByteArray` queue.

Same-target writes stay serial (`sameTargetFileWriteParallelism = 1`). Distinct files may use concurrent streams limited by the session window.

### 6. Directory copy is manifest + per-file streams

Default path: batched `OPEN manifest`, control `CreateFolders` for empty dirs, one write stream per file. Optional archive stream is allowed only as a later fast path for huge numbers of tiny files, and must decode to disk incrementally. HTTP archive routes on the device listener are deleted.

### 7. Credit windows replace HTTP transfer headers for devices

Reuse `HttpTransferRuntimeMemoryStatus` / `HttpTransferRuntimeTuning`, but apply them to session receive windows:

| memory | session window | per-stream ceiling | file streams |
|---|---|---|---|
| critical | 256 KiB | 256 KiB | 1 |
| tight | 1 MiB | 1 MiB | 2–4 |
| ample | 8–16 MiB | shared full session window | 8–16 |

The 64 KiB value is the maximum payload of one frame, not a stream credit window. A single large-file stream must be able to pipeline multiple frames before the first `WINDOW_UPDATE`; otherwise every frame becomes a network round trip and throughput collapses. The session window remains the aggregate in-flight memory bound across concurrent streams, while each stream may consume the available session credit. Receivers return credit only after the corresponding chunk has been consumed, such as after a download chunk has been written to its target range.

Recovery parallelism applies across distinct files, not across fixed-size ranges of the same file. Concurrent range streams on one TLS/TCP connection divide the same credit and socket capacity while turning sequential source and destination I/O into random access. A resumable single-file Session download therefore opens one read stream from the latest committed checkpoint offset through EOF; the fixed-size recovery chunk remains only the checkpoint durability unit and does not create a new protocol stream. The local receiver bridges session frames into one `FileUtils.writeByteStream` call, keeps one platform file handle open for the remaining range, coalesces frames into a bounded 256 KiB buffer, and persists each newly completed checkpoint boundary after the corresponding local bytes are committed.

The sender may read a bounded 256 KiB source block and split it into protocol frames no larger than 64 KiB. The transport writer drains already queued frames into a bounded TLS write batch without waiting to fill the batch, preserving frame boundaries and control ordering while avoiding one dispatcher hop and one flush for every 64 KiB DATA frame. Transfer completion diagnostics record bytes, elapsed time, effective bytes per second, and cumulative `sendData` time without logging file contents.

Checkpoint durability must not turn each adaptive recovery chunk into synchronous metadata I/O on the receive hot path. During one continuous Session download, the receiver coalesces committed recovery boundaries and schedules only the latest eligible checkpoint when either 64 MiB more data has committed, two seconds have elapsed since the previous scheduling point, or EOF is committed. Progress and credit still advance from each bounded local write; only durable checkpoint file replacement is coalesced. While checkpoint storage remains responsive, a crash replays only the most recent bounded interval. If storage stalls, the single latest pending checkpoint remains memory-bounded and catches up after storage recovers instead of stopping the transfer. The displayed transfer rate uses a short exponentially weighted moving average after the first complete sample so one checkpoint, scheduler slice, or filesystem pause does not appear as a network-rate collapse.

The remaining durable checkpoint writes also run outside the Session receive callback. One background writer owns a bounded latest-wins pending slot, so a slow temporary-file replace or persistence lock cannot delay local byte progress or the `WINDOW_UPDATE` coupled to it. Normal EOF closes and drains that writer before success is reported; cancellation does not wait for a blocked metadata write. A completion diagnostic records transfer bytes and duration together with checkpoint write count, cumulative and maximum checkpoint persistence time, and the maximum gap between committed local writes. This separates metadata stalls from network or destination-write stalls without adding per-frame logs.

Share HTTP (`/api/share/read-bytes`) keeps `X-FolderSpan-Transfer-*` headers.

### 8. UDP beacon replaces HTTPS ping

Beacon port `12041`, signed with `DeviceTlsIdentity.signSha256WithRsa`, link/site local only. The native discovery listener receives beacons continuously; it does not sweep /24 with TLS. Manual IP still opens `folderspan/1` on `12040`. Web discovery stays on WebRTC.

Native discovery is continuous for the lifetime of `DeviceState`: it starts the beacon listener once and consumes valid beacons without a startup/resume scanner job or a scan/pause UI. `DeviceState.scanner` remains only for JS/Wasm WebRTC discovery. Manual IP entry remains available on native platforms and is not treated as a scan.

Repeated beacons merge discovery metadata without replacing an in-flight native session state. In particular, `Loading` remains visible while the `Connect` RPC is waiting for remote approval even though the session client is not attached to the device record until approval succeeds.

When a manually entered endpoint has no matching discovered device, the client performs a narrow TOFU bootstrap on the session port: negotiate only `folderspan/1`, capture the presented leaf-certificate SHA-256 fingerprint, and call an unauthenticated read-only `Identify` RPC for the peer's public `SocketDevice` identity. The bootstrap connection performs no device business operation and is closed before the client reconnects with the captured fingerprint pinned. Known endpoints reuse their discovered identity and fingerprint without this probe.

### 9. Transport enum

`DeviceTransportType` becomes `Session | WebRtc`. Native LAN uses `Session`. `HttpRouteClientManager` leaves the native device connect path. `HttpShareRouteClientManager` remains for LinkShare.

## Risks / Trade-offs

- [UDP isolation] → Manual IP connect; no HTTPS ping fallback, per spec.
- [iOS background freeze] → Detect half-open sessions, reconnect, resume at committed offset.
- [Self-made multiplexing] → 256-stream cap, RST on unknown/oversize frames, never crash the accept loop.
- [TCP HOL on lossy Wi-Fi] → Rebuild the session on persistent timeout; do not add a second data connection.
- [Browser signaling on 12040] → Remove HTTP signaling from the device port; browsers use the existing WebRTC path.
- [Misuse of curl/HTTP on 12040] → Expected failure. LinkShare HTTP stays on the share port.

## Migration Plan

Development cutover, no dual-stack:

1. Land frame codec + in-memory session tests.
2. Switch the native device listener to ALPN-only session handshake (`Ping` / `Connect` / session ping).
3. Cut file and directory copy to streams; delete device HTTP file routes.
4. Cut scanner to UDP beacon; delete HTTPS ping.
5. Remove `DeviceTransportType.Http` and device `HttpRouteClientManager` usage.

Rollback is a git revert of the change. There is no protocol compatibility window.

## Open Questions

None that affect specs or task breakdown. Optional archive-stream fast path can be deferred until directory multi-stream is measured.
