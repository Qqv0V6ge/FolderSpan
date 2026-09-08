# Change: Add WebRTC path RPC for remote device browsing

## Why
WebRTC device connections currently stop at peer setup and file-transfer channels. Remote path browsing still depends on HTTP-only `PathRoutes`, so a WebRTC-connected device cannot browse the remote filesystem the same way as an HTTP-connected device.

## What Changes
- Add WebRTC path RPC support for the `PathRoutes` capability: `list`, `rootPaths`, `exists`, `create-directory`, and `delete-directory`.
- Extract shared path business logic so HTTP routes and WebRTC RPC use the same permission checks, path normalization, and result shaping.
- Pass an approval-scoped access token during WebRTC connect approval so subsequent path RPC requests can reuse the existing permission model.
- Expose a transport-agnostic device path client so connected WebRTC devices appear as browsable devices in the existing file UI.

## Impact
- Affected specs: `webrtc-device-path-rpc`
- Affected code: `PathRoutes`, `DeviceState`, `Device`, `DeviceCertificateState`, `MultiPeerWebRtcTestController`, WebRTC path client models, path service tests
