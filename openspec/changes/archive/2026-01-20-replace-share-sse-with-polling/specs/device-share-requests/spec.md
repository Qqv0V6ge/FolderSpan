## ADDED Requirements

### Requirement: Share request polling endpoint
The system SHALL expose an HTTP endpoint that accepts a device share request and returns the current approval status.

#### Scenario: Request waiting for approval
- **WHEN** a device share request is created and no approval decision has been made
- **THEN** the endpoint returns a status of WAITING

#### Scenario: Request approved
- **WHEN** the user approves the device share request
- **THEN** the endpoint returns a status of COMPLETED

#### Scenario: Request rejected or timed out
- **WHEN** the user rejects the device share request
- **OR** the share request times out
- **THEN** the endpoint returns a status of REJECTED

### Requirement: Client short polling for share approval
The share request client SHALL poll the share request endpoint at a short interval until a terminal status is returned.

#### Scenario: Polling completes on terminal status
- **WHEN** the endpoint returns COMPLETED, REJECTED, or ERROR
- **THEN** the client stops polling and updates local share status
