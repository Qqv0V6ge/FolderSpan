## ADDED Requirements

### Requirement: Uniform file locator
All MCP file tools SHALL identify an endpoint with `{protocol, sourceId}` and an absolute endpoint-native `path`. `protocol` SHALL be one of `Local`, `Share`, `Device`, or `Network`; Local SHALL use an empty `sourceId`; other protocols SHALL resolve a currently known source instance. Resolution SHALL NOT switch the current UI desk and SHALL reject missing, disconnected, or ambiguous sources.

#### Scenario: Resolve a device file
- **WHEN** a tool receives `protocol=Device`, a connected device ID, and a valid absolute device path
- **THEN** it resolves operations against that device without changing the user's current screen or desk

#### Scenario: Reject an unknown source
- **WHEN** a locator references an unknown or disconnected source ID
- **THEN** the tool returns `not_connected` or `not_found` before filesystem access

### Requirement: File list and metadata tools
The system SHALL expose `folderspan_files_list` and `folderspan_file_info` for all four protocols. List SHALL return paginated child metadata, and info SHALL return name, absolute path, directory flag, MIME type, size, timestamps, protocol, source ID, permissions, and link metadata when available.

#### Scenario: List a directory
- **WHEN** an authorized caller lists an accessible directory locator
- **THEN** the result contains only direct children in stable endpoint order with pagination metadata

#### Scenario: Inspect a file
- **WHEN** an authorized caller requests info for an accessible file
- **THEN** the result returns current metadata without reading the file body

### Requirement: Bounded file content reading
The system SHALL expose `folderspan_file_read` for regular files on Local, Share, Device, and Network. Inputs SHALL include `offset`, `length`, and `encoding`; offset defaults to 0, length defaults to 262144 bytes and MUST be between 1 and 4194304 bytes, and encoding SHALL be `utf8` or `base64`. The response SHALL include total size, returned offset, returned length, `hasMore`, encoding, and data. Directory reads SHALL be rejected, and invalid UTF-8 SHALL return a validation error that recommends base64.

#### Scenario: Read the first text chunk
- **WHEN** a caller reads a UTF-8 file without offset or length
- **THEN** at most 262144 bytes are returned from offset 0 as UTF-8
- **AND** `hasMore` indicates whether another range remains

#### Scenario: Continue reading a binary file
- **WHEN** a caller supplies a later offset, a valid bounded length, and `base64`
- **THEN** the exact available byte range is returned as base64 without loading the entire file

#### Scenario: Oversized range is rejected
- **WHEN** a caller requests more than 4194304 bytes in one call
- **THEN** the tool returns `invalid_argument` and does not begin reading

### Requirement: File mutation tools and conflict policy
The system SHALL expose `folderspan_file_rename`, `folderspan_files_delete`, `folderspan_files_copy`, and `folderspan_files_move`. Copy and move SHALL support every directed combination among Local, Share, Device, and Network, subject to source/target capabilities. Copy, move, and delete SHALL enqueue existing FolderSpan file-operation tasks and return task IDs immediately. Copy and move SHALL accept `error`, `skip`, `overwrite`, or `rename` conflict policy, defaulting to `error`; rename SHALL generate a deterministic non-conflicting name.

#### Scenario: Start cross-source copy
- **WHEN** an authorized caller supplies one or more source locators, a target directory locator, and a valid conflict policy
- **THEN** the system persists and starts a copy task using the existing manifest, progress, pause, cancel, retry, and checkpoint behavior
- **AND** the tool returns the task ID without waiting for transfer completion

#### Scenario: Rename one endpoint item
- **WHEN** an authorized caller supplies a valid locator and a valid new leaf name
- **THEN** the endpoint performs an immediate rename and returns refreshed metadata
- **AND** path separators, traversal segments, and endpoint root renames are rejected

#### Scenario: Delete uses task system
- **WHEN** an authorized caller requests deletion of files or directories
- **THEN** the system enqueues a delete task rather than deleting synchronously in the MCP request

### Requirement: Move preserves per-item source safety
For every move item, the system MUST delete the source only after the corresponding target item is confirmed complete. A failure to delete the source SHALL be reported as a retryable delete-source failure and SHALL NOT cause a second copy on retry. Partial task results SHALL identify copied, skipped, failed, and source-delete-failed items.

#### Scenario: Target copy fails
- **WHEN** a move item cannot be copied to its target
- **THEN** its source remains unchanged
- **AND** the task records a retryable copy failure

#### Scenario: Source deletion fails after copy
- **WHEN** a move item is copied successfully but source deletion is denied or fails
- **THEN** the copied target remains valid and the source remains present
- **AND** retry executes only the delete-source stage

### Requirement: File sharing tools
The system SHALL expose `folderspan_files_share_link` and `folderspan_files_share_device`. Link share SHALL authorize selected locators through the existing FolderSpan link-share model and return HTTP/HTTPS URLs, expiry, and permission summary. Device share SHALL target an online connected device, create the existing device-share transfer/request, and return an operation or task ID.

#### Scenario: Create a link share
- **WHEN** a Token with `files.share` supplies shareable file locators
- **THEN** the system creates or updates a link-share session and returns reachable URLs and effective restrictions

#### Scenario: Share to a device
- **WHEN** a Token with `files.share` selects an online connected device and accessible files
- **THEN** the system starts the existing device-share flow and returns its identifier

### Requirement: File authorization and path safety
Every file tool SHALL validate the Token scope, endpoint capabilities, canonical path boundary, and symbolic-link safety before IO. `files.read` SHALL be required for list, info, and read; `files.write` SHALL be required for rename, copy, move, and delete; `files.share` SHALL be required for share tools. Tool errors and logs SHALL redact Tokens, credentials, and file contents.

#### Scenario: Write scope is absent
- **WHEN** a read-only Token calls a mutation tool directly
- **THEN** the request is rejected before endpoint resolution or task creation

#### Scenario: Path escapes endpoint boundary
- **WHEN** a path uses traversal or a symbolic link to escape its authorized endpoint root
- **THEN** the operation is rejected without reading or mutating the escaped target
