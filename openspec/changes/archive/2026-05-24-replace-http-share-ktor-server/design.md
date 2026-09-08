## Context

`HttpShareFileServer` is the link-share browser server. It currently shares most business logic in `HttpShareFileServerCommon`, but that common layer is built around Ktor server concepts such as `Application`, `ApplicationCall`, route plugins, Ktor request/response helpers, and `respondHtml`. Platform startup is split across Ktor Netty on JVM/Android and Ktor CIO on iOS.

The project already has a first-party raw TLS HTTP server for device API traffic (`RawTlsHttpServer` under JVM, Android, and iOS). That code proves the project can own socket accept loops, TLS setup, HTTP/1.1 parsing, concurrency limits, and response writing. However, the link-share server has a different surface: browser HTML, cookies, CORS/security headers, static resources, uploads, range downloads, HTTP/HTTPS port coordination, and ShareNetwork compatibility.

## Goals / Non-Goals

**Goals:**

- Replace Ktor server engines for `HttpShareFileServer` on JVM, Android, and iOS with project-owned HTTP/1.1 server implementations.
- Preserve current link-share URL, header, cookie, status code, JSON, HTML, static asset, upload, download, range, and HTTPS consent contracts.
- Keep the common link-share business logic shared across platforms by introducing small project-owned request/response/router abstractions.
- Reuse existing TLS identity, protocol redirect, and raw socket patterns where they fit.
- Remove native link-share server dependency on `ktor-server-cio` and `ktor-server-netty` after parity is verified.

**Non-Goals:**

- Replacing Ktor client dependencies.
- Rewriting the device API `RawTlsHttpServer`; it may be refactored for shared helpers, but its behavior is not the target.
- Changing browser UI, ShareNetwork API semantics, authorization policy, or ZIP streaming behavior beyond preserving existing behavior.
- Supporting HTTP/2, WebSocket, multipart form parsing beyond the current link-share needs, or general-purpose server extensibility.

## Decisions

### Decision: Introduce a link-share HTTP runtime abstraction before replacing route logic

Create a small common server contract for link-share requests and responses, for example:

- request method, path, raw URI, query parameters, headers, cookies, body stream, remote host, local scheme/host/port
- response status, headers, cookies, byte/text/stream body writers, redirects, and close semantics
- route dispatch helpers for exact routes, prefix/static routes, and fallback path routes

The common business logic should depend on this project-owned contract rather than Ktor `ApplicationCall`.

Alternatives considered:

- Port the iOS server only and leave JVM/Android on Netty. This would reduce immediate work, but keep divergent behavior and Ktor APIs in the common server layer.
- Fork existing raw TLS server directly into link-share routes. This would move quickly but risks duplicating parsing, headers, response writing, and lifecycle behavior.

### Decision: Preserve generated HTML templates through string rendering or a narrow renderer boundary

Current templates use `kotlinx.html` through Ktor's `respondHtml` extension. The migration should either render templates to strings through `kotlinx.html` without Ktor server response APIs, or introduce a small renderer helper that returns HTML text and content type. Template call sites should not depend on Ktor `ApplicationCall`.

Alternatives considered:

- Rewrite templates as raw strings. This would remove dependencies faster, but increases escaping and layout regression risk.

### Decision: Keep socket/TLS startup platform-specific, keep routing common

JVM and Android can use Java sockets/SSL sockets. iOS should follow the existing native raw socket/TLS approach in `RawTlsHttpServer.ios.kt`. The route dispatcher and response semantics should remain in common code so platform implementations only own accept loops, protocol detection, TLS handshake, request parsing, and streaming I/O.

Alternatives considered:

- A fully common Kotlin/Native socket layer. The current project already has platform-specific raw server implementations, and forcing a common socket abstraction first would add migration risk.

### Decision: Migrate by compatibility slices

The implementation should land in small slices: static assets and basic page responses first, then authorization/session behavior, then downloads/range responses, then uploads, then HTTPS and protocol redirect behavior. Ktor should remain available until all route parity tests pass.

Alternatives considered:

- Big-bang replacement of `HttpShareFileServerCommon`. This is likely faster to write but makes regressions harder to isolate.

## Risks / Trade-offs

- HTML or header regressions during Ktor removal -> add route-level parity tests that assert status, key headers, cookies, content type, and body shape for browser and API requests.
- Range/download streaming regressions -> add tests for full download, valid byte range, invalid range fallback, and content-disposition/cache headers.
- Upload request parsing differences -> keep upload body format identical and add upload-check/upload tests before removing Ktor.
- iOS socket/TLS behavior differs from JVM/Android -> keep platform lifecycle tests or manual verification steps for iOS startup, stop, repeated start, and client disconnect.
- Dependency cleanup can happen too early -> remove `ktor-server-cio`/`ktor-server-netty` only after native source sets compile without Ktor server imports.

## Migration Plan

1. Add the common link-share request/response/router abstraction and focused unit tests for parsing, cookies, redirects, and response headers.
2. Extract Ktor-independent link-share route handlers from `HttpShareFileServerCommon`, keeping Ktor as a temporary adapter if useful.
3. Implement raw HTTP listeners for JVM and Android, including HTTP and internal HTTPS listeners plus existing protocol redirect behavior.
4. Implement the iOS raw HTTP listener using the existing native raw TLS server patterns.
5. Move HTML rendering away from Ktor server response APIs while preserving existing template output.
6. Run parity tests and compile all affected native source sets.
7. Remove Ktor server engine dependencies and obsolete Ktor route adapters once no production source set imports Ktor server APIs for `HttpShareFileServer`.

Rollback strategy: keep the current Ktor implementation available behind a temporary internal adapter until parity tests and platform compiles pass; if a platform server regression appears, switch that platform back to the Ktor adapter while preserving the common route extraction work.

## Open Questions

- Should the implementation temporarily keep a Ktor adapter for JVM/Android during migration, or move directly to the raw server after the common route abstraction exists?
- Which iOS validation target is available in CI or local developer machines for this project?
