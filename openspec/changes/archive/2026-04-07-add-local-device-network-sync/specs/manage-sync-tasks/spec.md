## ADDED Requirements
### Requirement: Sync capability entry and pages
The system SHALL provide a `Sync` capability entry and SHALL expose exactly two sync pages: `Create Sync` and `Sync Management`.
`Create Sync` SHALL be used for both creating and editing sync tasks.

#### Scenario: Open create sync page
- **WHEN** the user chooses to add a sync task
- **THEN** the system opens the `Create Sync` page

#### Scenario: Open sync management page
- **WHEN** the user opens sync from navigation
- **THEN** the system opens the `Sync Management` page

### Requirement: Local Device Network mutual sync scope
The system SHALL allow sync tasks between `Local`, `Device`, and `Network` endpoints when source and target resolve to different effective locations.
The supported directed pairs SHALL include `Local -> Device`, `Device -> Local`, `Local -> Network`, `Network -> Local`, `Device -> Network`, `Network -> Device`, and `Network -> Network`.
The system SHALL reject configurations where source and target resolve to the same endpoint and path.
The system SHALL reject configurations where source and target resolve to the same endpoint and the target path is inside the source path.

#### Scenario: Create Local to Device sync task
- **WHEN** the user selects Local as source and Device as target with valid paths
- **THEN** the system saves the sync task

#### Scenario: Create Network to Network sync task
- **WHEN** the user selects Network as source and Network as target with different effective paths
- **THEN** the system saves the sync task

#### Scenario: Reject identical endpoint and path
- **WHEN** source and target are the same endpoint and effective path
- **THEN** the system blocks saving and shows a validation error

#### Scenario: Reject nested target path on same endpoint
- **WHEN** source and target are the same endpoint and the target path is inside the source path
- **THEN** the system blocks saving and shows a validation error

### Requirement: One-way sync task model
Each sync task SHALL represent exactly one direction (`source -> target`).
To achieve bidirectional sync, users SHALL create two tasks with opposite directions.

#### Scenario: Configure bidirectional behavior
- **WHEN** the user needs bidirectional sync between Local and Network
- **THEN** the user creates one task for `Local -> Network` and another for `Network -> Local`

### Requirement: Create sync form fields and validation
The `Create Sync` page SHALL collect task name, enabled state, source endpoint, target endpoint, source path, target path, conflict policy, include-subdirectory option, filter rules, schedule settings, retry limit, timeout, and concurrency.
The page SHALL validate required fields, connectivity, and permission/auth readiness before allowing save.

#### Scenario: Save valid sync form
- **WHEN** all required fields are valid and endpoint checks pass
- **THEN** the sync task is persisted

#### Scenario: Save blocked by auth failure
- **WHEN** target endpoint auth validation fails
- **THEN** save is blocked and an actionable error is shown

### Requirement: Sync management list and operations
The `Sync Management` page SHALL list sync tasks with name, source-target pair, enabled state, last run, next run, and latest result.
The page SHALL provide list filtering by keyword, source type, target type, task enabled state, run status, and schedule type.
The page SHALL support sorting by update time, task name, and latest run status.
The page SHALL support both single-item operations (run now, pause/resume, edit, duplicate config, delete) and batch operations (run, pause/resume, delete) through selection mode.

#### Scenario: Filter tasks by run status
- **WHEN** the user filters to `Running`
- **THEN** the list shows only tasks in queued or running states

#### Scenario: Filter tasks by endpoint type
- **WHEN** the user filters source type to `Device` and target type to `Network`
- **THEN** the list shows only tasks whose source is device and target is network

#### Scenario: Sort tasks by status
- **WHEN** the user selects sort field `Status`
- **THEN** tasks are ordered by latest run status according to configured direction

#### Scenario: Batch pause selected tasks
- **WHEN** the user selects multiple enabled tasks and runs batch pause
- **THEN** all selected tasks are updated to disabled
- **AND** future schedule triggers are skipped until resumed

#### Scenario: Run task now
- **WHEN** the user selects Run Now for an enabled sync task
- **THEN** the task enters queued/running state and execution starts

#### Scenario: Pause scheduled task
- **WHEN** the user pauses an enabled scheduled task
- **THEN** future schedule triggers are skipped until resumed

### Requirement: Incremental copy execution semantics
Sync execution SHALL copy new and changed items from source to target without deleting source items.
Directory sync SHALL support recursive traversal and optional empty-directory creation.
The system SHALL support conflict policies `REPLACE`, `SKIP`, and `RENAME`.

#### Scenario: Incremental file copy
- **WHEN** a source file is new or changed compared to target
- **THEN** the file is copied to target according to conflict policy

#### Scenario: Keep source after sync
- **WHEN** a sync run completes successfully
- **THEN** source files remain unchanged

### Requirement: Device Network transfer strategy
For `Device <-> Network` and `Network -> Network` sync tasks, the system SHALL execute file transfer through a local staging workflow.
The system SHALL clean up staging files after the run completes or is canceled.

#### Scenario: Device to Network via local staging
- **WHEN** a `Device -> Network` sync run transfers files
- **THEN** files are first staged locally and then uploaded to network target

#### Scenario: Network to Network via local staging
- **WHEN** a `Network -> Network` sync run transfers files
- **THEN** files are first staged locally and then uploaded to the target network path

### Requirement: Run lifecycle and resilience
The system SHALL track run lifecycle states including `QUEUED`, `RUNNING`, `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`, and `CANCELED`.
A single file failure SHALL NOT abort the full run.
The system SHALL support canceling active runs and retrying failed items.
The system SHALL prevent concurrent active runs for the same task.

#### Scenario: Partial success run
- **WHEN** some files fail while others succeed
- **THEN** the run ends with `PARTIAL_SUCCESS`
- **AND** failed items are recorded for retry

#### Scenario: Reject duplicate active run
- **WHEN** the same task is triggered while already running
- **THEN** the system rejects or deduplicates the trigger per task lock policy

### Requirement: Sync run visibility and logs
The system SHALL provide per-run progress, throughput, counters, and per-item results in Sync Management details.
The system SHALL log failure reasons with sanitized messages and SHALL NOT expose secrets such as passwords or tokens.

#### Scenario: View run details
- **WHEN** the user opens run details for a sync task
- **THEN** progress, counts, and failed item reasons are shown

#### Scenario: Sanitized error logging
- **WHEN** an endpoint operation fails with sensitive auth data in context
- **THEN** logs redact secrets before persisting or displaying
