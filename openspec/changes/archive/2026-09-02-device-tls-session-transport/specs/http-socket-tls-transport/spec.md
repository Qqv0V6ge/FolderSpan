## MODIFIED Requirements

### Requirement: Device Transport Security
The system SHALL expose the native device API only over TLS with per-device certificate pinning and ALPN `folderspan/1`, and SHALL NOT provide a plaintext HTTP or HTTP/1.1 compatibility path on the device API port.

#### Scenario: HTTPS request succeeds
- **WHEN** a client connects to the device API over TLS with a matching certificate fingerprint and ALPN `folderspan/1`
- **THEN** the server accepts the session and processes device control or file streams on that connection

#### Scenario: Plain HTTP request is not accepted
- **WHEN** a client sends a plaintext HTTP request to the device API listener
- **THEN** the request SHALL NOT reach the business dispatcher

#### Scenario: Legacy encrypted HTTP payload rejected
- **WHEN** a client sends a device API request with `X-FolderSpan-Encrypted: v1`
- **THEN** the server rejects the request without decrypting or forwarding it to business handlers

### Requirement: Device Certificate Pinning
The system SHALL include the device session port and certificate SHA-256 fingerprint in discovered device information and SHALL persist the fingerprint after a connection is approved.

#### Scenario: Fingerprint matches saved trust
- **WHEN** a device reconnects with the same saved fingerprint
- **THEN** the client allows the TLS session connection attempt

#### Scenario: Fingerprint mismatch
- **WHEN** a device reconnects with a fingerprint different from the saved fingerprint
- **THEN** the client fails the connection without HTTP fallback

### Requirement: Protected File Byte Bodies
Successful native device file transfer bodies SHALL be session data frames inside TLS and SHALL NOT use HTTP routes or the old encrypted stream frame headers. Link-share archive and byte bodies on the share HTTP/HTTPS listener SHALL remain raw `application/octet-stream` bytes protected by TLS when HTTPS is used, and SHALL NOT use legacy encrypted stream frame headers.

#### Scenario: Read bytes
- **WHEN** a native device file read stream succeeds
- **THEN** the received bytes are exactly the requested file range
- **AND** the transfer does not include `X-FolderSpan-Encrypted-Stream`

#### Scenario: Write bytes
- **WHEN** a native device file write stream succeeds
- **THEN** the request data is written as raw bytes protected by TLS
- **AND** the write is not carried as an HTTP `/api/files/write-bytes` body

#### Scenario: Archive stream bodies
- **WHEN** `/api/share/archive-download` transfers archive data on a native TLS link-share connection
- **THEN** the archive stream body SHALL be raw `application/octet-stream` bytes protected by TLS
- **AND** the route SHALL NOT use legacy encrypted stream frame headers
- **AND** JS/Wasm HTTP clients that cannot send or receive a protected archive stream SHALL use existing per-file share routes.

### Requirement: Protected Protobuf API Bodies
Device session control RPC payloads SHALL be plain ProtoBuf bytes inside TLS and SHALL NOT be wrapped in application-level symmetric encryption.

#### Scenario: Request protobuf body
- **WHEN** a client sends a device control RPC with a ProtoBuf payload
- **THEN** the server decodes the payload directly as ProtoBuf after TLS has protected the transport

#### Scenario: Response protobuf body
- **WHEN** the server returns a device control RPC result with a ProtoBuf payload
- **THEN** the client decodes the payload directly as ProtoBuf after TLS has protected the transport

## REMOVED Requirements

### Requirement: Raw HTTP device server SHALL reserve control capacity
**Reason**: The device listener no longer speaks HTTP/1.1. Control and data isolation is provided by multiplexed session streams and credit windows.
**Migration**: Use the device TLS session control stream and session ping; do not send `/api/devices/heartbeat` or other device HTTP routes.

### Requirement: Data keep-alive SHALL be controlled by route class
**Reason**: Device connections are long-lived TLS sessions, not HTTP/1.1 keep-alive sockets classified by route.
**Migration**: Keep a single `folderspan/1` session open for control and file streams.

### Requirement: Raw HTTP data capacity SHALL cover archive routes
**Reason**: Native device archive HTTP routes are removed. Remaining share archive HTTP stays on the link-share listener, which is outside the device session port.
**Migration**: Native directory copies use session manifests and per-file streams. Share archive download remains on the link-share HTTP/HTTPS port.
