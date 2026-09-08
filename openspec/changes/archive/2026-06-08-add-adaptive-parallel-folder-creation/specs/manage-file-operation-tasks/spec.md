## MODIFIED Requirements

### Requirement: File operation tasks SHALL persist execution manifests and checkpoints
The system SHALL persist a structured manifest before executing copy, move, and delete tasks, and SHALL persist checkpoints while consuming that manifest. Directory traversal used by copy, move, delete, property summary, and device traverse operations SHALL use endpoint-adaptive, bounded parallel traversal across local, device, share, and network sources, SHALL adjust traversal concurrency during scanning based on observed list request performance, and SHALL preserve deterministic persisted entry ordering where manifests are persisted. Batch directory-create workers, batch file copy workers, and resumable chunk transfer workers SHALL also use endpoint-adaptive, bounded operation concurrency while preserving the existing manifest execution order, retry semantics, and delete ordering semantics.

#### Scenario: Persist copy task manifest before execution
- **GIVEN** the user starts a copy task
- **WHEN** the task is accepted for execution
- **THEN** the system SHALL write a manifest containing the ordered copy entries before the first entry is executed
- **AND** the task SHALL resume from that persisted manifest instead of re-traversing when the user later chooses `继续任务`

#### Scenario: Persist move task stage checkpoint
- **GIVEN** a move task has finished copying some entries and is about to continue deleting source entries
- **WHEN** the task writes its current execution state
- **THEN** the checkpoint SHALL record whether it is in the copy stage or delete-source stage
- **AND** continuing the task SHALL resume from the recorded stage without re-copying already completed target entries

#### Scenario: Persist delete task traversal result
- **GIVEN** the user starts deleting a directory tree
- **WHEN** the task begins executing
- **THEN** the system SHALL persist the ordered delete entries
- **AND** a later `继续任务` action SHALL continue with the remaining delete entries instead of traversing the directory again

#### Scenario: Build manifest with parallel traversal
- **GIVEN** a copy, move, or delete task scans a directory tree with multiple child directories
- **WHEN** the task builds its execution manifest
- **THEN** the system SHALL list multiple directories concurrently up to the endpoint-adaptive limit
- **AND** duplicate directory paths SHALL NOT be scanned more than once
- **AND** the persisted copy and delete queues SHALL keep their existing deterministic order

#### Scenario: Traversal concurrency adapts to endpoint performance
- **GIVEN** a file operation task is scanning a directory tree
- **WHEN** directory list requests complete quickly and successfully
- **THEN** the system SHALL increase traversal concurrency up to the endpoint maximum
- **AND** slow or failing list requests SHALL reduce traversal concurrency before the task reports the failure
- **AND** local, device, share, and network traversal SHALL re-evaluate runtime limits during the scan instead of only calculating concurrency once at scan start
- **AND** local, share, and network traversal SHALL base runtime limits on the local device state and observed list performance
- **AND** device traversal SHALL base runtime limits on both the local device state and the remote device transfer status, using the more conservative limit

#### Scenario: Directory creation concurrency adapts during execution
- **GIVEN** a copy or move task is executing persisted directory-create entries
- **WHEN** folder creation requests complete quickly and successfully
- **THEN** the system SHALL create multiple folders concurrently up to the endpoint operation maximum
- **AND** slow, failing, low-memory, or busy runtime states SHALL reduce folder creation concurrency
- **AND** local, share, and network folder creation SHALL base operation runtime limits on the local device state and observed operation performance
- **AND** device folder creation SHALL base operation runtime limits on both the local device state and the remote device transfer status, using the more conservative limit
- **AND** non-task-level folder creation failures SHALL be recorded as retryable item failures while other folder creation entries continue

#### Scenario: Copy operation concurrency adapts during execution
- **GIVEN** a copy or move task is executing folder file copies or resumable file chunks
- **WHEN** copy requests complete quickly and successfully
- **THEN** the system SHALL increase operation concurrency up to the endpoint operation maximum
- **AND** slow, failing, low-memory, or busy runtime states SHALL reduce operation concurrency
- **AND** local, share, and network copy execution SHALL base operation runtime limits on the local device state and observed operation performance
- **AND** device copy execution SHALL base operation runtime limits on both the local device state and the remote device transfer status, using the more conservative limit
- **AND** delete task execution order SHALL remain deterministic and SHALL NOT be parallelized in a way that can delete parent directories before children

#### Scenario: Parallel traversal reports scan speed and concurrency
- **GIVEN** a copy, move, or delete task is scanning a directory tree
- **WHEN** the parallel traversal discovers entries
- **THEN** the task result text SHALL be updated with the discovered entry count
- **AND** the task runtime metrics SHALL include scan speed, elapsed scan time in the remaining-time field, and current traversal concurrency for the task metrics area
- **AND** starting concrete copy or delete execution SHALL reset the remaining-time field to zero before execution ETA updates take over

#### Scenario: Parallel traversal respects pause and cancel
- **GIVEN** a file operation task is scanning a directory tree
- **WHEN** the task is paused or canceled during traversal
- **THEN** the scan SHALL stop dispatching new directory list requests
- **AND** active remote list requests SHALL be canceled rather than waiting for heartbeat retry or request timeout loops
- **AND** the task SHALL preserve existing pause, cancel, and continue-task behavior

#### Scenario: Device traversal preserves control-route responsiveness
- **GIVEN** a file operation task is scanning a remote device directory tree
- **WHEN** the scan dispatches multiple device path list requests
- **THEN** device heartbeat/control requests SHALL use an isolated control client
- **AND** device/share/link-share path listing services SHALL limit concurrent directory enumeration
- **AND** those app-controlled listing services SHALL dynamically raise or lower their active listing limit based on observed listing performance and runtime memory pressure
- **AND** device/share traversal concurrency SHALL be capped below the service-side directory-list capacity rather than reusing file-transfer chunk concurrency
- **AND** network traversal concurrency SHALL dynamically adapt on the client side while remaining independently capped because the remote directory-list service is not controlled by this app
- **AND** heartbeat, keep-alive, pause, cancel, and disconnect monitors SHALL NOT run on the same bulk worker dispatcher used by traversal and transfer workers
- **AND** traversal pressure SHALL NOT intentionally suppress heartbeat retry diagnostics
