## ADDED Requirements

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
The first-party link-share server SHALL preserve the existing HTTP default entry and HTTPS counterpart behavior for streamed ZIP downloads.

#### Scenario: Default HTTP browsing still works
- **WHEN** a browser opens the default link-share HTTP URL
- **THEN** the first-party server returns the normal link-share page and API routes over HTTP
- **AND** normal browsing and single-file downloads do not require HTTPS.

#### Scenario: HTTPS counterpart still works
- **GIVEN** HTTPS is available for link-share ZIP download
- **WHEN** a browser opens the HTTPS counterpart for the same share path after consent
- **THEN** the first-party server serves the same page, static assets, JSON listings, authorized downloads, and upload routes over HTTPS
- **AND** the endpoint uses the existing device self-signed TLS identity.

#### Scenario: Cross-protocol port mistakes are redirected
- **WHEN** plaintext HTTP reaches the public HTTPS port
- **THEN** the first-party server redirects to the matching public HTTP URL
- **AND** when HTTPS reaches the public HTTP port, the server redirects to the matching public HTTPS URL after TLS negotiation.
