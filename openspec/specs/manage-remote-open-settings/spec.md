# manage-remote-open-settings Specification

## Purpose
TBD - created by archiving change add-remote-file-open. Update Purpose after archive.
## Requirements
### Requirement: Remote open confirmation setting
The system SHALL provide a remote open settings section in settings `FileShareSettingsScreen` that lets users enable or disable the remote file open confirmation prompt.
The setting SHALL persist across app launches and default to enabled.
Changing the setting SHALL affect subsequent remote file open attempts.

#### Scenario: Toggle confirmation prompt
- **WHEN** the user disables the confirmation prompt in settings `FileShareSettingsScreen`
- **THEN** remote file opens skip the confirmation dialog

### Requirement: Remote open download directory setting
The system SHALL allow users to set a download directory for remote file opens.
The setting SHALL persist across app launches and default to the device cache path.
Changing the setting SHALL affect subsequent remote file open downloads.

#### Scenario: Configure download directory
- **WHEN** the user selects a download directory in settings `FileShareSettingsScreen`
- **THEN** subsequent remote file opens download into that directory

