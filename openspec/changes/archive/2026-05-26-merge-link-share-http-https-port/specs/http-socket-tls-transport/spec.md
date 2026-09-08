## MODIFIED Requirements

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
