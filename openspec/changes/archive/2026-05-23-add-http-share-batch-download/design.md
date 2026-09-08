## Context
`HttpShareFileServerCommon.kt` already serves the link-share browser page, `/static` assets, JSON directory listings when `X-API-Request` is present, and direct file downloads for authorized share paths. The page currently renders a “批量下载” modal that exposes Bash/PowerShell/CMD scripts, while the static assets already include StreamSaver files plus `workers/fflate.min.js` and `workers/folder-zip-worker.js`.

Browser streaming saves require a secure context for the reliable Service Worker path. The app's HTTPS transport uses a self-signed device certificate, so users will see a browser certificate warning before the app can serve HTTPS content for the first time.

## Goals / Non-Goals
- Goals: Keep HTTP as the default link-share URL for normal browsing.
- Goals: Add an HTTPS-only streamed batch-save path for browser ZIP downloads.
- Goals: Explain the expected self-signed certificate warning before app-initiated HTTPS navigation.
- Goals: Remember consent per browser/device endpoint so repeated batch downloads do not show the app prompt again.
- Goals: Preserve existing link-share authorization, hidden-file filtering, and Disk share permissions.
- Goals: Stream ZIP data through the worker and StreamSaver without buffering the full archive.
- Non-Goals: Do not remove the existing script-download fallback.
- Non-Goals: Do not require users to install the self-signed certificate into the operating system trust store.
- Non-Goals: Do not change app-to-app HTTPS certificate pinning semantics.

## Decisions
- The server will expose an HTTPS counterpart for the same link-share browser routes and static assets, using the existing self-signed device TLS identity where platform support exists.
- The rendered HTTP page will include a small page configuration with the matching HTTP URL, HTTPS URL, TLS fingerprint, and consent key. The default link opened or shown to users remains HTTP.
- The batch-download action will first check whether the page is running in a secure context. If not, it will show a confirmation dialog explaining that HTTPS is needed for stream saving and that the self-signed certificate warning is expected for this local FolderSpan share endpoint.
- Consent will be keyed by host, HTTP/HTTPS ports, and TLS fingerprint. A certificate change or endpoint change prompts again.
- A direct HTTPS browser request without remembered consent will redirect back to the HTTP URL with a marker so the HTTP page can explain why HTTPS was opened and offer a return-to-HTTPS action. If the browser blocks the self-signed certificate before the request reaches the server, the app cannot perform this redirect until the user proceeds through the browser warning.
- The ZIP pipeline will run in browser JavaScript: traverse the current share path through existing JSON listing requests, stream file bytes through the existing authorized download routes, feed file chunks into `folder-zip-worker.js`, and write worker ZIP chunks to StreamSaver.
- The implementation will keep request and byte concurrency bounded. Directory traversal and file reads may overlap, but the browser must not read every file into memory before writing ZIP output.
- The existing script modal remains available as fallback when HTTPS streaming is unavailable, the user declines HTTPS, StreamSaver cannot initialize, or the worker reports an error before any archive data is committed.

## Risks / Trade-offs
- Browser certificate interstitials happen before application code runs. The app can explain before its own HTTP-to-HTTPS navigation, but it cannot suppress a browser warning when a user manually enters HTTPS first.
- Serving the same Ktor link-share routes over both HTTP and HTTPS may require platform-specific server startup changes. The shared route logic should stay common, with only TLS socket/connector setup in platform source sets.
- StreamSaver and worker failures vary by browser. Keeping the script fallback reduces risk while the streamed path matures.

## Validation
- Validate OpenSpec deltas with `openspec validate add-http-share-batch-download --strict`.
- During implementation, run shared JVM tests and platform compile targets touched by HTTP share server changes.
- Manually verify HTTP default browsing, HTTP-to-HTTPS consent, direct HTTPS redirect/explanation, remembered consent, batch ZIP contents, cancellation/error cleanup, and fallback script downloads.
