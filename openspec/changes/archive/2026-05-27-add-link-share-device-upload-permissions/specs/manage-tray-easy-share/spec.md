## ADDED Requirements

### Requirement: Tray Easy Share upload permission management
The desktop tray SHALL allow the host to review and change upload permission for authorized link-share devices while the service is running.

#### Scenario: Pending upload request approve or reject
- **GIVEN** an authorized link-share device has requested upload permission
- **WHEN** the host opens the Easy Share tray authorization menu
- **THEN** the tray shows the device in an upload request group with a count
- **WHEN** the host approves the upload request
- **THEN** the device gains upload permission and is removed from pending upload requests
- **WHEN** the host rejects the upload request
- **THEN** the device remains authorized for browse/download without upload permission
- **AND** the device is removed from pending upload requests

#### Scenario: Authorized device upload permission toggle
- **GIVEN** a link-share device is authorized for browse/download
- **WHEN** the host enables upload permission for that device from the tray
- **THEN** the device can use upload-check and upload routes
- **WHEN** the host disables upload permission for that device from the tray
- **THEN** the device remains authorized for browse/download
- **AND** the device can no longer use upload-check and upload routes

#### Scenario: Rejected upload request delete
- **GIVEN** a link-share device appears in the rejected upload request group
- **WHEN** the host deletes the rejected upload request entry from the tray
- **THEN** the rejected upload request entry is removed
- **AND** the device browse/download authorization is unchanged
