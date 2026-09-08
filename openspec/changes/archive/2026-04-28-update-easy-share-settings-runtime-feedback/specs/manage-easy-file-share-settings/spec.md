## ADDED Requirements

### Requirement: Auto-start setting changes explain restart requirement
The system SHALL show a Snackbar in `EasyFileShareSettingsScreen` whenever the user turns "自动启动" on or off, and the message SHALL state that restarting the app is required for the change to take effect.

#### Scenario: User enables auto-start
- **WHEN** the user turns on "自动启动"
- **THEN** the new preference is persisted
- **AND** the screen shows a Snackbar indicating the change takes effect after restarting the app

#### Scenario: User disables auto-start
- **WHEN** the user turns off "自动启动"
- **THEN** the new preference is persisted
- **AND** the screen shows a Snackbar indicating the change takes effect after restarting the app

### Requirement: Running service port changes offer restart
The system SHALL detect when the Easy Share service is running during a service port change, and it SHALL show a Snackbar asking whether to restart the service so the new port can take effect.

#### Scenario: User changes port while service is running
- **WHEN** the Easy Share service is running
- **AND** the user saves a valid new service port
- **THEN** the new port preference is persisted
- **AND** the current service keeps running until the user chooses to restart or otherwise stops it
- **AND** the screen shows a Snackbar explaining that the service is running and offers a restart action

#### Scenario: User confirms service restart from Snackbar
- **WHEN** the port-change Snackbar restart action is shown
- **AND** the user clicks the restart action
- **THEN** the system stops the current Easy Share service instance
- **AND** starts the Easy Share service again with the persisted service port
- **AND** shows user-visible feedback for the restart result

#### Scenario: User changes port while service is stopped
- **WHEN** the Easy Share service is not running
- **AND** the user saves a valid new service port
- **THEN** the new port preference is persisted
- **AND** the screen does not show a port-update Snackbar
- **AND** the screen does not offer a service restart action

### Requirement: Default link-share options affect only new connections
The system SHALL apply changes to 默认分享路径、默认自动允许、默认同设备自动允许、and 默认密码访问 only to newly created link-share sessions or newly authorized connections.

#### Scenario: User changes defaults while clients are already connected
- **WHEN** one or more link-share clients are already authorized or connected
- **AND** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、or 默认密码访问
- **THEN** existing authorized clients keep their current access state and selected files
- **AND** future link-share sessions or new client authorizations use the updated defaults

#### Scenario: User starts a new link-share session after changing defaults
- **WHEN** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、or 默认密码访问
- **AND** a new link-share session or new client authorization is created after the change
- **THEN** the new session or authorization uses the latest persisted defaults
