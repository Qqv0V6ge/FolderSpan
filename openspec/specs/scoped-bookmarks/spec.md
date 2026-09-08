# scoped-bookmarks Specification

## Purpose
TBD - created by archiving change add-mcp-server. Update Purpose after archive.
## Requirements
### Requirement: Unified scoped bookmark storage
The system SHALL persist bookmarks in one local store keyed by `protocol` and `sourceId`. Each bookmark SHALL contain a stable ID, scope, name, absolute endpoint-native path, icon type, optional icon path, sort order, created time, and updated time. Local SHALL use an empty source ID; Device, Share, and Network SHALL use stable instance IDs. Mutations in one scope SHALL NOT change another scope.

#### Scenario: Same path in two scopes
- **WHEN** the same textual path is bookmarked for Local and for a Device
- **THEN** two independent bookmark records exist and are returned only from their matching scopes

#### Scenario: Source is temporarily unavailable
- **WHEN** bookmarks are listed for a known source that is currently disconnected
- **THEN** stored bookmarks remain available with `sourceAvailable=false`
- **AND** bookmark mutation remains local and does not require reconnecting the source

### Requirement: Existing bookmark migration
On schema migration, every existing local bookmark SHALL be assigned to the Local scope without changing its ID, name, path, icon, or sort order. The first time a known Device scope is accessed after migration, the system SHALL import that device's existing remote bookmarks once, record an import marker, and thereafter use the local scoped store as authoritative. Imported remote records SHALL NOT be deleted from or synchronized back to the remote device.

#### Scenario: Migrate local bookmarks
- **WHEN** the upgraded application opens an existing database containing legacy local bookmarks
- **THEN** each bookmark is migrated to `protocol=Local` and empty `sourceId`
- **AND** rerunning migration does not duplicate records

#### Scenario: Import device bookmarks once
- **WHEN** a connected device scope without an import marker is first listed
- **THEN** reachable remote bookmarks are imported into that Device scope and the import marker is stored
- **AND** later lists do not import the same remote records again

#### Scenario: Device is unavailable during first import
- **WHEN** a Device scope is first accessed while the device is unavailable
- **THEN** existing local scoped records are returned and the import remains pending
- **AND** the first later successful connection performs the one-time import

### Requirement: Scoped bookmarks are used consistently
The drawer, bookmark management UI, MCP tools, favorites navigation, and file navigation SHALL resolve bookmarks through the same scoped bookmark repository. Deleting a Device, Share, or Network configuration SHALL NOT silently delete its bookmarks; the management UI SHALL allow retaining or explicitly deleting the orphaned scope.

#### Scenario: Switch current data source
- **WHEN** the current desk changes from one data source instance to another
- **THEN** the drawer displays bookmarks for the new `{protocol, sourceId}` only

#### Scenario: Remove a source configuration
- **WHEN** a user deletes a Network or Share source with stored bookmarks
- **THEN** the system retains those bookmarks unless the user explicitly confirms bookmark deletion
