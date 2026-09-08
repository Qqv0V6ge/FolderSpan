## ADDED Requirements
### Requirement: File operation tasks SHALL expose current runtime metrics
The system SHALL display current speed, estimated remaining time, and active parallel item count for running or paused file operation tasks. Copy and move copy phases SHALL use byte-based speed and remaining-time estimates when byte totals are available. Delete tasks and move delete-source phases SHALL use item-based speed and remaining-time estimates. Missing metric data SHALL be displayed as an empty or placeholder value without attempting legacy data migration.

#### Scenario: Copy task shows byte metrics
- **GIVEN** a copy task is transferring file bytes
- **WHEN** progress is reported
- **THEN** the task UI shows byte speed and estimated remaining time
- **AND** the active parallel count reflects currently executing item results

#### Scenario: Delete task shows item metrics
- **GIVEN** a delete task is processing items
- **WHEN** item progress advances
- **THEN** the task UI shows item speed and estimated remaining time
- **AND** it does not require byte totals

#### Scenario: Missing runtime metrics
- **GIVEN** a running or restored task lacks the new runtime metric values
- **WHEN** the task UI is displayed
- **THEN** the UI shows placeholders or omits the unavailable metric values
- **AND** no old runtime data migration is required

### Requirement: Running task results SHALL show the latest active item
The system SHALL show at most the latest currently executing item result for running or paused file operation tasks. Queued runtime entries SHALL NOT be included in task dialog or task result screen result lists.

#### Scenario: Running task has active and queued items
- **GIVEN** a running task has active item messages and pending queue entries
- **WHEN** the task result display is loaded
- **THEN** the latest active item message is returned
- **AND** queued items are not displayed as result rows

#### Scenario: Failed task result details remain available
- **GIVEN** a task has failed item results
- **WHEN** the task result display is loaded after failure
- **THEN** failed item details remain visible
