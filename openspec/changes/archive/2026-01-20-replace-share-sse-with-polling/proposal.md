# Change: Replace share SSE with HTTP short polling

## Why
Share approval currently relies on SSE. We need to remove SSE and keep the device-share approval flow working via HTTP short polling.

## What Changes
- Replace the `/share/{device}` SSE handshake with an HTTP short polling endpoint under `/api/share`.
- Update the share request client to poll at a short interval until a terminal status is returned.
- Remove SSE plugin wiring and dependencies from share services and clients.
- Document the polling approach in the service/http AGENTS guidance.

## Impact
- Affected specs: device-share-requests (new)
- Affected code:
  - `shared/src/serverRouteMain/kotlin/com/folderspan/routes/ShareRoutes.kt`
  - `shared/src/serverRouteMain/kotlin/com/folderspan/routes/ShareSseRoutes.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/main/DeviceState.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/data/HttpRequests.kt`
  - `shared/src/*/kotlin/com/folderspan/service/http/server/FileShareService.*.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/client/HttpShareRouteClientManager.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/http/AGENTS.md`
  - `shared/build.gradle.kts`, `server/build.gradle.kts`
