## Why

`HttpShareFileServer` currently depends on Ktor server engines for link-share browsing, downloads, static assets, and HTTPS ZIP-download support. Ktor server support is problematic on iOS and forces the project to keep separate CIO/Netty runtime paths, so the link-share server should move to a first-party HTTP server implementation that can be controlled consistently across supported native platforms.

## What Changes

- Replace the Ktor server runtime used by `HttpShareFileServer` with a project-owned HTTP/1.1 server abstraction for JVM, Android, and iOS.
- Preserve existing link-share behavior: device authorization, password/session cookies, directory listings, file downloads with range support, uploads, static assets, security headers, HTTPS consent, and ZIP-download HTTPS routing.
- Reuse the existing `RawTlsHttpServer`/socket implementation experience where practical, but keep this change focused on the link-share server rather than the device API server.
- Remove `ktor-server-cio`/`ktor-server-netty` dependencies only after link-share routes, templates, and tests no longer require Ktor server APIs.
- Keep browser-facing URL and response contracts compatible with existing ShareNetwork and link-share clients.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `browse-share-network`: Link-share browsing, listing, download, upload, and streamed ZIP behavior must remain compatible while running on the first-party HTTP server.
- `http-socket-tls-transport`: Link-share HTTP/HTTPS transport must no longer depend on Ktor server engines and must preserve the existing HTTP/HTTPS port behavior.

## Impact

- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/server/HttpShareFileServerCommon.kt`
  - `shared/src/jvmMain/kotlin/com/folderspan/service/http/server/HttpShareFileServer.kt`
  - `shared/src/androidMain/kotlin/com/folderspan/service/http/server/HttpShareFileServer.kt`
  - `shared/src/iosMain/kotlin/com/folderspan/service/http/server/HttpShareFileServer.ios.kt`
  - `shared/src/*Main/kotlin/com/folderspan/service/http/server/raw/*`
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/server/templates/*`
  - `shared/build.gradle.kts`
- Affected dependencies:
  - `ktor-server-cio` and `ktor-server-netty` become removable from native link-share server source sets after the replacement is complete.
  - Ktor client dependencies are not part of this change.
- Affected validation:
  - Native server compile targets for JVM, Android, and iOS.
  - Link-share route behavior tests for authorization, static assets, downloads, range responses, uploads, HTTPS redirects, and ZIP prerequisites.
