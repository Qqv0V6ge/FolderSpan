## ADDED Requirements
### Requirement: File task pause and cancel SHALL control WebRTC stream transfers
The system SHALL propagate file task pause, resume, and cancel actions to active WebRTC payload-channel file transfers started by file operations.

#### Scenario: Pause and resume a WebRTC-backed task
- **GIVEN** a copy or download task is transferring a large file through a WebRTC payload-channel stream
- **WHEN** the user pauses the task and then resumes it
- **THEN** the underlying WebRTC transfer SHALL enter a paused state instead of continuing silently in the background
- **AND** the same transfer SHALL continue after resume without restarting from zero

#### Scenario: Cancel a WebRTC-backed task
- **GIVEN** a copy or download task is transferring a large file through a WebRTC payload-channel stream
- **WHEN** the user cancels the task from the task UI
- **THEN** the underlying WebRTC transfer SHALL be canceled
- **AND** the task SHALL finish with a cancellation reason instead of hanging until transfer completion or disconnect
