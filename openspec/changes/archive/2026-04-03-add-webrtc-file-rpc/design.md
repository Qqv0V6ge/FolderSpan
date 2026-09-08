## Context
Current file operations are split:
- `FileRoutes.kt` exposes HTTP endpoints for file CRUD, metadata lookup, and chunked read/write
- `FileRouteClient.kt` wraps those endpoints
- `Device.files` delegates directly to the HTTP client manager

WebRTC currently supports:
- device connect approval
- approval-scoped access tokens
- generic request/response device RPC for path and bookmark operations

That makes file operations, including the same-device remote copy route, the next capability to move onto the same transport.

## Goals
- Make WebRTC-connected devices support the same file operations as HTTP-connected devices.
- Keep file permission checks and response shaping in a shared service layer.
- Reuse the existing WebRTC device RPC envelope and approval token.
- Reuse the control data channel for copy progress delivery and copy control commands.

## Non-Goals
- Do not redesign the higher-level device copy pipeline beyond making the existing same-device copy route reachable over WebRTC.
- Do not replace the dedicated peer-to-peer file transfer data-channel implementation used for payload transfer between devices.

## Decisions
- Extract request/response file logic into `DeviceFileService`.
- Extract same-device remote copy logic into `DeviceFileCopyService`.
- Add a `DeviceFileClient` abstraction; HTTP and WebRTC implementations both satisfy it.
- Extend `WebRtcDeviceRpcMethod` with file request/response operations rather than adding a second RPC envelope type.
- Add a dedicated WebRTC copy-progress frame so `/copy` can stream progress updates while `/copy-control` remains a normal device RPC command.

## Risks / Trade-offs
- `FileRoutes` contains more heterogeneous behavior than path/bookmark routes; the shared service must preserve the existing protobuf shapes precisely.
- Copy progress delivery now exists in both HTTP stream form and WebRTC progress-frame form; the terminal progress semantics must remain aligned.
