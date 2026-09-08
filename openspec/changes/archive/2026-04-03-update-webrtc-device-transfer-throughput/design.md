## Context
`MultiPeerWebRtcTestController` currently performs production file transfer over a single primary data channel with fixed chunk sizes and a simple `bufferedAmount` loop. The repo already contains a more advanced transfer strategy in `WebRtcTestController`: platform-aware tuning, transport capability negotiation, striped stream channels, and windowed backpressure.

## Goals
- Improve production WebRTC file transfer throughput without changing user-visible file transfer semantics.
- Keep control traffic on the control channel and move file payloads onto striped stream channels.
- Prefer reliable delivery by default while allowing platform-aware throughput tuning.
- Preserve compatibility with peers that do not deliver transport capabilities in time by falling back to safe defaults.

## Non-Goals
- Do not redesign device approval, signaling, RPC envelopes, or copy-progress semantics.
- Do not introduce a new user-facing configuration surface for transfer tuning.
- Do not remove existing receive-side compatibility for legacy/single-stream chunk frames.

## Decisions
- Keep the control channel reliable/ordered for signaling-adjacent frames and RPC traffic.
- Exchange `frameTransport` capability payloads when the control channel opens.
- Negotiate transfer plan using the minimum safe values across local and remote capabilities.
- Use striped stream channels for file payload transfer, even when the negotiated stream count falls back to `1`.
- Reuse the existing `ReceivedByteRangeTracker`-based receive path so unordered reliable transfer remains safe.
- Wait for stream channels to drain before marking send completion.

## Risks / Trade-offs
- Multi-stream sending adds controller complexity and raises the cost of per-peer cleanup.
- Capability negotiation fallback must stay conservative to avoid regressions with slower peers/platforms.
- Throughput gains depend on both peers running compatible versions; fallback behavior must remain correct.
