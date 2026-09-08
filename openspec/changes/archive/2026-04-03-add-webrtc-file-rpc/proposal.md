# Change: Add WebRTC file RPC for remote device file operations

## Why
WebRTC-connected devices already support remote paths and bookmarks, but file operations still depend on HTTP-only `FileRoutes`. That prevents a WebRTC-connected device from renaming files, creating files/folders, deleting files, reading metadata, or performing chunked read/write the same way as an HTTP-connected device.

## What Changes
- Add WebRTC device RPC support for request/response style `FileRoutes` operations:
  - rename
  - create-folder
  - copy
  - copy-control
  - delete
  - write-bytes
  - read-bytes
  - get-file-by-path
  - get-file-info-by-path
  - get-file-by-path-and-name
  - get-file-info-by-path-and-name
  - create-file
  - read-lines
  - append-content
- Extract shared file business logic so HTTP and WebRTC reuse the same permission checks and result semantics.
- Extract shared same-device remote copy logic so HTTP and WebRTC reuse the same staging, progress, and control semantics.
- Add a transport-agnostic device file client for the `Device.files` operations.

## Impact
- Affected specs: `webrtc-device-file-rpc`
- Affected code: `FileRoutes`, `FileRouteClient`, `Device`, `SocketDevice`, `DeviceState`, WebRTC device RPC controller/models, file service tests
