## ADDED Requirements
### Requirement: WebRTC-connected devices SHALL support file operations
The system SHALL allow an approved WebRTC-connected device to execute the same file operations that are available through HTTP `FileRoutes`.

#### Scenario: Rename, create, or delete remote files over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can rename files, create files/folders, and delete files through device RPC
- **AND** the returned batch result semantics match the HTTP file route behavior

#### Scenario: Read remote file metadata and content over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request file info, file lookup by path, file lookup by path and name, file lines, and chunked byte ranges
- **AND** the returned payload shapes match the HTTP file route behavior

#### Scenario: Write remote file content over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can append content and write byte ranges to remote files
- **AND** the returned success semantics match the HTTP file route behavior

#### Scenario: Start and control same-device remote copy over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request the remote device to copy one remote path to another remote path
- **AND** it can pause, resume, or cancel that copy through copy-control
- **AND** it receives copy progress updates until the remote copy completes or fails

### Requirement: WebRTC file RPC SHALL reuse HTTP file permissions
The system SHALL validate WebRTC file RPC requests through the same role-based permission model used by HTTP file routes.

#### Scenario: Missing or invalid token
- **WHEN** a WebRTC file RPC request does not provide a valid approval-scoped token
- **THEN** the request is rejected
- **AND** no file operation is executed

#### Scenario: Permission denied for target path
- **WHEN** the peer token maps to a role that lacks permission for the requested file operation
- **THEN** the WebRTC file RPC request fails
- **AND** the returned error semantics match the HTTP route behavior

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
