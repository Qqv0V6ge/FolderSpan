## 1. OpenSpec

- [x] 1.1 Add delta requirements for single-port link-share HTTP/HTTPS behavior.
- [x] 1.2 Validate the change with `openspec validate merge-link-share-http-https-port --strict`.

## 2. Server Behavior

- [x] 2.1 Generate link-share HTTPS base URLs and consent keys from the same public port as HTTP.
- [x] 2.2 Stop JVM and Android from starting the public HTTPS redirect proxy on a second port.
- [x] 2.3 Update iOS to serve HTTP and HTTPS from one public link-share socket.

## 3. Tests

- [x] 3.1 Update common link-share HTTPS helper tests for same-port behavior.
- [x] 3.2 Update JVM transport tests so TLS on the public HTTP port is served directly, without a second-port redirect.
- [x] 3.3 Run relevant Gradle tests for link-share HTTP/TLS behavior.
