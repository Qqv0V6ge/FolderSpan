# Design: WebRTC WSS Signaling Server

## Overview
The signaling server is a standalone Ktor service in `server/` that exposes a single WSS endpoint at `/ws`. It only manages rooms and routes JSON messages between peers; it never stores SDP/ICE beyond in-flight routing.

## Architecture
- **Client ↔ WSS**: Clients connect via WSS and send JSON messages.
- **Room manager (in-memory)**: `roomId -> Map<deviceId, Session>` with last-seen timestamps.
- **Router**: For `offer/answer/ice`, route to `to.id` within the same room.
- **Heartbeat**: Server periodically sends `ping`; clients must respond with `pong` (or WS pong) or be disconnected.

## Message Model
All messages are JSON with common fields:
- `type`: string
- `roomId`: base64url 256-bit string
- `from`: `SocketDevice` (must include `id`)
- `to`: `SocketDevice` (required for `offer/answer/ice`)
- `ts`: client timestamp (ms)

`SocketDevice` payload uses the existing shape (`id`, `name`, `pathSeparator`, `host`, `port`, `type`, `connectType`), but the server only relies on `id` for routing and `name/type` for peer lists.

## Room Behavior
- On `join`, the server creates the room if missing and returns `joined` with current peers (array of `SocketDevice`).
- If `from.id` already exists, the old session is closed (kicked) and peers receive `peer-left` then `peer-joined`.
- On disconnect or `leave`, remove from room and broadcast `peer-left`. If the room becomes empty, it is deleted (and may be GC’d by idle timeout).

## Deterministic Offerer Rule (Client)
Clients select offerer using `if (myDeviceId < peerId) I am offerer`. The server remains agnostic.

## Config
On startup, the server loads `server/config.json` with:
- WSS port/host
- TLS keystore path + password (WSS required)
- Room limits: max members, idle timeout
- Message limits: max message size, per-connection rate limit
- Heartbeat: ping interval, disconnect timeout

## Failure Handling
- Invalid roomId (not base64url/256-bit) → `error` and close.
- `offer/answer/ice` to missing peer or room → `error`.
- Rate limit exceeded → `error` + close.
- Oversized message → close.

## Open Decisions
- Config file path/format (HOCON vs JSON) will be selected during implementation based on existing server patterns.
