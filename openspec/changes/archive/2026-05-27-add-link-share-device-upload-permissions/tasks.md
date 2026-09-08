## 1. Runtime Authorization Model

- [x] 1.1 Add a named link-share device access model that includes hidden-file access, upload permission, and shared files.
- [x] 1.2 Replace authorized device/session/ticket upload-related authorization data with the named access model while preserving existing browse/download behavior.
- [x] 1.3 Add runtime state and helpers for pending, allowed, and rejected upload permission requests keyed by link-share device id.
- [x] 1.4 Ensure upload permission changes revoke or refresh affected link-share sessions so route enforcement observes the latest device override.

## 2. Server Routes

- [x] 2.1 Apply default upload permission when issuing authorizations through manual approval, auto-approval, password access, and ticket access.
- [x] 2.2 Enforce per-device upload permission in upload-check, upload, and upload-cancel routes.
- [x] 2.3 Add an authenticated upload permission request route that records one pending request per device and returns clear already-allowed, pending, rejected, or accepted responses.
- [x] 2.4 Clear upload request notifications/state when a device is disconnected, rejected for browse access, or the service stops.

## 3. Host UI

- [x] 3.1 Update the share screen device management UI to show upload permission state for authorized devices.
- [x] 3.2 Add share screen actions to approve, reject, enable, disable, and remove upload request state without changing browse/download authorization.
- [x] 3.3 Update desktop tray Easy Share menus to show upload request groups and per-device upload actions.
- [x] 3.4 Add or reuse notifications so the host is alerted when a device requests upload permission.

## 4. Client UX

- [x] 4.1 Update the link-share browser page to show an upload permission request action when browsing is allowed but upload is denied.
- [x] 4.2 Make the browser/client upload flow retry upload-check or refresh visible upload state after the host approves upload permission.
- [x] 4.3 Keep clients without the new request behavior compatible with existing upload-check rejection semantics.

## 5. Verification

- [x] 5.1 Add shared route tests for default upload allow/deny, per-device upload override, upload request deduplication, and rejected upload requests.
- [x] 5.2 Add state tests for approving/rejecting upload requests without altering browse/download authorization.
- [x] 5.3 Run `./gradlew :shared:jvmTest`.
- [x] 5.4 Run `./gradlew :composeApp:jvmTest`.
