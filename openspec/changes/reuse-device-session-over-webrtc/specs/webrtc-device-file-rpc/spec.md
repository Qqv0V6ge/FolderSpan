## MODIFIED Requirements

### Requirement: WebRTC-connected devices SHALL support file operations
The system SHALL expose file lookup, metadata, content, create, append, write, rename, delete, and same-device copy through the common Device Session file contracts when Session is carried by WebRTC. After session-bound authentication and role/path authorization, behavior SHALL match the same operation over TLS Session. It MUST reject protected paths before local filesystem probing or mutation. Batch requests SHALL preserve request order and report per-item failures.

#### Scenario: Read authorized ordinary file metadata and content
- **WHEN** an approved WebRTC-connected Device Session requests an ordinary path covered by its read permission
- **THEN** the request returns the common Session metadata or content response

#### Scenario: Mutate an authorized ordinary file
- **WHEN** an approved device requests an ordinary path covered by its write, rename, or remove permission
- **THEN** the mutation behavior matches the same request over TLS Session

#### Scenario: Reject protected file access
- **WHEN** an approved device requests a protected source or target path
- **THEN** the request returns an authority failure before protected-path I/O

#### Scenario: Batch operations preserve result order
- **WHEN** a device sends a batch file or folder mutation request
- **THEN** the response list keeps the same order as the requested paths
- **AND** each unauthorized or protected item reports its own authority failure

### Requirement: WebRTC file RPC SHALL reuse HTTP file permissions
The system SHALL validate WebRTC-backed Device Session file requests through the same session-bound identity, role-based permission model and sensitive-path policy used by other device transports and HTTP file routes. Authentication or role permission success MUST NOT override a protected-path denial. Individual RPC calls SHALL use the authenticated Session identity and SHALL NOT require a second WebRTC-specific token field.

#### Scenario: Session authentication is missing or invalid
- **WHEN** a WebRTC peer has not established a valid approval-bound Device Session
- **THEN** the file request is rejected
- **AND** no file operation is executed

#### Scenario: Valid session and role authorize an ordinary path
- **WHEN** the Session identity is valid, its role grants the operation, and the path is not protected
- **THEN** the file RPC may execute the requested operation

### Requirement: WebRTC copy progress SHALL preserve HTTP terminal semantics
The system SHALL expose the same terminal copy and task-control semantics over a WebRTC-backed Device Session that are available through the common device copy implementation and HTTP `/api/files/copy` progress stream.

#### Scenario: Copy completes successfully
- **WHEN** a same-device remote copy finishes over WebRTC-backed Session
- **THEN** the caller receives a terminal progress event with `done = true`
- **AND** `success = true`

#### Scenario: Copy fails or is cancelled
- **WHEN** a same-device remote copy fails or is cancelled over WebRTC-backed Session
- **THEN** the caller receives a terminal progress event with `done = true`
- **AND** `success = false`
- **AND** the terminal message explains the failure or cancellation reason
