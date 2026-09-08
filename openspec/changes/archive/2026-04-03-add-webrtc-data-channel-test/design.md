## Context
We need a cross-platform WebRTC data-channel test page in Settings that can manually connect via the WSS signaling server and transfer files. The signaling protocol is defined in `server/README.md` and uses `join/offer/answer/ice` messages with `SocketDevice` for identity.
The signaling server already supports multi-member rooms, but the current controller/UI only keeps one `PeerConnection` and one active remote device at a time.

## Goals / Non-Goals
- Goals:
  - Provide a Settings subpage for manual WebRTC data-channel testing.
  - Support sending and receiving files with progress + speed UI.
  - Support multiple simultaneous peer sessions inside one signaling room.
  - Keep per-peer connection state visible and allow the user to choose the active transfer target.
  - Discard received data immediately and avoid holding full file data in memory.
  - Use `webrtc-java:0.14.0` for JVM and `webrtc-kmp:0.125.11` for non-JVM targets.
- Non-Goals:
  - Audio/video tracks, media streaming, or production-grade UI/UX.
  - Automatic full-mesh connection to every online peer by default.
  - Background service or persistent transfer queues.
  - Broadcast one outgoing transfer to every connected peer in a single action.

## Decisions
- Create a shared WebRTC data-channel abstraction in `shared/commonMain` with platform-specific implementations in source sets.
  - JVM uses `webrtc-java:0.14.0`.
  - Android/iOS/JS/Wasm use `webrtc-kmp:0.125.11` (non-JVM).
- Follow `server/README.md` signaling JSON fields and message types:
  - `join`, `joined`, `peer-joined`, `peer-left`, `offer`, `answer`, `ice`.
  - `from`/`to` are `SocketDevice` objects; route by `to.id`.
- Keep a single signaling client per joined room, then maintain a `PeerSession` map keyed by remote device id.
  - Each `PeerSession` owns its own `PeerConnection`, data channels, reconnect bookkeeping, and negotiated transport capabilities.
  - Signaling messages are routed to the matching `PeerSession` by `from.id` / `to.id`.
- Implement a simple data-channel file protocol:
  - Send a JSON metadata frame (file name, size, mime, relative path if any, transfer id).
  - Send binary chunks in order; receiver updates progress/speed and discards chunk data immediately.
  - Rely on reliable/ordered data channel; no custom ACK/NAK in the first iteration.
- UI inputs are manual and persisted in settings:
  - WSS URL, roomId, local device id, remote peer id.
  - The remote peer id acts as a preferred/seed target for auto-connect, while the peer list exposes additional connect/disconnect controls per peer.
  - Outgoing transfers are scoped to one selected connected peer at a time; transfer state includes peer attribution for display.

## Risks / Trade-offs
- WebRTC library support may vary by platform; we will scope to non-JVM targets via `webrtc-kmp` and keep JVM on `webrtc-java`.
- Manual signaling reduces usability but keeps scope minimal and debuggable.
- No transfer-level acknowledgements means reliance on data-channel reliability; retries can be added later if needed.
- Multi-peer session management increases controller complexity and requires careful cleanup so one failed peer does not tear down unrelated peer sessions.

## Migration Plan
- Add new settings keys with defaults and UI for editing.
- Introduce or adapt the WebRTC controller to separate room signaling state from per-peer session state.
- Update the Settings entry and test screen with per-peer controls and target selection.

## Open Questions
- None.
