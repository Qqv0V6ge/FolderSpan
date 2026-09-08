# track-recent-files Specification

## Purpose
TBD - created by archiving change add-recent-files. Update Purpose after archive.
## Requirements
### Requirement: Record recent file and folder clicks
The system SHALL record a recent entry whenever a user clicks a file or folder in FileScreen.
The system SHALL record entries for local and non-local protocols.
The system SHALL de-duplicate entries by path + protocol + protocolId by updating the existing record's metadata and lastAccessed timestamp.
The system SHALL retain only the most recent 300 entries after each update.

#### Scenario: Click local folder updates recents
- **WHEN** a user clicks a local folder in FileScreen
- **THEN** the recent table contains an entry for that path
- **AND** the entry's lastAccessed is updated to the current time

#### Scenario: Click remote file updates recents
- **WHEN** a user clicks a non-local file in FileScreen
- **THEN** the recent table contains an entry for that path + protocol + protocolId

#### Scenario: Oldest entries are pruned
- **WHEN** the recent list exceeds 300 entries
- **THEN** the oldest entries are removed, keeping the most recent 300

### Requirement: View and open recent entries
The system SHALL provide a Recent screen reachable from the drawer above Favorites.
The Recent screen SHALL list recent entries ordered by lastAccessed descending.
Selecting a recent folder SHALL open that folder path on the desk resolved by protocol + protocolId.
Selecting a recent file SHALL open the file on the resolved desk.
If the recent entry's protocol differs from the current desk, the system SHALL switch to the corresponding desk before opening.
If the resolved desk is not currently connected (Device/Share/Network), the system SHALL attempt to connect and open the target path on success.
If the target desk cannot be resolved, the system SHALL show a snackbar message "目标不可用" and remain on the current desk.

#### Scenario: Open recent folder from drawer
- **WHEN** the user selects a recent folder whose target desk is available
- **THEN** the file browser navigates to that folder path

#### Scenario: Open recent file from a different protocol
- **WHEN** the user selects a recent file whose protocol differs from the current desk and the target desk is available
- **THEN** the app switches to that desk and opens the file

#### Scenario: Open recent entry with auto-connect
- **WHEN** the user selects a recent entry on a Device/Share/Network desk that is discoverable but not connected
- **THEN** the app attempts to connect and opens the target path on success

#### Scenario: Target unavailable
- **WHEN** the user selects a recent entry whose protocol/protocolId cannot be resolved
- **THEN** the app shows a snackbar message "目标不可用"
- **AND** no navigation occurs

### Requirement: Delete recent entries
The system SHALL allow deleting recent entries from the Recent screen.
The Recent screen SHALL support selecting multiple entries and deleting them in a batch.
Deleting a recent entry SHALL remove it from local persistence.

#### Scenario: Delete selected recent entries
- **WHEN** the user deletes selected recent entries
- **THEN** the selected entries are removed from the recent table

#### Scenario: Clear all recent entries
- **WHEN** the user clears all recent entries
- **THEN** the recent table contains no entries

### Requirement: Record successful transfer destinations
The system SHALL record a recent entry when a file or folder transfer completes successfully and produces a destination path that is supported by recent tracking.
The system SHALL record the destination entry rather than the source entry.
The system SHALL apply the same de-duplication, metadata refresh, and retention rules used for click-tracked recent entries.
The system SHALL NOT create a recent entry for a transfer that fails or is cancelled.

#### Scenario: Successful file transfer records destination
- **WHEN** a user completes a successful file transfer
- **THEN** the recent table contains the destination file entry
- **AND** the entry metadata reflects the destination file path and protocol

#### Scenario: Successful folder transfer records destination
- **WHEN** a user completes a successful folder transfer
- **THEN** the recent table contains the destination folder entry

#### Scenario: Failed transfer does not record destination
- **WHEN** a file or folder transfer fails or is cancelled
- **THEN** no new recent entry is created for the destination path

