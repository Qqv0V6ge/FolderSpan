## ADDED Requirements

### Requirement: File operations SHALL preserve task semantics with adaptive HTTP transfers
File operation tasks SHALL use adaptive HTTP transfer plans without changing existing pause, cancel, retry, checkpoint, result reporting, or manifest ordering semantics.

#### Scenario: Pause and cancel still control adaptive transfer
- **GIVEN** a copy or move task is transferring a device-direct file through adaptive HTTP ranges
- **WHEN** the user pauses or cancels the task
- **THEN** the task SHALL stop scheduling new ranges
- **AND** active tracked HTTP requests SHALL be canceled or allowed to finish according to existing request cancellation behavior
- **AND** the task SHALL report the same pause or cancellation state used by non-adaptive transfers.

#### Scenario: Checkpoints remain range-aware
- **GIVEN** a large file copy uses adaptive stream or byte ranges
- **WHEN** part of the file has been written successfully and the task is interrupted
- **THEN** the task checkpoint SHALL record completed progress using the existing transfer checkpoint model
- **AND** continuing the task SHALL resume from unfinished ranges rather than restarting completed data.

#### Scenario: Retry metadata remains item-based
- **GIVEN** an adaptive HTTP transfer fails for one file entry
- **WHEN** the task records failure metadata
- **THEN** retry information SHALL remain associated with the failed file operation item
- **AND** successful sibling entries SHALL NOT be retried.
