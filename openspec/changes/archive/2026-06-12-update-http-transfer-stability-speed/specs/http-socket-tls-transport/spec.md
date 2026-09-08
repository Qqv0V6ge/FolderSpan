## ADDED Requirements

### Requirement: Raw HTTP device server SHALL reserve control capacity
The Raw HTTP device server SHALL classify incoming routes as control or data routes and SHALL reserve connection or execution capacity so control routes remain serviceable during bulk data activity.

#### Scenario: Heartbeat remains admitted during data saturation
- **GIVEN** data routes have reached their configured data capacity
- **WHEN** a valid `/api/devices/heartbeat` request arrives
- **THEN** the server SHALL admit and process the heartbeat using reserved control capacity
- **AND** it SHALL NOT reject or delay the heartbeat solely because data routes are saturated.

#### Scenario: Data route respects data capacity
- **GIVEN** data routes have reached their configured data capacity
- **WHEN** another data request arrives
- **THEN** the server SHALL queue, reject, or close that data request according to the data admission policy
- **AND** it SHALL preserve reserved control capacity.

#### Scenario: Control-route classification covers transfer controls
- **WHEN** the server classifies device routes
- **THEN** heartbeat, connect, theme, and copy-control requests SHALL be treated as control traffic
- **AND** path listing, read-bytes, write-bytes, stream-file, and copy requests SHALL be treated as data traffic.

### Requirement: Data keep-alive SHALL be controlled by route class
The Raw HTTP device server SHALL allow HTTP/1.1 keep-alive for data routes only when doing so cannot consume reserved control capacity.

#### Scenario: Data keep-alive is allowed under capacity isolation
- **GIVEN** control and data capacities are enforced independently
- **WHEN** a data route completes successfully and data keep-alive is enabled
- **THEN** the server MAY keep the data connection open for reuse
- **AND** that open data connection SHALL count only against data capacity.

#### Scenario: Control routes keep low-latency reuse
- **WHEN** a control route completes successfully over HTTP/1.1
- **THEN** the server SHALL continue to allow keep-alive unless the client or server explicitly requests close
- **AND** later control requests SHALL NOT wait behind idle data keep-alive sockets.
