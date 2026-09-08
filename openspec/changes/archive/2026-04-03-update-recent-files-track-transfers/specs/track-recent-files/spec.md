## ADDED Requirements
### Requirement: Record successful transfer destinations
The system SHALL record a recent entry when a file or folder transfer completes successfully and produces a destination path that is supported by recent tracking.
The system SHALL record the destination entry rather than the source entry.
The system SHALL apply the same de-duplication, metadata refresh, and retention rules used for click-tracked recent entries.
The system SHALL NOT create a recent entry for a transfer that fails or is cancelled.

#### Scenario: Successful file transfer records destination
- **WHEN** a user completes a successful file transfer
- **THEN** the recent table contains the destination file entry
- **AND** the entry metadata reflects the destination file path and protocol

#### Scenario: Successful folder transfer records destination
- **WHEN** a user completes a successful folder transfer
- **THEN** the recent table contains the destination folder entry

#### Scenario: Failed transfer does not record destination
- **WHEN** a file or folder transfer fails or is cancelled
- **THEN** no new recent entry is created for the destination path
