## ADDED Requirements
### Requirement: WebRTC-connected devices SHALL support remote path browsing
The system SHALL allow a WebRTC-connected device to browse a remote device's paths using the same path operations available through HTTP device connections.

#### Scenario: List remote path over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request `list` for a remote path
- **AND** it receives the same grouped path result semantics as the HTTP path route client

#### Scenario: Get remote root paths over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request `rootPaths`
- **AND** it receives the remote root path list without requiring HTTP

#### Scenario: Create or delete remote directory over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request `create-directory` and `delete-directory`
- **AND** the remote side applies the same permission checks as HTTP

### Requirement: WebRTC path RPC SHALL reuse the existing permission model
The system SHALL validate WebRTC path RPC requests through the same role-based device permission model used by HTTP device routes.

#### Scenario: Approved peer receives scoped access token
- **WHEN** a target device approves a WebRTC connect request
- **THEN** it issues a scoped access token for that peer session
- **AND** later WebRTC path RPC calls must present that token

#### Scenario: Missing or invalid token
- **WHEN** a WebRTC path RPC request does not provide a valid approval-scoped token
- **THEN** the request is rejected
- **AND** no path operation is executed

#### Scenario: Permission denied for target path
- **WHEN** the peer token maps to a role that lacks the required permission for the target path
- **THEN** the WebRTC path RPC request fails
- **AND** the error semantics match the HTTP path route behavior
