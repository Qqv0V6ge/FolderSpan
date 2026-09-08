## Context

`FileCard` and `FileGridCard` currently render `FileIcon` and are composed by a `LazyVerticalGrid` with a cache window beyond the visible viewport. `FileSimpleInfo` already carries protocol identity, size, MIME/filter classification, and modification time. Existing file APIs support bounded range reads for Local, Device, and Share and streaming download for Network. The current multiplatform image loader samples efficiently on Android but its Skia decoder reads the complete encoded source before resizing on Desktop and iOS.

## Goals / Non-Goals

**Goals:**

- Keep source reads, decoded bitmap dimensions, parallel work, and caches explicitly bounded.
- Preserve cancellation when lazy items leave the allowed loading region.
- Keep UI components previewable and independent of `FileState` by accepting a loader and load permission.

**Non-Goals:**

- Server-side thumbnail generation or protocol changes.
- User-configurable thumbnail policy in the first version.
- Guaranteed decoding of RAW, 3D, or every platform-specific image codec.

## Decisions

### Use a protocol-neutral loader above existing file access

An internal loader accepts `FileSimpleInfo` plus a target pixel size. FileState supplies a read callback that resolves Local, Device, Share, and Network endpoints, including the system Share-to-Local mapping. This keeps cards free of state-container and transport knowledge. Adding thumbnail bytes to `FileSimpleInfo` was rejected because it would inflate listings and serialized protocol payloads.

### Decode with platform sampling implementations

A common decoder contract has Android, JVM/Desktop, iOS, JS, and Wasm implementations. Native Local sources are decoded directly from their path; remote sources are staged in bounded chunks before decoding. Android uses sampled bitmap decoding, Desktop uses ImageIO reader subsampling, and iOS uses ImageIO/CoreGraphics thumbnail creation. Browser targets use an 8 MiB bounded encoded buffer and browser/Skia decoding at a capped target. Reusing the current loader everywhere was rejected because Desktop/iOS can allocate a complete encoded source before resizing.

### Bound requests at the UI and loader layers

FileScreen derives the keys permitted to load from `LazyGridState.layoutInfo.visibleItemsInfo`, including only visible items plus a narrow forward prefetch band. Each card uses a keyed `LaunchedEffect`; leaving that set or composition cancels its request. Remote work waits 120 ms before acquisition, uses two permits on native targets and one on browser targets, and shares in-flight work by cache key.

### Use byte-accounted LRU caches

Decoded bitmaps use an LRU sized by `width * height * 4`: 16 MiB on native targets and 8 MiB on browser targets. Native Device and Share sources are staged in 1 MiB chunks and removed immediately after decoding; Network reuses the existing editor-content streamed cache and closes it after decoding. Browser sources use a bounded encoded buffer instead of persistent staging. Cache keys include protocol, protocol id, path, size, update time, and target-size bucket. Partial temporary files are removed after cancellation or failure.

### Preserve the icon as every non-success state

The thumbnail composable is a small reusable atom with a `Modifier` and file-icon fallback slot. Successful thumbnails use `ContentScale.Crop` with a 100% rounded (`CircleShape`) clip. File cards retain their current icon until a bitmap is ready and always show selection controls in selection mode. No progress animation or crossfade is used, avoiding extra layout and bitmap retention during scrolling.

## Risks / Trade-offs

- [Platform decoders support different image formats and metadata] → Cover JPEG/PNG first, apply orientation where the native API exposes it, and fall back to the icon on unsupported data.
- [Network staging consumes bandwidth even for a small result] → Enforce size limits, visibility gating, request delay, cancellation, and bounded source caching.
- [Visibility updates can cause frequent recomposition] → Derive a stable set of operation keys and pass a Boolean to keyed item content; do not read layout state inside every card.
- [Cache accounting cannot include every native decoder allocation] → Bound source buffers, output dimensions, concurrency, and retained bitmaps, then verify platform peak memory with profiling.

## Migration Plan

Introduce the loader with a no-op/default path first, then connect FileScreen and both card variants. Existing callers and previews continue to compile by using the optional loader defaults. Rollback consists of removing the loader arguments and thumbnail content; no persisted model or remote compatibility migration is required.
