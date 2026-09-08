## 1. Implementation
- [x] 1.1 Add an app-level lifecycle hook that detects “leave → return” and triggers a scan on resume.
- [x] 1.2 Ensure resume-triggered scans are skipped when `DeviceState.loadingDevices` is true (early return).
- [x] 1.3 Ensure resume-triggered scans run off the UI thread (e.g. `Dispatchers.Default`).
- [x] 1.4 Align existing manual scan click handlers with the same guard (`if (loadingDevices) return@clickable`) and dispatcher usage.
- [x] 1.5 JS/Wasm: trigger scan when the browser tab becomes visible (hidden → visible).
- [x] 1.6 Manual validation: start scan → background/tab away → return → verify exactly one new scan triggers when idle, and no scan triggers when already scanning.

## 2. Validation
- [x] 2.1 Run `openspec validate add-resume-device-rescan --strict`.
