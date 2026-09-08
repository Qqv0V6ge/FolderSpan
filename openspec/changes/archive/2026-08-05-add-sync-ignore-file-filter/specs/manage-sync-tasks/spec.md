## ADDED Requirements

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
