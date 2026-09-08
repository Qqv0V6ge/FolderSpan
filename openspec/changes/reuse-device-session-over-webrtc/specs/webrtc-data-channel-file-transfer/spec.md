## MODIFIED Requirements

### Requirement: Multiple peer sessions in one room
The WebRTC test page SHALL allow multiple simultaneous peer connections within the same signaling room and SHALL manage an independent Device Session for each peer by `SocketDevice.id`.

#### Scenario: Connect to multiple peers in one room
- **WHEN** the user is joined to a room and starts sessions to two different peers
- **THEN** the client maintains separate `PeerConnection`, Session DataChannel and Device Session state for each peer
- **AND** disconnecting one peer session does not close the other peer sessions or the room signaling connection

### Requirement: Send files over data channel with peer-targeted progress
The WebRTC test page SHALL allow the user to select files, choose a connected target peer, and send the files through logical streams on that peer's WebRTC-backed Device Session with visible transfer progress.

#### Scenario: Send selected files to the active peer
- **WHEN** multiple peer sessions are connected and the user selects one peer as the active transfer target before sending files
- **THEN** the files are sent only through that peer's Device Session
- **AND** the UI shows per-file progress until each transfer completes or fails together with the target peer identity

#### Scenario: Consecutive files reuse the Session DataChannel
- **WHEN** two files are sent sequentially to the same peer in one connected session
- **THEN** both file streams reuse the existing Session DataChannel
- **AND** the sender does not create a fresh per-file DataChannel

#### Scenario: Transfer completion follows Session stream state
- **WHEN** the sender has produced all file data
- **THEN** the sender waits for the common Device Session stream and task terminal state before marking the transfer successful
- **AND** the test page does not run a separate missing-range retransmission protocol

#### Scenario: Progress reflects common transfer state
- **WHEN** a file transfer is in progress
- **THEN** displayed progress is sourced from the common Device Session file-transfer task
- **AND** progress is not derived from a WebRTC-only chunk confirmation state machine

### Requirement: Receive files without persistence
The WebRTC test page SHALL consume incoming data through the common Device Session file stream, discard file contents after processing, and keep only transfer progress state in memory.

#### Scenario: Receive incoming file
- **WHEN** the client receives an incoming Device Session file stream
- **THEN** the client updates common transfer progress for the sending peer
- **AND** discards the data without saving it to disk

## ADDED Requirements

### Requirement: Test page SHALL exercise the production Session over WebRTC protocol
The WebRTC test page SHALL use the same Session handshake, framing, RPC, stream and carrier backpressure behavior as production WebRTC device connections. Test-only presentation or discard sinks MUST NOT introduce a second transfer protocol.

#### Scenario: Establish a test-page peer session
- **WHEN** the test page completes signaling with a compatible peer
- **THEN** it establishes a Device Session on the peer's ordered reliable DataChannel
- **AND** connection state distinguishes signaling, PeerConnection and Session readiness

#### Scenario: Carrier or Session fails
- **WHEN** the test page encounters a DataChannel closure, protocol error or Session timeout
- **THEN** it displays the corresponding peer-scoped failure
- **AND** it does not silently retry through the removed WebRTC file protocol

## REMOVED Requirements

### Requirement: Plain ProtoBuf File-Stream Control Envelopes
**Reason**: WebRTC 专用文件流控制信封、块确认和缺失区间重传由统一 Device Session 帧、逻辑流与信用量控制取代。

**Migration**: 测试页和生产连接共同使用 Session over WebRTC；旧版文件流控制信封不再解析，也不提供协议回退。

#### Scenario: Control envelope exchange
- **WHEN** 旧版对等方发送 WebRTC 专用文件流控制信封
- **THEN** 新版测试页报告协议不兼容且不启动文件接收
