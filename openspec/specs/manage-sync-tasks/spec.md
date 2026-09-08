# manage-sync-tasks Specification

## Purpose
TBD - created by archiving change add-local-device-network-sync. Update Purpose after archive.
## Requirements
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

### Requirement: Sync task Pro synchronization

Sync task definitions SHALL be included in Pro configuration synchronization when the user is signed in.
Sync run history, per-item results, runtime queues, checkpoints, and logs SHALL remain local-only.

#### Scenario: Sync task definition is uploaded
- **WHEN** a signed-in user creates, edits, deletes, pauses, or resumes a sync task
- **THEN** the sync task definition snapshot is saved through the Pro settings API

#### Scenario: Sync task definition is restored
- **WHEN** a signed-in user pulls a valid sync task snapshot
- **THEN** sync task definitions are restored into the local sync task store

#### Scenario: Sync run history is not restored
- **WHEN** a sync task snapshot is applied
- **THEN** existing remote run history is not applied because it is not part of the snapshot

### Requirement: Task-level Ignore file configuration
The Create Sync page SHALL place an advanced Ignore switch above the simple filter rules, SHALL default it to disabled, and SHALL allow the user to select one or more supported regular Ignore files that exist directly in the configured source root.
Advanced Ignore SHALL be valid only for directory sources and SHALL load selected files from the configured source root.
The switch, description, selection summary, and selection action SHALL be presented as one compact flat settings group rather than a detached full-size action button.
The header SHALL use the same flat row layout, text style, switch placement, and horizontal bounds as the adjacent Include subdirectories and Sync empty directories settings.
The file-selection entry SHALL use the same horizontal bounds without extra start or end inset, while retaining sufficient vertical spacing and touch height.
The group SHALL NOT add a card outline or distinct container background.
Only the Switch control itself SHALL change whether Advanced Ignore is enabled; tapping the title, description, or surrounding row SHALL NOT toggle it.
The selection entry and its trailing action icon SHALL be hidden when the source root contains no supported Ignore files.
Changing the source endpoint type, source endpoint reference, or source root path SHALL immediately clear every selected and draft Ignore file name and close the selection dialog, including while Advanced Ignore is disabled.
The system SHALL reject saving an enabled advanced Ignore configuration with no selected file names, unsupported file names, or selected files that are absent from the source root at save time.
Disabling advanced Ignore SHALL preserve the selected names in the task while preventing those files from affecting execution.

#### Scenario: Enable advanced Ignore with selected files
- **WHEN** the user enables advanced Ignore for a directory source and selects one or more supported Ignore file names
- **THEN** the system saves the switch and selected names with the sync task

#### Scenario: Reject empty Ignore selection
- **WHEN** the user enables advanced Ignore without selecting an Ignore file
- **THEN** the system blocks saving and shows an actionable validation error

#### Scenario: List only existing source-root Ignore files
- **WHEN** the user enables advanced Ignore and the configured source root contains a subset of the supported Ignore file names
- **THEN** the selection dialog lists only supported regular files that exist directly in that source root

#### Scenario: Hide selection entry without candidates
- **WHEN** the user enables advanced Ignore and the configured source root contains no supported Ignore files
- **THEN** the page does not show the Ignore file selection entry or its action icon

#### Scenario: Align advanced Ignore with adjacent settings
- **WHEN** the page renders the advanced Ignore group above the simple filter rules
- **THEN** its title and Switch use the same horizontal bounds and visual treatment as the Include subdirectories and Sync empty directories settings
- **AND** the group and its file-selection row have no card outline or distinct container background
- **AND** the file-selection row adds no horizontal inset while retaining vertical breathing room

#### Scenario: Toggle only from the switch
- **WHEN** the user taps the Advanced Ignore title, description, or unused area in their row
- **THEN** the enabled state does not change
- **WHEN** the user taps the Advanced Ignore Switch
- **THEN** the enabled state toggles

#### Scenario: Clear selection when the source changes
- **WHEN** the user changes the source endpoint type, source endpoint, or source root path after selecting Ignore files
- **THEN** the page immediately clears all selected and draft Ignore file names
- **AND** closes any open Ignore file selection dialog
- **AND** discovers candidates from the new source without restoring the previous selection

#### Scenario: Reject a stale selection during save
- **WHEN** a previously selected Ignore file is absent from the configured source root at save time
- **THEN** the system blocks saving and identifies the missing Ignore file

#### Scenario: Preserve selections while disabled
- **WHEN** the user disables advanced Ignore after selecting Ignore files
- **THEN** the task retains the selected names but executes without applying them

### Requirement: Strict Ignore file loading
Before performing any target mutation, an advanced-Ignore run SHALL read every selected Ignore file from the source root using the source endpoint.
The system SHALL support Local, Device, and Network directory sources and SHALL combine multiple selected files in the system-defined stable order.
If any selected file is missing, exceeds the supported size, or cannot be read, the run SHALL fail before creating, copying, replacing, renaming, or deleting any target item.

#### Scenario: Load Ignore files from a remote source
- **WHEN** a Device or Network source exposes every selected Ignore file at its source root
- **THEN** the system reads and applies those files before planning target operations

#### Scenario: Fail closed for an unreadable Ignore file
- **WHEN** any selected Ignore file is missing or unreadable
- **THEN** the run fails with an actionable message
- **AND** the target receives no mutation from that run

### Requirement: Combined sync filtering semantics
Advanced Ignore rules and the existing simple filter rules SHALL be cumulative, and a path matched by either mechanism SHALL be excluded.
The system SHALL prune an ignored directory and its descendants, SHALL exclude each selected Ignore file itself, and SHALL preserve target paths corresponding to ignored source paths in any execution mode that can delete target content.
Advanced Ignore SHALL use the existing supported syntax for comments, negation, root anchoring, directory rules, single-star globs, and recursive double-star globs.

#### Scenario: Exclude a path matched by either rule system
- **WHEN** a path matches a simple filter rule or an enabled advanced Ignore rule
- **THEN** the system excludes that path from the sync plan

#### Scenario: Prune an ignored directory
- **WHEN** an enabled advanced Ignore rule matches a source directory
- **THEN** the system does not traverse or transfer that directory or any descendant

#### Scenario: Protect an ignored target path
- **WHEN** a deletion-capable sync execution compares the target with a source tree containing ignored paths
- **THEN** the system does not delete the corresponding target paths merely because they were omitted from the source snapshot

### Requirement: Ignore rule refresh during repeated execution
Each manual or scheduled run SHALL reload the selected Ignore files before scanning the source.
If directory-watch synchronization is available, changes to a selected Ignore file SHALL reload the matcher and request reconciliation while the Ignore file itself remains excluded from transfer.

#### Scenario: Apply edited rules on the next run
- **WHEN** a selected Ignore file changes after one run completes
- **THEN** the next manual or scheduled run uses the updated rules

#### Scenario: Reconcile after a watched Ignore file changes
- **WHEN** a directory-watch task observes a change to one of its selected Ignore files
- **THEN** the system reloads all selected rules and reconciles affected source and target paths
