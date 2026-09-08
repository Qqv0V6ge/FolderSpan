# mcp-file-access Specification

## Purpose
TBD - created by archiving change add-mcp-server. Update Purpose after archive.
## Requirements
### Requirement: Uniform file locator
All MCP file tools SHALL identify an endpoint with `{protocol, sourceId}` and an absolute endpoint-native `path`. `protocol` SHALL be one of `Local`, `Share`, `Device`, or `Network`; Local SHALL use an empty `sourceId`; other protocols SHALL resolve a currently known source instance. Resolution SHALL NOT switch the current UI desk and SHALL reject missing, disconnected, or ambiguous sources.

#### Scenario: Resolve a device file
- **WHEN** a tool receives `protocol=Device`, a connected device ID, and a valid absolute device path
- **THEN** it resolves operations against that device without changing the user's current screen or desk

#### Scenario: Reject an unknown source
- **WHEN** a locator references an unknown or disconnected source ID
- **THEN** the tool returns `not_connected` or `not_found` before filesystem access

### Requirement: File list and metadata tools
The system SHALL expose `folderspan_files_list` and `folderspan_file_info` for all four protocol locator shapes. An authorized Local, Device, Share, or Network locator MAY return paginated child metadata and file metadata according to endpoint capability. Listed entries SHALL include `sensitivity` (`none`, `sensitive`, or `critical`) and a non-secret sensitivity category. A protected entry MAY be shown only from an independently authorized parent listing, SHALL advertise no file capabilities, and SHALL reject direct metadata access before probing the protected path.

#### Scenario: List an authorized ordinary local directory
- **WHEN** an authorized caller lists a Local directory outside protected storage
- **THEN** the result contains its direct children in stable order with pagination metadata
- **AND** ordinary children are marked `none`

#### Scenario: Mark a protected child
- **WHEN** an authorized parent listing contains an application-private or recovery-data child
- **THEN** the child is marked `sensitive` or `critical` with a stable category
- **AND** the child advertises no read, write, rename, delete, or share capability

#### Scenario: Reject protected metadata access
- **WHEN** an authorized MCP caller requests info for a protected Local or Device path
- **THEN** the tool returns `permission_denied` before filesystem probing
- **AND** the error does not contain the requested path

### Requirement: Bounded file content reading
The system SHALL expose `folderspan_file_read` with `offset`, `length`, and `encoding`; offset defaults to 0, length defaults to 262144 bytes and MUST be between 1 and 4194304 bytes, and encoding SHALL be `utf8` or `base64`. Permitted Local, Device, Share, and Network reads SHALL include total size, returned offset, returned length, `hasMore`, encoding, and data. Network staging SHALL use an application-selected protected staging location. Protected source paths SHALL return `permission_denied` before reading any bytes or starting a staged download.

#### Scenario: Read an authorized ordinary local file range
- **WHEN** a caller supplies an authorized Local or Device file outside protected storage and a valid range
- **THEN** the exact available bounded range is returned without loading the entire file

#### Scenario: Reject protected content reading
- **WHEN** an MCP caller requests any byte range from a protected Local or Device file
- **THEN** the tool returns `permission_denied`
- **AND** no file content is read

#### Scenario: Read a Network file through protected staging
- **WHEN** an authorized Network read requires a local staging file
- **THEN** the implementation may use its internally selected staging path
- **AND** MCP Local callers cannot browse or mutate that staging directory

#### Scenario: Oversized range is rejected
- **WHEN** a caller requests more than 4194304 bytes in one call
- **THEN** the tool returns `invalid_argument` without reading content

### Requirement: File mutation tools and conflict policy
The system SHALL expose `folderspan_file_rename`, `folderspan_files_delete`, `folderspan_files_copy`, and `folderspan_files_move`. Authorized operations SHALL retain `error`, `skip`, `overwrite`, or `rename` conflict policy behavior. Every Local source and destination SHALL be classified before direct I/O; Device hosts SHALL apply the same policy before their local I/O. A protected source, target, staging override, or cleanup target MUST return `permission_denied` without mutating the protected path.

#### Scenario: Rename an authorized ordinary Local item
- **WHEN** an authorized MCP caller requests rename of a non-sensitive Local item
- **THEN** the item is renamed according to endpoint capability

#### Scenario: Reject protected mutation
- **WHEN** a copy, move, rename, or delete request targets a protected Local or Device path
- **THEN** the operation returns `permission_denied` before protected filesystem mutation

