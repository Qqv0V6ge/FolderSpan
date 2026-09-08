## Why

Devices on the same LAN can currently discover the FolderSpan service and initiate file-sharing connections without a deployment-specific gate. Users need an optional shared secret so only devices configured with the same value can discover, connect to, and transfer through the app-hosted HTTP service.

## What Changes

- Add an optional file-share access-key configuration with a fixed internal `X-FolderSpan-Key` request header.
- Reject non-preflight HTTP service requests with an empty `403 Forbidden` response when access-key protection is enabled and the supplied value is missing or does not match; preserve existing behavior while protection is disabled.
- Attach the latest configured key to device discovery, device/share route clients, liveness probes, share approval polling, and app-hosted HTTP WebRTC signaling requests.
- Add file-share settings controls to enable protection, edit a masked key value, and generate a random 32-character alphanumeric key without exposing the fixed header name in the UI.
- Persist the enable flag and key value atomically, include the configuration in the existing Pro settings-sync allowlist, and refresh in-memory settings after remote application.
- Add localized copy, sync documentation, and focused tests for validation, persistence, request injection, server enforcement, and synchronization.

## Capabilities

### New Capabilities

- `file-share-access-key`: Defines configuration, user interaction, client propagation, and server-side enforcement for the optional LAN file-share access key.

### Modified Capabilities

- `sync-pro-configuration-settings`: Allows the file-share access-key configuration to be synchronized as an explicitly selected cross-device setting and applied atomically when the remote value is newer.

## Impact

- Affected shared UI and state: `FileShareSettingsScreen`, `SettingsState`, localized strings, and settings-sync documentation.
- Affected core HTTP paths: access-key model and persistence, request builders, device discovery/liveness, device/share route managers, and HTTP WebRTC signaling.
- Affected app-hosted server behavior: every non-`OPTIONS` request dispatched by `RawHttpApiDispatcher` gains an optional leading access-key check, and CORS preflight responses advertise the fixed header.
- Affected remote settings data: `settings.fileShare.accessKey` stores and synchronizes one JSON object containing both the enable flag and key value.
- No new third-party dependencies or external API endpoints are introduced.
