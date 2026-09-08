## 1. Implementation

- [x] 1.1 Add shared transfer-status header model, codec, and safe default/clamping logic.
- [x] 1.2 Add server-side status tracking and attach transfer headers in the selected routes.
- [x] 1.3 Parse transfer headers in HTTP clients and expose latest advisory tuning per file client.
- [x] 1.4 Apply advisory chunk size, concurrency, and busy backoff to HTTP file and share chunk transfer scheduling.
- [x] 1.5 Add/update tests and validate the OpenSpec change.
