# manage-tray-easy-share Specification

## Purpose
TBD - created by archiving change update-tray-easy-share. Update Purpose after archive.
## Requirements
### Requirement: Tray Easy Share entry status
The desktop tray SHALL present an Easy Share entry that reflects whether the link-share service is running.

#### Scenario: Service not running
- **WHEN** the link-share service is not running
- **THEN** the tray shows an Easy Share entry labeled as disabled
- **AND** selecting the entry opens the share page

#### Scenario: Service running
- **WHEN** the link-share service is running
- **THEN** the tray shows an Easy Share entry labeled as enabled
- **AND** the entry exposes submenu actions defined in related requirements

### Requirement: Tray Easy Share open page action
The desktop tray SHALL provide an action to open the Easy Share page when the service is running.

#### Scenario: Open page from tray
- **WHEN** the user selects the Open Page action
- **THEN** the application window is shown and the share page is opened

### Requirement: Tray Easy Share stop service action
The desktop tray SHALL provide an action to stop the Easy Share service when it is running.

#### Scenario: Stop service from tray
- **WHEN** the user selects the Stop Service action and confirms the stop
- **THEN** the link-share service stops
- **AND** pending, authorized, and rejected device lists are cleared

### Requirement: Tray Easy Share address actions
The desktop tray SHALL expose link-share addresses when the service is running.

#### Scenario: Address list
- **WHEN** the service is running
- **THEN** the tray shows an Address submenu
- **AND** each address displays the IP address only

#### Scenario: Open or copy address
- **WHEN** the user selects Open in Browser for an address
- **THEN** the default browser opens the full URL using the configured port and password query when enabled
- **WHEN** the user selects Copy to Clipboard for an address
- **THEN** the full URL using the configured port and password query when enabled is copied to the clipboard

### Requirement: Tray Easy Share authorization restrictions
The desktop tray SHALL allow toggling link-share authorization restrictions while the service is running.

#### Scenario: Toggle auto-approve
- **WHEN** the user enables auto-approve from the tray
- **THEN** autoApprove is set true and autoAuthorizeSameDevice is set false
- **AND** disabling auto-approve sets autoApprove false

#### Scenario: Toggle auto-authorize same device
- **WHEN** the user enables auto-authorize same device from the tray
- **THEN** autoAuthorizeSameDevice is set true and autoApprove is set false
- **AND** disabling auto-authorize same device sets autoAuthorizeSameDevice false

#### Scenario: Toggle password access
- **WHEN** the user enables password access from the tray
- **THEN** a non-empty connectPassword is set
- **AND** autoApprove is set false
- **WHEN** the user disables password access from the tray
- **THEN** connectPassword is cleared

### Requirement: Tray Easy Share device authorization management
The desktop tray SHALL present pending, authorized, and rejected device groups with counts while the service is running.

#### Scenario: Pending device approve or reject
- **WHEN** the user approves a pending device from the tray
- **THEN** the device is removed from pending and added to authorized using the current share selection synchronized from the share page bottom sheet
- **AND** if no share selection exists the full share list is used
- **AND** the current hidden-file preference synchronized from the share page bottom sheet is applied
- **AND** the approval does not prompt for file selection
- **WHEN** the user rejects a pending device
- **THEN** the device is removed from pending and added to rejected

#### Scenario: Authorized device reject
- **WHEN** the user rejects an authorized device
- **THEN** the device is removed from authorized
- **AND** the device is added to rejected when password access is enabled

#### Scenario: Rejected device delete
- **WHEN** the user deletes a rejected device
- **THEN** the device is removed from the rejected list

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

