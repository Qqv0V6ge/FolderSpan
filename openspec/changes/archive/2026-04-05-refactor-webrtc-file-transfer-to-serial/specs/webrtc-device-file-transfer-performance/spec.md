## MODIFIED Requirements
### Requirement: Production WebRTC file transfer SHALL negotiate transport throughput safely
The production WebRTC device file transfer path SHALL exchange transport capabilities on the control channel and derive a safe serial transfer plan from both peers before sending payload streams.

#### Scenario: Remote capabilities arrive before transfer starts
- **WHEN** both peers have opened a control channel
- **AND** each side sends a transport capability frame
- **THEN** the sender derives chunk size, queue capacity, buffer limits, and frame size from the safest common values
- **AND** the sender uses a single dedicated payload stream for the transfer

#### Scenario: Remote capabilities are unavailable
- **WHEN** a transfer starts before remote transport capabilities are received
- **THEN** the sender falls back to a local safe transfer profile
- **AND** the sender still uses a single dedicated payload stream

### Requirement: Production WebRTC file payloads SHALL use a dedicated serial stream channel
The production WebRTC device file transfer path SHALL send file payloads on a dedicated data channel separate from the control channel and SHALL send payload frames serially on that channel.

#### Scenario: Start payload transfer
- **WHEN** a file transfer starts
- **THEN** the sender opens one dedicated payload data channel for the transfer
- **AND** payload frames are emitted in byte-offset order on that channel

#### Scenario: Payload channel availability is reduced
- **WHEN** the dedicated payload stream channel cannot be opened
- **THEN** the transfer fails
- **AND** the sender does not fall back to sending file chunks on the control channel

### Requirement: Production WebRTC transfer completion SHALL wait for buffered payload drain
The production WebRTC device file transfer path SHALL not mark a send as completed until buffered payload data has drained from the active payload stream channel.

#### Scenario: Payload queued but not drained
- **WHEN** the sender has emitted the last payload frame
- **AND** the payload stream channel still reports buffered data
- **THEN** the transfer remains in progress until the stream buffer drains or timeout

#### Scenario: Stream closes before completion
- **WHEN** the active payload stream closes before all bytes are delivered
- **THEN** the transfer fails
- **AND** the failure reason indicates that the data channel closed
