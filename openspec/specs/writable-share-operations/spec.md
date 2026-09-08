# writable-share-operations Specification

## Purpose
TBD - created by archiving change add-mcp-server. Update Purpose after archive.
## Requirements
### Requirement: Share capability negotiation
Remote Share sessions SHALL advertise versioned capabilities for list/read, upload/create, rename, and delete. New clients SHALL treat absent capability fields or legacy peers as list/read-only. The local Share `DiskMenuPermission` and MCP tool availability SHALL be derived from the negotiated capability set and the active session authorization.

#### Scenario: Legacy Share peer connects
- **WHEN** a Share session connects to a peer that does not advertise writable capabilities
- **THEN** the session remains readable and downloadable
- **AND** upload, rename, delete, and move-source operations are rejected as unsupported

#### Scenario: Writable Share peer connects
- **WHEN** the peer advertises upload, rename, and delete and the session grants those permissions
- **THEN** the Share data source exposes the corresponding operations to UI and MCP callers

### Requirement: Share write authorization
Every Share write request SHALL carry the active share-session credential and SHALL be checked against the current authorization snapshot, allowed roots, hidden-file rules, and individual operation capability. Upload authorization SHALL NOT imply rename or delete authorization, and delete authorization SHALL be required before a Share item can be removed as a move source.

#### Scenario: Upload allowed but delete denied
- **WHEN** a session may upload but may not delete
- **THEN** it can be used as a copy target
- **AND** it cannot complete a move whose source is that Share session

#### Scenario: Authorization changes during task
- **WHEN** Share write authorization is revoked while a queued operation is running
- **THEN** later items fail with `permission_denied` without bypassing the new snapshot
- **AND** already completed items remain reported as completed

### Requirement: Share upload, rename, and delete routes
The Share protocol SHALL provide bounded streaming upload/create, rename, and delete routes with the same structured success and per-item error semantics used by existing device file operations. Upload SHALL write to a temporary target and atomically publish it when the endpoint supports atomic replacement; incomplete temporary content SHALL be cleaned up after failure or cancellation.

#### Scenario: Upload a file to Share
- **WHEN** an authorized copy task targets a Share directory
- **THEN** file bytes are streamed without buffering the complete file in memory
- **AND** the final target appears only after a complete successful upload when atomic publish is supported

#### Scenario: Rename a Share item
- **WHEN** an authorized caller renames an item within its allowed Share root
- **THEN** the route validates the new leaf name and returns the resulting metadata

#### Scenario: Delete a Share directory tree
- **WHEN** an authorized delete task removes a Share directory
- **THEN** children are deleted before parents using the existing manifest ordering
- **AND** per-item failures remain retryable

### Requirement: Share cross-endpoint transfer semantics
Share SHALL be usable as a source or target in cross-endpoint copy and move tasks when negotiated permissions allow it. Transfer coordination MAY use bounded local staging when no direct streaming path exists, but SHALL preserve task pause, cancel, progress, cleanup, checkpoint, and retry behavior.

#### Scenario: Copy Device to Share
- **WHEN** an authorized task copies a Device file to a writable Share target
- **THEN** the transfer completes through the available bounded streaming or staging path
- **AND** temporary local data is removed after success, failure, or cancellation

#### Scenario: Move Share to Network
- **WHEN** an authorized task moves a readable/deletable Share item to a writable Network target
- **THEN** the source Share item is deleted only after the Network target is confirmed complete
