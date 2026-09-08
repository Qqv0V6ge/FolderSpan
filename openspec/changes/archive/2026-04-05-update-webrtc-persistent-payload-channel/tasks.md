## 1. Implementation
- [x] 1.1 Add OpenSpec deltas for persistent per-peer payload-channel reuse and update markdown index entries.
- [x] 1.2 Refactor `MultiPeerWebRtcController` so each peer session owns one reusable payload data channel and sequential file transfers reuse it.
- [x] 1.3 Update `WebRtcController` and any shared transfer helpers to use the same persistent payload-channel lifecycle.
- [x] 1.4 Adjust platform-specific data-channel lifecycle handling if needed for accurate close/open state transitions.
- [x] 1.5 Add or extend tests for consecutive file transfers, payload-channel reuse, and recovery after channel closure.

## 2. Validation
- [x] 2.1 Run `openspec validate update-webrtc-persistent-payload-channel --strict`.
- [x] 2.2 Run targeted shared WebRTC tests.
- [x] 2.3 Update this checklist to reflect the implemented state.
