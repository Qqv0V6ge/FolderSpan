## Purpose

Defines the native LAN device API as a TLS multiplexed session: one pinned connection carries control RPCs and credit-limited file streams, and the listener never speaks HTTP.

## Requirements

### Requirement: Device listener accepts only the session ALPN

Native desktop, Android, and iOS device listeners SHALL complete TLS with per-device certificate pinning and SHALL accept only the application protocol `folderspan/1`. Connections that omit that ALPN, advertise HTTP, or send plaintext HTTP SHALL be closed before any device business handler runs.

#### Scenario: Session handshake succeeds

- **WHEN** a native client connects to the device port with a matching certificate fingerprint and ALPN `folderspan/1`
- **THEN** the server keeps the connection and enters the session frame loop

#### Scenario: JVM client negotiates with an Android listener

- **WHEN** a JVM client offers `folderspan/1` to an Android device listener
- **THEN** the Android listener configures `folderspan/1` on the accepted TLS socket before its handshake
- **AND** both peers observe `folderspan/1` as the negotiated application protocol

#### Scenario: HTTP or missing ALPN is rejected

- **WHEN** a client connects to the device port without ALPN `folderspan/1`
- **THEN** the server closes the connection
- **AND** no device control or file transfer work starts

#### Scenario: Plaintext HTTP is rejected

- **WHEN** a client sends a plaintext HTTP request to the device port
- **THEN** the request never reaches a device business handler

#### Scenario: Unknown manual endpoint exposes only public identity before authorization

- **WHEN** a native client opens `folderspan/1` for a manually entered endpoint whose device identity and certificate fingerprint are not known
- **THEN** the client MAY call a read-only `Identify` RPC that returns the peer's public `SocketDevice` identity
- **AND** the client captures the presented leaf-certificate SHA-256 fingerprint and closes the bootstrap connection
- **AND** no file, path, bookmark, message, or other device business RPC is authorized on that bootstrap connection
- **AND** the subsequent device connection pins the captured fingerprint

### Requirement: One session multiplexes control and file streams

An established session SHALL carry a control stream plus concurrent file streams on the same TLS connection. Control traffic SHALL remain serviceable while file streams are saturated. A session SHALL NOT open more than 256 active streams.

#### Scenario: Control ping during bulk copy

- **WHEN** a session is transferring one or more files at full rate
- **AND** a session ping is sent on the control stream
- **THEN** the peer replies with a pong without waiting for file streams to finish

#### Scenario: Stream limit is enforced

- **WHEN** a peer tries to open more than 256 concurrent streams
- **THEN** the extra streams are refused
- **AND** already open streams continue

### Requirement: Frames are bounded and credit-controlled

Session frames SHALL be little-endian with a type, stream id, length, and payload. Payload length SHALL NOT exceed 64 KiB. Senders SHALL wait for credit before emitting data. Receivers SHALL size the session receive window from available memory so that in-flight buffered bytes stay within that window.

#### Scenario: Oversized frame is rejected

- **WHEN** a peer sends a frame whose declared payload length exceeds 64 KiB
- **THEN** the receiving session resets that stream or closes with goaway
- **AND** the accept loop of the device listener remains running

#### Scenario: Zero credit blocks data

- **WHEN** a stream has no remaining receive credit
- **THEN** the sender does not emit further data frames on that stream
- **AND** data resumes only after a window update

#### Scenario: One file stream pipelines bounded frames

- **WHEN** a large file stream has more than one frame of available receive credit
- **THEN** the sender may emit multiple data frames before the first window update arrives
- **AND** every frame payload remains at most 64 KiB
- **AND** aggregate in-flight data remains within the memory-bounded session window

#### Scenario: Ready frames use a bounded TLS write batch

- **WHEN** multiple encoded session frames are already queued for the same TLS connection
- **THEN** the transport MAY write and flush them as one bounded batch without waiting for more frames
- **AND** each decoded frame still has its original type, stream id, ordering, and payload
- **AND** no individual frame payload exceeds 64 KiB

#### Scenario: Low memory shrinks the window

- **WHEN** the local runtime reports critical available heap or a low-memory flag
- **THEN** the session receive window is at most 256 KiB
- **AND** at most one file stream is writable at a time

### Requirement: Control RPCs replace device HTTP routes

After a session is authenticated, device operations that previously used HTTP device routes SHALL run as control RPCs on the session, including connect, list, create, rename, delete, bookmarks, and copy control. The session token SHALL be bound to that TLS connection. Later frames SHALL NOT carry a bearer header. A closed connection SHALL require a new handshake and connect. Token revocation SHALL end the session with goaway.

