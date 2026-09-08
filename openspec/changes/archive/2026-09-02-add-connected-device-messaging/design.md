## Context

See proposal.md for motivation and `specs/connected-device-messaging/spec.md` for observable behavior. The native device path uses one authenticated TLS Session whose control plane is currently client-request/server-response, while WebRTC already has a symmetric peer RPC envelope. Device authorization is represented by a live session plus a device-bound token; a saved device record, discovery beacon, or local message history is not authorization to send.

The clarified offline requirement means local durable history that remains readable while the peer is offline. It does not mean store-and-forward: a new outgoing message is accepted only when a live authenticated endpoint exists. This change depends on `device-tls-session-transport` and crosses shared protocol models, SQLDelight, native Session, WebRTC, device state, and Compose Multiplatform UI.

## Goals / Non-Goals

**Goals:**

- Send up to 1 MiB of UTF-8 plain text incrementally over either connected device transport.
- Allow either side of an authenticated connection to initiate a message without opening a reverse connection.
- Persist complete incoming and outgoing messages locally before reporting durable acceptance.
- Restore history, delivery status, and unread state while the peer is offline or after application restart.
- Derive sender identity and authorization from the live endpoint, not from user-controlled metadata.
- Make receipt, persistent deduplication, disconnect, revocation, partial transfer, and explicit retry deterministic.

**Non-Goals:**

- Sending to an offline device, an offline outbox, automatic resend, server relay, account-backed store-and-forward, or push delivery.
- Read receipts, typing indicators, reactions, rich text, executable links, attachments, voice messages, or group conversations.
- Synchronizing message history through account configuration sync or remotely deleting the peer's copy.
- Replacing file transfer with message content larger than 1 MiB or exposing messaging through LinkShare/MCP in this change.

## Decisions

### 1. Separate message metadata, body stream, and durable receipt

Use shared ProtoBuf models conceptually equivalent to:

```text
DeviceMessageMetadata(messageId, sentAtEpochMillis, utf8Length, sha256)
DeviceMessageReceipt(messageId, receivedAtEpochMillis)
```

The metadata intentionally contains no authoritative sender ID. The receiving endpoint supplies the authenticated remote device ID before persistence. A send follows three phases:

```text
BeginMessage(metadata) → bounded body chunks → CommitMessage(messageId) → durable receipt
```

`BeginMessage` validates connection authorization, message ID, declared length, rate limit, and duplicate state. Body chunks are bounded by the underlying transport (Session frames remain at most 64 KiB; WebRTC uses its negotiated chunk limit). `CommitMessage` waits for body completion, verifies actual UTF-8 length and SHA-256, decodes strict UTF-8, persists atomically, and only then returns the receipt.

Small and long text use the same path so authorization, checksum, deduplication, and receipt behavior cannot diverge. Sending the entire body in a control RPC was rejected because WebRTC frame limits differ and it requires buffering before transport-level backpressure. Treating long text as a file attachment was rejected because it would change message presentation and retention semantics.

### 2. Keep one transport-neutral client and incoming coordinator

A `DeviceMessageClient` exposes `send(metadata, bodyChunks): Result<Receipt>`. Session and WebRTC adapters implement the wire phases, while a shared incoming coordinator owns validation, bounded assembly, timeout cleanup, persistence, deduplication, and receipts.

Incoming bodies use a strictly bounded accumulator capped at 1,048,576 bytes. Partial accumulators are keyed by authenticated peer and message ID, expire after the transfer timeout, and are discarded on disconnect, revocation, checksum failure, or storage failure. Logs include peer ID, message ID, declared size, chunk count, and result but never text content.

### 3. Make TLS Session control and message streams symmetric

The current Session client can originate RPCs, but the Session server cannot, and the client ignores inbound control requests and stream opens. Refactor the session endpoint so both roles can:

- allocate outbound request IDs from disjoint sequences (client odd, server even),
- correlate responses and dispatch inbound requests,
- open and consume the message-body stream in either direction,
- return commit errors and receipts,
- fail pending calls and discard partial bodies on GOAWAY or close.

Only `Connect` is registered before authentication. Message RPCs and streams become available after the session is bound and are disabled immediately on revocation. A second reverse TLS connection was rejected because it violates the one-connection-per-pair design.

### 4. Add chunked messaging to authenticated WebRTC device RPC

Add begin/commit message methods to the existing WebRTC device RPC set and a message-body frame that reuses the current data-channel backpressure and chunk-size negotiation. Every begin, chunk, and commit is associated with the connected peer session; begin/commit additionally use the existing peer-bound access token.

The receiver rejects chunks without an authorized active transfer and validates the access token against the data-channel peer before allocating the accumulator. No signaling message or unauthenticated DataChannel frame carries message content.

### 5. Register live endpoints separately from durable conversations

A live endpoint registry is keyed by remote device ID, transport, and generated connection ID. Outgoing Session clients, accepted inbound Session connections, and authenticated WebRTC peers register only after approval. Disconnect, token revocation, permanent rejection, and controller shutdown unregister the endpoint before failing in-flight sends.

