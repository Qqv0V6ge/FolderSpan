## ADDED Requirements

### Requirement: HTTP small-file batches SHALL use archive streams
The system SHALL provide first-party archive stream routes for eligible small-file batches while preserving existing byte and stream routes for large files, unsupported peers, and fallback.

#### Scenario: Share small-file folder download uses archive stream
- **GIVEN** a Share-to-local folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL request `/api/share/archive-download` for grouped small files
- **AND** it SHALL write decoded file entries to the local target as they arrive
- **AND** ineligible files SHALL continue through the existing Share byte or stream routes.

#### Scenario: Device small-file folder download uses archive stream
- **GIVEN** a Device-to-local folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL request `/api/files/archive-download` for grouped small files
- **AND** large files SHALL continue through existing adaptive read-bytes or stream-file routes.

#### Scenario: Local small-file folder upload uses archive stream
- **GIVEN** a local-to-Device folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL upload a FolderSpan archive stream to `/api/files/archive-upload`
- **AND** the server SHALL extract entries under the validated destination root
- **AND** ineligible files SHALL continue through the existing write-bytes route.

#### Scenario: Archive stream fallback preserves existing routes
- **GIVEN** an archive stream route is unavailable, rejected, canceled, or fails before all entries complete
- **WHEN** the file task continues processing remaining entries
- **THEN** entries not confirmed complete SHALL be retried through the existing per-file transfer path
- **AND** entries already confirmed complete SHALL NOT be transferred again.

#### Scenario: Archive transfer reports transfer status
- **WHEN** an archive download or upload route returns a response
- **THEN** it SHALL include `X-FolderSpan-Transfer-*` headers where the existing transfer status model applies
- **AND** clients SHALL parse those headers for later scheduling without changing archive frame semantics.
