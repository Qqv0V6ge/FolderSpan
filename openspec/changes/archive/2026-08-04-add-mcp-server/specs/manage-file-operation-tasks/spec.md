## ADDED Requirements

### Requirement: MCP SHALL control existing file operation tasks
The system SHALL expose existing copy, move, and delete tasks through authenticated MCP list, detail, pause, resume, cancel, and delete tools. MCP operations SHALL invoke the same `TaskState` transitions, scheduler signals, recovery data, and validation rules as the application UI and SHALL NOT create a parallel task state machine.

#### Scenario: MCP lists tasks
- **WHEN** a Token with `tasks.read` lists file operation tasks
- **THEN** the response is derived from the same task snapshots shown by the application
- **AND** includes status, progress, runtime metrics, failures, and currently valid control actions

#### Scenario: MCP cancels a task
- **WHEN** a Token with `tasks.control` cancels a running or paused task
- **THEN** the existing scheduler cancellation signal is used
- **AND** runtime cleanup and terminal status follow the same behavior as UI cancellation

#### Scenario: MCP attempts an invalid transition
- **WHEN** an MCP caller pauses a terminal task or resumes a task that is not paused or recoverable
- **THEN** the tool returns a structured invalid-state error
- **AND** the stored task is unchanged
