# http-socket-tls-transport Specification
## Purpose
TBD - created by archiving change replace-http-transport-with-socket-tls. Update Purpose after archive.

## Requirements

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

### Requirement: File-backed TLS identity storage
The system SHALL persist the local device HTTPS certificate/private-key identity in a dedicated app-private directory instead of app settings.

#### Scenario: Load existing file-backed identity
- **GIVEN** a valid file-backed TLS identity exists in the dedicated identity directory
- **WHEN** the device HTTPS server starts
- **THEN** the system loads the certificate/private-key identity from that file
- **AND** the advertised certificate SHA-256 fingerprint is derived from the loaded certificate

#### Scenario: Create file-backed identity
- **GIVEN** no valid file-backed TLS identity exists
- **WHEN** the device HTTPS server starts
- **THEN** the system generates a new self-signed device certificate/private-key identity
- **AND** stores it in the dedicated identity directory
- **AND** does not read legacy settings-backed TLS identity values

### Requirement: Obfuscated segmented TLS identity file
The system SHALL store the local TLS identity file with an encrypted or obfuscated filesystem-safe file name and SHALL encode the file content as three independently encrypted segments.

#### Scenario: Write segmented identity file
- **WHEN** the system persists a generated TLS identity
- **THEN** the clear text identity file name is not present on disk
- **AND** the file contains three encrypted segments
- **AND** exactly one encrypted segment contains the TLS identity payload
- **AND** the other two encrypted segments contain random filler payloads
- **AND** each segment is encrypted separately

#### Scenario: Randomized layout
- **WHEN** the system writes the TLS identity file
- **THEN** the identity segment position is selected randomly
- **AND** filler payload sizes vary so the total file size is not fixed

### Requirement: Link-share browser HTTPS counterpart
The system SHALL expose a TLS-protected counterpart for link-share browser routes and static assets on the same public port as the default HTTP link-share entry when streamed browser saving requires HTTPS.

#### Scenario: Default link opens over HTTP
- **WHEN** the application presents or opens a link-share browser URL for normal browsing
- **THEN** the URL uses `http://`
- **AND** the URL uses the configured link-share public port
- **AND** normal directory browsing and single-file downloads continue to work without requiring HTTPS.

#### Scenario: HTTPS serves same link-share routes on same port
- **GIVEN** the user has chosen to continue to HTTPS for streamed batch download
- **WHEN** the browser opens the HTTPS counterpart for the same share path
- **THEN** the HTTPS endpoint uses the same host and port as the HTTP link-share URL with the `https://` scheme
- **AND** the HTTPS endpoint serves the same link-share page, static assets, JSON listings, and authorized file downloads
- **AND** the endpoint uses the app's self-signed TLS identity for that device.

### Requirement: Link-share HTTPS consent guidance
The system SHALL require an explicit app-level consent step before app-initiated navigation from HTTP to HTTPS for streamed batch saving, and SHALL remember consent for the same host, public port, and endpoint identity.

#### Scenario: User starts streamed download from HTTP
- **GIVEN** the user is browsing the link-share page over HTTP
- **AND** no remembered consent exists for the current host, public port, and TLS fingerprint
- **WHEN** the user starts streamed batch download
- **THEN** the page explains that HTTPS is needed for stream saving
- **AND** explains that the browser's self-signed certificate warning is expected for this FolderSpan share endpoint
- **AND** asks whether to continue to HTTPS before navigating.

#### Scenario: User declines HTTPS
- **GIVEN** the HTTPS consent prompt is shown
- **WHEN** the user declines
- **THEN** the page stays on HTTP
- **AND** does not start streamed batch download
- **AND** leaves the script download fallback available.

#### Scenario: Consent is remembered
- **GIVEN** the user has already consented for the current host, public port, and TLS fingerprint
- **WHEN** the user starts streamed batch download again
- **THEN** the app does not show the HTTPS consent prompt again
- **AND** proceeds to the HTTPS streamed download flow.

### Requirement: Link-share first-party HTTP server runtime
Native `HttpShareFileServer` implementations SHALL run link-share HTTP/HTTPS listeners through project-owned HTTP/1.1 server code instead of Ktor server engines.

#### Scenario: Native platforms start without Ktor server engines
- **WHEN** `HttpShareFileServer.start(port)` is called on JVM, Android, or iOS
- **THEN** the listener is created by the first-party server implementation for that platform
- **AND** no production native `HttpShareFileServer` implementation requires `ktor-server-cio` or `ktor-server-netty` to accept or process link-share requests.

#### Scenario: Server lifecycle remains compatible
- **WHEN** the link-share server is started, queried for running state, stopped, and started again
- **THEN** the first-party implementation preserves existing `start`, `stop`, `isRunning`, duplicate-start, port-conflict, and failure-notification behavior.

### Requirement: Link-share HTTP and HTTPS port behavior is preserved
The first-party link-share server SHALL preserve the existing HTTP default entry and HTTPS counterpart behavior for streamed ZIP downloads while serving both protocols from one public port.

#### Scenario: Default HTTP browsing still works
- **WHEN** a browser opens the default link-share HTTP URL
- **THEN** the first-party server returns the normal link-share page and API routes over HTTP on the configured public port
- **AND** normal browsing and single-file downloads do not require HTTPS.

#### Scenario: HTTPS counterpart still works on the same public port
- **GIVEN** HTTPS is available for link-share ZIP download
- **WHEN** a browser opens the HTTPS counterpart for the same share path after consent
- **THEN** the URL uses the same host and port as the default HTTP URL with the `https://` scheme
- **AND** the first-party server serves the same page, static assets, JSON listings, authorized downloads, and upload routes over HTTPS
- **AND** the endpoint uses the existing device self-signed TLS identity.

#### Scenario: No separate public HTTPS listener is required
- **WHEN** `HttpShareFileServer.start(port)` starts the link-share server
- **THEN** the public `port` accepts both plaintext HTTP and TLS HTTPS link-share connections
- **AND** the implementation does not start a second public link-share HTTPS port for the same share.
