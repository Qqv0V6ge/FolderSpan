## ADDED Requirements

### Requirement: Crash screen Feedback action when log capture is on

The crash screen SHALL keep restart, exit, and copy actions. When Auto capture logs is on, it SHALL also offer Feedback, which opens the in-app feedback form with the displayed crash details and the captured log snapshot. When Auto capture logs is off, Feedback SHALL NOT appear.

#### Scenario: Feedback available after a captured crash

- **WHEN** an unhandled exception shows the crash screen and Auto capture logs is on
- **THEN** Feedback is shown alongside the existing recovery actions

#### Scenario: Feedback hidden when capture is off

- **WHEN** an unhandled exception shows the crash screen and Auto capture logs is off
- **THEN** restart, exit, and copy remain available
- **AND** Feedback is not shown