#### Scenario: Preserve conflict handling
- **WHEN** an authorized transfer uses only ordinary paths
- **THEN** its existing conflict policy and task semantics remain unchanged

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
Every file tool SHALL validate Token scope, endpoint capabilities, configured device path permissions where applicable, canonical path boundary, symbolic-link safety, and the sensitive-path policy before I/O. `files.read` SHALL be required for list, info, and read; `files.write` SHALL be required for rename, copy, move, and delete; `files.share` SHALL be required for share tools. A valid scope or device role MUST NOT override a protected-path denial. Tool errors and logs SHALL redact Tokens, credentials, file contents, and denied target paths.

#### Scenario: Write scope is absent
- **WHEN** a read-only Token calls a mutation tool directly
- **THEN** the request is rejected before endpoint resolution or task creation

#### Scenario: Scope exists and ordinary path is authorized
- **WHEN** a Token has the required scope and the path is not protected
- **THEN** the request proceeds to endpoint capability and filesystem checks

#### Scenario: Scope exists but path is protected
- **WHEN** a Token has the required file scope but the requested path is protected
- **THEN** the request returns `permission_denied` before protected-path I/O

#### Scenario: Path escapes endpoint boundary
- **WHEN** a path uses traversal or a symbolic link to escape its authorized endpoint root
- **THEN** the operation is rejected without reading or mutating the escaped target

### Requirement: Bounded direct file writing
The system SHALL expose `folderspan_file_write` for Local, Device, Share, and Network file locators. The tool SHALL require `files.write`, endpoint write capability, and all existing path, symbolic-link, device-role, and sensitive-path checks. It SHALL accept `data` encoded as `utf8` or `base64`, SHALL reject decoded payloads larger than 524288 bytes, and SHALL support `overwrite` and `append` modes with `overwrite` as the default. A successful response SHALL include refreshed file metadata, the decoded byte count, and the effective mode.

#### Scenario: Create or replace a UTF-8 file
- **WHEN** an authorized caller writes UTF-8 data in `overwrite` mode to an ordinary file locator
- **THEN** the target is created if missing or its complete content is replaced if it exists
- **AND** the response returns refreshed metadata and the decoded byte count

#### Scenario: Append Base64 content
- **WHEN** an authorized caller writes valid Base64 data in `append` mode to an existing ordinary regular file
- **THEN** the decoded bytes are appended after the current file content
- **AND** the existing prefix remains unchanged

#### Scenario: Clear a file
- **WHEN** an authorized caller supplies empty data in `overwrite` mode for an existing ordinary regular file
- **THEN** the file is truncated to zero bytes
- **AND** the response reports zero bytes written

#### Scenario: Reject an oversized or malformed payload
- **WHEN** decoded data exceeds 524288 bytes or Base64 data is invalid
- **THEN** the tool returns `invalid_argument` before resolving or mutating the endpoint

#### Scenario: Reject append to a missing file or directory
- **WHEN** append targets a missing path or either write mode targets a directory
- **THEN** the tool returns `not_found` or `invalid_argument` without creating or modifying directory content

#### Scenario: Reject protected or unsupported targets
- **WHEN** the target is sensitive, linked outside its authorized boundary, outside a configured device-role path, or lacks endpoint write capability
- **THEN** the tool returns `permission_denied` or `unsupported` before writing content

### Requirement: Optimistic file write preconditions
`folderspan_file_write` SHALL accept optional non-negative `expectedSize` and `expectedUpdatedAt` values. When either value is supplied, the target MUST already exist and its current metadata MUST match every supplied value before overwrite or append. A mismatch or missing target SHALL return `conflict` without writing data.

#### Scenario: Metadata still matches
- **WHEN** an authorized caller supplies preconditions that match the current regular file
- **THEN** the requested overwrite or append proceeds normally

#### Scenario: File changed after inspection
- **WHEN** the current size or update timestamp differs from a supplied precondition
- **THEN** the tool returns `conflict`
- **AND** the target content remains unchanged

### Requirement: MCP directory creation
The system SHALL expose `folderspan_directory_create` for Local, Device, Share, and Network locators. The tool SHALL require `files.write`, endpoint write capability, and all existing path, symbolic-link, device-role, and sensitive-path checks. It SHALL create one directory whose parent already exists and SHALL return refreshed directory metadata. Calling it for an existing directory SHALL be idempotent; calling it where a regular file already exists SHALL return `conflict`.

#### Scenario: Create an ordinary directory
- **WHEN** an authorized caller supplies a missing ordinary directory locator with an existing writable parent
- **THEN** the directory is created and its refreshed metadata is returned

#### Scenario: Existing directory is idempotent
- **WHEN** the locator already identifies an ordinary directory
- **THEN** the tool succeeds without replacing it and returns its metadata

#### Scenario: Existing file conflicts
- **WHEN** the locator identifies a regular file
- **THEN** the tool returns `conflict` without modifying the file
