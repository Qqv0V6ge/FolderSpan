## ADDED Requirements
### Requirement: Open local files without download
The system SHALL open a file immediately when a user clicks a non-directory file in FileScreen while the current desk is Local.

#### Scenario: Local file open
- **WHEN** the user clicks a local file in FileScreen
- **THEN** the file opens without a download prompt

### Requirement: Confirm remote file download before opening
The system SHALL show a confirmation dialog when a user clicks a non-directory file in FileScreen while the current desk is non-local and the confirmation prompt is enabled.
The dialog SHALL include Confirm and Cancel actions.
The dialog SHALL include a "do not show again" checkbox that is checked by default.
If the user dismisses the dialog with the checkbox checked, the system SHALL disable the confirmation prompt for future remote file opens.
If the user cancels, the system SHALL not download or open the file.

#### Scenario: Remote file confirmation displayed
- **WHEN** the user clicks a non-local file and the confirmation prompt is enabled
- **THEN** the confirmation dialog is shown with Confirm, Cancel, and a checked "do not show again" control

#### Scenario: Confirmation canceled
- **WHEN** the user cancels the confirmation dialog
- **THEN** no download or open occurs

#### Scenario: Cancel with prompt disabled
- **WHEN** the user cancels the confirmation dialog with the checkbox checked
- **THEN** future remote file opens skip the confirmation dialog

### Requirement: Download remote files to the configured directory
After the user confirms a remote open, or when the confirmation prompt is disabled, the system SHALL download the file to the configured directory.
If no directory is configured, the system SHALL default to the device cache path.
When a download completes, the system SHALL open the downloaded file.

#### Scenario: Prompt suppressed
- **WHEN** the confirmation prompt is disabled and the user clicks a non-local file
- **THEN** the file is downloaded to the configured directory

#### Scenario: Download to default cache directory
- **WHEN** no download directory is configured and the user proceeds with a remote file open
- **THEN** the file is downloaded to the device cache path
- **AND** the file opens after the download completes

#### Scenario: Download to configured directory
- **WHEN** a download directory is configured and the user proceeds with a remote file open
- **THEN** the file is downloaded into the configured directory
- **AND** the file opens after the download completes
