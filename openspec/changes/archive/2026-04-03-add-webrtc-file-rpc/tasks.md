## 1. Implementation
- [x] 1.1 Add OpenSpec delta for WebRTC file RPC and update markdown index documentation.
- [x] 1.2 Extract shared file service and refactor HTTP `FileRoutes` to use it for request/response endpoints.
- [x] 1.3 Extract shared same-device copy service and refactor HTTP `/copy` and `/copy-control` to use it.
- [x] 1.4 Extend WebRTC device RPC with file operations for CRUD, metadata, chunked read/write, copy start, and copy control.
- [x] 1.5 Add WebRTC copy progress framing and connect it to the WebRTC device file client.
- [x] 1.6 Add a transport-agnostic device file client and connect it to `Device.files`.
- [x] 1.7 Add tests for shared file service behavior and run shared JVM tests.
