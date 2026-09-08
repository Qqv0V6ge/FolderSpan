# share-files-system Specification

## Purpose
TBD - created by archiving change add-system-share-action. Update Purpose after archive.
## Requirements
### Requirement: System share entry on file share screen
The system SHALL expose a standalone system share action in `FileShareScreen`.

#### Scenario: Open system share selection
- **WHEN** the user activates the system share action
- **THEN** the share list selection UI is shown using the same multi-select list used for link sharing

### Requirement: System share selection semantics
The system SHALL share the items currently checked in the share list; if no items are checked it SHALL share the full current share list.

#### Scenario: Share checked items
- **WHEN** the user confirms system sharing with one or more checked items
- **THEN** only those items are passed to the platform share implementation

#### Scenario: Share all when nothing checked
- **WHEN** the user confirms system sharing with no checked items
- **THEN** all items in the current share list are passed to the platform share implementation

### Requirement: Platform system share delegation
The system SHALL delegate system sharing to platform-specific implementations via a common expect API, passing file and folder items with their paths and metadata.

#### Scenario: Share files and folders
- **WHEN** the platform supports system sharing and the selection includes files or folders
- **THEN** the platform share UI is launched with the selected items
- **AND** folders are shared directly without automatic compression

#### Scenario: Web share API usage
- **WHEN** the platform is web-based and the Web Share API is available
- **THEN** the Web Share API is used to share the selected items

### Requirement: Desktop system share support
The system SHALL support system sharing on JVM desktop targets for Windows 10+, macOS 11+, and Linux with xdg-desktop-portal.

#### Scenario: Windows share UI
- **WHEN** the app runs on Windows 10+ and the system share UI is available
- **THEN** system share launches the Windows share UI with the selected files or folders

#### Scenario: macOS share UI
- **WHEN** the app runs on macOS 11+ and the system share UI is available
- **THEN** system share launches the macOS share sheet with the selected files or folders

#### Scenario: Linux portal share
- **WHEN** the app runs on Linux and xdg-desktop-portal is available
- **THEN** system share uses the portal to share the selected files or folders

### Requirement: Desktop system share fallback
The system SHALL report desktop system share as unsupported when the platform integration cannot be invoked.

#### Scenario: Desktop share unavailable
- **WHEN** the OS share UI or portal is unavailable
- **THEN** the system share API returns false so the UI can display a snackbar warning

