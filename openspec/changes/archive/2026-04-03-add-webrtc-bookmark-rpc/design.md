## Context
Current bookmark management is HTTP-only:
- `BookmarkRoutes.kt` owns the server-side route logic
- `BookmarkRouteClient.kt` owns the HTTP client calls
- `Device.BookmarkOperations` delegates directly to `HttpRouteClientManager.bookmarkRouteClient`

WebRTC already has:
- approval-gated device connect
- an approval-scoped access token
- a control-channel RPC mechanism introduced for path operations

That makes bookmark RPC the next obvious capability to move onto the same transport.

## Goals
- Make WebRTC-connected devices support remote bookmark get/create/update/delete.
- Reuse the same token-based permission model as HTTP.
- Reuse the same control-channel RPC layer as WebRTC path operations.
- Keep `Device.bookmarks` transport-agnostic.

## Non-Goals
- Do not add bookmark sort RPC in this change.
- Do not add file metadata/read/write RPC in this change.
- Do not redesign bookmark persistence itself.

## Decisions
- Extract bookmark business logic into a shared `DeviceBookmarkService`.
- Generalize the existing WebRTC path RPC envelope into a device route RPC envelope so path and bookmark operations share one request/response frame type.
- Keep route-level HTTP compatibility unchanged; `BookmarkRoutes` remains the HTTP adapter around the shared service.
- Reuse the existing approval-scoped WebRTC token for bookmark RPC authorization.

## Protocol Sketch
- Initiator already has an approved WebRTC peer and a scoped token.
- Initiator sends a device RPC request for `GetBookmarks`, `CreateBookmark`, `UpdateBookmark`, or `DeleteBookmark`.
- Target validates the token and executes `DeviceBookmarkService`.
- Target returns a device RPC response with the same `requestId`.
- Caller decodes the typed payload into bookmark results.

## Risks / Trade-offs
- Generalizing the existing path RPC model touches previously added WebRTC path code; tests must cover both path and bookmark request encoding.
- Bookmark updates over WebRTC should not silently diverge from HTTP permissions; the shared service must remain the only bookmark business entrypoint.
