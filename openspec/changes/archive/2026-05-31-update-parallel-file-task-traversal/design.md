## Context
File task manifests are persisted before execution so interrupted tasks can continue without re-traversing. The current manifest scan is depth-first and serial, so every directory listing waits for the previous one.

## Decisions
- Use a bounded worker pool for one-level directory list calls and keep final manifest ordering deterministic by sorting after collection.
- Resolve initial traversal parallelism from endpoint type and existing runtime/remote transfer status, then adapt during scanning for all endpoint kinds: runtime/memory/remote limits are re-read while scanning, fast successful list calls increase concurrency, slow calls reduce it, and failures cut it back before surfacing the error.
- Publish throttled scan status while traversing: keep the result text focused on discovered entry count, and publish scan speed, elapsed scan time in the remaining-time slot, and traversal concurrency through task runtime metrics. When execution starts, runtime metrics reset the time display to zero before normal ETA updates take over.
- Poll pause/cancel while waiting for active list workers, and attach task scans to cancelable remote request batches so cancellation does not wait for device heartbeat retry loops.
- Keep control traffic isolated from bulk path/file traffic: heartbeat/theme requests use a separate control `HttpClient`; device/share/link-share path listing services dynamically limit concurrent directory enumeration; and heartbeat, keep-alive, pause, cancel, and disconnect monitors do not run on the same `Dispatchers.Default` lane used by bulk workers.
- Keep only the scan phase parallel. The persisted queue consumer remains ordered so delete directory ordering and checkpoint recovery stay intact.

## Risks / Trade-offs
- Remote endpoints may be sensitive to many list requests, so device/share scans use a dedicated directory-list cap of 12 below the app-controlled service-side dynamic list maximum of 24 rather than reusing byte-transfer concurrency. Network scans still adapt dynamically on the client side but stay more conservative because the remote service is not controlled by this app. Runtime feedback still reduces pressure when responses slow down.
- Parallel result arrival is nondeterministic, so manifest builders must continue to sort copy/delete entries before persisting.

## Migration Plan
No schema, protocol, or user setting migration is required.
