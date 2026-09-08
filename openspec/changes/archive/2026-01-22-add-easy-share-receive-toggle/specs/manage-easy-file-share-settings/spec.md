## ADDED Requirements
### Requirement: Easy Share receive permission toggle
The system SHALL expose a "允许其他设备分享给我" toggle in `EasyFileShareSettingsScreen` to control whether new devices may share to this device.

#### Scenario: Default enabled
- **WHEN** the user opens Easy Share settings without a stored preference
- **THEN** the toggle is enabled by default

#### Scenario: Preference is persisted
- **WHEN** the user changes the toggle state
- **THEN** the updated preference is persisted and restored on next launch
