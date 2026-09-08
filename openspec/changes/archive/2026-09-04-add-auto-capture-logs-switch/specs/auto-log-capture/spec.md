## Purpose

Lets the user turn on a device-local diagnostic log capture from About software so that later errors can be diagnosed from the same session's logs.

## ADDED Requirements

### Requirement: About software exposes an auto-capture logs switch

The About software page SHALL offer an Auto capture logs switch on every supported target. The switch SHALL default to off. The choice SHALL persist on the current device and SHALL NOT sync to other devices. Supporting text SHALL state that captured logs may include file paths and device names, stay on this device until the user sends feedback, and that capture is off by default.

#### Scenario: Switch is off by default

- **WHEN** the user has never changed Auto capture logs
- **THEN** log capture is disabled
- **AND** the About software switch is shown in the off position

#### Scenario: Enabling capture persists on this device

- **WHEN** the user turns Auto capture logs on
- **THEN** capture starts immediately without restarting the app
- **AND** the on state is still selected after the app is relaunched on that device

#### Scenario: Disabling capture stops recording

- **WHEN** the user turns Auto capture logs off
- **THEN** new log lines are not captured
- **AND** the previously buffered snapshot is discarded

### Requirement: Enabled capture records application logs with a size cap

While Auto capture logs is on, the system SHALL record application log lines produced by the shared logger, including info, warning, and error lines from file, device, share, network, sync, account, and other in-app features. The buffer SHALL drop oldest lines first so that captured size stays at or below 2 MiB. Web JS and Wasm SHALL keep the buffer in memory only. Android, iOS, and Desktop MAY also keep a cache-directory copy of the same capped snapshot.

#### Scenario: Recent logs survive a burst of output

- **WHEN** capture is on and more than 2 MiB of log lines are produced
- **THEN** the retained snapshot still contains the newest lines
- **AND** the snapshot is no larger than 2 MiB

#### Scenario: Capture off records nothing

- **WHEN** Auto capture logs is off and the app logs an error
- **THEN** no diagnostic snapshot is retained for later feedback

### Requirement: Major features emit operation and failure logs

The system SHALL write shared-logger entries for user-facing feature operations and their failures, covering at least: local file copy/move/delete, device connect, file share and easy share, link share, network drives, WebRTC transfer, sync tasks, bookmarks, search, editor save, clipboard paste, account sign-in, MCP, and settings changes. Failure paths SHALL log at error level with the failure reason.

#### Scenario: File copy failure is logged

- **WHEN** a local or remote file copy fails while capture is on
- **THEN** an error log line is recorded with the failure reason

#### Scenario: Device connect failure is logged

- **WHEN** a device connection attempt fails while capture is on
- **THEN** an error log line is recorded with the failure reason
