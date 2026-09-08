## ADDED Requirements
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
The WebRTC test page SHALL allow the user to select files, choose a connected target peer, and send the files over that peer's data channel with visible transfer progress.

#### Scenario: Send selected files to the active peer
- **WHEN** multiple peer sessions are connected and the user selects one peer as the active transfer target before sending files
- **THEN** the files are sent only over that peer's data channel
- **AND** the UI shows per-file progress until each transfer completes or fails together with the target peer identity

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
