# Change: Merge link-share HTTP and HTTPS onto one port

## Why

Link-share currently exposes the default HTTP entry on one port and the HTTPS counterpart on a second port. That creates redirect loops and extra port-management complexity when streamed ZIP download moves from `http://host:1204` to `https://host:1205`.

## What Changes

- Serve link-share HTTP and HTTPS on the same public port, defaulting to `1204`.
- Keep normal share URLs and QR codes on `http://host:1204`.
- Use `https://host:1204` only when browser streamed ZIP saving requires HTTPS, with the existing consent flow.
- Remove the public `1205` link-share listener and cross-port redirect behavior.
- **BREAKING**: Existing `https://host:1205` link-share URLs are no longer guaranteed to work.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `http-socket-tls-transport`: link-share HTTPS counterpart now shares the HTTP public port instead of using a separate public HTTPS port.

## Impact

- Affected specs: `http-socket-tls-transport`
- Affected code: native `HttpShareFileServer` implementations, link-share HTTPS page config, iOS raw link-share server protocol selection, link-share HTTP/TLS server tests
- Affected behavior: streamed ZIP HTTPS navigation targets the same port as normal HTTP browsing.
