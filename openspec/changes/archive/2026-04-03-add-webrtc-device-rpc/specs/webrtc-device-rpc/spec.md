## ADDED Requirements
### Requirement: Approval-gated WebRTC device connect
The system SHALL require an explicit connect approval step before establishing a WebRTC peer connection for device access.

#### Scenario: Initiator requests WebRTC device connect
- **WHEN** a joined room member chooses to connect to another room member over WebRTC
- **THEN** the initiator sends a WebRTC connect request instead of creating an offer immediately
- **AND** no PeerConnection offer is created until the target approves

#### Scenario: Target approves request
- **WHEN** the target device approves a pending WebRTC connect request
- **THEN** the initiator receives an approval response
- **AND** the initiator may begin the normal `offer/answer/ice` handshake

#### Scenario: Target rejects request
- **WHEN** the target device rejects a pending WebRTC connect request
- **THEN** the initiator receives a rejection response
- **AND** the WebRTC device stays disconnected

### Requirement: WebRTC connect requests reuse device approval notifications
The system SHALL reuse the existing device-connect notification flow for WebRTC connect requests on the target side.

#### Scenario: Manual approval required
- **WHEN** a WebRTC connect request reaches a target device that is not configured for auto-approval
- **THEN** the target device enters the existing `WAITING` device-connect state
- **AND** the system emits the existing device-connect notification

#### Scenario: Auto-approval enabled
- **WHEN** the target device is configured to auto-approve device connections
- **THEN** the WebRTC connect request is approved without entering manual review
- **AND** the initiator can continue with peer connection setup

#### Scenario: Request times out
- **WHEN** a pending WebRTC connect request is not approved before timeout
- **THEN** the request is rejected
- **AND** both sides clear the pending approval state
