## MODIFIED Requirements

### Requirement: Approval-gated WebRTC device connect
The system SHALL require an explicit connect approval step before establishing a WebRTC peer connection and Device Session for device access. An approval SHALL authorize only the corresponding peer connection attempt, and no offer or Device Session business traffic SHALL start before approval.

#### Scenario: Initiator requests WebRTC device connect
- **WHEN** a joined room member chooses to connect to another room member over WebRTC
- **THEN** the initiator sends a WebRTC connect request instead of creating an offer immediately
- **AND** no PeerConnection offer or Device Session is created until the target approves

#### Scenario: Target approves request
- **WHEN** the target device approves a pending WebRTC connect request
- **THEN** the initiator receives an approval response bound to that connection attempt
- **AND** the initiator may begin the normal `offer/answer/ice` handshake and subsequent Device Session setup

#### Scenario: Target rejects request
- **WHEN** the target device rejects a pending WebRTC connect request
- **THEN** the initiator receives a rejection response
- **AND** the WebRTC device stays disconnected without creating a Device Session

## ADDED Requirements

### Requirement: WebRTC device RPC SHALL use Device Session envelopes
WebRTC device RPC SHALL use the same Device Session request, response, stream, error and lifecycle contracts as a TLS Session. The system SHALL NOT maintain a parallel WebRTC-specific business RPC dispatcher after Session establishment.

#### Scenario: Exchange a device RPC
- **WHEN** a peer invokes a device operation over an established WebRTC connection
- **THEN** the operation is encoded and dispatched through the Device Session protocol
- **AND** the result semantics match the same operation over TLS Session

#### Scenario: Session is not established
- **WHEN** a peer attempts a business RPC before the WebRTC-backed Device Session is ready
- **THEN** the operation fails without reaching a device handler

## REMOVED Requirements

### Requirement: Plain ProtoBuf RPC Envelopes
**Reason**: 独立的 WebRTC RPC 信封和分发器被统一 Device Session RPC 取代，继续保留会形成第二套业务协议。

**Migration**: 所有 WebRTC 设备 RPC 调用方和处理方改用 WebRTC 载体上的 Device Session 请求、响应与流；不提供旧协议兼容回退。

#### Scenario: RPC envelope exchange
- **WHEN** 旧版对等方发送 WebRTC 专用的普通 ProtoBuf RPC 信封
- **THEN** 新版会话将其视为不兼容协议且不执行对应业务调用
