## ADDED Requirements

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
