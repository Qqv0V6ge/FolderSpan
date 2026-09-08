## ADDED Requirements
### Requirement: WebDav base URL handling
The system SHALL treat `Network.host` for WebDav as a base URL (scheme + host + optional path) and append remote paths when issuing WebDav requests.

#### Scenario: List root path from base URL
- **WHEN** the user lists the WebDav root path
- **THEN** the request URL is built from the stored base URL with the root path appended

### Requirement: WebDav authentication and headers
The system SHALL support WebDav auth types Basic, Digest, and Token.
For Basic/Digest, the system SHALL use the configured username and password.
For Token, the system SHALL send the token using the configured token header name and token prefix.
When the token prefix is blank, the token value SHALL be sent as-is; otherwise the value SHALL be `<prefix> <token>`.
The system SHALL apply the configured custom headers to every WebDav request, with precedence after auth and token headers.

#### Scenario: Use Digest auth with custom headers
- **WHEN** a WebDav entry is configured with Digest auth and custom headers
- **THEN** WebDav requests include Digest authentication and the custom headers

#### Scenario: Use token header name and prefix
- **WHEN** a WebDav entry is configured with Token auth, header name `Authorization`, and prefix `Bearer`
- **THEN** WebDav requests include `Authorization: Bearer <token>`

### Requirement: WebDav file operations and logging
The system SHALL support WebDav list, download, upload, rename, delete, create folder, and create file operations.
Each operation SHALL log start, success, and failure reasons via LogKit; list operations SHALL also log the entry count.

#### Scenario: Download a WebDav file
- **WHEN** the user downloads a file from a WebDav entry
- **THEN** the client issues a WebDav GET request
- **AND** success or failure is logged via LogKit
