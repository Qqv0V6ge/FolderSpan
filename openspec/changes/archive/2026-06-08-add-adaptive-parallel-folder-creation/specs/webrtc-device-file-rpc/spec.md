## MODIFIED Requirements

### Requirement: WebRTC-connected devices SHALL support file operations
The system SHALL allow an approved WebRTC-connected device to execute the same file operations that are available through HTTP `FileRoutes`. Batch folder creation requests SHALL preserve HTTP-compatible per-path result order while allowing the receiving service to execute authorized folder creations with bounded adaptive parallelism.

#### Scenario: Rename, create, or delete remote files over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can rename files, create files/folders, and delete files through device RPC
- **AND** the returned batch result semantics match the HTTP file route behavior

#### Scenario: Batch folder creation preserves result order while running in parallel
- **WHEN** a device sends a batch create-folder request containing multiple folder paths
- **THEN** the receiving service SHALL be allowed to create authorized folders concurrently
- **AND** the response list SHALL keep the same order as the requested paths
- **AND** a failed folder creation SHALL NOT prevent other authorized folder paths in the same batch from being attempted

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
