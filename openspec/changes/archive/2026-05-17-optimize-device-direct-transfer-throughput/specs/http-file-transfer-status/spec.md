## MODIFIED Requirements
### Requirement: HTTP file clients SHALL adapt later requests from transfer status
The HTTP file clients SHALL parse transfer status headers when present and use the latest valid values to tune later chunk requests. Device direct HTTP transfers SHALL keep byte-route file chunks no larger than the device-direct byte-route limit, SHALL use a bounded in-flight budget, SHALL allow Raw TLS HTTP/1.1 connections to be reused across sequential byte-route requests, SHALL allow device-to-device large file copies to overlap source reads and destination writes with bounded queue depth, SHALL allow device-to-local downloads to use bounded long range stream requests, and SHALL allow low-pressure recommendations up to `MAX_CONCURRENT_FILE_CHUNKS` while applying busy retry delays only to later chunk scheduling.

#### Scenario: Valid status tunes later chunks
- **WHEN** a client receives valid transfer status headers
- **THEN** later HTTP chunk scheduling uses the recommended chunk size and parallel request count within safe local limits
- **AND** device direct HTTP transfers do not exceed the advertised device-direct maximum chunk size
- **AND** device direct HTTP transfers do not exceed their configured in-flight byte budget
- **AND** sequential Raw TLS byte-route requests can reuse an existing HTTP/1.1 connection unless either side requests close
- **AND** device-to-device large file copies may prefetch read chunks while previous chunks are being written within bounded queue depth
- **AND** a busy response with a retry delay postpones scheduling only subsequent chunks.

#### Scenario: Device direct routes can advertise larger chunks without changing Share limits
- **WHEN** a device direct `/api/files/read-bytes` or `/api/files/write-bytes` response includes transfer status
- **THEN** it MAY advertise a 16MB maximum chunk size for later device direct byte-route requests
- **AND** clients still clamp actual concurrency by the 128MB in-flight budget
- **AND** Share routes continue to advertise and enforce the generic 8MB range limit.

#### Scenario: Device direct schedulers preserve larger chunks across copy flows
- **WHEN** HTTP device direct clients receive a valid 16MB transfer status recommendation
- **THEN** local-device, device-local, device-device, and recovery/relay schedulers SHALL preserve the 16MB device-direct protocol ceiling within local in-flight limits
- **AND** Path copy schedulers MAY use a smaller aligned runtime chunk size when doing so increases usable request parallelism under the same in-flight byte budget
- **AND** single-file device-device copies MAY use independent source-read and target-write request permits
- **AND** multi-file directory copies still retain task-level request bounding.

#### Scenario: Device downloads avoid per-fragment local writes
- **WHEN** a device direct download writes a remote file range to a local target
- **THEN** the client SHALL write each validated HTTP range to the target file using a bounded range buffer or platform streaming strategy
- **AND** the default hot path SHALL avoid issuing local file writes for every HTTP/TLS network sub-fragment when that lowers throughput
- **AND** it SHALL avoid routing each network sub-chunk through an intermediate `Flow<Pair<Long, ByteArray>>`
- **AND** progress, cancellation, pause checks, and range validation remain enforced.

#### Scenario: Device downloads can use long range streams
- **WHEN** a device direct large file is downloaded to local storage
- **THEN** the client MAY request `/api/files/stream-file` ranges larger than the byte-route chunk limit while staying within the device-direct stream range limit
- **AND** it SHALL preserve enough stream parallelism to fill high-speed local network links while bounding per-stream buffers
- **AND** it SHALL allow the client target stream range size to be lower than the server stream range limit when that improves request rolling and link utilization
- **AND** the server SHALL enforce the same authorization and file range validation used by `/api/files/read-bytes`
- **AND** if the long stream path fails or is unavailable, the client SHALL fall back to `/api/files/read-bytes` for unfinished ranges only
- **AND** existing `/api/files/read-bytes` and `/api/files/write-bytes` payload semantics remain unchanged.

#### Scenario: Low pressure recommends high concurrency
- **WHEN** the server observes low active byte-route pressure
- **THEN** it MAY recommend parallel request counts up to `MAX_CONCURRENT_FILE_CHUNKS`
- **AND** clients still clamp actual concurrency by local memory and task limits.

#### Scenario: High pressure preserves busy retry
- **WHEN** the server observes saturated active byte-route pressure or very slow recent requests
- **THEN** it marks transfer status busy
- **AND** it includes a bounded retry delay for later chunk scheduling.

#### Scenario: Missing or invalid status falls back safely
- **WHEN** transfer status headers are missing or invalid
- **THEN** the client uses the existing fixed defaults for chunk size and concurrency.
