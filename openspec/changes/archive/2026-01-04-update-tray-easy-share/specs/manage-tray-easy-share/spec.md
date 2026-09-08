## ADDED Requirements
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
