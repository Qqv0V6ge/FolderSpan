## Context
Link-share is currently served via `HttpShareFileServerCommon` and accessed through a browser/QR flow.
The app needs a first-class DiskBase entry to browse and download link-share files via the existing file UI.

## Goals / Non-Goals
- Goals:
  - Provide a `ShareNetwork` DiskBase that can list and download files from the link-share server.
  - Use Route mode only, always sending `X-API-Request`, `pwd` from `ShareNetwork.password`, and a User-Agent derived from `getSocketDevice()`.
  - Integrate with `NetworkState` and `FileState` for disk switching and remote open/download flows.
- Non-Goals:
  - WebSocket mode support.
  - Upload, rename, or delete operations for ShareNetwork.

## Decisions
- Convert `Network` to an `open class` and add `ShareNetwork` as a subclass. Preserve value semantics by implementing `equals`/`hashCode` using the same fields as the former data class.
- Represent ShareNetwork files as `FileProtocol.Network`, and set a stable `protocolId` derived from the ShareNetwork instance to drive download/open flows.
- Add a dedicated HTTP client for ShareNetwork that calls the link-share Route endpoints:
  - List: `GET /{path}` with `X-API-Request: true`, optional `pwd` query param, and User-Agent from `getSocketDevice()`.
  - Download: `GET /{path}` with the same headers/query params, streaming bytes to the local file system.

## Risks / Trade-offs
- Changing `Network` from a data class to an open class can alter equality behavior; explicit `equals`/`hashCode` mitigates list diff and selection issues.
- Link-share server returns `FileSimpleInfo` without protocol metadata; the client must override protocol fields after decoding.

## Migration Plan
- No persistent migration required. Existing in-memory lists will continue to work with the updated `Network` type.

## Open Questions
- None.
