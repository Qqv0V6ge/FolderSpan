## Context
The shared HTTP clients issue many concurrent requests without a way to cancel a single request or a group of requests. Disconnecting the entire client is too coarse for user-driven cancellations.

## Goals / Non-Goals
- Goals:
  - Track every outbound HTTP request with an optional request ID and batch ID.
  - Allow canceling a specific request, a batch of requests, or all in-flight requests.
  - Surface cancellation as `Result.failure` to callers.
- Non-Goals:
  - Server-side changes or protocol changes.
  - Changing network retry behavior or timeouts.

## Decisions
- Add a per-manager request registry that stores a `Job` for each in-flight request and maps optional batch IDs to request IDs.
- Provide a small wrapper to register a request and execute its body within the tracked `Job`.
- If a new request registers with an ID already in-flight, cancel the previous request and replace it.
- For flows and long-running loops, emit a single `Result.failure` on cancellation and terminate the flow/loop.

## Alternatives considered
- Closing the entire `HttpClient` to cancel requests: too disruptive and breaks other in-flight work.
- Relying on external coroutine cancellation only: does not allow cancel-by-ID or batch control.

## Risks / Trade-offs
- Request registry must clean up promptly to avoid leaks; enforce cleanup on completion and cancellation.
- Optional IDs mean callers must pass IDs when they need precise cancellation.

## Migration Plan
- Introduce registry and cancellation APIs first.
- Update client request methods to accept optional requestId/batchId and use the registry.
- Update call sites that need targeted cancellation to pass IDs.

## Open Questions
- None.
