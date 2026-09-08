## ADDED Requirements

### Requirement: Link-share custom server compatibility
The link-share browser server SHALL preserve the existing browser and API contract when served by the first-party HTTP server instead of Ktor server engines.

#### Scenario: Browser page and static assets remain compatible
- **GIVEN** a user has an authorized link-share session
- **WHEN** the user opens the shared root or a shared directory in a browser
- **THEN** the server returns the same HTML page contract, static asset routes, content types, security headers, and cache headers required by the current link-share page
- **AND** browser navigation, search, breadcrumbs, script download, and ZIP-download controls continue to work.

#### Scenario: JSON listing remains compatible
- **GIVEN** a ShareNetwork or browser ZIP traversal client sends `X-API-Request: true`
- **WHEN** the client requests the shared root or a shared directory
- **THEN** the server returns the existing JSON listing shape and status-code semantics
- **AND** hidden-file and Disk share permission filtering are applied exactly as normal link-share browsing applies them.

#### Scenario: File download remains compatible
- **GIVEN** an authorized link-share request targets a shared file
- **WHEN** the client downloads the file with or without a valid `Range` header
- **THEN** the server returns the same download headers, content type, byte body, partial-content behavior, and error semantics that existing link-share clients expect.

#### Scenario: Upload remains compatible
- **GIVEN** uploads are enabled for the active link-share session
- **WHEN** the browser calls upload-check or upload routes using the existing query parameters, headers, and request body format
- **THEN** the server accepts, rejects, overwrites, and reports upload results with the same status codes and response bodies as the current link-share upload flow.
