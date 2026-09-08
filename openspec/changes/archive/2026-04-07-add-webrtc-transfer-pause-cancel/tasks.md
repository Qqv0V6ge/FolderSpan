## 1. Spec And Protocol

- [x] 1.1 Add OpenSpec deltas for WebRTC stream-transfer pause/cancel behavior and update `md_descriptions_paths.md`.
- [x] 1.2 Extend WebRTC file-stream control models with pause/resume/cancel command and result types plus per-transfer control bookkeeping.

## 2. Controller Integration

- [x] 2.1 Update `MultiPeerWebRtcController` send/receive flow so active payload-channel transfers can pause, resume, and cancel without tearing down the peer session.
- [x] 2.2 Ensure cancel cleanup removes incomplete receive targets and resolves waiters with cancellation-aware errors.

## 3. Task Wiring

- [x] 3.1 Pass stable request IDs from WebRTC task call sites in `Device.kt` and `FileState.kt`, and bridge `TaskState` pause/resume/cancel signals to the new controller APIs.
- [x] 3.2 Keep existing `copyPath/controlCopy` behavior unchanged and avoid double-controlling RPC copy transfers.

## 4. Validation

- [x] 4.1 Add or update shared WebRTC tests for pause, resume, cancel, and disconnect cleanup.
- [x] 4.2 Run `openspec validate add-webrtc-transfer-pause-cancel --strict`.
- [x] 4.3 Run targeted shared WebRTC tests.
- [x] 4.4 Update this checklist to reflect the implemented state.
