## Purpose

为海量小文件复制提供跨 Session、WebRTC 与链接分享 HTTP 的统一流式归档能力，在不缓冲完整批次的前提下减少逐文件固定开销，并按链路能力选择整流快速压缩。

## ADDED Requirements

### Requirement: Eligible small files SHALL use shared archive batches

The system SHALL apply the shared small-file batch planner to copy operations, group at least two eligible files into bounded archive batches, and leave single files, large files, symbolic links, and entries outside the configured limits on their existing paths.

#### Scenario: Small-file tree is grouped
- **WHEN** a copy traversal discovers multiple regular files that satisfy the configured archive entry and batch limits
- **THEN** the system SHALL group them into one or more archive batches
- **AND** it SHALL avoid opening one transport stream per grouped file.

#### Scenario: Ineligible entries keep existing behavior
- **WHEN** a traversal contains a large file, a symbolic link, or fewer than two eligible regular files
- **THEN** large and ungrouped regular files SHALL use the existing single-file transfer behavior
- **AND** symbolic links SHALL remain skipped and reported according to existing copy semantics.

#### Scenario: Same-device copy stays server-side
- **WHEN** source and destination refer to the same device
- **THEN** the system SHALL keep using the device's server-side path copy operation
- **AND** it SHALL NOT route the copy through an archive stream.

### Requirement: Archive streams SHALL use one transport-neutral format

The system SHALL use only the current self-describing FSAR2 stream format for archive batches carried over Session, WebRTC DataChannel, and link-share HTTP bodies, with entry headers, relative paths, uncompressed sizes, and an explicit end marker.

#### Scenario: Device and local endpoints exchange an archive stream
- **WHEN** an eligible Device-to-Local or Local-to-Device batch is transferred over the selected Session or WebRTC device transport
- **THEN** the sender SHALL produce the shared FSAR stream on one archive stream
- **AND** the receiver SHALL decode and write its entries incrementally.

#### Scenario: Device-to-Device relays without local staging
- **WHEN** an eligible batch is copied between two different devices
- **THEN** the coordinator SHALL pipe the source device's FSAR bytes to the target device with bounded backpressure
- **AND** it SHALL NOT materialize a complete archive or a temporary local file tree
- **AND** the target device SHALL validate and extract the stream.

#### Scenario: Share paths reuse existing transport ownership
- **WHEN** an approved native share is saved locally or copied to another device
- **THEN** it SHALL inherit the Device-to-Local or Device-to-Device archive behavior
- **AND** it SHALL NOT introduce a separate native share byte protocol.

#### Scenario: Link-share download uses the same format
- **WHEN** an eligible link-share batch is downloaded by a client that supports archive streaming
- **THEN** `/api/share/archive-download` SHALL return the shared self-describing FSAR stream in its HTTP body
- **AND** device-port HTTP archive routes SHALL remain unavailable.

### Requirement: Compression SHALL be optional and cover the complete frame sequence

The system SHALL negotiate `none` and supported stream compression codecs before an archive transfer, record the selected codec once in the self-describing stream prelude, and apply compression as one continuous stream around the complete FSAR entry-frame sequence rather than once per file.

#### Scenario: Constrained device transport selects fast compression
- **WHEN** both native peers support zstd and the selected policy identifies the connection as bandwidth-constrained, including a WebRTC/WAN transfer
- **THEN** the sender SHALL use the configured fast zstd level for the complete archive frame sequence
- **AND** the receiver SHALL decompress it incrementally before decoding entries.

#### Scenario: Local network favors framing without compression
- **WHEN** the transfer uses a Session/LAN connection and policy does not identify bandwidth pressure
- **THEN** the sender SHALL use the `none` codec by default
- **AND** the batch SHALL still receive the fixed-cost reduction from shared framing.

#### Scenario: Platform codec negotiation selects a current codec
- **WHEN** the endpoints have no common compressed codec or a JS/Wasm client cannot provide streaming compression
- **THEN** they SHALL negotiate `none` when the client can stream FSAR
- **AND** an endpoint that does not implement the current archive protocol SHALL fail explicitly rather than emulate an older peer.

