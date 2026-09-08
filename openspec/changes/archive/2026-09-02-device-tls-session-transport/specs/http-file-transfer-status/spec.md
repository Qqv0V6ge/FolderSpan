## MODIFIED Requirements

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
