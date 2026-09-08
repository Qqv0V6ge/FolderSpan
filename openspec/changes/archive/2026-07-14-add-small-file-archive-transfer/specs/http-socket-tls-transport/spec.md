## MODIFIED Requirements

### Requirement: Protected File Byte Bodies
Successful native file byte transfer bodies SHALL be plain `application/octet-stream` bytes inside TLS and SHALL NOT use the old encrypted stream frame headers. JS/Wasm HTTP file byte transfer bodies SHALL be carried inside the encrypted HTTP payload envelope. First-party archive stream download and upload bodies SHALL use the same native TLS protection model as file byte bodies, and clients that cannot stream protected archive request bodies SHALL fall back to existing per-file routes.

#### Scenario: Read bytes
- **WHEN** `/api/files/read-bytes` succeeds
- **THEN** the response body contains exactly the requested file bytes and does not include `X-FolderSpan-Encrypted-Stream`

#### Scenario: Write bytes
- **WHEN** `/api/files/write-bytes` succeeds
- **THEN** the request body is written as raw bytes protected by TLS and the response remains a protobuf boolean

#### Scenario: Archive stream bodies
- **WHEN** `/api/files/archive-download`, `/api/files/archive-upload`, or `/api/share/archive-download` transfers archive data on a native TLS connection
- **THEN** the archive stream body SHALL be raw `application/octet-stream` bytes protected by TLS
- **AND** the route SHALL NOT use legacy encrypted stream frame headers
- **AND** JS/Wasm HTTP clients that cannot send or receive a protected archive stream SHALL use existing per-file routes.

## ADDED Requirements

### Requirement: Raw HTTP data capacity SHALL cover archive routes
Raw HTTP server admission SHALL classify bulk archive transfer routes as data routes so reserved control capacity remains available during archive downloads and uploads.

#### Scenario: Archive routes use data capacity
- **WHEN** a request targets `/api/files/archive-download`, `/api/files/archive-upload`, or `/api/share/archive-download`
- **THEN** Raw HTTP route classification SHALL mark it as a data route
- **AND** data route keep-alive and data request capacity accounting SHALL apply.

#### Scenario: Archive upload body is streamed
- **WHEN** a native client sends `/api/files/archive-upload` with a non-empty archive request body
- **THEN** the platform Raw HTTP server SHALL expose the request through `RawHttpRequestBodyReader`
- **AND** the dispatcher SHALL NOT require the whole archive body to be buffered in memory before extraction starts.
