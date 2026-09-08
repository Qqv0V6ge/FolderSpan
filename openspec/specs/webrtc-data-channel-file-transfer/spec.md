# webrtc-data-channel-file-transfer Specification

## Purpose
TBD - created by archiving change add-webrtc-data-channel-test. Update Purpose after archive.
## Requirements
### Requirement: Data-channel-only WebRTC sessions
The WebRTC test page SHALL use data channels for transfer and SHALL NOT create audio or video tracks for any peer session.

#### Scenario: Start a data-channel session
- **WHEN** the user connects to a peer from the WebRTC test page
- **THEN** the peer session opens a data channel without audio/video tracks

### Requirement: Manual signaling inputs and peer-targeted WSS exchange
The WebRTC test page SHALL provide manual inputs for WSS URL, roomId, local device id, and preferred remote peer id, and SHALL exchange SDP/ICE using the message formats described in `server/README.md`.

#### Scenario: Connect using manual inputs
- **WHEN** the user enters the WSS URL, roomId, local device id, and preferred remote peer id and taps Connect
- **THEN** the client joins the room via WSS using `SocketDevice` identities
- **AND** it starts `offer/answer/ice` exchange with the preferred peer when that peer is available

### Requirement: Multiple peer sessions in one room
The WebRTC test page SHALL allow multiple simultaneous peer connections within the same signaling room and SHALL manage them independently by `SocketDevice.id`.

#### Scenario: Connect to multiple peers in one room
- **WHEN** the user is joined to a room and starts sessions to two different peers
- **THEN** the client maintains separate `PeerConnection` and data-channel state for each peer
- **AND** disconnecting one peer session does not close the other peer sessions or the room signaling connection

### Requirement: Send files over data channel with peer-targeted progress
The WebRTC test page SHALL allow the user to select files, choose a connected target peer, and send the files over that peer's dedicated reusable payload data channel with visible transfer progress.

#### Scenario: Send selected files to the active peer
- **WHEN** multiple peer sessions are connected and the user selects one peer as the active transfer target before sending files
- **THEN** the files are sent only over that peer's dedicated reusable payload data channel
- **AND** the UI shows per-file progress until each transfer completes or fails together with the target peer identity

#### Scenario: Consecutive files reuse the payload channel
- **WHEN** two files are sent sequentially to the same peer in one connected session
- **THEN** the second file reuses the existing payload data channel from the first file
- **AND** the sender does not create a fresh per-file payload channel between those transfers

#### Scenario: Transfer completion waits for receiver confirmation
- **WHEN** the sender has finished one round of file payload delivery
- **THEN** the sender SHALL wait for receiver-side completion confirmation before marking the transfer successful
- **AND** if the receiver reports missing ranges, the sender SHALL retransmit only those missing ranges

#### Scenario: Progress reflects confirmed chunks
- **WHEN** a file transfer is in progress
- **THEN** the displayed chunk progress SHALL represent receiver-confirmed completed chunks rather than estimated chunks derived only from sent bytes

### Requirement: Receive files without persistence
The WebRTC test page SHALL discard incoming file data immediately after processing and SHALL keep only transfer progress state in memory.

#### Scenario: Receive incoming file
- **WHEN** the client receives file metadata and chunk data
- **THEN** the client updates transfer progress for the sending peer and discards the data without saving to disk

### Requirement: Real-time transfer speed display
The WebRTC test page SHALL display real-time transfer speed for active send and receive operations.

#### Scenario: Show transfer speed
- **WHEN** a transfer is in progress
- **THEN** the UI shows the current transfer speed alongside progress and its peer attribution

### Requirement: Plain ProtoBuf File-Stream Control Envelopes
WebRTC file-stream control envelopes SHALL be encoded as plain ProtoBuf bytes on the WebRTC data channel and SHALL NOT be wrapped in application-level symmetric encryption.

#### Scenario: Control envelope exchange
- **WHEN** a peer sends a file-stream control envelope over an established data channel
- **THEN** the receiving peer decodes the envelope directly as ProtoBuf bytes
