## 1. Implementation
- [x] 1.1 Add `WebDavDriveExtras` to `NetworkDriveExtras` (ProtoBuf) with auth type, token, and headers map.
- [x] 1.2 Update WebDav add/edit forms to collect full base URL, auth type selection, token, token header name/prefix, and header key/value list; stop using `parseNetworkAddress` for WebDav validation.
- [x] 1.3 Implement `WebDavNetworkClient` using Ktor (PROPFIND/GET/PUT/MOVE/DELETE/MKCOL) with auth + headers and LogKit logging.
- [x] 1.4 Wire `NetworkClientFactory` to return `WebDavNetworkClient` on all platforms.
- [x] 1.5 Add Ktor client auth dependency and implement lightweight XML parsing for PROPFIND responses.
- [x] 1.6 Update `docs/network-protocol-requirements.md` to document WebDav base URL + auth/header extras.

## 2. Validation
- [x] 2.1 Add tests for WebDav auth/header request building (MockEngine) and basic PROPFIND parsing.
- [x] 2.2 Run `./gradlew :shared:jvmTest` (known local environment failure: `java.lang.IllegalArgumentException: 25.0.2`).
