## MODIFIED Requirements

### Requirement: WebRTC-connected devices SHALL support remote bookmark management
The system SHALL allow an approved WebRTC-backed Device Session to fetch and manage a remote device's bookmarks through the common Session bookmark contracts.

#### Scenario: Fetch remote bookmarks over WebRTC
- **WHEN** a device has an approved WebRTC-backed Device Session to another device
- **THEN** it can request the remote bookmark list
- **AND** it receives the same bookmark payload semantics as TLS Session and the HTTP bookmark route client

#### Scenario: Create or update remote bookmark over WebRTC
- **WHEN** a device has an approved WebRTC-backed Device Session to another device
- **THEN** it can create or update a remote bookmark
- **AND** the operation result matches the common Session and HTTP bookmark behavior

#### Scenario: Delete remote bookmark over WebRTC
- **WHEN** a device has an approved WebRTC-backed Device Session to another device
- **THEN** it can delete a remote bookmark
- **AND** the operation result matches the common Session and HTTP bookmark behavior

### Requirement: WebRTC bookmark RPC SHALL reuse HTTP bookmark permissions
The system SHALL validate WebRTC-backed Device Session bookmark requests through the same session-bound identity and role-based permission model used by other device transports and HTTP bookmark routes. Individual bookmark calls SHALL NOT require a second WebRTC-specific token field.

#### Scenario: Session authentication is missing or invalid
- **WHEN** a bookmark request is received outside a valid approval-bound Device Session
- **THEN** the request is rejected
- **AND** no bookmark mutation is executed

#### Scenario: Permission denied for bookmark operation
- **WHEN** the Session identity maps to a role that lacks bookmark permission for the requested action
- **THEN** the bookmark RPC request fails
- **AND** the returned error semantics match the common Session and HTTP route behavior
