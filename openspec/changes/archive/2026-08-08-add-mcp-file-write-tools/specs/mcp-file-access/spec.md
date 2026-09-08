## ADDED Requirements

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
