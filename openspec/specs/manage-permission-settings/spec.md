# manage-permission-settings Specification

## Purpose
TBD - created by archiving change add-permission-settings. Update Purpose after archive.
## Requirements
### Requirement: Permissions settings entry visibility
The system SHALL display a Permissions entry in Settings when the platform permission provider reports at least one required permission.
The entry SHALL be hidden when the provider reports no permissions.

#### Scenario: Permissions available
- **WHEN** the provider returns one or more permissions
- **THEN** Settings shows the Permissions entry

#### Scenario: Permissions not available
- **WHEN** the provider returns an empty list
- **THEN** Settings hides the Permissions entry

### Requirement: View platform permission status and description
The permissions settings page SHALL list each platform permission with a title, description, and current status.

#### Scenario: Permissions list rendered
- **WHEN** the permissions settings page is opened
- **THEN** each permission item shows its title, description, and status

### Requirement: Request platform permissions
The permissions settings page SHALL allow the user to request a permission through the platform permission provider and update the status after the request completes.
The system SHALL trigger system permission dialogs only after explicit user action and SHALL NOT auto-request permissions during app startup.

#### Scenario: Request permission from settings
- **WHEN** the user triggers a permission request in the permissions settings page
- **THEN** the platform provider handles the request
- **AND** the UI refreshes the permission status

#### Scenario: App startup with missing permissions
- **WHEN** the app starts and one or more required permissions are not granted
- **THEN** the app does not immediately launch a system permission dialog

### Requirement: Startup permission reminder notification
The system SHALL evaluate required permission statuses after app startup and create a permission reminder notification when required permissions are missing.
The reminder SHALL summarize missing permissions and guide the user to the permissions settings page.
The reminder SHALL be deduplicated in the same app session when the missing permission set is unchanged.

#### Scenario: Missing permissions detected on startup
- **WHEN** startup permission evaluation finds one or more missing required permissions
- **THEN** a permission reminder notification is posted to the in-app notification center

#### Scenario: No missing permissions on startup
- **WHEN** startup permission evaluation finds all required permissions granted
- **THEN** no permission reminder notification is created

#### Scenario: Duplicate reminder suppression in one session
- **WHEN** startup evaluation runs again in the same session with the same missing permission set
- **THEN** no duplicate permission reminder notification is posted

### Requirement: Open permissions settings from reminder notification
The system SHALL allow the user to open the permissions settings page from a permission reminder notification.

#### Scenario: Open from in-app notification
- **WHEN** the user opens a permission reminder notification in the notification center
- **THEN** the app navigates to the permissions settings page

#### Scenario: Open from system notification
- **WHEN** the user taps a system notification generated from a permission reminder
- **THEN** the app navigates to the permissions settings page

### Requirement: JVM login startup permission
The system SHALL expose a JVM desktop "开机启动" permission in the permissions settings page on Windows, macOS, and Linux.
The permission SHALL register FolderSpan to start after the current user logs in only after explicit user action.
The permission SHALL report `Unsupported` when the current desktop platform or current app executable cannot be registered safely.

#### Scenario: Login startup permission visible on supported JVM desktop
- **WHEN** the app runs on JVM desktop on Windows, macOS, or Linux
- **THEN** the permissions settings page includes the "开机启动" permission

#### Scenario: User enables login startup
- **WHEN** the user requests the "开机启动" permission
- **THEN** the JVM provider creates or updates the current-user login startup entry for FolderSpan
- **AND** the permission status refreshes after the request completes

#### Scenario: Development runtime is not registered
- **WHEN** the current process executable resolves to a generic JVM launcher such as `java` or `javaw`
- **THEN** the JVM provider reports the "开机启动" permission as `Unsupported`
- **AND** it does not write a login startup entry

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

