## Purpose

Replaces LAN HTTPS ping scanning with a signed UDP beacon so native devices can find each other without opening hundreds of TLS connections.

## Requirements

### Requirement: Native devices advertise a signed LAN beacon

Native desktop, Android, and iOS devices SHALL periodically broadcast a small UDP beacon on port 12041 while the device listener is running. The beacon SHALL include device id, name, type, session port, certificate fingerprint, protocol `folderspan/1`, and a signature produced with the local device identity key. Beacons SHALL be restricted to link-local or site-local destinations and SHALL NOT be answered as a public unicast scan service.

#### Scenario: Running listener advertises the device

- **WHEN** the native device session listener is running on a LAN
- **THEN** peers on the same link or site can observe a beacon for that device
- **AND** the beacon carries the current certificate fingerprint and session port

#### Scenario: Unverified LAN devices remain visible

- **WHEN** a fresh, structurally valid beacon arrives from a remote link-local or site-local address but its identity signature cannot be verified
- **THEN** the receiver displays the device with an unverified identity status
- **AND** discovery alone does not save device metadata, grant trust, or trigger automatic connection
- **AND** an unverified advertisement cannot overwrite an existing verified identity or active connection for the same device

#### Scenario: Invalid discovery envelopes are ignored

- **WHEN** a datagram is malformed, expired, uses an unsupported protocol, has invalid endpoints, or comes from a public or local-self address
- **THEN** the receiver does not add or update a discovered device from that datagram

### Requirement: Unverified identities require explicit fingerprint confirmation

Before authenticating a manually selected unverified LAN device, the client SHALL obtain its identity and actual certificate fingerprint using the existing public Session Identify bootstrap. The bootstrap SHALL be closed before an authenticated connection starts. The identified device id MUST match the selected device. The app SHALL show the device name, endpoint, SHA-256 certificate fingerprint, and any previously trusted fingerprint, with explicit trust and cancel actions. The confirmed fingerprint SHALL be persisted and the subsequent connection SHALL pin exactly that fingerprint. A matching previously saved fingerprint MAY be reused. Signature-valid discovery and existing certificate-change handling SHALL remain available.

#### Scenario: User cancels identity confirmation

- **WHEN** the user cancels or dismisses the trust prompt, or its UI host disappears
- **THEN** the client sends no Connect authorization, credentials, file request, or share payload to that device
- **AND** the device remains visible as unverified
- **AND** no new fingerprint is saved

#### Scenario: User trusts the observed identity

- **WHEN** the user explicitly trusts the displayed TLS fingerprint
- **THEN** the client persists that fingerprint and continues through the existing pinned connection and remote approval flow
- **AND** a subsequent different fingerprint requires a new explicit decision
- **AND** repeated discovery does not replace the identity currently awaiting confirmation

#### Scenario: Device status is visible in the interface

- **WHEN** an unverified device appears in the drawer or device page
- **THEN** the status badge identifies the device as unverified and the manual action offers identity confirmation
- **AND** the confirmation content remains selectable and scrollable on compact windows

### Requirement: Native discovery continuously consumes beacons instead of HTTPS ping

Native device discovery SHALL continuously discover LAN devices by receiving current, structurally valid beacons while the application runtime is active, distinguishing verified and unverified signatures. It SHALL NOT start an address scan, probe the /24 with HTTPS ping requests, or expose a scan/pause control. When automatic discovery is blocked, the user MAY still enter an IP and connect to the session port with `folderspan/1`.
Normal beacon broadcast, receive, expected self/local rejection, and discovered-device refresh SHALL NOT emit a log entry for every packet. Lifecycle transitions, validation warnings, and transport failures MAY still be logged.

#### Scenario: Beacon upserts a discovered device

- **WHEN** a valid signed beacon is received by the native discovery listener
- **THEN** the app inserts or updates that device in the discovered list
- **AND** it does not send an HTTPS ping to confirm the device

#### Scenario: Repeated beacon preserves a pending connection

- **WHEN** a native session connection is waiting for the remote user to approve its `Connect` RPC
- **AND** another valid beacon for the same device refreshes the discovered list
- **THEN** the device remains in the connecting state until the RPC succeeds, fails, or is cancelled
- **AND** the refresh does not replace the connecting state with disconnected

#### Scenario: Private peer source is not mistaken for the local device

- **WHEN** a valid signed beacon arrives from a link-local or site-local source address that is not assigned to the receiver
- **THEN** the receiver does not add that datagram source to its local-address fallback set
- **AND** the app inserts or updates the peer in the discovered list

#### Scenario: Continuous discovery stays quiet during normal operation

- **WHEN** native devices continuously broadcast, receive, reject expected self/local beacons, or refresh a discovered device
- **THEN** those normal per-packet events do not emit repetitive log entries
- **AND** listener lifecycle and transport failures remain diagnosable, and unverified identity status remains visible without per-packet warnings

#### Scenario: Isolated network still allows manual IP

- **WHEN** client isolation prevents UDP beacons from arriving
- **AND** the user enters the peer IP manually
- **THEN** the app obtains the peer's public identity and presented certificate fingerprint through an ALPN-only `folderspan/1` bootstrap
- **AND** closes the bootstrap connection before opening a fingerprint-pinned `folderspan/1` session to the device session port
- **AND** it does not fall back to HTTPS ping
