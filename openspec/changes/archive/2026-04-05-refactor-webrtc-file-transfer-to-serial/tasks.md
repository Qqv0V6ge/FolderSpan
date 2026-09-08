## 1. Implementation
- [x] 1.1 Add OpenSpec deltas for serial single-stream WebRTC file transfer and update markdown index entries.
- [x] 1.2 Refactor shared WebRTC transfer planning helpers so fallback and negotiated plans default to one payload stream.
- [x] 1.3 Update `MultiPeerWebRtcController` to create and use a single payload data channel for file and random-data sends.
- [x] 1.4 Update `WebRtcController` to use the same single-stream serial behavior.
- [x] 1.5 Update or extend tests for transfer planning and controller-facing serial semantics.

## 2. Validation
- [x] 2.1 Run `openspec validate refactor-webrtc-file-transfer-to-serial --strict`.
- [x] 2.2 Run targeted shared WebRTC tests.
- [x] 2.3 Update this checklist to reflect the implemented state.
