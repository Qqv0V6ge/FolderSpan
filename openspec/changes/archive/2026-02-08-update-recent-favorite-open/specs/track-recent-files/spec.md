## MODIFIED Requirements
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
