## ADDED Requirements
### Requirement: Failed File Operation Items Can Be Retried
The system SHALL persist structured retry metadata for failed copy, delete, and move task items so the original task can retry only the failed items.

#### Scenario: Retry failed copy items from the original task
- **GIVEN** a copy task finishes with one or more failed file or directory items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL reuse the original task
- **AND** only the failed copy items SHALL be executed again
- **AND** items that already succeeded SHALL NOT be re-executed

#### Scenario: Retry failed delete items from the original task
- **GIVEN** a delete task finishes with failed file or directory items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL retry only the failed delete items
- **AND** the task SHALL be removed after all failed items succeed

#### Scenario: Retry move task delete-source stage
- **GIVEN** a move task copied data successfully but failed while deleting source items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL retry only the failed source deletion items
- **AND** the system SHALL NOT copy the already copied target items again

### Requirement: Failed File Operation Tasks Expose Retry Entry Points
The system SHALL expose retry entry points for failed file operation tasks in both the task dialog and the task result screen.

#### Scenario: Retry action is visible only when retryable failures exist
- **GIVEN** a failed file operation task has structured failed retry items
- **WHEN** the user opens the task dialog or task result screen
- **THEN** the UI SHALL show a `重试失败项` action

#### Scenario: Retry action is hidden when no retryable failures remain
- **GIVEN** a failed task has no failed retry items remaining
- **WHEN** the user opens the task dialog or task result screen
- **THEN** the UI SHALL NOT show a `重试失败项` action
