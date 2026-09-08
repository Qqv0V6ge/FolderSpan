# Change: Add HTTP client request cancellation support

## Why
Some user actions need to force-cancel in-flight HTTP client requests without tearing down the whole connection. The current clients do not expose per-request cancellation control.

## What Changes
- Add request tracking with request IDs and optional batch IDs for all HTTP client calls in `com.folderspan.service.http.client`.
- Expose cancellation APIs on `HttpRouteClientManager` and `HttpShareRouteClientManager` to cancel by ID, by batch, or all in-flight requests.
- Ensure canceled requests surface as `Result.failure` (including flow-based operations).

## Impact
- Affected specs: `cancel-http-client-requests` (new)
- Affected code: `shared/src/commonMain/kotlin/com/folderspan/service/http/client/*`
