# mcp-automation-tools Specification

## Purpose
TBD - created by archiving change add-mcp-server. Update Purpose after archive.
## Requirements
### Requirement: Stable MCP tool naming and collection responses
The system SHALL expose FolderSpan MCP tools with `folderspan_`-prefixed snake-case names and JSON Schema input definitions. Collection tools SHALL accept an optional opaque `cursor` and `limit`, where the default limit is 50 and the maximum is 200, and SHALL return `items`, `nextCursor`, and relevant grouping metadata. Tool failures SHALL return stable codes including `invalid_argument`, `not_found`, `permission_denied`, `conflict`, `not_connected`, `unsupported`, and `internal_error` without exposing secrets.

#### Scenario: List tool is paginated
- **WHEN** a collection contains more items than the requested limit
- **THEN** the tool returns only the requested page and a non-empty `nextCursor`
- **AND** passing that cursor continues after the last returned item

### Requirement: Bookmark MCP tools
The system SHALL expose `folderspan_bookmarks_list`, `folderspan_bookmarks_create`, `folderspan_bookmarks_update`, and `folderspan_bookmarks_delete`. Every call SHALL identify a bookmark scope using `protocol` and `sourceId`; Local SHALL use an empty `sourceId`, while Device, Share, and Network SHALL require the stable instance identifier.

#### Scenario: Create and update a scoped bookmark
- **WHEN** a Token with `bookmarks.write` creates or updates a bookmark with a valid scope, name, path, icon type, and sort value
- **THEN** the tool persists the bookmark only in that scope and returns the complete stored bookmark

#### Scenario: Read bookmarks without write access
- **WHEN** a Token has `bookmarks.read` but not `bookmarks.write`
- **THEN** it can call `folderspan_bookmarks_list`
- **AND** mutation tools are omitted and rejected

### Requirement: Favorite and recent MCP tools
The system SHALL expose `folderspan_favorites_list`, `folderspan_favorites_add`, `folderspan_favorites_remove`, `folderspan_favorites_pin`, `folderspan_recents_list`, `folderspan_recents_delete`, and `folderspan_recents_clear`. Favorite add/remove SHALL resolve a file locator to current metadata before mutation. Recent tools SHALL manage existing access records and SHALL NOT create synthetic recent entries.

#### Scenario: Add a favorite by locator
- **WHEN** a Token with `favorites.write` supplies an accessible Local, Share, Device, or Network file locator
- **THEN** the system resolves current file metadata and creates or returns the matching favorite record

#### Scenario: Clear recent records
- **WHEN** a Token with `recents.write` calls `folderspan_recents_clear`
- **THEN** all recent records are deleted and the response reports the deleted count

### Requirement: File task MCP tools
The system SHALL expose `folderspan_tasks_list`, `folderspan_tasks_get`, `folderspan_tasks_pause`, `folderspan_tasks_resume`, `folderspan_tasks_cancel`, and `folderspan_tasks_delete` for FolderSpan copy, move, and delete tasks. Task responses SHALL include task ID, type, status, endpoints, progress, runtime metrics, failure summary, and available actions. These tools SHALL NOT implement the MCP protocol-level experimental `tasks/*` primitive.

#### Scenario: Pause and resume a file task
- **WHEN** a Token with `tasks.control` pauses a running task and later resumes it
- **THEN** the operations use the existing TaskState pause/resume signals
- **AND** the returned snapshot reflects the resulting status or a stable invalid-state error

#### Scenario: Delete an active task
- **WHEN** a Token requests deletion of a running or paused task
- **THEN** the tool rejects deletion until the task is terminal or canceled

### Requirement: HTTP device discovery and connection tools
The system SHALL expose `folderspan_device_scan`, `folderspan_device_scan_status`, `folderspan_devices_list`, and `folderspan_device_connect`. A scan without a subnet SHALL cover all active non-loopback IPv4 interface subnets. A specific scan SHALL accept an IPv4 CIDR containing at most 4096 host addresses. Scan SHALL return an operation ID immediately. Device listing SHALL exclude offline and failed devices and SHALL group remaining devices as `connected`, `connecting`, `approval_required`, or `discovered`.

#### Scenario: Scan all LAN subnets
- **WHEN** `folderspan_device_scan` is called without a subnet by a Token with `devices.scan`
- **THEN** the system starts one bounded scan over every active IPv4 interface subnet
- **AND** returns an operation ID that can be queried until completed, failed, or canceled

#### Scenario: Scan one subnet
- **WHEN** a valid bounded IPv4 CIDR is supplied
- **THEN** only addresses in that CIDR are scanned
- **AND** local addresses, network addresses, and broadcast addresses are not probed as remote devices

#### Scenario: Connect requires existing trust flow
- **WHEN** `folderspan_device_connect` targets a discovered device that requires certificate or user approval
- **THEN** the existing trust/approval flow is started rather than bypassed
- **AND** the tool returns `approval_required` with the pending device identity

### Requirement: WebRTC device tools
The system SHALL expose `folderspan_webrtc_devices_list` and `folderspan_webrtc_device_connect`. The list SHALL include only currently discovered online WebRTC peers and SHALL use the same status groups and response shape as HTTP device listing. MCP SHALL NOT initiate subnet scanning or manage WebRTC signaling rooms.

#### Scenario: List discovered WebRTC peers
- **WHEN** a Token with `devices.read` lists WebRTC devices
- **THEN** only peers already discovered by the application's active WebRTC state are returned
- **AND** offline room members are excluded

### Requirement: Network and synchronization tools
The system SHALL expose `folderspan_networks_list`, `folderspan_network_connect`, `folderspan_sync_list`, and `folderspan_sync_run`. Network connect SHALL resolve a saved/session entry by ID, validate credentials by listing its root, and only then mark it connected. Sync run SHALL enqueue an existing sync task and return its task ID and current run state without waiting for completion.

#### Scenario: Connect a configured network
- **WHEN** a Token with `networks.connect` supplies an existing network entry ID
- **THEN** the system validates the root listing and marks the entry connected on success
- **AND** auth or connectivity failures are returned without exposing stored credentials

#### Scenario: Run a sync task manually
- **WHEN** a Token with `sync.run` supplies an existing sync task ID that is not already active
- **THEN** the system invokes the existing manual run path and returns an accepted state
