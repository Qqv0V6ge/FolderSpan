## MODIFIED Requirements
### Requirement: Protocol add screens and persistence toggle
Each FTP/SFTP add screen SHALL collect Network fields (name, host, username, password, pathSeparator).
The WebDav add screen SHALL collect a base URL (scheme + host + optional path), username, password, pathSeparator, an auth type selection (Basic/Digest/Token), an optional token value, token header name and token prefix, and custom header key/value pairs.
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

#### Scenario: Save WebDav auth settings
- **WHEN** the user saves a WebDav entry with Digest selected, a base URL, and custom headers
- **THEN** the stored entry includes the base URL and the selected auth settings
- **AND** the custom headers are preserved for future requests

### Requirement: Network entry management screen
The system SHALL provide a network management screen accessible from the drawer network section.
The screen SHALL list all network entries and display name, protocol, host, username, and whether the entry is saved locally.
The screen SHALL allow toggling the saved-local status; enabling it SHALL persist the entry, disabling it SHALL remove the persisted record while keeping a session entry.
The screen SHALL allow editing entry details (name, host, username, password, pathSeparator) and update persisted records when applicable.
The screen SHALL allow editing WebDav auth settings (auth type, token, token header name/prefix, custom headers) and update persisted records when applicable.
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
