# http-file-transfer-status Specification
## Purpose
TBD - created by archiving change add-http-transfer-status-headers. Update Purpose after archive.

## Requirements

### Requirement: HTTP file byte routes SHALL expose transfer status headers
The system SHALL include transfer tuning status headers on HTTP responses for `/api/share/read-bytes` without changing the existing protobuf or octet-stream response body types. Native device file transfers SHALL NOT use these HTTP routes or headers.

#### Scenario: Successful byte response includes status
- **WHEN** a selected share route completes successfully
- **THEN** the response body matches the previous route contract
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with recommended chunk size, recommended parallel requests, maximum limits, active request count, busy flag, retry delay, and sample time.

#### Scenario: Older clients ignore status
- **WHEN** a client decodes only the protobuf or octet-stream response body
- **THEN** the selected share routes remain decodable using the previous body type.

### Requirement: HTTP file clients SHALL adapt later requests from transfer status
Share HTTP file clients SHALL parse transfer status headers when present and use the latest valid values to tune later chunk requests. Native device session transfers SHALL instead tune later streams from session credit and local memory: they SHALL keep data frames no larger than 64 KiB, SHALL keep in-flight buffered bytes within the session receive window, SHALL copy distinct files over concurrent streams on one TLS session, and SHALL serialize writes to the same target file.

#### Scenario: Valid status tunes later chunks
- **WHEN** a share HTTP client receives valid transfer status headers
- **THEN** later HTTP chunk scheduling uses the recommended chunk size and parallel request count within safe local limits
- **AND** a busy response with a retry delay postpones scheduling only subsequent chunks.

#### Scenario: Device session uses credit instead of HTTP chunks
- **WHEN** a native device session is transferring files
- **THEN** data frames do not exceed 64 KiB
- **AND** in-flight buffered bytes do not exceed the session receive window
- **AND** distinct files MAY use concurrent streams
- **AND** writes to one destination path remain serial

#### Scenario: Device downloads avoid per-fragment local writes
- **WHEN** a device session download writes a remote file range to a local target
- **THEN** the client SHALL write validated stream data to the target file using a bounded buffer or platform streaming strategy
- **AND** the default hot path SHALL avoid assembling the whole range in a single `ByteArray` before writing
- **AND** a resumed large-file download SHALL keep one Session read stream and one platform file writer open from the committed checkpoint offset through EOF
- **AND** adjacent session frames MAY be coalesced into bounded writes no larger than the local stream buffer
- **AND** fixed-size checkpoint boundaries SHALL be persisted after their local bytes are committed without reopening the Session stream
- **AND** checkpoint persistence SHALL coalesce adjacent committed boundaries so synchronous metadata replacement does not run for every recovery chunk
- **AND** the latest eligible boundary SHALL be scheduled after 64 MiB of additional committed data, after two seconds, or at EOF, whichever occurs first
- **AND** eligible checkpoint replacement SHALL run on one bounded latest-wins background writer instead of blocking the Session receive callback
- **AND** successful EOF SHALL wait for the final checkpoint write while cancellation SHALL NOT wait for a blocked metadata write
- **AND** progress, cancellation, pause checks, and range validation remain enforced.

#### Scenario: Device transfer speed display resists short stalls
- **WHEN** a native device transfer has produced at least one complete speed sample
- **THEN** subsequent displayed speed samples use a bounded short-window smoothing calculation
- **AND** exact transferred-byte progress and checkpoint offsets remain unsmoothed
- **AND** a single scheduler, filesystem, or checkpoint pause does not replace the displayed rate with the full one-second drop

#### Scenario: Low pressure recommends high concurrency
- **WHEN** the local runtime has ample heap and the peer is not credit-blocked
- **THEN** the session MAY open multiple file streams up to the stream limit
- **AND** clients still clamp actual concurrency by local memory and task limits.

#### Scenario: High pressure preserves busy retry
- **WHEN** the local runtime reports low memory or the peer advertises a closed window
- **THEN** the sender pauses further data
- **AND** it resumes only after credit or memory recovers.

#### Scenario: Missing or invalid status falls back safely
- **WHEN** share transfer status headers are missing or invalid
- **THEN** the share HTTP client uses the existing fixed defaults for chunk size and concurrency.

### Requirement: HTTP share transfer routes SHALL expose transfer status headers
The system SHALL include transfer tuning status headers on share byte-transfer responses so clients can adapt range size and parallelism.

#### Scenario: Successful share read-bytes response includes status
- **WHEN** `/api/share/read-bytes` returns a file range successfully
- **THEN** the response body is raw octet-stream data for the requested range
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with the latest transfer status snapshot.

#### Scenario: Successful share stream response includes status
- **WHEN** `/api/share/stream-file` returns a file range successfully
- **THEN** the response body is raw octet-stream data for the requested range
- **AND** the response includes `X-FolderSpan-Transfer-*` headers with the latest transfer status snapshot.

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

### Requirement: HTTP small-file batches SHALL use archive streams
The system SHALL provide first-party archive stream routes for eligible small-file batches while preserving existing byte and stream routes for large files, unsupported peers, and fallback.

#### Scenario: Share small-file folder download uses archive stream
- **GIVEN** a Share-to-local folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL request `/api/share/archive-download` for grouped small files
- **AND** it SHALL write decoded file entries to the local target as they arrive
- **AND** ineligible files SHALL continue through the existing Share byte or stream routes.

#### Scenario: Device small-file folder download uses archive stream
- **GIVEN** a Device-to-local folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL request `/api/files/archive-download` for grouped small files
- **AND** large files SHALL continue through existing adaptive read-bytes or stream-file routes.

#### Scenario: Local small-file folder upload uses archive stream
- **GIVEN** a local-to-Device folder copy contains many eligible small files
- **WHEN** the client schedules the copy batch
- **THEN** it SHALL upload a FolderSpan archive stream to `/api/files/archive-upload`
- **AND** the server SHALL extract entries under the validated destination root
- **AND** ineligible files SHALL continue through the existing write-bytes route.

#### Scenario: Archive stream fallback preserves existing routes
- **GIVEN** an archive stream route is unavailable, rejected, canceled, or fails before all entries complete
- **WHEN** the file task continues processing remaining entries
- **THEN** entries not confirmed complete SHALL be retried through the existing per-file transfer path
- **AND** entries already confirmed complete SHALL NOT be transferred again.

#### Scenario: Archive transfer reports transfer status
- **WHEN** an archive download or upload route returns a response
- **THEN** it SHALL include `X-FolderSpan-Transfer-*` headers where the existing transfer status model applies
- **AND** clients SHALL parse those headers for later scheduling without changing archive frame semantics.

### Requirement: Native App device-share Save SHALL tune from the device transport
Save and Auto-Save of a native App device share SHALL take transfer chunk size and parallelism from the device Session credit plan or WebRTC transfer plan. They SHALL NOT require `/api/share/read-bytes` transfer-status headers for that copy.

#### Scenario: Session share save uses session windows
- **WHEN** a native App device-share Save runs over Session
- **THEN** frame size and in-flight bytes follow the session receive window
- **AND** distinct files MAY use concurrent streams within that window

#### Scenario: WebRTC share save uses WebRTC transfer plan
- **WHEN** a native App device-share Save runs over WebRTC
- **THEN** chunk size and concurrency follow the negotiated WebRTC transfer plan

#### Scenario: Link-share HTTP still exposes share transfer status
- **WHEN** a browser or HTTP share client reads `/api/share/read-bytes`
- **THEN** the response still includes `X-FolderSpan-Transfer-*` headers
