## Context

Link-share already separates device access into pending, authorized, rejected, sessions, and tickets. Uploads are currently gated by service/session-level state, so once upload is enabled the same permission applies broadly to any authorized client that can reach the upload routes. The host needs finer control: browse/download access can remain granted while upload is granted, pending, or denied per device.

This change crosses shared route handling, runtime share state, browser/API client behavior, share screen controls, tray controls, and notifications. It also affects security because upload permission must be enforced by the server, not only hidden in the UI.

## Goals / Non-Goals

**Goals:**

- Model upload permission as part of each link-share device authorization.
- Let a connected device request upload permission without requiring full reauthorization.
- Let the host approve or reject upload access for one device without changing other devices.
- Keep existing global upload setting as a default for new authorizations and sessions.
- Enforce upload permission in upload-check, upload, and upload-cancel routes.

**Non-Goals:**

- Persist per-device upload decisions across app restarts.
- Add path-level upload permissions within a single shared file list.
- Replace the existing browse/download authorization flow.
- Change WebRTC device-to-device file transfer permissions.

## Decisions

- Replace loose link-share authorization pairs with a named access model.
  - Decision: introduce a runtime model such as `LinkShareDeviceAccess(allowHidden, allowUpload, files)` and use it for authorized devices, sessions, tickets, and `LinkShareAuthorization`.
  - Rationale: upload permission becomes a first-class attribute next to hidden-file visibility and the shared file snapshot.
  - Alternatives considered: keep upload permission in separate maps keyed by device id. That avoids touching session models but makes route enforcement depend on combining multiple mutable sources and risks stale state after session refresh.

- Keep global upload setting as the default, not the only policy.
  - Decision: `FileShareState.allowUpload` and persisted Easy Share upload settings define the initial `allowUpload` value when a device is manually approved, auto-approved, password-authorized, or ticket-authorized.
  - Rationale: current behavior remains predictable for users who rely on a simple upload switch, while advanced per-device overrides become possible after the connection exists.
  - Alternatives considered: remove the global switch. That would be a larger behavior change and would force extra approvals for users who intentionally allow uploads for everyone.

- Add a dedicated upload-permission request action.
  - Decision: authenticated clients without upload permission can call a route such as `POST /api/share/upload-permission/request`; the server records a pending upload request for that device and returns a deterministic accepted/already-pending/already-allowed/rejected response.
  - Rationale: upload-check should remain a lightweight capability check, while the explicit request endpoint can trigger notifications and UI state changes.
  - Alternatives considered: treat failed upload-check as an implicit request. That is surprising because routine capability probes would create host prompts.

- Enforce upload permission server-side.
  - Decision: upload-check, upload, and upload-cancel must resolve the active link-share authorization and reject requests unless `allowUpload` is true for that device/session.
  - Rationale: browser controls are advisory; API clients can call routes directly.
  - Alternatives considered: hide upload controls when unauthorized. This improves UX but does not provide security.

- Track upload request state separately from browse authorization state.
  - Decision: keep browse/device lists as pending/allowed/rejected, and add upload-specific pending/allowed/rejected status for already authorized devices.
  - Rationale: a device can be allowed to browse but denied upload, and rejecting upload must not remove browse access.
  - Alternatives considered: move devices back to the main pending list for upload requests. That conflates browse authorization with upload authorization and makes the host action ambiguous.

## Risks / Trade-offs

- Existing sessions could keep stale upload permission after host changes a device override -> revoke or refresh sessions for that device when upload permission changes.
- Global default and per-device override could be confusing -> label controls as default policy vs device-specific upload permission.
- Upload request notifications could spam the host -> deduplicate pending upload requests by device id and clear them when approved, rejected, or disconnected.
- Browser/API clients might not support the new request route -> keep upload-check rejection compatible and make the request action additive.

## Migration Plan

1. Introduce the named link-share access model while preserving existing authorization behavior.
2. Map existing global upload state into new device access records when sessions/tickets are issued.
3. Add upload request state and host actions.
4. Update route enforcement and browser/tray/share-screen UI.
5. Add tests for default allow upload, per-device deny, upload request approval, and rejected upload requests.
6. Roll back by treating `allowUpload` as the global value and ignoring upload request state if needed.

## Open Questions

- Should a rejected upload request be removable from the rejected upload list without affecting browse access?
- Should approving upload permission refresh the browser page automatically, or should clients retry upload-check after approval?
