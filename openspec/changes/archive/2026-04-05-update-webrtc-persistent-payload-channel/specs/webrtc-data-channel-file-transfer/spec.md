## MODIFIED Requirements
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
