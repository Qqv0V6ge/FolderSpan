# Change: Add a WebRTC room section to the app drawer

## Why
Users currently need to open the WebRTC test settings page to join a signaling room and inspect who is online.
For quick verification against the `server` signaling backend, the drawer needs a lightweight entry that can join/leave a room and show room members without exposing the full test workflow.

## What Changes
- Add a dedicated `WebRTC` drawer section with expand/collapse persistence.
- Reuse the saved WebRTC signaling settings (`wssUrl`, `roomId`, `localDeviceId`) instead of introducing a second configuration source.
- Allow the drawer to generate/edit `Room ID`, join or leave a room, and render the current room member list.
- Show room connection state and signaling errors inside the drawer.
- Keep peer connect/disconnect controls and file transfer operations on the existing WebRTC test settings page.

## Impact
- Affected specs: `drawer-webrtc-server-connections`
- Affected code: drawer UI, drawer state persistence, shared settings bootstrap, OpenSpec docs
