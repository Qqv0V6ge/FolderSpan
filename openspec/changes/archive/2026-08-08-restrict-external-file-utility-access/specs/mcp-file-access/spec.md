## MODIFIED Requirements

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