#### Scenario: Stream identifies its codec independently of its pipe
- **WHEN** a receiver opens an FSAR archive stream from Session, WebRTC, or HTTP
- **THEN** it SHALL determine the archive version and compression codec from the FSAR prelude
- **AND** it SHALL NOT depend solely on transport-specific headers to decode the body.

### Requirement: Encoding and extraction SHALL remain bounded and secure

The system SHALL encode, compress, transfer, decompress, and extract archive data incrementally with bounded buffers, without first building a complete archive on disk or in memory, and SHALL enforce path and uncompressed-size limits before committing entries.

#### Scenario: Receiver extracts incrementally
- **WHEN** a receiver obtains archive chunks
- **THEN** it SHALL write file payload bytes as they are decoded
- **AND** memory usage SHALL remain bounded by configured stream buffers rather than archive batch size.

#### Scenario: Unsafe or duplicate path is rejected
- **WHEN** an entry contains an absolute path, `..`, backslash, invalid path segment, or a duplicate regular-file relative path
- **THEN** the receiver SHALL reject the archive before writing that unsafe entry outside or over another target
- **AND** completed safe entries SHALL remain identifiable for fallback accounting.

#### Scenario: Decompressed data exceeds declared bounds
- **WHEN** decompressed entry bytes exceed the declared entry size or cumulative configured batch limit
- **THEN** the receiver SHALL abort the archive stream
- **AND** it SHALL NOT commit the incomplete entry.

#### Scenario: Archive terminator or compressed stream is invalid
- **WHEN** the compressed stream is truncated, contains trailing decompressed data, or does not yield the FSAR end marker
- **THEN** the receiver SHALL fail the batch
- **AND** it SHALL report only fully committed entries as completed.

### Requirement: Archive optimization SHALL preserve task semantics

The system SHALL keep archive batching and compression transparent to file-operation tasks: progress is based on uncompressed file bytes, each fully committed entry advances completion, and pause, cancel, retry, and fallback operate on file entries rather than compressed chunks.

#### Scenario: Progress uses uncompressed file bytes
- **WHEN** a compressed archive batch is transferred
- **THEN** task progress SHALL advance using decoded file payload bytes and uncompressed totals
- **AND** compression ratio SHALL NOT cause progress to regress or exceed the total.

#### Scenario: Pause or cancel stops the active archive pipeline
- **WHEN** the user pauses or cancels a task during an archive batch
- **THEN** the coordinator SHALL propagate cancellation to the producer, compressor, transport, decompressor, and consumer
- **AND** resume or retry SHALL restart from the first uncommitted entry without serializing a live compression context.

#### Scenario: Failed batch falls back only remaining entries
- **WHEN** an archive batch fails after one or more entries were fully committed
- **THEN** the receiver SHALL return the completed relative paths
- **AND** the coordinator SHALL preserve those entries and retry only uncommitted entries through the existing single-file path.

### Requirement: Archive protocol SHALL reject development-era legacy formats

The system SHALL use FSAR2 as the only archive version, negotiate only codecs and resource limits, and reject FSAR1, requests without current codec capabilities, unknown versions, and unknown codecs without attempting extraction.

#### Scenario: Current peers use FSAR2
- **WHEN** endpoints start an archive transfer
- **THEN** they SHALL use FSAR2 with the negotiated codec on every supported transport.

#### Scenario: Legacy stream is rejected
- **WHEN** a receiver obtains an FSAR1 stream or a link-share request omits current codec capabilities
- **THEN** it SHALL reject the transfer before extracting entries.

#### Scenario: Device peer omits current archive capabilities
- **WHEN** a Session or WebRTC device peer does not provide valid current archive capabilities
- **THEN** connection or archive negotiation SHALL fail explicitly
- **AND** it SHALL NOT restore or call device HTTP archive routes.
