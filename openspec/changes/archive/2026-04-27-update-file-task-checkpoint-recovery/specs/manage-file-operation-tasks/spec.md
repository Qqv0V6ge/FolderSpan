## ADDED Requirements
### Requirement: File operation tasks SHALL persist execution manifests and checkpoints
The system SHALL persist a structured manifest before executing copy, move, and delete tasks, and SHALL persist checkpoints while consuming that manifest.

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

### Requirement: Large local and device file copies SHALL persist transfer checkpoints
The system SHALL persist per-file transfer checkpoints for local/device file copy entries whose size is greater than or equal to `MAX_LENGTH * 30`.

#### Scenario: Continue large file copy from completed chunks
- **GIVEN** a local or device file copy entry is at least `MAX_LENGTH * 30` bytes
- **AND** some chunks have already been written successfully
- **WHEN** the task fails and the user later chooses `继续任务`
- **THEN** the system SHALL continue copying from the first unfinished chunk
- **AND** the file SHALL NOT be copied from byte offset `0`

#### Scenario: Small file copy keeps file-level retry behavior
- **GIVEN** a file copy entry is smaller than `MAX_LENGTH * 30` bytes
- **WHEN** the task fails and the user later chooses `继续任务`
- **THEN** the system SHALL retry that file entry from the beginning
- **AND** it SHALL NOT create a per-file transfer checkpoint

#### Scenario: Invalid transfer checkpoint resets current file
- **GIVEN** a large file copy has a persisted transfer checkpoint
- **AND** the target file is missing or incompatible with that checkpoint
- **WHEN** the user chooses `继续任务`
- **THEN** the system SHALL discard the invalid transfer checkpoint
- **AND** the current file entry SHALL restart from byte offset `0`

### Requirement: Failed or interrupted file operation tasks SHALL expose continue entry points
The system SHALL expose a `继续任务` action when a task still has persisted manifest/checkpoint state indicating unfinished entries.

#### Scenario: Continue task after restart
- **GIVEN** the application exits while a file task still has unfinished manifest entries
- **WHEN** the task list is restored after restart
- **THEN** the interrupted task SHALL remain visible
- **AND** the task SHALL be marked as manually recoverable instead of auto-running
- **AND** the UI SHALL expose `继续任务`

#### Scenario: Cancel task removes runtime recovery files
- **GIVEN** a file task has persisted manifest/checkpoint files
- **WHEN** the user actively cancels the task
- **THEN** the system SHALL delete the persisted manifest/checkpoint files
- **AND** the same task SHALL NOT expose `继续任务`
