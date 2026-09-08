## Context
Device share approval currently uses SSE for a request/response handshake. SSE is being removed, so approval must be delivered over HTTP short polling instead.

## Goals / Non-Goals
- Goals:
  - Preserve the existing share approval semantics (WAITING, COMPLETED, REJECTED, ERROR)
  - Use CryptoProtoBuf over HTTP with Content-Type application/x-protobuf
  - Minimize scope by reusing existing share state maps
- Non-Goals:
  - Change share transfer APIs (/api/share/list, /api/share/read-bytes, etc.)
  - Redesign the share UI/notification flow

## Decisions
- Add a `/api/share/heartbeat` endpoint that accepts a share request payload and returns the current approval status.
- The server checks for existing terminal statuses first, then returns WAITING while the request is pending.
- The client polls at a short interval (about 1s) until a terminal status is returned or the job is canceled.

## Risks / Trade-offs
- Short polling increases request volume compared to SSE; polling interval should remain small but reasonable.

## Migration Plan
- Implement the new polling endpoint and client loop.
- Remove ShareSseRoutes and SSE plugin usage/dependencies.
- Update documentation to describe the new polling approach.

## Open Questions
- None.
