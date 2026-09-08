## MODIFIED Requirements

### Requirement: Production WebRTC file transfer SHALL negotiate transport throughput safely
The production WebRTC device file transfer path SHALL derive safe Session frame size, carrier message size and buffer limits from capabilities supported by both peers before sending file streams. Device Session credit SHALL control logical-stream flow, while WebRTC carrier watermarks SHALL bound DataChannel buffering.

#### Scenario: Remote capabilities arrive before transfer starts
- **WHEN** both peers establish the WebRTC-backed Device Session and exchange supported limits
- **THEN** each side uses the safest common frame, message and buffer limits
- **AND** file streams use Session credit without creating a WebRTC-specific serial transfer plan

#### Scenario: Safe limits cannot be agreed
- **WHEN** peers cannot agree on compatible Session or carrier limits
- **THEN** the Session fails with a protocol compatibility error before payload transfer starts
- **AND** the system does not fall back to the old WebRTC file protocol

### Requirement: Production WebRTC file payloads SHALL use a persistent payload channel
The production WebRTC device file transfer path SHALL carry file payloads as Device Session streams on the persistent, ordered and reliable Session DataChannel. The channel SHALL be reused for RPC and multiple concurrent or sequential logical streams during the peer session, and the system SHALL NOT create a per-file DataChannel.

#### Scenario: Start the first payload transfer in a peer session
- **WHEN** a file transfer starts and the WebRTC-backed Device Session is open
- **THEN** the sender opens a logical Session stream on the existing Session DataChannel
- **AND** the sender does not create a new per-file payload channel

#### Scenario: Start consecutive or concurrent file transfers
- **WHEN** another file transfer starts in the same peer session
- **THEN** it opens an independently controlled logical Session stream on the same DataChannel
- **AND** it does not depend on SCTP stream or DataChannel teardown

#### Scenario: Session DataChannel is unavailable
- **WHEN** the Session DataChannel cannot be opened or closes during transfer
- **THEN** active file streams fail with a connection error
- **AND** later file transfers require a new Device Session

### Requirement: Production WebRTC transfer completion SHALL wait for buffered payload drain
The production WebRTC device file transfer path SHALL not mark a send as completed until the Session confirms the logical stream terminal state and the WebRTC carrier has accepted all corresponding frames without a pending transport failure.

#### Scenario: Payload queued but not accepted by the carrier
- **WHEN** the sender has produced the final stream data but the DataChannel is above its buffer watermark
- **THEN** the transfer remains in progress until frames are accepted and the Session reaches its terminal state or timeout

#### Scenario: Session DataChannel closes before completion
- **WHEN** the Session DataChannel closes before the logical stream reaches successful terminal state
- **THEN** the transfer fails
- **AND** the failure reason indicates that the session carrier closed

### Requirement: Production WebRTC stream transfers SHALL support cooperative pause and cancel control
The production WebRTC device file transfer path SHALL use the common Device Session task and stream controls to pause, resume, and cancel an active file transfer without tearing down the peer session.

#### Scenario: Pause an active transfer
- **WHEN** a local file task pauses an active WebRTC-backed Session upload or download
- **THEN** the common task controller stops producing or consuming new data for that logical stream after already-accepted data is handled
- **AND** other Session RPC and streams remain usable

#### Scenario: Resume a paused transfer
- **WHEN** a paused transfer is resumed
- **THEN** transfer continues through the existing Device Session
- **AND** progress continues from the position maintained by the common transfer implementation instead of restarting from zero

#### Scenario: Cancel an active transfer
- **WHEN** a local file task cancels an active WebRTC-backed Session upload or download
- **THEN** both peers stop the corresponding logical stream promptly
- **AND** any incomplete receive target created for that transfer is cleaned up
- **AND** the operation returns the common cancellation result instead of a generic transport failure

## ADDED Requirements

### Requirement: WebRTC-backed Session SHALL preserve concurrent stream isolation
The system SHALL allow multiple Device Session file streams and RPC calls to make progress over one WebRTC DataChannel subject to negotiated global and per-stream limits. Pausing, cancelling or failing one logical stream MUST NOT implicitly stop unrelated streams.

#### Scenario: Transfer multiple files concurrently
- **WHEN** multiple file operations run concurrently on one WebRTC-backed Device Session
- **THEN** each operation has independent progress and terminal state
- **AND** Session credit prevents one stream from consuming unbounded memory

#### Scenario: One stream is cancelled
- **WHEN** one of several active file streams is cancelled
- **THEN** only that stream ends with cancellation
- **AND** unrelated streams and RPC calls remain active
