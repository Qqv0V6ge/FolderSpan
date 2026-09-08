# webrtc-signaling-server Specification

## Purpose
TBD - created by archiving change add-webrtc-signaling-server. Update Purpose after archive.
## Requirements
### Requirement: WSS-only signaling endpoint
The signaling server SHALL expose a single WebSocket endpoint at `/ws` over TLS (WSS) and SHALL NOT require authentication.

#### Scenario: Client connects to WSS endpoint
- **WHEN** a client opens `wss://<host>:<port>/ws`
- **THEN** the server accepts the WebSocket connection

#### Scenario: TLS is not configured
- **WHEN** the server is started without valid TLS configuration
- **THEN** the server refuses to start and reports a configuration error

### Requirement: Room ID validation
The server SHALL accept only room IDs that are base64url-encoded 256-bit random strings.

#### Scenario: Join with invalid room ID
- **WHEN** a client sends `join` with a roomId that is not base64url 256-bit
- **THEN** the server responds with `error` and closes the connection

### Requirement: Join and peer synchronization
The server SHALL support multi-member rooms and synchronize peer membership.

#### Scenario: First member joins
- **WHEN** a client sends `join`
- **THEN** the server responds with `joined` containing an empty `peers` list

#### Scenario: Additional member joins
- **WHEN** a second client sends `join` to the same room
- **THEN** the server responds with `joined` containing existing peers
- **AND** broadcasts `peer-joined` to existing members

#### Scenario: Member leaves
- **WHEN** a client sends `leave` or disconnects
- **THEN** the server broadcasts `peer-left` to remaining members

### Requirement: Message routing for WebRTC exchange
The server SHALL route `connect-request`, `connect-approved`, `connect-rejected`, `offer`, `answer`, and `ice` messages to the `to.id` peer within the same room.

#### Scenario: Route connect request to peer
- **WHEN** a client sends a `connect-request` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route connect approval to peer
- **WHEN** a client sends `connect-approved` or `connect-rejected` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route offer to peer
- **WHEN** a client sends an `offer` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route to missing peer
- **WHEN** a client sends a `connect-request/connect-approved/connect-rejected/offer/answer/ice` to a non-member
- **THEN** the server responds with `error`

### Requirement: SocketDevice-based identity
The server SHALL use `SocketDevice.id` as the unique peer identifier and SHALL treat `from`/`to` as `SocketDevice` objects.

#### Scenario: Duplicate device ID
- **WHEN** a client joins with a `SocketDevice.id` already present in the room
- **THEN** the server disconnects the old session and accepts the new one
- **AND** broadcasts `peer-left` and `peer-joined` updates

### Requirement: Heartbeat and timeouts
The server SHALL perform heartbeat checks and disconnect stale connections.

#### Scenario: Heartbeat timeout
- **WHEN** a client does not respond within the configured timeout
- **THEN** the server disconnects the client and broadcasts `peer-left`

### Requirement: Configuration-driven limits
The server SHALL load limits and timeouts from `server/config.json` at startup, including room size, idle timeout, message size, rate limits, and heartbeat intervals.

#### Scenario: Rate limit exceeded
- **WHEN** a client exceeds the configured message rate
- **THEN** the server responds with `error` and closes the connection

### Requirement: No SDP/ICE persistence
The server SHALL NOT persist SDP or ICE data beyond in-flight routing.

#### Scenario: Server restarts
- **WHEN** the server restarts
- **THEN** no SDP/ICE history is retained

### Requirement: App-hosted HTTP signaling for browser clients
The system SHALL expose HTTP signaling endpoints on the app HTTP service so browser WASM/JS clients can exchange WebRTC signaling with the native app without using WebSocket TLS.

#### Scenario: Browser sends connect request over HTTP
- **WHEN** a browser client joins the HTTP signaling endpoint and sends a `connect-request`
- **THEN** the native app receives the request through the existing WebRTC approval flow
- **AND** the browser can poll for `connect-approved` or `connect-rejected`

#### Scenario: Multiple browser clients connect to one app
- **WHEN** multiple browser clients are registered with the app HTTP signaling endpoint
- **THEN** each browser exchanges signaling only with the app
- **AND** browser clients do not receive each other as peers

#### Scenario: Existing WebSocket signaling remains available
- **WHEN** a client uses a `ws://` or `wss://` signaling URL
- **THEN** the existing WebSocket signaling client and room behavior remain unchanged

