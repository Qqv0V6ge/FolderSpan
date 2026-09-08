# view-crash-screen Specification

## Purpose
TBD - created by archiving change add-crash-error-page. Update Purpose after archive.
## Requirements
### Requirement: Crash interception screen
The system SHALL intercept unhandled UI exceptions and keep the application running on a dedicated crash screen that displays full error details, including exception name, message, and stack trace when available.

#### Scenario: Unhandled UI exception
- **WHEN** an unhandled exception occurs during UI runtime
- **THEN** the crash screen is shown with the full error details

### Requirement: Crash screen recovery actions
The crash screen SHALL provide actions to restart the app, exit the app, and copy the error details.

#### Scenario: Recovery actions available
- **WHEN** the crash screen is displayed
- **THEN** restart, exit, and copy actions are available to the user

### Requirement: Post-crash recovery display
The system SHALL persist the last crash details and display the crash screen on the next launch if the previous run terminated due to an unhandled exception.

#### Scenario: Crash screen after relaunch
- **WHEN** the app is launched after a previous crash
- **THEN** the crash screen is shown with the last captured error details

