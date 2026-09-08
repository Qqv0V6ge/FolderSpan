## ADDED Requirements

### Requirement: Android Root Permission Settings
The Android platform permission provider SHALL expose a Root permission item when the Android implementation supports checking root availability.
The Root permission item SHALL include a title, description, current status, and request action consistent with other permissions on the permissions settings page.
The Root permission request action SHALL invoke the libsu root authorization flow and refresh the displayed permission status after completion.
The Root permission item SHALL report `Unsupported` instead of requesting authorization when root is not available on the current device or runtime.
The Root permission item SHALL include a startup request setting that allows the user to opt in to requesting Root during app startup.

#### Scenario: Root permission item is shown on Android
- **WHEN** the permissions settings page is opened on Android
- **AND** the platform permission provider supports root availability checks
- **THEN** the permissions settings page includes a Root permission item

#### Scenario: Request Root permission from settings
- **WHEN** the user triggers the Root permission request action
- **THEN** the Android platform permission provider invokes the libsu authorization flow
- **AND** the permissions settings page refreshes the Root permission status after the request completes

#### Scenario: Enable startup Root request
- **WHEN** the user enables the startup Root request setting from the Root permission item
- **THEN** the setting is persisted
- **AND** subsequent app startup may invoke the libsu authorization flow when Root is not already granted

#### Scenario: Root unavailable shows unsupported status
- **WHEN** root is not available on the current Android device or runtime
- **THEN** the Root permission item shows `Unsupported`
- **AND** the Root permission action is disabled by the permissions settings page

### Requirement: Android Root Permission Reminder
The startup permission reminder SHALL include Root permission only when the Android platform permission provider reports Root as `Denied` or `NotDetermined`.
The startup permission reminder SHALL NOT include Root permission when the Android platform permission provider reports Root as `Unsupported`.
The startup permission reminder SHALL NOT trigger a root authorization prompt.

#### Scenario: Missing Root permission is included in reminder
- **WHEN** startup permission evaluation runs on Android
- **AND** Root permission status is `Denied` or `NotDetermined`
- **THEN** the permission reminder includes Root permission

#### Scenario: Unsupported Root permission is omitted from reminder
- **WHEN** startup permission evaluation runs on Android
- **AND** Root permission status is `Unsupported`
- **THEN** the permission reminder does not include Root permission

#### Scenario: Reminder does not request root
- **WHEN** startup permission evaluation includes Root permission in a reminder
- **THEN** the system posts the reminder without invoking libsu authorization
