## Why

HTTP file chunk transfer uses fixed chunk and concurrency settings. Devices can become busy or perform better with different settings, but the client currently has no per-response signal for tuning later requests.

## What Changes

- Add HTTP response headers that describe the remote device's current transfer tuning status on selected file chunk routes.
- Keep existing protobuf response bodies unchanged so older clients can continue decoding `Boolean` and `ByteArray` responses.
- Update HTTP file clients to parse the headers and use the latest status for later chunk size, concurrency, and busy backoff decisions.

## Capabilities

### New Capabilities
- `http-file-transfer-status`: Response-header based transfer tuning status for HTTP file byte routes.

### Modified Capabilities

## Impact

- Affected routes: `FileRoutes` `write-bytes`, `read-bytes`, `append-content`; `ShareRoutes` `read-bytes`.
- Affected clients: `FileRouteClient`, `HttpShareRouteClientManager`, and HTTP transfer pipeline configuration.
- No response body or WebRTC RPC protocol changes.
