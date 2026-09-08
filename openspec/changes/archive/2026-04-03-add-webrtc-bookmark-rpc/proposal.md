# Change: Add WebRTC bookmark RPC for remote device bookmark management

## Why
WebRTC-connected devices can now browse remote paths, but remote bookmark management is still HTTP-only. A WebRTC-connected device cannot fetch, create, update, or delete the remote device's bookmarks the same way an HTTP-connected device can.

## What Changes
- Add WebRTC bookmark RPC support for the `BookmarkRoutes` capability: get, create, update, and delete.
- Extract shared bookmark business logic so HTTP routes and WebRTC RPC reuse the same permission checks and result semantics.
- Extend the existing WebRTC device RPC layer from path-only to device route RPC so bookmark operations reuse the same approval token and request/response flow.
- Expose a transport-agnostic device bookmark client so `Device.bookmarks` works over HTTP and WebRTC.

## Impact
- Affected specs: `webrtc-device-bookmark-rpc`
- Affected code: `BookmarkRoutes`, `BookmarkRouteClient`, `Device`, `SocketDevice`, `DeviceState`, WebRTC RPC models/controller, bookmark service tests
