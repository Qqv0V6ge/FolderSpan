## ADDED Requirements
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
