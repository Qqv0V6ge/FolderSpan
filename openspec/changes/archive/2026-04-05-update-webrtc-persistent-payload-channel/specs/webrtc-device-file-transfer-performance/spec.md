## MODIFIED Requirements
### Requirement: Production WebRTC file transfer SHALL negotiate transport throughput safely
The production WebRTC device file transfer path SHALL exchange transport capabilities on the control channel and derive a safe serial transfer plan from both peers before sending payload frames on a reusable payload data channel.

#### Scenario: Remote capabilities arrive before transfer starts
- **WHEN** both peers have opened a control channel
- **AND** each side sends a transport capability frame
- **THEN** the sender derives chunk size, queue capacity, buffer limits, and frame size from the safest common values
- **AND** the sender reuses one payload data channel for sequential file transfers in that peer session

#### Scenario: Remote capabilities are unavailable
- **WHEN** a transfer starts before remote transport capabilities are received
- **THEN** the sender falls back to a local safe transfer profile
- **AND** the sender still uses one reusable payload data channel for that peer session

### Requirement: Production WebRTC file payloads SHALL use a persistent payload channel
The production WebRTC device file transfer path SHALL send file payloads on a dedicated data channel separate from the control channel and SHALL reuse that payload channel across sequential file transfers in the same peer session.

#### Scenario: Start the first payload transfer in a peer session
- **WHEN** a file transfer starts and the peer session has an open reusable payload data channel
- **THEN** the sender emits payload frames on that existing payload channel
- **AND** the sender does not create a new per-file payload channel

#### Scenario: Start a consecutive file transfer
- **WHEN** one file transfer finishes and another file transfer starts in the same peer session
- **THEN** the second file reuses the same payload data channel
- **AND** the sender does not depend on per-file SCTP stream teardown before continuing

#### Scenario: Payload channel is unavailable
- **WHEN** the reusable payload data channel cannot be opened or closes during transfer
- **THEN** the active transfer fails
- **AND** later transfers require the session to recreate the payload channel before payload sending resumes

### Requirement: Production WebRTC transfer completion SHALL wait for buffered payload drain
The production WebRTC device file transfer path SHALL not mark a send as completed until buffered payload data has drained from the active reusable payload channel.

#### Scenario: Payload queued but not drained
- **WHEN** the sender has emitted the last payload frame
- **AND** the reusable payload channel still reports buffered data
- **THEN** the transfer remains in progress until the payload buffer drains or timeout

#### Scenario: Payload channel closes before completion
- **WHEN** the reusable payload channel closes before all bytes are delivered
- **THEN** the transfer fails
- **AND** the failure reason indicates that the data channel closed
