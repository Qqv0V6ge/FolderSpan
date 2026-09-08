# Change: Expand WebRTC data-channel test for multi-peer sessions

## Why
We need a lightweight, manual test surface to validate WebRTC data-channel file transfer across platforms using the existing WSS signaling server.
The current WebRTC test flow is modeled around a single remote peer, which prevents validation of multiple simultaneous sessions inside one signaling room even though the signaling server supports multi-member rooms.

## What Changes
- Add a new Settings entry that opens a WebRTC test subpage.
- Implement a WebRTC data-channel client that uses WSS signaling (per `server/README.md`) and supports send/receive file transfer with progress + speed UI.
- Refactor the test controller from a single remote peer model to a room session with independently managed peer sessions keyed by `SocketDevice.id`.
- Allow the WebRTC test page to create and manage multiple peer connections concurrently in the same room while keeping one signaling socket.
- Show per-peer connection status and let the user choose which connected peer receives outgoing transfers.
- Use `webrtc-java:0.14.0` on JVM and `webrtc-kmp:0.125.11` on non-JVM platforms.
- Discard incoming file data immediately after updating progress (no persistence).

## Impact
- Affected specs: `view-webrtc-settings`, `webrtc-data-channel-file-transfer` (new)
- Affected code: settings UI (`composeApp/.../settings`), shared settings/state, WebRTC controller/signaling flow, platform WebRTC clients, Gradle dependencies
