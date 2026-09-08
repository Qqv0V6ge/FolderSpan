## ADDED Requirements
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
Selecting a recent folder SHALL open that folder path.
Selecting a recent file SHALL open the file.
If the recent entry's protocol differs from the current desk, the system SHALL switch to the corresponding desk before opening.

#### Scenario: Open recent folder from drawer
- **WHEN** the user opens the Recent screen and selects a recent folder
- **THEN** the file browser navigates to that folder path

#### Scenario: Open recent file from a different protocol
- **WHEN** the user selects a recent file whose protocol is not the current desk
- **THEN** the app switches to that protocol desk and opens the file

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
