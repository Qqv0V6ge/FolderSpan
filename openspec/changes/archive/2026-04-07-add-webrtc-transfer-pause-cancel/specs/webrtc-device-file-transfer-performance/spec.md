## ADDED Requirements
### Requirement: Production WebRTC stream transfers SHALL support cooperative pause and cancel control
The production WebRTC device file transfer path SHALL let the initiating side pause, resume, and cancel an active payload-channel file stream without tearing down the peer session.

#### Scenario: Pause an active transfer
- **WHEN** a local file task pauses an active WebRTC stream upload or download
- **THEN** the controller SHALL send or apply a transfer pause control for that specific transfer
- **AND** the active sender SHALL stop reading new file chunks and stop enqueueing new payload frames after any already-buffered payload drains
- **AND** the transfer SHALL remain resumable on the existing peer session

#### Scenario: Resume a paused transfer
- **WHEN** a paused WebRTC stream transfer is resumed
- **THEN** payload emission SHALL continue on the existing peer session
- **AND** progress SHALL continue from the already confirmed byte position instead of restarting from zero

#### Scenario: Cancel an active transfer
- **WHEN** a local file task cancels an active WebRTC stream upload or download
- **THEN** both peers SHALL stop the transfer promptly
- **AND** any incomplete receive target created for that transfer SHALL be cleaned up
- **AND** the operation SHALL return a cancellation result instead of a generic transport failure
