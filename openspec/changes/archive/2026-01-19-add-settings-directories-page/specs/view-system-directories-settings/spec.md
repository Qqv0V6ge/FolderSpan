## ADDED Requirements

### Requirement: Directories entry in Settings
The system SHALL display a Settings entry that opens a directories subpage.

#### Scenario: Open directories settings page
- **WHEN** the user selects the directories entry in Settings
- **THEN** the directories settings page is opened

### Requirement: List available platform directories
The directories settings page SHALL display a list of directory items provided by the platform directory provider.
Each directory item SHALL include a title, description, and directory path string.
The list SHALL be platform-dependent and SHALL include all directories that can be retrieved on the current platform.
The page SHALL omit directories that are not available on the current platform.
When available, the list SHALL include the system home directory path, the cache directory path, and the app data directory path.

#### Scenario: Render directories list
- **WHEN** the directories settings page is opened
- **THEN** each directory item shows its title, description, and directory path string

#### Scenario: Platform-dependent directories
- **WHEN** the app runs on a platform that cannot provide a given directory path
- **THEN** that directory item is not shown

#### Scenario: Common directories included when available
- **WHEN** the platform provides home, cache, and app data directory paths
- **THEN** the directories settings page shows items for home, cache, and app data

### Requirement: Empty state when no directories are available
The directories settings page SHALL render an empty state when the platform directory provider returns no directory items.

#### Scenario: No directories available
- **WHEN** the platform directory provider returns an empty list
- **THEN** the directories settings page shows an empty state
