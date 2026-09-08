## ADDED Requirements
### Requirement: Share save transfers SHALL use bounded high-speed chunk reads
Save and Auto-Save file transfers from device shares SHALL copy large files through bounded concurrent `/api/share/read-bytes` chunk requests.

#### Scenario: Save large shared file
- **WHEN** a user saves a large shared file from a device share
- **THEN** the client splits the file into bounded byte ranges and downloads them through `/api/share/read-bytes`
- **AND** the total active requests for the copy operation is bounded
- **AND** the total in-flight bytes for the copy operation is bounded
- **AND** the file is written locally through offset writes without buffering the whole file in memory.

#### Scenario: Save small shared file
- **WHEN** a user saves a shared file that fits in one bounded byte range
- **THEN** the client MAY download it through `/api/share/read-bytes`
- **AND** the file is written locally without creating the chunk worker pipeline.

#### Scenario: Save shared directory with bounded concurrency
- **WHEN** a user saves a shared directory
- **THEN** the client copies files with a bounded file-worker pool
- **AND** it SHALL limit total active requests across the copy operation.
