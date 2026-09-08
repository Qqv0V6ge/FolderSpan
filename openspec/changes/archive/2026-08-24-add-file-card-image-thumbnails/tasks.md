## 1. Thumbnail Core

- [x] 1.1 Add thumbnail eligibility, identity, size-limit, target-bucket, and byte-accounted LRU policy models with common tests
- [x] 1.2 Add a cancellable loader with bounded decode/remote concurrency, in-flight request sharing, and deterministic failure fallback

## 2. Protocol Sources and Platform Decoding

- [x] 2.1 Resolve Local, Device, Share, and Network thumbnail sources through FileState with bounded remote reads and temporary-file cleanup
- [x] 2.2 Implement target-specific sampled thumbnail decoding for Android, JVM/Desktop, iOS, JS, and Wasm

## 3. Compose Integration

- [x] 3.1 Add the reusable FileThumbnail composable and integrate it into list and grid cards without changing selection behavior
- [x] 3.2 Gate requests from FileScreen to visible and narrowly prefetched stable item keys

## 4. Verification and Documentation

- [x] 4.1 Add loader, protocol, cache, cancellation, and Compose fallback tests
- [x] 4.2 Run OpenSpec validation plus shared/core tests and cross-target compilation, then update documentation index entries
