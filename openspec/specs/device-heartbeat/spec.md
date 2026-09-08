# device-heartbeat Specification
## Purpose
TBD - created by archiving change update-device-heartbeat-polling. Update Purpose after archive.

## Requirements

### Requirement: Device heartbeat polling
The service SHALL keep an authenticated native device session alive with session ping/pong on the control stream. A valid ping SHALL update the device last-seen timestamp. The service SHALL NOT expose `/api/devices/heartbeat` on the device listener.

#### Scenario: Valid heartbeat updates last-seen
- **WHEN** an authenticated session sends a ping
- **THEN** the service updates the device last-seen timestamp
- **AND** responds with a pong

#### Scenario: Invalid token is rejected
- **WHEN** a session has no bound token or the bound token is invalid
- **THEN** the service closes the session with goaway
- **AND** the last-seen timestamp is not updated
