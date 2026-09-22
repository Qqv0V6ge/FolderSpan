# mcp-http-server Specification

## Purpose
TBD - created by archiving change add-mcp-server. Update Purpose after archive.
## Requirements
### Requirement: Native MCP Streamable HTTP endpoints
The system SHALL expose MCP on Android, Desktop, and iOS through an independent listener whose configured public port defaults to `52137`. The listener SHALL bind all available interfaces for LAN access, SHALL serve stateful Streamable HTTP at `/mcp`, SHALL serve stateless POST-only Streamable HTTP at `/mcp/stateless`, and SHALL NOT provide a stdio transport. Android, Desktop, and iOS SHALL accept both HTTP and HTTPS on the configured public port using the existing device TLS identity for HTTPS.

#### Scenario: LAN client connects to stateful endpoint
- **WHEN** an authenticated LAN client sends a valid MCP `initialize` request to `/mcp`
- **THEN** the server returns a negotiated MCP initialize result and an `Mcp-Session-Id`
- **AND** subsequent POST, GET/SSE, and DELETE requests using that session ID follow Streamable HTTP semantics

#### Scenario: Stateless client connects
- **WHEN** an authenticated client sends a valid JSON-RPC request to `/mcp/stateless`
- **THEN** the server processes that request without creating or requiring an MCP session
- **AND** GET or DELETE on `/mcp/stateless` returns Method Not Allowed

#### Scenario: Unsupported platform opens MCP settings
- **WHEN** the MCP management screen is opened on JS or Wasm
- **THEN** the screen reports that inbound MCP serving is unavailable on that platform
- **AND** it does not attempt to start a listener

### Requirement: MCP protocol and session lifecycle
The server SHALL accept MCP protocol versions `2025-06-18` and `2025-11-25`, SHALL negotiate the newest mutually supported version during initialization, and SHALL implement `initialize`, initialized notifications, `ping`, `tools/list`, and `tools/call`. Stateful sessions SHALL expire after 30 minutes of inactivity, SHALL be removable through DELETE `/mcp`, and SHALL support an authenticated GET SSE stream. The server SHALL cap active sessions at 32 per Token and SHALL return structured JSON-RPC errors for invalid methods, parameters, sessions, and tools.

#### Scenario: Protocol version is negotiated
- **WHEN** a client initializes with a supported protocol version
- **THEN** the server returns that version or the newest mutually supported version
- **AND** later stateful requests must carry the negotiated version and session ID

#### Scenario: Session expires
- **WHEN** a stateful session has been idle for more than 30 minutes
- **THEN** the server removes the session and closes its SSE stream
- **AND** a later request with that session ID receives a session-not-found response

#### Scenario: Invalid JSON-RPC request
- **WHEN** an authenticated client sends malformed JSON-RPC or invalid tool arguments
- **THEN** the server returns the applicable JSON-RPC error without invoking business logic

### Requirement: Bearer Token authentication and scopes
Every MCP request SHALL require `Authorization: Bearer <token>`. Tokens SHALL use a lookup identifier plus a 256-bit random secret, SHALL persist only a SHA-256 hash of the secret, and SHALL be compared in constant time. Each Token SHALL have a name, enabled state, created time, last-used time, and a selectable set of scopes. The supported scopes SHALL be `bookmarks.read`, `bookmarks.write`, `favorites.read`, `favorites.write`, `recents.read`, `recents.write`, `tasks.read`, `tasks.control`, `devices.read`, `devices.connect`, `networks.read`, `networks.connect`, `sync.read`, `sync.run`, `files.read`, `files.write`, and `files.share`.

#### Scenario: Missing or invalid Token
- **WHEN** a request omits the Bearer Token, supplies an unknown Token, or supplies a disabled Token
- **THEN** the server returns HTTP 401 with a Bearer challenge
- **AND** no MCP method or business operation runs

#### Scenario: Token lacks tool scope
- **WHEN** an authenticated Token requests a tool for which it lacks the required scope
- **THEN** `tools/list` omits that tool
- **AND** a direct `tools/call` attempt returns a structured forbidden error without invoking the tool

#### Scenario: Token is created
- **WHEN** the user creates a Token with a name and at least one scope
- **THEN** the UI displays the complete Token exactly once for copying
- **AND** only the lookup identifier, secret hash, metadata, and scopes are persisted

### Requirement: MCP LAN and Origin protection
The MCP listener SHALL advertise loopback and non-loopback interface URLs, SHALL prefer HTTPS URLs in the UI, and SHALL visibly warn that plaintext HTTP exposes Bearer Tokens and file parameters to LAN observers. Requests without an `Origin` header SHALL be accepted after Host validation. Requests with an `Origin` header SHALL be accepted only when the Origin is loopback. The management screen SHALL NOT expose a configurable Origin allowlist. Host and Origin validation SHALL prevent DNS-rebinding access to an unadvertised host.

#### Scenario: LAN addresses are displayed
- **WHEN** the service is running with one or more active non-loopback interfaces
- **THEN** the management screen lists HTTP and HTTPS URLs for both MCP endpoints on each usable address
- **AND** HTTPS URLs are displayed before plaintext HTTP URLs

#### Scenario: Browser Origin is rejected
- **WHEN** a request includes an Origin that is not loopback
- **THEN** the server rejects the request before Token or tool processing

### Requirement: MCP management entry and lifecycle
`ToolboxScreen` SHALL contain an MCP entry that opens a Material 3 management screen. The screen SHALL expose enabled/running/error status, a switch that starts or stops the service, public port, advertised addresses, and Token create/edit/disable/delete operations. Port or listener-affecting changes SHALL restart the service when the user applies them, and SHALL preserve unrelated file-share services.

#### Scenario: Enable MCP service
- **WHEN** the user enables MCP on a supported native platform
- **THEN** the independent MCP listener starts on the configured port and all interfaces
- **AND** the screen reflects running state or an actionable startup error

#### Scenario: Change MCP port
- **WHEN** the user saves a valid port different from the running port
- **THEN** the service restarts on the new port
- **AND** the advertised URLs update without changing the device API port
