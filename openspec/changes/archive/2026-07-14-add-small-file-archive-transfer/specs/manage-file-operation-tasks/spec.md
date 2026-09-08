## ADDED Requirements

### Requirement: File operation tasks SHALL preserve item semantics during archive transfer
File operation tasks SHALL treat archive streams as an internal transfer optimization and SHALL preserve item-level progress, pause/cancel, retry, and fallback behavior.

#### Scenario: Archive batch advances item progress
- **GIVEN** a copy task is transferring an archive batch
- **WHEN** a file or directory entry is successfully extracted or written
- **THEN** task item progress SHALL advance for that entry
- **AND** byte metrics SHALL advance by the file payload bytes written.

#### Scenario: Pause and cancel stop archive scheduling
- **GIVEN** a copy task is transferring or about to transfer an archive batch
- **WHEN** the user pauses or cancels the task
- **THEN** the task SHALL stop scheduling new archive batches
- **AND** active archive requests SHALL be canceled or allowed to finish according to existing request cancellation behavior
- **AND** the task SHALL report the same pause or cancellation state used by non-archive transfers.

#### Scenario: Failed archive entries remain retryable
- **GIVEN** an archive batch fails after some entries completed
- **WHEN** the task records failure metadata
- **THEN** entries confirmed complete SHALL remain successful
- **AND** entries not confirmed complete SHALL keep item-level retry metadata
- **AND** retrying failed items SHALL NOT require replaying successful sibling entries.

#### Scenario: Device-to-device remains on existing pipeline
- **GIVEN** a Device-to-Device folder copy contains many small files
- **WHEN** archive relay is not implemented for that copy direction
- **THEN** the task SHALL continue using the existing bounded device transfer pipeline
- **AND** existing pause, cancel, retry, checkpoint, and progress behavior SHALL remain unchanged.
