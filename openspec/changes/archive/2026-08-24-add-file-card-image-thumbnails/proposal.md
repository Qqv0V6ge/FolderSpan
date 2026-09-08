## Why

FileCard currently shows only generic file-type icons, which makes image-heavy directories slow to scan visually. Thumbnail loading must work across every file protocol and Compose target without allowing original image size, directory size, or rapid scrolling to produce unbounded memory use or UI jank.

## What Changes

- Display bounded, asynchronously loaded image thumbnails in list and grid file cards while preserving selection controls and file-icon fallbacks.
- Resolve Local, Device, Share, and Network image sources through a common loader with protocol-aware limits, cancellation, concurrency control, and cache invalidation.
- Decode thumbnails at the requested display size with platform-specific sampling on Android, Desktop, iOS, JS, and Wasm.
- Restrict work to visible or narrowly prefetched items and cancel obsolete requests during scrolling.

## Capabilities

### New Capabilities

- `file-card-image-thumbnails`: Defines eligibility, cross-protocol loading, bounded resource use, fallback behavior, and list/grid presentation for image thumbnails.

### Modified Capabilities

None.

## Impact

- Affects shared file-card UI, file-list visibility coordination, FileState protocol resolution, and target-specific image decoding.
- Adds internal thumbnail source/loader/cache interfaces without changing `FileSimpleInfo` serialization or Device/Share/Network wire protocols.
- Reuses existing Compose and file-access dependencies; no new external service is required.
