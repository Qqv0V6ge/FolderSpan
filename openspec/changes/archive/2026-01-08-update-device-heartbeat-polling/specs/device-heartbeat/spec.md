## ADDED Requirements
### Requirement: Device heartbeat polling
The service SHALL provide an authenticated heartbeat endpoint at `/api/devices/heartbeat` that uses protobuf request/response payloads and updates the device last-seen timestamp.

#### Scenario: Valid heartbeat updates last-seen
- **WHEN** an authenticated client sends a heartbeat request
- **THEN** the service updates the device last-seen timestamp
- **AND** responds with a protobuf heartbeat response

#### Scenario: Invalid token is rejected
- **WHEN** the heartbeat request is missing a token or uses an invalid token
- **THEN** the service responds with HTTP 401
- **AND** the last-seen timestamp is not updated
