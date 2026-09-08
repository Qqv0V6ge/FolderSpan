## Context
HTTP device connect already has a server-side approval path:
- client calls `/api/devices/connect`
- target side enters `WAITING`
- `DeviceState.updateConnectionRequest(...)` emits the existing notification
- approval/rejection resolves the request

WebRTC currently has no equivalent. Joining a room only synchronizes peers, and receiving an `offer` immediately produces an `answer`. That makes WebRTC connect behavior inconsistent with HTTP and prevents Linux from showing approval notifications for Android-originated WebRTC connects.

## Goals
- Reuse the existing approval/notification semantics for WebRTC device connects.
- Keep room membership separate from connect approval; joining a room must not imply approval.
- Prevent `offer/answer` exchange until approval is granted.
- Preserve auto-approve behavior where existing device settings already allow it.

## Non-Goals
- Do not add full WebRTC file/path/bookmark RPC in this change.
- Do not redesign the HTTP approval pipeline.
- Do not require new UI surfaces beyond existing notification handling and current WebRTC entry points.

## Decisions
- Add three signaling message types:
  - `connect-request`
  - `connect-approved`
  - `connect-rejected`
- Keep them on the existing `/ws` signaling channel and route them the same way as `offer/answer/ice`, using `to.id`.
- Treat `connect-request` as the WebRTC equivalent of HTTP `/api/devices/connect`:
  - target side enters `WAITING`
  - target side posts the existing device-connect notification
  - approval/rejection resolves the request
- Only after receiving `connect-approved` may the initiator call `connectPeer(...)` / create `offer`.
- If the target device is configured for auto-connect approval, it may respond with `connect-approved` immediately without entering `WAITING`.
- If the request is rejected or times out, initiator state becomes `Fail` and no PeerConnection is established.

## Protocol Sketch
- Initiator already joined room and sees peer in member list.
- Initiator sends `connect-request(roomId, from, to)`.
- Target receives request:
  - if auto-approved: send `connect-approved`
  - else: call `updateConnectionRequest(..., WAITING, ...)` and wait for notification action
- On approve: target sends `connect-approved`
- On reject/timeout: target sends `connect-rejected`
- Initiator receives `connect-approved` and then starts existing `connectPeer(...)` flow
- Target only accepts incoming `offer` from peers with an approved/pending-approved connect request

## Risks / Trade-offs
- Adding signaling message types changes server routing behavior; specs must stay explicit so existing clients are not broken.
- Approval state now exists in both device notification flow and WebRTC session flow; implementation must ensure timeout/reject cleanup happens on both sides.
- If approval is granted but the peer disappears before `offer`, initiator must handle this as a normal connect failure.

## Migration Plan
- Extend server routing first so new message types are accepted.
- Add controller-side request/approval state handling.
- Wire notification actions to WebRTC approval responses.
- Update drawer/settings UI to trigger `connect-request` rather than direct `connectPeer`.

## Open Questions
- None for implementation: current default should reuse the same notification and auto-approval rules as HTTP.
