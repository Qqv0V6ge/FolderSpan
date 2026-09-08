## ADDED Requirements

### Requirement: Request cancellation controls
The system SHALL allow all outbound HTTP client requests in `com.folderspan.service.http.client` to be registered with a request ID and optional batch ID, and expose cancellation by request ID, by batch ID, or for all in-flight requests via `HttpRouteClientManager` and `HttpShareRouteClientManager`.

#### Scenario: Cancel by request ID
- **WHEN** a request is issued with a request ID
- **AND** the caller cancels that request ID
- **THEN** the in-flight request is aborted
- **AND** the request returns `Result.failure`

#### Scenario: Cancel by batch ID
- **WHEN** multiple requests are issued with the same batch ID
- **AND** the caller cancels that batch ID
- **THEN** all matching in-flight requests are aborted
- **AND** each request returns `Result.failure`

#### Scenario: Cancel all in-flight requests
- **WHEN** the caller cancels all in-flight requests for a manager
- **THEN** every tracked request is aborted
- **AND** each request returns `Result.failure`

### Requirement: Cancellation propagates through client APIs
The system SHALL surface forced cancellations as `Result.failure` for all client APIs that issue HTTP requests, including flow-based operations which MUST emit a failure and then complete.

#### Scenario: Cancellation returns failure
- **WHEN** a client request is force-canceled
- **THEN** the caller receives `Result.failure` for that request

#### Scenario: Flow request cancellation
- **WHEN** a flow-based client request is force-canceled
- **THEN** the flow emits a `Result.failure`
- **AND** the flow completes without further emissions

### Requirement: Duplicate request ID handling
The system SHALL cancel any in-flight request when a new request registers with the same request ID in the same manager.

#### Scenario: Reused request ID cancels previous
- **WHEN** a new request starts with a request ID that is already in-flight
- **THEN** the previous request is canceled and returns `Result.failure`
- **AND** the new request becomes the active request for that ID
