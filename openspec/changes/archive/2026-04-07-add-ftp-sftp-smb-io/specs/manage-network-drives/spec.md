## MODIFIED Requirements
### Requirement: Network protocol selection
The system SHALL present a protocol selection screen listing FTP, SFTP, SMB, WebDav, and LinkShare.
On JS/WASM platforms, FTP/SFTP/SMB entries SHALL be disabled and display a not-supported hint.
Selecting an enabled protocol SHALL open that protocol's dedicated add screen.
Selecting a disabled protocol SHALL NOT open the add screen and SHALL surface a not-supported message.

#### Scenario: Choose a protocol
- **WHEN** the user selects FTP from the protocol list on a supported platform
- **THEN** the FTP add screen is opened

#### Scenario: Choose LinkShare
- **WHEN** the user selects LinkShare from the protocol list
- **THEN** the LinkShare add screen is opened

#### Scenario: Disabled protocol on web
- **WHEN** the user taps SMB from the protocol list on JS/WASM
- **THEN** the add screen is not opened
- **AND** a not-supported hint is shown

### Requirement: Protocol add screens and persistence toggle
Each FTP/SFTP/SMB/WebDav add screen SHALL collect shared Network fields (name, host, username, password, pathSeparator).
The pathSeparator field SHALL default to "/" for FTP/SFTP/WebDav and "\\" for SMB, and be editable by the user.
The FTP add screen SHALL collect port (default 21), a passive-mode toggle (default enabled), and an FTPS toggle (default disabled).
The SFTP add screen SHALL collect port (default 22), an optional private key, and optional known_hosts content.
The SMB add screen SHALL collect share name (required), port (default 445), and an optional domain/workgroup.
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

#### Scenario: Default FTP port
- **WHEN** the user opens the FTP add screen
- **THEN** the port field is prefilled with 21

### Requirement: Network entry management screen
The system SHALL provide a network management screen accessible from the drawer network section.
The screen SHALL list all network entries and display name, protocol, host, username, and whether the entry is saved locally.
The screen SHALL allow toggling the saved-local status; enabling it SHALL persist the entry, disabling it SHALL remove the persisted record while keeping a session entry.
The screen SHALL allow editing entry details (name, host, username, password, pathSeparator, port, FTP passive/FTPS, SFTP private key/known_hosts, SMB share/domain) and update persisted records when applicable.
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
