## MODIFIED Requirements
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

## ADDED Requirements
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