#### Scenario: Connect binds the session

- **WHEN** a client completes the existing device identity proof and is approved
- **THEN** the server binds the resulting token to that TLS session
- **AND** subsequent control and file streams on the same connection are authorized without a bearer header

#### Scenario: Token revocation ends the session

- **WHEN** the bound token is revoked while streams are open
- **THEN** the server sends goaway
- **AND** it does not leave a half-open file stream authorized

### Requirement: Files transfer as credit-limited streams

A file write SHALL open a write stream with path, file size, and start offset, then emit data frames within credit until a trailer. A file read SHALL open a read stream with path and range, then receive data frames within the reader's credit. Writes to the same target file SHALL be serialized. Distinct files MAY use concurrent streams. Resume SHALL continue from the already committed offset.

#### Scenario: Large file write is streamed

- **WHEN** a native client writes a multi-gigabyte file to a peer
- **THEN** the payload is sent as successive data frames no larger than 64 KiB
- **AND** the receiver commits bytes without assembling the whole file in memory

#### Scenario: Same-file writes stay serial

- **WHEN** two write streams target the same destination path
- **THEN** the second write waits or is refused until the first finishes
- **AND** the destination is not overwritten by interleaved ranges from both streams

#### Scenario: Resume uses start offset

- **WHEN** a transfer reconnects after a drop and the destination already has N committed bytes
- **THEN** the new write stream starts at offset N
- **AND** already written bytes are not resent as a required prefix

#### Scenario: Resumable single-file download keeps one stream open

- **WHEN** a large Session download resumes from a committed fixed-size recovery checkpoint
- **THEN** one read stream opens at the committed offset and remains open through EOF
- **AND** fixed-size checkpoint boundaries advance in ascending order as local bytes are committed
- **AND** the implementation does not close and reopen a protocol stream at every checkpoint boundary
- **AND** distinct files may still use concurrent streams

#### Scenario: Continuous download coalesces durable checkpoints

- **WHEN** a long-lived Session download crosses many fixed-size recovery boundaries at high rate
- **THEN** local byte progress and receive credit continue after each bounded local write
- **AND** durable checkpoint replacement stores the latest committed recovery boundary at a bounded byte or time interval instead of once per boundary
- **AND** the final EOF boundary is persisted before the transfer reports success
- **AND** while checkpoint storage remains responsive, interruption resumes from a boundary that is no more than the configured byte or time interval behind the last committed local write
- **AND** a storage stall retains at most the latest pending boundary and catches up after storage recovers without blocking receive credit

#### Scenario: Slow checkpoint storage does not stall receive credit

- **WHEN** a continuous Session download reaches an eligible checkpoint while checkpoint storage or its persistence lock is slow
- **THEN** durable checkpoint replacement runs outside the local-write and receive-credit callback
- **AND** local byte progress and receive credit continue while at most one latest pending checkpoint is retained
- **AND** normal EOF waits for its final checkpoint to be persisted before success is reported
- **AND** cancellation does not wait for a blocked checkpoint replacement to drain
- **AND** one completion diagnostic exposes checkpoint persistence time and the largest committed-write gap without logging file contents

### Requirement: Directory copies use a manifest and per-file streams

Native directory copies SHALL send a batched manifest of relative paths and sizes, create empty directories over control RPCs, and open one write stream per file. Symbolic links SHALL be skipped, recorded as failures, and SHALL NOT abort the remaining entries. Packing hundreds of small files into one HTTP archive SHALL NOT be the default path.

#### Scenario: Mixed directory copy

- **WHEN** a native client copies a tree that contains small files, large files, and empty directories
- **THEN** empty directories are created
- **AND** each file is transferred on its own stream within the session window
- **AND** the copy does not issue HTTP archive requests

#### Scenario: Symbolic links are skipped

- **WHEN** a directory entry is a symbolic link
- **THEN** that entry is recorded as failed
- **AND** remaining entries continue

### Requirement: Web clients do not use the device session port

JS and Wasm clients SHALL NOT listen on the device session port and SHALL NOT open `folderspan/1` sessions to it. Browser and cross-network device transfers SHALL continue to use the existing WebRTC path.

#### Scenario: Browser connect does not use the session port

- **WHEN** a JS or Wasm user connects to a native device
- **THEN** the client does not perform a `folderspan/1` handshake on the device session port
- **AND** the transfer uses the existing WebRTC device path
