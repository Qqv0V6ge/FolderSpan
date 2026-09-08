# Change: Add HTTP WebRTC signaling for browser clients

## Why
Browser-based WASM/JS clients cannot reliably use self-signed HTTPS/WSS endpoints. They need a plain HTTP signaling path on the existing app HTTP port so they can send WebRTC signaling to the native app and reuse the app approval flow.

## What Changes
- Add App-hosted HTTP signaling endpoints for browser clients.
- Route browser connect requests into the existing WebRTC approval flow, including auto-approve behavior.
- Keep existing WS/WSS room signaling behavior unchanged.
- Use the existing device scan/connect flow so WASM/JS connects to discovered app devices through WebRTC.
- Keep existing WebRTC room pages and room validation unchanged.

## Impact
- Affected specs: webrtc-signaling-server, connect-browser-device-webrtc
- Affected code: raw device HTTP routes, browser WebRTC signaling client, DeviceState browser WebRTC controller, device scan/connect flow
