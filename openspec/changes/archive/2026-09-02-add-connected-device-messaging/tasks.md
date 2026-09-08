## 1. Message contract and durable storage

- [x] 1.1 Add ProtoBuf message metadata/receipt models, durable direction/status models, and constants for the 1 MiB UTF-8 body limit
- [x] 1.2 Implement shared validation for non-blank text, stable message IDs, timestamps, strict UTF-8 length, SHA-256 integrity, and literal plain-text handling
- [x] 1.3 Add SQLDelight message and conversation-summary tables with persistent incoming-message uniqueness, paging indexes, delivery status, and unread state
- [x] 1.4 Verify fresh-schema creation on relevant SQLDelight targets; pre-release database migration compatibility is intentionally out of scope
- [x] 1.5 Implement transactional outgoing insert/status updates, incoming insert-plus-unread updates, persistent deduplication, mark-read, and local conversation deletion
- [x] 1.6 Convert leftover `Sending` rows to `Unconfirmed` on startup without scheduling any resend
- [x] 1.7 Expose paged per-device history and conversation summaries while excluding message tables from settings, account sync, and remote snapshots
- [x] 1.8 Add persistence tests for restart restoration, transport-independent device history, unread state, idempotent retry, local-only deletion, fresh-schema creation, and storage failure

## 2. Shared long-message transfer coordination

- [x] 2.1 Add a transport-neutral `DeviceMessageClient`, live endpoint identity, and begin/body/commit transfer contract
- [x] 2.2 Implement bounded incoming accumulators capped at 1,048,576 bytes with strict length, checksum, UTF-8, timeout, and disconnect cleanup
- [x] 2.3 Persist complete incoming messages before returning receipts and ensure incomplete or failed bodies never enter history
- [x] 2.4 Gate outgoing database creation on a live authenticated endpoint, then map post-gate transport failures to durable `Failed` or `Unconfirmed` states
- [x] 2.5 Implement stable-ID explicit retry without automatic resend, using persistent receiver deduplication to return an idempotent receipt
- [x] 2.6 Add the 30-per-minute, burst-10 receive limiter and a fixed per-peer concurrent-body cap without logging message text
- [x] 2.7 Add common tests for small and 1 MiB messages, multibyte boundaries, chunk ordering, truncation, checksum mismatch, timeout cleanup, receipts, rate limits, and concurrent limits

## 3. Symmetric TLS Session messaging

- [x] 3.1 Refactor the Session control endpoint so client and server can both originate requests, dispatch inbound requests, correlate responses, and fail pending calls on close
- [x] 3.2 Allocate disjoint client/server request ID sequences and add loopback tests for simultaneous bidirectional RPC without response collisions
- [x] 3.3 Extend Session stream dispatch so both roles can open and consume message-body streams while preserving the 64 KiB frame cap and credit control
- [x] 3.4 Preserve the rule that only `Connect` is available before approval and register message begin/body/commit handlers only after token binding
- [x] 3.5 Implement Session message begin, streamed body, commit/receipt, unsupported-method, and error mapping in the transport-neutral client
- [x] 3.6 Register outgoing and accepted inbound Session endpoints after approval, then unregister them before disconnect, GOAWAY, token revocation, or permanent rejection cleanup
- [x] 3.7 Add Session tests for both directions, 1 MiB chunking, offline/unapproved rejection, forged identity, duplicate IDs, revocation, partial transfer, disconnect races, and receipt timeout
- [x] 3.8 Re-run existing Session control, heartbeat, stream, credit, and file-transfer tests to verify the symmetric endpoint refactor does not regress current behavior

## 4. WebRTC long-message transport

- [x] 4.1 Add message begin and commit methods plus bounded message-body frames to the WebRTC device RPC protocol
- [x] 4.2 Reuse negotiated DataChannel chunk limits and backpressure to transmit up to 1 MiB without a single oversized RPC frame
- [x] 4.3 Validate that begin/commit tokens and every body transfer belong to the connected DataChannel peer before allocation or persistence
- [x] 4.4 Register and unregister WebRTC message endpoints with peer connection, approval, rejection, token revocation, and controller shutdown events
- [x] 4.5 Map WebRTC close, timeout, unsupported-method, integrity, and authorization failures into shared durable message states without automatic resend
- [x] 4.6 Add WebRTC tests for bidirectional small/long delivery, transport-parity receipts, unapproved peers, peer/token mismatch, partial bodies, persistent deduplication, limits, and disconnect races

## 5. Device state and offline-history UI

- [x] 5.1 Expose paged durable conversations, persistent unread counts, selected live endpoint, and online-only send availability from shared device state
- [x] 5.2 Resolve the selected `SocketDevice` transport explicitly when multiple endpoints target the same device, while aggregating history by stable device ID
- [x] 5.3 Make service-level sends fail with an offline error and create no outgoing row when no live authenticated endpoint exists
- [x] 5.4 Add a message entry for devices that are online or have local history, including offline history access when the device is not connected
- [x] 5.5 Build the Compose Multiplatform history screen with paged messages, literal text, long-body expand/copy behavior, persistent unread handling, and delivery states
- [x] 5.6 Disable the composer immediately on disconnect, re-enable it on authenticated reconnect, and never send stored history or drafts automatically
- [x] 5.7 Add explicit retry for failed/unconfirmed messages only while online, reusing the stable message ID, plus a confirmed local-only conversation deletion action
- [x] 5.8 Add and update localized strings for online/offline state, 1 MiB validation, long transfer, delivery states, storage/rate failures, retry, unread, and local deletion
- [x] 5.9 Add state and shared UI tests for offline history after restart, offline send rejection with no row, reconnect without auto-send, long text rendering, unread persistence, retry, and deletion
- [x] 5.10 Rework the messaging list and conversation into a minimal constraint-driven layout for compact, medium, expanded, and wide windows
- [x] 5.11 Add shared layout tests for single-column compact windows, adaptive wide grids, readable content bounds, and short-window composer sizing
- [x] 5.12 Remove the visible transport selector, add deterministic live-endpoint fallback, and show a current/total Unicode character counter while preserving UTF-8 byte validation

## 6. Verification and documentation

- [x] 6.1 Run `:core:jvmTest` and `:app:shared:jvmTest`, then fix all message, database, and existing device-transport regressions
- [x] 6.2 Build the relevant native/shared targets to catch Session, WebRTC, fresh SQLDelight schema, and platform source-set compilation errors
- [x] 6.3 Verify message content never enters logs, account sync, configuration snapshots, or an offline outgoing queue
- [x] 6.4 Verify receiver storage failure, disk pressure, malformed UTF-8, integrity failure, and interrupted 1 MiB transfer return no false delivery receipt
- [x] 6.5 Run `openspec validate add-connected-device-messaging --strict` and confirm every requirement scenario is covered by implementation or tests
