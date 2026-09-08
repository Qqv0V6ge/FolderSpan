## ADDED Requirements

### Requirement: HTTP file byte routes SHALL expose transfer status headers
The system SHALL include transfer tuning status headers on HTTP responses for `/api/files/write-bytes`, `/api/files/read-bytes`, `/api/files/append-content`, and `/api/share/read-bytes` without changing the existing protobuf response body types.

#### Scenario: Successful byte response includes status
- **WHEN** a selected route completes successfully
- **THEN** the response body matches the previous route contract
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with recommended chunk size, recommended parallel requests, maximum limits, active request count, busy flag, retry delay, and sample time.

#### Scenario: Older clients ignore status
- **WHEN** a client decodes only the protobuf response body
- **THEN** the selected routes remain decodable using the previous body type.

### Requirement: HTTP file clients SHALL adapt later requests from transfer status
The HTTP file clients SHALL parse transfer status headers when present and use the latest valid values to tune later chunk requests.

#### Scenario: Valid status tunes later chunks
- **WHEN** a client receives valid transfer status headers
- **THEN** later HTTP chunk scheduling uses the recommended chunk size and parallel request count within safe local limits
- **AND** a busy response with a retry delay postpones scheduling only subsequent chunks.

#### Scenario: Missing or invalid status falls back safely
- **WHEN** transfer status headers are missing or invalid
- **THEN** the client uses the existing fixed defaults for chunk size and concurrency.
