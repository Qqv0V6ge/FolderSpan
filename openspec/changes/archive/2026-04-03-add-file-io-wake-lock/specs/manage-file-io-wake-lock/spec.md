## ADDED Requirements
### Requirement: Prevent sleep during active file content I/O
The system SHALL request a platform-specific wake lock while actively reading or writing file content for local reads, local writes, copy, upload, download, and cross-endpoint transfer flows.

The system SHALL share a single platform wake lock across concurrent file I/O sessions by reference counting, and SHALL release the platform wake lock only after the last active session finishes, fails, or is cancelled.

If the current platform cannot provide a wake lock, the system SHALL continue the file task without surfacing a user-visible error and SHALL only log the downgrade.

#### Scenario: Local file copy keeps the device awake
- **WHEN** the user copies a file or directory through the local file task flow
- **THEN** the system requests the platform wake lock before content transfer begins
- **AND** the wake lock remains held until the last file chunk has been written or the task ends

#### Scenario: Remote download keeps the device awake
- **WHEN** the user downloads a remote file to local storage through a device, share, or network transfer flow
- **THEN** the system keeps the platform wake lock active for the duration of the content transfer
- **AND** the wake lock is released after success, failure, or cancellation

#### Scenario: Platform does not support wake lock
- **WHEN** a file content I/O task runs on a platform without an available wake-lock mechanism
- **THEN** the file task still executes
- **AND** the system records a downgrade log instead of failing the task
