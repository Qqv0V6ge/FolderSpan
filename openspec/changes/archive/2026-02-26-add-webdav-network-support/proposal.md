# Change: Add WebDav network support

## Why
WebDav is listed as a selectable protocol, but there is no protocol client implementation. Users cannot browse or transfer files via WebDav today.

## What Changes
- Add a Ktor-based WebDav client that supports list/download/upload/rename/delete/create operations.
- Extend WebDav configuration to accept a full URL (scheme + host + optional path) and auth configuration (Basic/Digest/Token + custom headers).
- Store WebDav auth configuration in `NetworkDriveExtras` using ProtoBuf.
- Enable WebDav on all platforms (JVM/Android/iOS/JS/Wasm) via Ktor client.

## Impact
- Affected specs: `specs/manage-network-drives/spec.md` (WebDav form fields and validation), new `specs/webdav-network-io/spec.md`.
- Affected code: `shared/src/commonMain/kotlin/com/folderspan/data/main/network`, `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/network`, Gradle Ktor client deps.
- Data: `NetworkDriveExtras` gains WebDav fields; no DB migration required per project policy.
