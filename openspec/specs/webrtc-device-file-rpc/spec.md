# webrtc-device-file-rpc Specification

## Purpose
TBD - created by archiving change add-webrtc-file-rpc. Update Purpose after archive.
## Requirements
### Requirement: WebRTC-connected devices SHALL support file operations
The system SHALL retain the WebRTC file RPC method and response contracts. After token and role/path authorization, the receiving service SHALL support file lookup, metadata, content, create, append, write, rename, delete, and same-device copy for ordinary paths. It MUST reject protected paths before local filesystem probing or mutation. Batch requests SHALL preserve request order and report per-item failures.

#### Scenario: Read authorized ordinary file metadata and content
- **WHEN** an approved WebRTC-connected device requests an ordinary path covered by its read permission
- **THEN** the request returns the existing metadata or content response

#### Scenario: Mutate an authorized ordinary file
- **WHEN** an approved device requests an ordinary path covered by its write, rename, or remove permission
- **THEN** the existing mutation behavior is preserved

#### Scenario: Reject protected file access
- **WHEN** an approved device requests a protected source or target path
- **THEN** the request returns an authority failure before protected-path I/O

#### Scenario: Batch operations preserve result order
- **WHEN** a device sends a batch file or folder mutation request
- **THEN** the response list keeps the same order as the requested paths
- **AND** each unauthorized or protected item reports its own authority failure

### Requirement: WebRTC file RPC SHALL reuse HTTP file permissions
The system SHALL validate WebRTC file RPC requests through the same authentication and role-based permission model used by HTTP file routes and SHALL additionally enforce the sensitive-path policy. Authentication or role permission success MUST NOT override a protected-path denial.

#### Scenario: Missing or invalid token
- **WHEN** a WebRTC file RPC request does not provide a valid approval-scoped token
- **THEN** the request is rejected
- **AND** no file operation is executed

#### Scenario: Valid token and role authorize an ordinary path
- **WHEN** the peer token is valid, its role grants the operation, and the path is not protected
- **THEN** the file RPC may execute the requested operation

### Requirement: WebRTC copy progress SHALL preserve HTTP terminal semantics
The system SHALL preserve the same terminal copy semantics over WebRTC that HTTP `/api/files/copy` exposes through its progress stream.

#### Scenario: Copy completes successfully
- **WHEN** a same-device remote copy finishes over WebRTC
- **THEN** the caller receives a terminal progress event with `done = true`
- **AND** `success = true`

#### Scenario: Copy fails or is cancelled
- **WHEN** a same-device remote copy fails or is cancelled over WebRTC
- **THEN** the caller receives a terminal progress event with `done = true`
- **AND** `success = false`
- **AND** the terminal message explains the failure or cancellation reason
