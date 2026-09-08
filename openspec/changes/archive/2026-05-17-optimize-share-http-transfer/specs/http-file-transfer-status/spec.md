## ADDED Requirements
### Requirement: HTTP share transfer routes SHALL expose transfer status headers
The system SHALL include transfer tuning status headers on share byte-transfer responses so clients can adapt range size and parallelism.

#### Scenario: Successful share read-bytes response includes status
- **WHEN** `/api/share/read-bytes` returns a file range successfully
- **THEN** the response body is raw octet-stream data for the requested range
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with the latest transfer status snapshot.

#### Scenario: Successful share stream response includes status
- **WHEN** `/api/share/stream-file` returns a file range successfully
- **THEN** the response body is raw octet-stream data for the requested range
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with the latest transfer status snapshot.
