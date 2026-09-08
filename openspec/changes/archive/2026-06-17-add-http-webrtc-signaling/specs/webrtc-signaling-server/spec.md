## ADDED Requirements
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
