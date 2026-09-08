## Context

Device-direct HTTP copy currently has two conflicting pressures:

- Stability: heartbeat, pause, cancel, and disconnect handling must remain responsive while directory traversal or byte transfer is busy.
- Speed: high-quality LAN transfers need enough range size, keep-alive reuse, and read/write overlap to fill the link.

Recent diagnostics showed two classes of failures:

- heartbeat timeouts during directory traversal or bulk transfer pressure;
- `/api/files/read-bytes` range request timeouts when a fixed 16MB byte-route request cannot complete within the fixed 20 second timeout.

The codebase already has transfer status headers, stream-file routes, checkpointed file operations, request cancellation, and endpoint-adaptive traversal. This change builds on those pieces instead of adding a new protocol.

## Goals / Non-Goals

**Goals:**

- Keep device control requests responsive during bulk transfer.
- Increase transfer speed automatically when the link and devices are stable.
- Reduce timeout frequency by using streaming large ranges and adaptive request deadlines.
- Preserve existing ProtoBuf and octet-stream request/response payload contracts.
- Preserve pause, cancel, retry, checkpoint, and permission behavior.

**Non-Goals:**

- No UI setting is required for manual speed tuning in this change.
- No new external dependency or transport protocol is introduced.
- No change to WebRTC payload-channel transfer semantics.
- No change to share browser APIs beyond reusing existing transfer status concepts if needed later.

## Decisions

### Prefer stream-file for large ranges

Large device-direct downloads and relay reads shall prefer `/api/files/stream-file` once the requested range is above a small threshold or when the planner chooses a stream range. The stream route writes bytes as they are read instead of materializing the whole range in one `ByteArray`, which lowers memory pressure and makes long requests less likely to fail because a single fixed block deadline is too short.

`/api/files/read-bytes` remains available for small ranges and as fallback for unfinished large ranges. This keeps existing payload semantics intact and avoids making every tiny file pay the stream setup cost.

Alternatives considered:

- Increase `read-bytes` timeout only. This hides symptoms but still allocates full blocks and keeps poor pressure behavior.
- Use stream-file for all transfers. This is stable but can be slower for tiny files and small metadata-driven reads.

### Add an adaptive transfer planner

Introduce an internal planner for device-direct HTTP transfers. It will maintain a per-client or per-transfer window with:

- byte-route chunk size;
- stream range size;
- read parallelism;
- write parallelism;
- queue depth;
- request timeout.

The planner starts conservatively and uses additive increase / multiplicative decrease:

- after consecutive successful low-latency ranges, it grows parallelism and range size within memory, protocol, and server-advertised limits;
- after timeout, server busy, low memory, slow range, write backpressure, or cancellation-adjacent failures, it halves parallelism and lowers range size;
- timeout calculation uses recent throughput and range size with a floor and ceiling, instead of a fixed 20 seconds for every range.

This matches the existing transfer status header model while making the client respond to actual transfer behavior.

Alternatives considered:

- Fixed safe values. Stable but leaves speed on the table for strong LAN links.
- Always use max values. Fast in ideal cases but reintroduces heartbeat starvation and timeout bursts.

### Split control and data server capacity

Raw HTTP server connection admission shall distinguish control and data paths. Control paths include heartbeat, connect, theme, copy-control, and request cancellation style routes. Data paths include list, read/write bytes, stream-file, and copy.

The server shall reserve capacity for control routes and apply separate data limits for bulk routes. Once this exists, data keep-alive can be allowed again within controlled limits, which restores speed without letting idle data sockets starve heartbeats.

Alternatives considered:

- Keep all data routes as forced short connections. This protects heartbeat but costs speed because TLS and socket setup repeats for each range.
- Raise global `MAX_CONCURRENT_CLIENTS`. This delays starvation but does not guarantee control-route admission.

### Keep operation semantics at the task boundary

The adaptive planner is an implementation detail beneath file operation tasks. Task code still owns:

- manifest ordering;
- pause/cancel checks;
- retry item recording;
- checkpoint persistence;
- task progress and metrics.

Read/write transport helpers may change how they move bytes, but they must report completion and failure through the existing task pipeline.

## Risks / Trade-offs

- Planner oscillation under unstable Wi-Fi -> Use conservative start values, bounded growth, and cooldown after failures.
- Stream-file can hold a connection longer than read-bytes -> Data capacity isolation and adaptive stream parallelism keep long streams bounded.
- More code paths can complicate recovery -> Keep fallback range-aware and reuse existing checkpoints for unfinished ranges.
- Controlled data keep-alive could reintroduce starvation if classification is wrong -> Add tests for route classification and reserved control capacity.
- Android memory pressure varies widely -> Include runtime memory status in planner bounds and reduce stream buffer/range size when low memory is reported.

## Migration Plan

1. Add planner unit tests for increase/decrease, timeout calculation, and transfer-status clamping.
2. Add Raw HTTP route classification tests for control/data capacity and keep-alive behavior.
3. Implement planner and route server admission without changing public payload contracts.
4. Route large device-direct downloads and relays through stream-file with range fallback.
5. Re-enable data keep-alive only after server-side control/data isolation tests pass.
6. Run shared JVM tests and Android shared compilation.

Rollback can disable the planner by falling back to the existing fixed chunk and byte-route behavior, while keeping control/data admission isolation if it proves safe.

## Open Questions

- The initial stream threshold should start conservatively at 8MB unless profiling suggests a lower value.
- The maximum target stream range should remain no larger than the existing device-direct stream target range unless Android memory telemetry proves it safe.
