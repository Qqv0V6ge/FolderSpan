## Context
WebDav is available in the protocol selection UI, but the data layer has no WebDav client. The app already relies on Ktor HTTP client across platforms.

## Goals / Non-Goals
- Goals:
  - Implement WebDav list/download/upload/rename/delete/create operations using Ktor.
  - Support user-selected auth types (Basic/Digest/Token) and custom headers.
  - Store WebDav auth settings in `NetworkDriveExtras` (ProtoBuf) and keep existing encryption flow.
  - Treat `Network.host` as a full WebDav base URL (scheme + host + optional path).
- Non-Goals:
  - WebDav locks, ACLs, or extended properties.
  - OAuth flows or certificate pinning.

## Decisions
- Use `createNoProxyHttpClient` to build a shared Ktor `HttpClient` for all platforms.
- Add the Ktor client auth plugin dependency and configure Basic/Digest support based on the selected auth type.
- Parse PROPFIND XML responses with a lightweight tag scanner to avoid extra XML parser dependencies.
- Encode WebDav auth settings as `WebDavDriveExtras` inside `NetworkDriveExtras`, including:
  - authType enum (Basic/Digest/Token)
  - token string
  - tokenHeaderName (default Authorization)
  - tokenPrefix (default Bearer, allow empty)
  - headers map (String -> String)
- Use `Network.host` as the base URL. The path supplied by file operations is appended to this base, respecting leading/trailing slashes.

## Risks / Trade-offs
- Digest authentication behavior varies across servers; rely on Ktor Auth plugin and add clear error logging when handshake fails.
- Custom headers are flexible but can override auth headers; define precedence as: auth headers (Basic/Digest) -> token header -> custom headers.

## Migration Plan
- No database migration. Existing records keep their extras; WebDav extras default to empty.

## Open Questions
- None.
