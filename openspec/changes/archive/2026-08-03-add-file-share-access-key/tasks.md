## 1. Access-Key Configuration Model

- [x] 1.1 Add the fixed header constant, atomic `{enabled, value}` configuration model, normalization, printable-ASCII/length validation, and version-tolerant Settings serialization.
- [x] 1.2 Add exact constant-work key comparison and common tests for defaults, normalization, invalid persisted data, accepted values, rejected values, and matching behavior.

## 2. Server Enforcement

- [x] 2.1 Add the access-key gate to the central raw HTTP dispatcher before route-specific handling, preserving disabled behavior and returning an empty 403 for missing, mismatched, or invalid enabled configurations.
- [x] 2.2 Keep existing CORS origin checks for `OPTIONS` requests and advertise `X-FolderSpan-Key` in allowed request headers for device APIs and HTTP WebRTC signaling.
- [x] 2.3 Add JVM dispatcher tests for disabled behavior, missing/mismatched rejection, matching-key routing, empty response bodies, and CORS preflight coverage.

## 3. First-Party Client Propagation

- [x] 3.1 Add a reusable request-header helper that reads the latest atomic setting, omits disabled/invalid values, replaces an existing header, and emits exactly one current key.
- [x] 3.2 Install per-request key injection in long-lived device and share route clients without capturing a stale value at client creation.
- [x] 3.3 Apply key injection to device discovery, liveness probes, share approval polling, and all app-hosted HTTP WebRTC signaling requests.
- [x] 3.4 Add common client tests proving disabled omission, invalid omission, replacement semantics, and live configuration changes between requests.

## 4. Settings State and User Interface

- [x] 4.1 Expose the atomic access-key configuration through `SettingsState`, persist changes with one notification key, and reload state after external or remote writes.
- [x] 4.2 Add the LAN access-protection section to file-share settings with an enable flow that requires a valid key, a masked overview, and a password-style editor that never displays the fixed request-header name.
- [x] 4.3 Add validation feedback and a secure-random action that produces a 32-character alphanumeric value, plus English and Simplified Chinese strings.
- [x] 4.4 Add settings-state tests for default state, atomic persistence, and reload after a remote write.

## 5. Settings Synchronization and Documentation

- [x] 5.1 Add `settings.fileShare.accessKey` to the typed settings-sync allowlist with the disabled atomic JSON default and ensure newer remote values overwrite the complete local configuration.
- [x] 5.2 Extend sync tests to cover allowlist parity, upload serialization, remote replacement, and in-memory state refresh expectations.
- [x] 5.3 Update the settings-sync whitelist documentation and Markdown description index to describe the synchronized access-key entry and overwrite behavior.

## 6. Verification

- [x] 6.1 Run the focused core common/JVM and Pro synchronization tests covering access-key behavior.
- [x] 6.2 Compile the affected shared UI and supported multiplatform source sets to verify the settings dialog and common HTTP helpers remain portable.
