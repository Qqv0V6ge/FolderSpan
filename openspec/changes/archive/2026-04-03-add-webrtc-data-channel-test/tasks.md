## 1. Implementation
- [x] 1.1 Add Settings keys/state for WebRTC test inputs (WSS URL, roomId, local device id, remote peer id) and persist defaults.
- [x] 1.2 Add WebRTC data-channel client abstraction in `shared` with platform-specific implementations (JVM via `webrtc-java:0.14.0`, non-JVM via `webrtc-kmp:0.125.11`).
- [x] 1.3 Refactor the signaling/controller layer to keep one room-level signaling client and route `offer/answer/ice` to independently managed peer sessions by `SocketDevice.id`.
- [x] 1.4 Support multiple concurrent `PeerConnection`/data-channel sessions and per-peer cleanup/reconnect without tearing down unrelated peer sessions.
- [x] 1.5 Scope outgoing transfers to a selected connected peer and attribute send/receive progress to the corresponding peer while keeping no-persistence receive behavior.
- [x] 1.6 Add Settings entry and WebRTC test screen with manual inputs, per-peer connect/disconnect controls, active target selection, and peer-aware send/receive progress + speed UI.
- [x] 1.7 Add minimal tests for message serialization/session routing/progress attribution (commonTest where possible) and validate with relevant Gradle test tasks.
