## ADDED Requirements
### Requirement: Production WebRTC file transfer SHALL negotiate transport throughput safely
The production WebRTC device file transfer path SHALL exchange transport capabilities on the control channel and derive a safe transfer plan from both peers before sending payload streams.

#### Scenario: Remote capabilities arrive before transfer starts
- **WHEN** both peers have opened a control channel
- **AND** each side sends a transport capability frame
- **THEN** the sender derives stream count, chunk size, queue capacity, buffer limits, and frame size from the safest common values

#### Scenario: Remote capabilities are unavailable
- **WHEN** a transfer starts before remote transport capabilities are received
- **THEN** the sender falls back to a local safe transfer profile
- **AND** the transfer still proceeds without changing file semantics

### Requirement: Production WebRTC file payloads SHALL use striped stream channels
The production WebRTC device file transfer path SHALL send file payloads on dedicated data channels separate from the control channel.

#### Scenario: Negotiated multi-stream transfer
- **WHEN** the negotiated transfer plan allows more than one stream
- **THEN** the sender opens dedicated stream channels for the transfer
- **AND** payload frames are striped across those channels using transfer id and byte offset metadata

#### Scenario: Stream availability is reduced
- **WHEN** fewer stream channels open than requested
- **THEN** the sender degrades to the available open stream count when at least one stream is available
- **AND** the transfer does not fall back to unordered raw chunk semantics on the control channel

### Requirement: Production WebRTC transfer completion SHALL wait for buffered payload drain
The production WebRTC device file transfer path SHALL not mark a send as completed until buffered payload data has drained from the active stream channels.

#### Scenario: Payload queued but not drained
- **WHEN** the sender has emitted the last payload frame
- **AND** one or more stream channels still report buffered data
- **THEN** the transfer remains in progress until the stream buffers drain or timeout

#### Scenario: Stream closes before completion
- **WHEN** an active payload stream closes before all bytes are delivered
- **THEN** the transfer fails
- **AND** the failure reason indicates that the data channel closed
