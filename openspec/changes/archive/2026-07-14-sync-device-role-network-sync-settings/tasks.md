## 1. Tests First

- [x] 1.1 Add repository tests proving generic setting list/save/batch calls use `SettingApiService` with caller-provided type, target, key, and timestamp.
- [x] 1.2 Add snapshot serialization tests proving device, role, network, and sync task snapshots round-trip with unknown-field tolerance.
- [x] 1.3 Add credential tests proving network snapshot serialization preserves local encrypted password/extras values and excludes plaintext secrets.
- [x] 1.4 Add apply tests proving pulled snapshots update local device/role/network/sync stores without decrypting credential fields in the sync layer.

## 2. Remote Setting Repository

- [x] 2.1 Extend the domain repository with generic settings operations while preserving existing device-setting convenience methods.
- [x] 2.2 Implement generic list, save, batch save, batch delete, and target operations through `SettingApiService`.
- [x] 2.3 Add shared parsing helpers for setting entries and updated timestamps.

## 3. Snapshot Models and Providers

- [x] 3.1 Add versioned snapshot models and constants for device, role, network, and sync task snapshot keys.
- [x] 3.2 Implement provider logic for device tables, role/permission tables, persisted network drives, and sync task definitions.
- [x] 3.3 Preserve locally encrypted network credential fields during remote serialization and apply them without sync-layer decryption.
- [x] 3.4 Exclude active tokens, socket state, sync run history, runtime queues, checkpoints, failure items, and logs.

## 4. Sync Orchestration and Change Notifications

- [x] 4.1 Extend the Pro settings sync service to upload and pull all configuration snapshot families.
- [x] 4.2 Add a configuration sync notifier with debounced upload registration at app startup for signed-in users.
- [x] 4.3 Trigger notifications after successful device, role, network, and sync task mutations.
- [x] 4.4 Refresh affected in-memory states after applying remote snapshots.

## 5. Verification

- [x] 5.1 Run targeted tests for Pro settings repository and configuration snapshots.
- [x] 5.2 Run `./gradlew :shared:jvmTest`.
- [x] 5.3 Run `openspec validate sync-device-role-network-sync-settings --strict`.
