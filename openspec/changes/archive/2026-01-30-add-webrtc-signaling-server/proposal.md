# Change: Add WebRTC signaling server over WSS

## Why
Clients need a dedicated, room-based signaling service to exchange WebRTC SDP/ICE using only WebSocket signaling, with lightweight in-memory state and no authentication.

## What Changes
- Add a Ktor-based WSS signaling service in `server/` that exposes a fixed `/ws` endpoint.
- Define a JSON message protocol using `SocketDevice` (no separate userId field).
- Add room management, message routing, heartbeat, rate limiting, and size limits.
- Load runtime limits (timeouts, max members, message size, rate limits) from `server/config.json` on startup.

## Impact
- Affected code: `server/` module (Ktor WS server, config loading, room manager), shared data models for JSON parsing if reused.
- New spec: `webrtc-signaling-server`.
- No changes to existing device-share HTTP routes (separate signaling service).
