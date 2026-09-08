# Change: Improve WebRTC device file transfer throughput

## Why
The current production WebRTC file transfer path uses a single data channel, fixed chunk sizes, and a simple buffered-amount polling loop. File transfer works, but throughput is noticeably lower than the higher-throughput striped transfer model already implemented in the test controller.

## What Changes
- Add transport capability exchange on the production control channel.
- Upgrade production file payload transfer to use negotiated multi-stream striped data channels.
- Reuse windowed backpressure and platform-aware transfer planning for the production controller.
- Keep device RPC, copy-progress frames, approval flow, and public controller APIs unchanged.

## Impact
- Affected specs: `webrtc-device-file-transfer-performance`
- Affected code: `MultiPeerWebRtcTestController`, shared WebRTC transfer helpers, WebRTC controller tests
