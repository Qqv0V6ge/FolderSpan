## ADDED Requirements

### Requirement: Root Authorization Lifecycle
The system SHALL support Android root authorization through topjohnwu/libsu.
The system SHALL NOT request root access during app startup unless the user has enabled the startup Root request setting.
The system SHALL NOT request root access during passive permission evaluation, file browsing, or background work.
The system SHALL request root access only after explicit user action through the platform permission provider.
The system SHALL report root access as `Unsupported` when the current Android device or runtime cannot provide a usable root shell or root service.

#### Scenario: User grants root access
- **WHEN** the user requests Root permission from the permissions settings page
- **AND** the device grants a usable libsu root shell or root service
- **THEN** the system reports Root permission as `Granted`
- **AND** root-backed privileged file operations become available

#### Scenario: Root unavailable
- **WHEN** the current Android device cannot provide a usable root shell or root service
- **THEN** the system reports Root permission as `Unsupported`
- **AND** the system does not attempt root-backed file operations

#### Scenario: Startup does not prompt for root
- **WHEN** the app starts and Root permission has not been granted
- **AND** the startup Root request setting is disabled
- **THEN** the system does not show a root authorization prompt

#### Scenario: Startup prompts for root when enabled
- **WHEN** the app starts and Root permission has not been granted
- **AND** the startup Root request setting is enabled
- **THEN** the system invokes the libsu authorization flow

### Requirement: Root-Backed File Operation Parity
The system SHALL provide root-backed equivalents for the Android privileged file operations currently supported by Shizuku-backed access.
Root-backed access SHALL support listing directories, reading simple file entries, reading detailed file metadata, creating files, creating directories, deleting paths, deleting directories, renaming entries, checking path existence, reading total/free space, opening read descriptors, and opening write descriptors.
Root-backed reads and writes SHALL support the existing in-memory, range, chunk, byte-range, and byte-stream file paths used by Android `FileUtils`.
When Root permission is `Granted`, ordinary Android filesystem paths SHALL use root-backed access directly before normal Android app file IO.

#### Scenario: Restricted directory listing succeeds with root
- **WHEN** Root permission is `Granted`
- **AND** the root-backed file client can access the directory
- **THEN** the system returns the directory entries using root-backed access
- **AND** the system does not attempt normal Android app file IO for the same directory listing

#### Scenario: Restricted file write succeeds with root
- **WHEN** Root permission is `Granted`
- **AND** the root-backed file client can open the target for writing
- **THEN** the system writes the requested bytes through root-backed access
- **AND** the system does not attempt normal Android app file IO for the same write

#### Scenario: Root operation failure is surfaced
- **WHEN** a root-backed file operation is attempted
- **AND** the root-backed file client returns an operation failure
- **THEN** the system returns a failed result for that file operation

### Requirement: Privileged Backend Selection
The system SHALL attempt normal Android app file IO before using a privileged backend when Root permission is not `Granted`.
The system SHALL attempt privileged fallback only when normal Android app file IO fails with a permission-related error.
Root and Shizuku SHALL be treated as alternative privileged backends where either granted backend is sufficient.
When Root permission is `Granted`, the system SHALL use root-backed access before normal Android app file IO.
When Root permission is not `Granted` and Shizuku is `Granted`, the system SHALL use Shizuku-backed access as the selected privileged backend.
When Root permission is not `Granted` and no privileged backend is selected or the selected backend cannot complete the operation, the system SHALL preserve the original normal IO failure for user-facing error handling.

#### Scenario: Non-permission error does not use privileged fallback
- **WHEN** normal Android file IO fails for a reason that is not permission-related
- **THEN** the system returns the normal IO failure
- **AND** the system does not attempt Shizuku-backed or root-backed access

#### Scenario: Root direct mode bypasses local IO when both privileged backends are granted
- **WHEN** Root permission is `Granted`
- **AND** Shizuku permission is `Granted`
- **THEN** the system attempts root-backed access
- **AND** the system does not attempt normal Android app file IO
- **AND** the system does not attempt Shizuku-backed access for the same operation

#### Scenario: Shizuku is selected when Root is not granted
- **WHEN** normal Android file IO fails with a permission-related error
- **AND** Root permission is not `Granted`
- **AND** Shizuku permission is `Granted`
- **THEN** the system attempts Shizuku-backed access

### Requirement: Content URI Behavior Remains Read-Only
The system SHALL keep Android `content://` URI handling independent from root-backed access.
The system SHALL NOT use root-backed access to write, rename, delete, or create entries for `content://` URIs.

#### Scenario: Content URI write is not escalated
- **WHEN** the user attempts a write operation on a `content://` URI
- **THEN** the system returns the existing read-only content URI failure
- **AND** the system does not attempt Shizuku-backed or root-backed access
