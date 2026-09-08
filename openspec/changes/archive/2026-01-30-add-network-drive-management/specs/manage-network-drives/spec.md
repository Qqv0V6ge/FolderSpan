## ADDED Requirements

### Requirement: Network drawer add entry
The system SHALL always render the drawer "网络" entry with expand/collapse controls.
When expanded, the drawer entry SHALL present a single Add action that opens the network protocol selection screen.

#### Scenario: Open protocol selection from the drawer
- **WHEN** the user expands the drawer "网络" entry and taps Add
- **THEN** the network protocol selection screen is opened

### Requirement: Network protocol selection
The system SHALL present a protocol selection screen listing FTP, SFTP, WebDav, and LinkShare.
Selecting a protocol SHALL open that protocol's dedicated add screen.

#### Scenario: Choose a protocol
- **WHEN** the user selects FTP from the protocol list
- **THEN** the FTP add screen is opened

#### Scenario: Choose LinkShare
- **WHEN** the user selects LinkShare from the protocol list
- **THEN** the LinkShare add screen is opened

### Requirement: Protocol add screens and persistence toggle
Each FTP/SFTP/WebDav add screen SHALL collect Network fields (name, host, username, password, pathSeparator).
The pathSeparator field SHALL default to "/" and be editable by the user.
The LinkShare add screen SHALL collect a share link, optional password, and an optional name (defaulting to the share link host).
Each add screen SHALL include a "save to database" toggle that defaults to enabled.
Saving with the toggle enabled SHALL persist the entry and add it to NetworkState; saving with the toggle disabled SHALL add it only to NetworkState for the current session.

#### Scenario: Save a persisted network entry
- **WHEN** the user completes a protocol add screen with the save toggle enabled
- **THEN** the network entry is stored in the database
- **AND** the entry is added to NetworkState

#### Scenario: Save a session-only network entry
- **WHEN** the user completes a protocol add screen with the save toggle disabled
- **THEN** the network entry is added to NetworkState
- **AND** no database record is created

### Requirement: Load persisted network entries on startup
The system SHALL load persisted network entries from the database on app start and populate NetworkState for disk switching.

#### Scenario: Restore persisted network entries
- **WHEN** the app launches and stored network entries exist
- **THEN** NetworkState contains those entries for disk switching

### Requirement: Network entry management screen
The system SHALL provide a network management screen accessible from the drawer network section.
The screen SHALL list all network entries and display name, protocol, host, username, and whether the entry is saved locally.
The screen SHALL allow toggling the saved-local status; enabling it SHALL persist the entry, disabling it SHALL remove the persisted record while keeping a session entry.
The screen SHALL allow editing entry details (name, host, username, password, pathSeparator) and update persisted records when applicable.
The screen SHALL allow deleting an entry with confirmation; deleting the currently selected network entry SHALL switch back to the local disk.

#### Scenario: Open network management
- **WHEN** the user taps the network management action in the drawer
- **THEN** the network management screen is opened

#### Scenario: Toggle persistence status
- **WHEN** the user enables the saved-local toggle for a network entry
- **THEN** the entry is stored in the database and marked saved locally

#### Scenario: Edit a persisted network entry
- **WHEN** the user edits a network entry that is saved locally
- **THEN** the entry details are updated in the database
- **AND** the list reflects the updated values

#### Scenario: Delete active network entry
- **WHEN** the user confirms deletion of a network entry that is currently selected
- **THEN** the entry is removed
- **AND** the desk switches back to the local disk