Durable conversations are keyed only by stable remote device ID, so history survives reconnects and transport changes. The conversation UI does not expose transport controls: it keeps the current live `SocketDevice` endpoint, otherwise prefers the endpoint used to open the conversation, then deterministically falls back to the first sorted live endpoint. This selection never opens a connection or queues work.

The UI checks online state for usability, but the service checks the registry and authorization again before creating the outgoing database record. If the endpoint disappears after that atomic gate, the created message becomes `Failed` or `Unconfirmed`; it never becomes queued.

### 6. Persist history with SQLDelight

Add SQLDelight storage for:

```text
DeviceMessage(
  localId, messageId, peerDeviceId, direction, body,
  sentAtMillis, receivedAtMillis, status, isRead, createdAtMillis
)
DeviceConversation(peerDeviceId, lastMessageAtMillis, unreadCount)
```

Use a persistent uniqueness constraint for incoming `(peerDeviceId, messageId, direction)` so explicit retry after reconnect cannot duplicate history. Insert the outgoing row only after a live endpoint passes the final pre-send check; update it transactionally to `Delivered`, `Failed`, or `Unconfirmed`. Insert an incoming row and update its conversation summary/unread count in one transaction before returning a receipt.

Expose paged history queries ordered by timestamp and stable local ID, plus mark-read and local conversation deletion transactions. Database initialization converts any leftover `Sending` rows from a terminated process to `Unconfirmed`; it never schedules them. Message tables are excluded from settings/account sync and remote snapshot code.

History remains until local deletion or ordinary application-data removal. No automatic expiry is introduced. Database write or disk-full failure rejects incoming durable acceptance and therefore returns no receipt.

### 7. Present offline history while gating the composer on live state

Add a message entry for devices that are online or have local history. The Compose screen observes paged database history, persisted unread state, and the live endpoint registry. It renders text literally, offers copy/expand behavior for long bodies, and shows `Sending`, `Delivered`, `Failed`, `Unconfirmed`, and `Received` states.

The message list remains available offline, but the composer and send action are disabled unless the automatically resolved endpoint is live and authenticated. Reconnection re-enables the composer without transmitting anything. A failed/unconfirmed row can be resent only through an explicit user action after reconnection, reusing the stable message ID for idempotency. The composer displays Unicode character count in current/total format for writing feedback while retaining the 1 MiB UTF-8 byte limit for validation and transport safety.

Deleting a conversation is a confirmed local-only operation. It deletes local rows and unread state but sends no remote deletion message.

The messaging UI derives a small layout specification from the available Compose constraints rather than the target platform. Compact windows use a tight single-column conversation list and wider message bubbles; medium and expanded windows progressively add breathing room, while wide windows use an adaptive conversation grid and center the history/composer inside a readable maximum width. Short landscape windows reduce vertical spacing and composer growth so primary actions remain visible. These layout changes do not alter paging, retry, unread, deletion, or send-gating behavior.

### 8. Bound abuse and storage pressure

Each receiving endpoint applies the 1 MiB message limit and a token-bucket rate limit, initially 30 accepted messages per minute with a burst of 10. Rate-limit rejection occurs before body allocation. At most a small fixed number of message bodies may be assembling concurrently per peer; excess begins are rejected.

History queries are paged so long conversations do not load all bodies into memory. Storage failures are surfaced without acknowledging delivery, and the local delete action provides explicit reclamation. Rate limiting and size limits do not substitute for authentication: unauthenticated requests are rejected before allocation or persistence.

## Risks / Trade-offs

- [Symmetric Session RPC/stream refactor can regress existing control and file flows] → Preserve existing envelopes, isolate message stream handling, and add simultaneous bidirectional RPC/stream regression tests first.
- [Disconnect or revocation races with database creation] → Recheck the live endpoint before inserting; mark post-insert failures as failed/unconfirmed and never enqueue them.
- [A lost receipt leaves sender uncertain after receiver persisted] → Reuse stable message IDs; explicit retry receives an idempotent receipt from persistent deduplication.
- [Partial long messages consume memory] → Cap body size and concurrent accumulators, apply timeouts, and discard all partial state on failure or disconnect.
- [Persistent text increases privacy and disk usage] → Keep data local and out of account sync/logs, page reads, expose local deletion, and reject on storage failure instead of silently dropping history.
- [Session and WebRTC expose different low-level errors] → Map both into shared durable delivery states while retaining transport diagnostics only in content-free logs.

## Development Rollout Plan

1. Add SQLDelight message tables, fresh-schema coverage, shared models, validation, hashing, persistence, and paged queries without a visible send entry. Existing development databases may be cleared and recreated; no pre-release schema migration is required.
2. Generalize TLS Session RPC/message streams for bidirectional begin/body/commit and verify existing control/file/heartbeat behavior.
3. Add authenticated WebRTC begin/body/commit handling and shared endpoint lifecycle registration.
4. Add device state and Compose history UI, then enable sending only after both transports pass tests.
5. During development, protocol and schema changes may be updated in place without compatibility shims for older builds.

Peers are expected to run the same development protocol version. Unknown methods still fail cleanly without affecting file or device control operations, but cross-version compatibility is not a release requirement.
