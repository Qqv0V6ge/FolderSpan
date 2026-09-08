## Context

`SettingApiService` already wraps the Pro settings API with list, save, batch save, delete, target list, target delete, and target clone calls. The current repository and `DeviceSettingsSyncService` use that API only for `type = "device"` scalar settings plus bookmark/favorite snapshots.

The requested configuration spans multiple storage backends: SQLDelight tables for device/role/network data, encrypted local network credentials, and file-backed sync task definitions. The implementation must reuse the existing settings API and must not upload plaintext credentials.

## Goals / Non-Goals

**Goals:**

- Synchronize device connection configuration, role/permission configuration, persisted network drives, and sync task definitions for Pro users.
- Use `SettingApiService` as the only remote transport.
- Preserve locally encrypted credential fields inside remote setting values.
- Keep existing scalar settings, bookmark, and favorite synchronization working.
- Apply pulled remote snapshots to local stores and refresh in-memory state.

**Non-Goals:**

- No new server API routes.
- No synchronization of active connection state, temporary tokens, sync run history, failed item records, or logs.
- No full database mirror.
- No conflict-resolution UI in this change.

## Decisions

### Use Versioned Snapshot Entries

Each configuration family is stored as one JSON setting entry under `type = "device"` for the current target:

- `app.devices.snapshot`
- `app.roles.snapshot`
- `app.networks.snapshot`
- `app.syncTasks.snapshot`

The values include `version = 1` and typed `items` arrays. A single entry per family keeps the client compatible with the existing settings API and avoids requiring remote schema changes. The client ignores unknown fields for forward compatibility.

Alternative considered: create new setting `type` values (`role`, `network`, `sync`). This would make target browsing clearer, but the existing current-device target behavior and profile target management already operate around device targets. Snapshot keys under the current device target keep rollout smaller.

### Preserve Locally Encrypted Payload Fields Before Remote Save

Network credentials and other sensitive network extras are already encrypted in local `NetworkDrive` storage. Remote snapshots serialize those stored encrypted values directly and do not decrypt then re-encrypt them for sync. Remote snapshots must not include plaintext passwords, private keys, session tokens, or secret access keys.

Existing local network drive storage already encrypts `password` and `extras`; the remote snapshot will keep those encrypted fields opaque, with binary extras represented as Base64 for JSON transport. On pull, stored encrypted fields are written back as local encrypted values without decrypting them in the sync layer.

### Add Snapshot Providers Behind the Sync Service

Introduce provider-style interfaces for device, role, network, and sync task snapshots. `DeviceSettingsSyncService` remains the orchestration point and gains methods to upload/pull all configuration snapshots while keeping existing setting upload behavior.

Providers isolate storage-specific details:

- Device provider reads/writes `Device`, `DeviceConnect`, and `DeviceReceiveShare`.
- Role provider reads/writes `DeviceRole`, `DevicePermission`, and `DeviceRoleDevicePermission`.
- Network provider reads/writes persisted `NetworkDrive` entries through `NetworkState`.
- Sync task provider reads/writes `SyncTaskStore` task definitions and excludes run history.

### Trigger Snapshot Uploads From Existing State Mutations

Local mutation points notify a Pro configuration change notifier after successful persistence. App startup registers a debounced uploader when the user has a valid session token. Startup/login pull requests all known snapshot keys after the existing scalar settings pull.

## Risks / Trade-offs

- Snapshot-level conflict resolution can overwrite same-family local edits from another device. Mitigation: preserve timestamp-based pull and upload complete snapshots only after local persistence succeeds.
- Remote credential fields depend on the app's existing local encrypted storage format being compatible across devices. Mitigation: keep sync transport opaque and let normal network-drive runtime validation handle unusable restored credentials.
- Applying role/permission snapshots touches seeded rows. Mitigation: upsert by stable IDs where present and keep default seeded rows if remote data is empty.
- Network and sync snapshots may reference unavailable local paths or endpoints. Mitigation: store definitions but keep runtime validation unchanged when users run or edit tasks.

## Migration Plan

1. Add tests for snapshot serialization, locally encrypted credential transport, repository calls, and apply behavior.
2. Extend repository interfaces to expose generic settings operations while preserving existing device-setting methods.
3. Add snapshot models and provider implementations for device, role, network, and sync task data.
4. Extend `DeviceSettingsSyncService` to upload and pull configuration snapshots.
5. Wire successful local mutations to configuration sync notifications and register debounced Pro upload handlers at app startup.
6. Run targeted tests, shared JVM tests, and OpenSpec validation.

Rollback can leave existing scalar settings sync active and disable the new snapshot uploader/puller; remote snapshot entries are inert unless clients read their keys.
