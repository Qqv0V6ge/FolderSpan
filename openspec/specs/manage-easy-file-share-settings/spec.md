# manage-easy-file-share-settings Specification

## Purpose
TBD - created by archiving change add-easy-share-receive-toggle. Update Purpose after archive.
## Requirements
### Requirement: Easy Share receive permission toggle
The system SHALL expose a "允许其他设备分享给我" toggle in `EasyFileShareSettingsScreen` to control whether new devices may share to this device.

#### Scenario: Default enabled
- **WHEN** the user opens Easy Share settings without a stored preference
- **THEN** the toggle is enabled by default

#### Scenario: Preference is persisted
- **WHEN** the user changes the toggle state
- **THEN** the updated preference is persisted and restored on next launch

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
The system SHALL apply changes to 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、and 默认允许上传 only to newly created link-share sessions or newly authorized connections.

#### Scenario: User changes defaults while clients are already connected
- **WHEN** one or more link-share clients are already authorized or connected
- **AND** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、or 默认允许上传
- **THEN** existing authorized clients keep their current access state, selected files, hidden-file visibility, and upload permission
- **AND** future link-share sessions or new client authorizations use the updated defaults

#### Scenario: User starts a new link-share session after changing defaults
- **WHEN** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、or 默认允许上传
- **AND** a new link-share session or new client authorization is created after the change
- **THEN** the new session or authorization uses the latest persisted defaults

### Requirement: Easy Share default upload permission
The system SHALL expose the Easy Share upload setting as the default upload permission for newly authorized link-share devices.

#### Scenario: Default upload enabled for new device
- **WHEN** the default upload setting is enabled
- **AND** a new link-share device is authorized through manual approval, auto-approval, password access, or ticket access
- **THEN** the new device authorization includes upload permission

#### Scenario: Default upload disabled for new device
- **WHEN** the default upload setting is disabled
- **AND** a new link-share device is authorized through manual approval, auto-approval, password access, or ticket access
- **THEN** the new device authorization does not include upload permission
- **AND** the device can request upload permission after it is authorized to browse

### Requirement: Simple account-device automatic connection setting
The system SHALL expose one “自动连接我的设备” toggle in file-sharing settings.
The preference SHALL default to disabled, SHALL be persisted locally across application restarts, and SHALL be excluded from Pro configuration synchronization so each device opts in independently.
The setting SHALL NOT ask the user to configure an access key, identity key, authorization role, WebSocket channel, or other account-device parameter.

#### Scenario: Open settings without a stored preference
- **WHEN** the user opens file-sharing settings before choosing an account-device preference
- **THEN** the “自动连接我的设备” toggle is disabled
- **AND** no extra account-device configuration fields are shown

#### Scenario: Enable while signed in
- **WHEN** the user enables “自动连接我的设备” while signed in
- **THEN** the preference is stored locally
- **AND** the feature becomes eligible without an application restart

#### Scenario: Enable while signed out
- **WHEN** the user enables “自动连接我的设备” while signed out
- **THEN** the preference is stored locally
- **AND** the settings page explains in ordinary language that signing in is required
- **AND** no account-device request is made until a valid session exists

#### Scenario: Restore after restart
- **WHEN** the application restarts after the user changed the preference
- **THEN** the toggle restores the locally persisted value

#### Scenario: Pro settings are synchronized
- **WHEN** Pro configuration settings are uploaded or downloaded
- **THEN** the account-device automatic connection preference is not included or overwritten

#### Scenario: Disable the setting
- **WHEN** the user disables “自动连接我的设备”
- **THEN** the disabled preference is stored locally
- **AND** temporary account-device trust is cleared without requiring an application restart

### Requirement: Settings avoid account-device protocol status
The settings page SHALL describe the feature in terms of automatically connecting the user's own nearby devices and SHALL NOT expose ticket, WebSocket, revision, reconnect, protocol-version, identity-key, or cryptographic terminology.

#### Scenario: Feature is operating normally
- **WHEN** the user views file-sharing settings while account-device automatic connection is available
- **THEN** the page shows the toggle and a short plain-language description
- **AND** does not show a persistent online-channel status

#### Scenario: Account data cannot be refreshed
- **WHEN** account devices cannot currently be refreshed
- **THEN** the page may show a concise sign-in or retry message understandable to ordinary users
- **AND** does not expose internal protocol or credential details
