## ADDED Requirements

### Requirement: HTTP device transfers SHALL adapt stability and throughput
The system SHALL use an adaptive device-direct HTTP transfer plan that adjusts byte-route chunk size, stream range size, transfer parallelism, queue depth, and request timeout from recent transfer results, runtime memory pressure, and transfer status headers.

#### Scenario: Stable transfer increases throughput
- **GIVEN** a device-direct HTTP transfer has consecutive successful ranges with low latency and no busy status
- **WHEN** the planner prepares later ranges
- **THEN** it SHALL increase range size or parallelism within protocol, memory, in-flight byte, and server-advertised limits
- **AND** it SHALL preserve the existing maximum device-direct byte-route and stream-route range limits.

#### Scenario: Timeout reduces transfer pressure
- **GIVEN** a device-direct HTTP range request times out or receives a busy transfer status
- **WHEN** the planner prepares later ranges
- **THEN** it SHALL reduce parallelism before scheduling more work
- **AND** it SHALL lower the selected range size when range duration or memory pressure indicates that the current size is unsafe.

#### Scenario: Timeout is derived from range and throughput
- **GIVEN** the planner has a selected range size and recent observed throughput
- **WHEN** it creates a later range request
- **THEN** the request timeout SHALL be computed from the selected range size and observed throughput with bounded minimum and maximum values
- **AND** the timeout SHALL NOT rely on the previous fixed 20 second byte-route deadline for all range sizes.

### Requirement: Large device-direct reads SHALL prefer stream-file
The system SHALL prefer `/api/files/stream-file` for large device-direct file reads and SHALL keep `/api/files/read-bytes` for small reads or fallback.

#### Scenario: Large range uses stream route
- **GIVEN** a device-direct copy or relay needs to read a large file range
- **WHEN** the adaptive planner selects a stream range
- **THEN** the client SHALL request `/api/files/stream-file`
- **AND** the local destination SHALL be written as bytes arrive using bounded buffers.

#### Scenario: Small range can use read-bytes
- **GIVEN** a device-direct copy or relay needs to read a small file range
- **WHEN** the planner determines byte-route transfer is cheaper
- **THEN** the client MAY request `/api/files/read-bytes`
- **AND** it SHALL still enforce range length validation and transfer status parsing.

#### Scenario: Stream failure falls back by unfinished range
- **GIVEN** a stream-file range fails before the whole file is complete
- **WHEN** retry or fallback is attempted
- **THEN** the client SHALL retry only unfinished ranges according to the existing checkpoint and task retry behavior
- **AND** it SHALL NOT restart already completed file ranges solely because the stream route failed.

### Requirement: Large device-direct writes SHALL prefer stream-upload
The system SHALL prefer `/api/files/stream-upload` for large local-to-device file writes and SHALL keep `/api/files/write-bytes` for small writes or fallback.

#### Scenario: Large local write uses stream upload
- **GIVEN** a local-to-device copy needs to write a large file range
- **WHEN** the adaptive planner selects a stream write range
- **THEN** the client SHALL request `/api/files/stream-upload`
- **AND** the request body SHALL be written from local file chunks using bounded buffers.

#### Scenario: Stream upload preserves write validation
- **WHEN** `/api/files/stream-upload` receives a request
- **THEN** the server SHALL enforce the same authorization, destination path validation, and range validation used by `/api/files/write-bytes`
- **AND** it MAY allow larger request bodies within the device-direct stream range limit.

#### Scenario: Stream upload failure falls back safely
- **GIVEN** a stream-upload range fails before the copy completes
- **WHEN** fallback is attempted
- **THEN** the client SHALL use `/api/files/write-bytes` without changing the existing protobuf payload semantics
- **AND** pause, cancel, request tracking, progress, retry metadata, and task result reporting remain enforced.

### Requirement: Web local stream writes SHALL coalesce fragments
The JS and Wasm local file writers SHALL aggregate small incoming byte-stream fragments into bounded local writes when streaming a known byte range to browser-backed storage.

#### Scenario: Small stream fragments become bounded writes
- **GIVEN** a JS or Wasm target receives many small stream fragments for a known file range
- **WHEN** `writeByteStream` writes the range
- **THEN** it SHALL buffer fragments up to the configured buffer size before committing local chunks
- **AND** it SHALL still report progress using the committed chunk offsets and sizes.
