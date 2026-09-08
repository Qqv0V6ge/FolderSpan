## Context

The JVM and Android link-share servers already use a public-port protocol switcher that can forward plain HTTP and TLS connections from one public port to separate internal raw servers. iOS currently starts separate public raw servers and therefore still exposes the old second HTTPS port.

## Goals / Non-Goals

**Goals:**
- Use one public link-share port for both HTTP browsing and HTTPS streamed ZIP download.
- Keep the default user-facing link HTTP.
- Preserve the explicit HTTPS consent prompt and remembered consent behavior.
- Stop starting a public `1205` link-share listener.

**Non-Goals:**
- Changing device API discovery or device API HTTPS ports.
- Removing the self-signed TLS identity or browser certificate warning guidance.
- Supporting old `https://host:1205` link-share URLs.

## Decisions

- Treat the configured link-share port as both the HTTP and HTTPS public endpoint.
- Keep JVM and Android internal HTTP/TLS raw servers behind the existing scheme-switching proxy.
- Add same-socket HTTP/TLS detection to the iOS raw link-share server by peeking at the first client bytes before choosing plain HTTP parsing or TLS accept.
- Build HTTPS consent identity from host, public port, and TLS fingerprint; do not include a separate HTTPS port.

## Risks / Trade-offs

- Some clients may have cached old `https://host:1205` URLs. Those links can fail because the second public listener is intentionally removed.
- iOS TLS detection depends on peeking without consuming bytes. Keep detection narrow: known HTTP methods use plain HTTP, all other non-empty first bytes proceed through TLS.
