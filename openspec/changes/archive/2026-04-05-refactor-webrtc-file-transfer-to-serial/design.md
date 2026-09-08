## Context
The repository currently contains two WebRTC file-transfer controllers. Both controllers still carry multi-stream striped sending logic, while the receive side and retransmit flow already work correctly with a single stream. Because WebRTC transfer has not shipped, we can simplify the send path directly instead of preserving a compatibility layer.

## Goals
- Make file payload sending default to a single dedicated payload data channel.
- Keep control traffic on the control channel and keep receiver confirmation semantics unchanged.
- Reduce controller complexity and make logs, tests, and specs describe serial transfer explicitly.

## Non-Goals
- Do not change signaling, connection approval, device RPC, or copy-progress envelopes.
- Do not introduce a user-facing switch between serial and parallel transfer modes.
- Do not redesign the receive-side retransmit protocol.

## Decisions
- Transport capability exchange remains, but negotiated/fallback send plans now always resolve to one payload stream.
- Existing protocol fields such as `maxStreams` and `FileMeta.streams` remain in place for now and are emitted as `1`.
- The existing payload send pipeline stays reusable, but queueing and channel creation are constrained to a single stream.
- Both controllers adopt the same serial behavior to avoid divergence between production and test code paths.

## Risks / Trade-offs
- Transfer throughput may decrease on fast peer pairs compared with the striped model.
- Some multi-stream helper code remains structurally present, but its runtime behavior is now fixed to single-stream delivery.
