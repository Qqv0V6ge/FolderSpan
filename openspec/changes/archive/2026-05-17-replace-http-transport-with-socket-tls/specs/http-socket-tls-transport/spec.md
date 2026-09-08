## ADDED Requirements

### Requirement: HTTPS Device Transport
The system SHALL expose device HTTP APIs only over HTTPS using a per-device self-signed TLS certificate.

#### Scenario: HTTPS request succeeds
- **WHEN** a client connects to the device API over HTTPS and the certificate fingerprint matches the expected device fingerprint
- **THEN** the server processes the HTTP/1.1 request and returns the matching API response

#### Scenario: Plain HTTP request rejected
- **WHEN** a client sends a plaintext HTTP request to the device API listener
- **THEN** the request SHALL NOT reach the business dispatcher

### Requirement: Device Certificate Pinning
The system SHALL include `httpsPort` and certificate SHA-256 fingerprint in discovered device information and SHALL persist the fingerprint after a connection is approved.

#### Scenario: Fingerprint matches saved trust
- **WHEN** a device reconnects with the same saved fingerprint
- **THEN** the client allows the HTTPS connection attempt

#### Scenario: Fingerprint mismatch
- **WHEN** a device reconnects with a fingerprint different from the saved fingerprint
- **THEN** the client fails the connection without HTTP fallback

### Requirement: TLS-Protected File Byte Bodies
Successful file byte transfer bodies SHALL be plain `application/octet-stream` bytes inside TLS and SHALL NOT use the old application encrypted stream frame headers.

#### Scenario: Read bytes
- **WHEN** `/api/files/read-bytes` succeeds
- **THEN** the response body contains exactly the requested file bytes and does not include `X-FolderSpan-Encrypted-Stream`

#### Scenario: Write bytes
- **WHEN** `/api/files/write-bytes` succeeds
- **THEN** the request body is written as raw bytes protected by TLS and the response remains a protobuf boolean
