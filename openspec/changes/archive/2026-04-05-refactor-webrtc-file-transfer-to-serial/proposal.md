# Change: Refactor WebRTC file transfer to serial single-stream delivery

## Why
The current WebRTC file-transfer implementation still defaults to the multi-stream striped model introduced for throughput tuning. This feature has not shipped yet, so keeping the extra negotiation and multi-stream send complexity only increases maintenance and debugging cost.

## What Changes
- Change WebRTC file payload sending to use a single dedicated payload data channel by default.
- Keep transport capability exchange, ACK/NACK retransmit, receiver-confirmed progress, and drain-before-complete semantics.
- Align both `MultiPeerWebRtcController` and `WebRtcController` on the same serial single-stream default behavior.
- Update OpenSpec deltas and tests to reflect serial transfer as the intended design.

## Impact
- Affected specs: `webrtc-device-file-transfer-performance`, `webrtc-data-channel-file-transfer`
- Affected code: shared WebRTC transfer helpers, `MultiPeerWebRtcController`, `WebRtcController`, shared WebRTC tests
