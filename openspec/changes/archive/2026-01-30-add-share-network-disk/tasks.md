## 1. Implementation
- [x] 1.1 Convert `Network` to an open class and add `ShareNetwork` subclass in `shared/src/commonMain/kotlin/com/folderspan/data/main/Drawer.kt` and `shared/src/commonMain/kotlin/com/folderspan/data/main/StorageDevice.kt` (preserve `equals`/`hashCode`).
- [x] 1.2 Add a ShareNetwork HTTP client for Route-mode list/download with `X-API-Request`, `pwd` from `password`, and User-Agent from `getSocketDevice()`.
- [x] 1.3 Integrate ShareNetwork into `NetworkState` and `FileState` for root paths, listing, and open/download flows; set `FileProtocol.Network` + stable `protocolId` on results.
- [x] 1.4 Update `FileState.copyFile` to support ShareNetwork file downloads to Local.
- [x] 1.5 Add a settings entry to add/remove ShareNetwork addresses in `NetworkState`.

## 2. Tests
- [x] 2.1 Add unit tests for ShareNetwork list/download request construction (headers/query params) using a mock HTTP engine.
- [x] 2.2 Add a ShareNetwork protocol tagging test to ensure `FileProtocol.Network` and `protocolId` are applied.

## 3. Validation
- [x] 3.1 Run `./gradlew :shared:jvmTest`.
