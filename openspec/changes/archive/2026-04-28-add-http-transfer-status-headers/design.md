## Context

The selected routes currently return raw protobuf bodies: `Boolean` for writes/appends and `ByteArray` for reads. The client needs transfer hints without breaking those body contracts, so status is carried only through HTTP headers.

## Goals

- Preserve existing response bodies.
- Let new HTTP clients tune later requests using device-provided transfer status.
- Use safe defaults when headers are missing, invalid, or from older peers.

## Non-Goals

- Do not change WebRTC file RPC.
- Do not expose a user-facing transfer tuning setting.
- Do not cancel or resize requests that are already in flight.

## Decisions

- Add one shared transfer-status model and header codec in `commonMain`.
- Use explicit `X-FolderSpan-Transfer-*` headers for version, recommended chunk bytes, recommended/max parallel requests, active requests, busy flag, retry delay, and sample time.
- Track active HTTP transfer requests on the server through a lightweight singleton status provider; routes record request start/end and append a fresh snapshot before responding.
- Clients parse headers after successful and handled-error responses, update per-client status, and use the latest snapshot when choosing chunk size, pipeline depth, share-download concurrency, and optional busy backoff for subsequent chunks.
- Clamp all parsed values to safe existing limits, falling back to `MAX_LENGTH`, `DEVICE_TRANSPORT_PIPELINE_DEPTH`, and `MAX_CONCURRENT_FILE_CHUNKS`.

## Risks / Trade-offs

- Header-only transport is less expressive than protobuf, but keeps old clients compatible.
- Per-client tuning is advisory; servers still enforce existing maximum block-size validation.
- A busy signal can improve stability but should only delay scheduling the next chunk batch, not block the whole task indefinitely.
