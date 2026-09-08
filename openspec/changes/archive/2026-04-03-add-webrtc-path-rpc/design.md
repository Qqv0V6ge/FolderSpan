## Context
Current device browsing is HTTP-only:
- `PathRoutes.kt` owns the server-side implementation.
- `PathRouteClient` owns the client-side HTTP calls.
- `Device.PathOperations` delegates directly to `HttpRouteClientManager.pathRouteClient`.

WebRTC currently only provides:
- signaling for room membership and connect approval
- peer connection setup
- data channels for test/file transfer

That means a WebRTC-connected peer can become "connected" without being usable as a browsable device.

## Goals
- Make WebRTC-connected devices support the same path operations as HTTP-connected devices.
- Keep path business logic single-sourced.
- Reuse the existing device permission model instead of inventing a WebRTC-only permission table.
- Keep the first WebRTC RPC scope limited to path operations.

## Non-Goals
- Do not add WebRTC bookmark RPC in this change.
- Do not add WebRTC file metadata/read/write RPC in this change.
- Do not redesign the whole device transport abstraction beyond path browsing needs.

## Decisions
- Extract path business logic into a shared `DevicePathService`.
- Keep HTTP wire compatibility unchanged; `PathRoutes` remains the HTTP adapter around the shared service.
- Add a WebRTC RPC frame type on the existing control data channel for request/response path operations.
- Reuse the existing permission model by issuing an approval-scoped token on WebRTC `connect-approved` and validating it through `DeviceCertificateState`.
- Avoid clobbering HTTP device tokens by adding support for token-only permissions that are not bound to `deviceId`.
- Introduce a `DevicePathClient` interface; HTTP and WebRTC path clients both implement it.

## Protocol Sketch
- Target approves a WebRTC connect request.
- Target resolves a role and issues a transient access token.
- Target sends `connect-approved` with that token.
- Initiator stores the token for that peer.
- Initiator sends `path-*` RPC requests over the control data channel, including the token in the encrypted RPC envelope.
- Target validates the token, executes the shared path service, and returns a typed response envelope with the same `requestId`.

## Risks / Trade-offs
- `DeviceCertificateState` previously assumed one token per device; adding token-only permissions is required to avoid conflicts with simultaneous HTTP and WebRTC connections.
- WebRTC path browsing depends on the control data channel staying open; callers must treat disconnects and timeouts as normal transport failures.
- Only path browsing becomes transport-agnostic in this change; other device operations remain HTTP-bound.
