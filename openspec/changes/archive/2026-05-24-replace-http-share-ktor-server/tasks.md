## 1. Runtime Abstraction

- [x] 1.1 Define a common link-share HTTP request/response contract for method, URI, path, query, headers, cookies, remote host, scheme, body stream, response headers, cookies, status, and streaming response bodies.
- [x] 1.2 Add focused tests for request parsing helpers, cookie parsing/serialization, redirects, CORS headers, security headers, and static content metadata without using Ktor server APIs.
- [x] 1.3 Introduce a small route dispatcher for exact routes, static prefix routes, auth/upload API routes, root route, and fallback shared-path route.

## 2. Route Migration

- [x] 2.1 Extract link-share device identification, session/ticket/password auth, and rejection/waiting responses from `ApplicationCall` into the new request/response contract.
- [x] 2.2 Migrate browser root and shared-path listing handlers, including HTML rendering, JSON listing responses, search filtering, hidden-file filtering, and single-root redirect/download behavior.
- [x] 2.3 Migrate static assets, favicon, theme CSS, security headers, StreamSaver special headers, and cache/ETag handling.
- [x] 2.4 Migrate file download behavior for local files, content URIs, remote/shared Disk files, content-disposition, cache-control, accept-ranges, full responses, and partial byte ranges.
- [x] 2.5 Migrate upload-check and upload routes, preserving API marker checks, origin checks, overwrite behavior, directory creation, file body streaming, and JSON/status responses.

## 3. Platform Servers

- [x] 3.1 Implement the first-party JVM listener for public HTTP, internal HTTPS, protocol detection/redirect behavior, lifecycle state, port conflict handling, and client concurrency limits.
- [x] 3.2 Implement the first-party Android listener with the same behavior as JVM and Android-specific content URI streaming support.
- [x] 3.3 Implement the first-party iOS listener using the existing raw socket/TLS patterns, including start/stop, client disconnect handling, request body streaming, and TLS identity reuse.
- [x] 3.4 Remove production `HttpShareFileServer` usage of Ktor `Application`, `ApplicationCall`, Ktor routing, CIO, and Netty after parity is achieved.

## 4. Dependency Cleanup

- [x] 4.1 Remove `ktor-server-cio` and `ktor-server-netty` from native link-share source sets once production code no longer imports their APIs.
- [x] 4.2 Keep or relocate any remaining Ktor-independent HTML/template utilities so shared templates still compile without Ktor server response helpers.
- [x] 4.3 Confirm Ktor client dependencies and unrelated raw device API server code remain untouched unless required by compile failures.

## 5. Validation

- [x] 5.1 Add route parity tests for HTTP browser pages, API JSON listings, authorization failures, password auth, pending approval pages, static assets, CORS preflight, security headers, redirects, and cookies.
- [x] 5.2 Add download/upload tests for full download, valid range, invalid range fallback, hidden-file rejection, Disk share permission rejection, upload-check, upload overwrite, and upload body streaming.
- [x] 5.3 Add protocol/TLS tests for HTTP default browsing, HTTPS counterpart browsing, HTTP-to-HTTPS-port redirect, HTTPS-to-HTTP-port redirect, and remembered HTTPS consent compatibility.
- [x] 5.4 Run `./gradlew :shared:jvmTest`, `./gradlew :shared:compileDebugKotlinAndroid`, and the available iOS shared compile target for changed source sets.
- [x] 5.5 Manually verify link-share browsing, ShareNetwork listing/download, browser upload, single-file download, ZIP download, HTTP/HTTPS consent flow, repeated start/stop, and port-conflict notifications on at least one desktop and one mobile platform.
