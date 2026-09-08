# Change: Add ShareNetwork disk for link-share browsing

## Why
Users need a Network disk entry that can browse and download files from the link-share HTTP server.
Today, link-share is only accessible via the browser/QR flow and is not integrated into the disk switch.

## What Changes
- Convert `Network` in `shared/src/commonMain/kotlin/com/folderspan/data/main/Drawer.kt` to an `open class` and add a `ShareNetwork` subclass.
- Add a client and operations for ShareNetwork to list files and download files from `HttpShareFileServerCommon` (Route mode only), always using `X-API-Request`, `pwd` from `password`, and User-Agent derived from `getSocketDevice()`.
- Wire ShareNetwork into `NetworkState` and `FileState` so it can be selected via the disk switch and supports list + download flows.
- Tag ShareNetwork file entries with `FileProtocol.Network` and a stable `protocolId` for downloads/open-remote behavior.

## Impact
- Affected specs: new capability `browse-share-network`.
- Affected code: data models (`Drawer.kt`, `StorageDevice.kt`), HTTP client layer, `FileState`, and network state/UI integration.
