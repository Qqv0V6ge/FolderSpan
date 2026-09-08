# Change: Add approval-gated WebRTC device connect flow

## Why
WebRTC device connections currently bypass the existing device approval pipeline. When one device connects to another over WebRTC, the target side receives an `offer` and auto-generates an `answer` immediately, so Linux never enters the `WAITING` device-connect state and never emits the existing authorization notification.

## What Changes
- Add a WebRTC device connect approval handshake before `offer/answer` exchange begins.
- Extend the signaling server to route WebRTC control messages for connect request, approval, and rejection inside a room.
- Reuse the existing device connection notification pipeline on the target side so Linux shows the same approval notification semantics as HTTP device connects.
- Block PeerConnection setup until the target side approves the request or auto-approval rules allow it.
- Surface pending/rejected states back to the initiating device so the drawer and WebRTC settings page stay consistent.

## Impact
- Affected specs: `webrtc-device-rpc`, `webrtc-signaling-server`
- Affected code: WebRTC signaling client/controller, signaling server routing, device connection notification flow, drawer/settings WebRTC entry points
