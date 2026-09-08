## 1. Proposal and specification
- [x] Draft proposal, design, and spec deltas for the signaling server

## 2. Configuration and bootstrapping
- [x] Define signaling `server/config.json` schema and load it on server startup
- [x] Enforce TLS/WSS startup requirements using config values

## 3. Core signaling server
- [x] Implement WebSocket `/ws` endpoint in `server/` with JSON message parsing
- [x] Implement in-memory room manager and peer membership tracking
- [x] Implement join/leave handling and peer sync messages

## 4. Routing and safety
- [x] Implement offer/answer/ice routing by `to.id`
- [x] Enforce roomId validation (base64url 256-bit)
- [x] Enforce message size limits and per-connection rate limits

## 5. Heartbeat and cleanup
- [x] Implement ping/pong heartbeat and stale connection eviction
- [x] Implement room idle timeout cleanup

## 6. Tests and validation
- [x] Add unit tests for room manager (join/leave, duplicate id, routing errors)
- [x] Add integration test for WebSocket join/peer sync
- [x] Document how to run signaling server locally
