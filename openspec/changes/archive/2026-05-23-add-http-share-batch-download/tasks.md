## 1. Server and Transport
- [x] 1.1 Add an HTTPS counterpart for `HttpShareFileServer` on supported server platforms, reusing the device self-signed TLS identity and keeping the existing HTTP listener as the default entry.
- [x] 1.2 Add common helpers to derive/render the current HTTP URL, HTTPS URL, TLS fingerprint, and consent key for link-share pages.
- [x] 1.3 Route direct HTTPS browser requests without remembered consent back to the matching HTTP explanation flow, while allowing already-consented HTTPS requests to continue.
- [x] 1.4 Ensure link-share session cookies/headers and authorization checks work consistently across the HTTP and HTTPS endpoints.

## 2. Browser UX
- [x] 2.1 Update the batch download modal/action in `IndexTemplate.kt` to expose streamed ZIP download controls and keep script downloads as fallback.
- [x] 2.2 Add localized copy for HTTPS/self-signed guidance, consent actions, progress, cancellation, and fallback states.
- [x] 2.3 Implement consent persistence keyed by endpoint and TLS fingerprint so accepted users are not prompted again until the endpoint identity changes.
- [x] 2.4 Handle direct HTTPS return markers on the HTTP page and offer a clear action to continue to HTTPS when the user chooses streaming.

## 3. Streamed ZIP Pipeline
- [x] 3.1 Load StreamSaver, polyfill assets, and `folder-zip-worker.js` only when the user starts streamed batch download.
- [x] 3.2 Traverse the current share path through existing `X-API-Request` JSON listings, preserving relative ZIP paths for nested directories.
- [x] 3.3 Fetch file bytes through existing authorized download routes with bounded concurrency and feed chunks to the ZIP worker without full-file buffering.
- [x] 3.4 Pipe ZIP worker chunks to StreamSaver, surface progress/status, and support cancellation/cleanup.
- [x] 3.5 Fall back to script download guidance when HTTPS, StreamSaver, worker initialization, or ZIP streaming is unavailable before committing archive output.

## 4. Validation
- [x] 4.1 Add focused tests for URL derivation, consent-key behavior, and direct HTTPS redirect decisions where the logic is testable in common/JVM code.
- [x] 4.2 Run `./gradlew :shared:jvmTest` and the relevant platform compile/test target for changed server source sets.
- [x] 4.3 Manually verify HTTP browsing, first streamed download consent, self-signed HTTPS continuation, repeated consent skip, direct HTTPS redirect/explanation, ZIP contents, and script fallback.
