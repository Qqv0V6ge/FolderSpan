## Purpose

Protects the app-hosted LAN file-sharing service with an optional shared access key so only devices configured with the same value can discover and use it.

## ADDED Requirements

### Requirement: Users can configure LAN access-key protection
The system SHALL provide one file-sharing setting that atomically stores whether access-key protection is enabled and the shared key value. The setting SHALL default to disabled, and a key SHALL be valid only after surrounding whitespace is removed and the remaining value contains 1 to 256 printable ASCII characters.

#### Scenario: Protection is disabled by default
- **WHEN** no access-key configuration has been stored
- **THEN** file-sharing access-key protection is disabled
- **AND** the stored key value is treated as empty

#### Scenario: Enabling protection requires a valid key
- **WHEN** the user enables access-key protection without a valid stored value
- **THEN** the system opens the key editor
- **AND** protection is not enabled until the user confirms a valid value

#### Scenario: User saves a valid key
- **WHEN** the user confirms a value that becomes 1 to 256 printable ASCII characters after trimming
- **THEN** the system persists the normalized value together with the current enable state as one configuration

#### Scenario: Stored configuration is malformed
- **WHEN** the persisted access-key configuration cannot be decoded
- **THEN** the system treats access-key protection as disabled with an empty value

### Requirement: The settings UI protects and can generate the key value
The system SHALL mask a configured access key in the settings overview and editor, SHALL NOT display the fixed HTTP header name in the settings UI, and SHALL provide an action that generates a 32-character alphanumeric key.

#### Scenario: Existing key is displayed in settings
- **WHEN** the file-sharing settings screen loads with a non-empty key
- **THEN** the screen shows a masked placeholder instead of the key value
- **AND** the screen does not show the fixed request-header name

#### Scenario: User generates a key
- **WHEN** the user selects the random-key action in the editor
- **THEN** the editor is populated with a 32-character alphanumeric value suitable for confirmation

#### Scenario: User enters an invalid key
- **WHEN** the edited value is empty after trimming, contains a non-printable or non-ASCII character, or exceeds 256 characters
- **THEN** the system shows validation feedback
- **AND** the confirm action remains unavailable

### Requirement: The app-hosted HTTP service enforces the configured key
When access-key protection is enabled, the app-hosted HTTP service SHALL compare the configured value with the `X-FolderSpan-Key` request header before normal route handling. Missing, mismatched, or invalid configured values SHALL fail closed with an empty HTTP 403 response.

#### Scenario: Matching key reaches normal routing
- **WHEN** protection is enabled with a valid configured key
- **AND** a non-preflight request supplies the exact same `X-FolderSpan-Key` value
- **THEN** the service continues with its existing route-specific authentication and request handling

#### Scenario: Missing or mismatched key is rejected
- **WHEN** protection is enabled with a valid configured key
- **AND** a non-preflight request omits `X-FolderSpan-Key` or supplies a different value
- **THEN** the service responds with HTTP 403 and an empty body
- **AND** no route-specific operation is executed

#### Scenario: Enabled configuration contains an invalid value
- **WHEN** protection is enabled but the configured key is invalid
- **THEN** every non-preflight request is rejected with HTTP 403 and an empty body

#### Scenario: Protection is disabled
- **WHEN** access-key protection is disabled
- **THEN** requests continue to use the pre-existing routing and route-specific authentication behavior without requiring `X-FolderSpan-Key`

#### Scenario: Browser sends a CORS preflight request
- **WHEN** the service receives an `OPTIONS` request while protection is enabled
- **THEN** it processes the existing CORS-origin policy without requiring the access key
- **AND** an allowed preflight response advertises `X-FolderSpan-Key` as an accepted request header

### Requirement: First-party clients propagate the latest valid key
First-party clients that use the app-hosted HTTP service SHALL attach the latest configured access key to device discovery and liveness probes, device and share route requests, share approval polling, and app-hosted HTTP WebRTC signaling whenever protection is enabled with a valid value. They SHALL omit the header when protection is disabled or the value is invalid.

#### Scenario: Protected request is created
- **WHEN** a first-party client creates an eligible request while protection is enabled with a valid key
- **THEN** the request contains exactly one `X-FolderSpan-Key` header with the latest configured value

#### Scenario: Protection changes while a client remains active
- **WHEN** the configured key is changed after an HTTP client has been created
- **THEN** the next eligible request reads and sends the new value without requiring that client to be recreated

#### Scenario: Client configuration is disabled or invalid
- **WHEN** protection is disabled or the stored value is invalid
- **THEN** first-party clients omit `X-FolderSpan-Key` from eligible requests
