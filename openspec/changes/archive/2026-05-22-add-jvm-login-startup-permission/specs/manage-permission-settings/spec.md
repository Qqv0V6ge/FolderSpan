## ADDED Requirements
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
