# Change: Reuse a persistent WebRTC payload channel per peer session

## Why
The current WebRTC file-transfer path still creates and closes a dedicated payload data channel for each file. In practice this causes large multi-file transfers to fail after the first file on some WebRTC stacks, because the next file can start before the previous SCTP stream lifecycle has fully settled and the sender times out while waiting for receiver completion.

## What Changes
- Reuse one long-lived payload data channel per peer session instead of creating a new payload channel per file.
- Serialize payload transfers per peer session so consecutive files share the same payload channel lifecycle.
- Keep file metadata, receiver confirmation, retransmit, device RPC, and copy-progress control traffic on the control channel.
- Align production and test WebRTC controllers on the same persistent-payload-channel behavior.

## Impact
- Affected specs: `webrtc-device-file-transfer-performance`, `webrtc-data-channel-file-transfer`
- Affected code: `MultiPeerWebRtcController`, `WebRtcController`, shared WebRTC transfer helpers, platform data-channel wrappers, WebRTC controller tests
