# manage-tray-settings Specification

## Purpose
TBD - created by archiving change add-tray-open-settings. Update Purpose after archive.
## Requirements
### Requirement: Tray Settings open page action
The desktop tray SHALL provide an action to open the Settings page.

#### Scenario: Open settings from tray
- **WHEN** the user selects the Settings action in the tray menu
- **THEN** the application window is shown and the Settings page is opened

#### Scenario: Settings already open
- **WHEN** the user selects the Settings action in the tray menu while the current page is Settings or a settings-related subpage
- **THEN** the application window is shown
- **AND** the current Settings page remains unchanged

