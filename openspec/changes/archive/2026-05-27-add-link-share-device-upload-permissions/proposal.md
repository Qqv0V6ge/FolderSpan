## Why

Link-share upload access is currently controlled as a service-wide option, so a host cannot allow one trusted device to upload while keeping other devices read-only. Devices also have no explicit way to ask for upload access when they encounter a read-only share, leaving the host without a clear review workflow.

## What Changes

- Add per-device link-share upload authorization so each authorized client can be allowed or denied upload independently from its browse/download authorization.
- Add an upload permission request flow for clients that can browse a share but do not currently have upload permission.
- Surface pending upload requests, allowed upload devices, and rejected upload devices in the share screen and desktop tray management flows.
- Preserve the existing global upload setting as the default policy for newly authorized link-share devices, while allowing later per-device overrides.
- Ensure upload-check and upload routes enforce the active device-specific upload authorization.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `browse-share-network`: Link-share API and browser upload behavior will enforce device-specific upload authorization and support requesting upload permission.
- `manage-easy-file-share-settings`: Easy Share defaults will define the initial upload policy for new device authorizations without preventing per-device overrides.
- `manage-tray-easy-share`: The tray will expose upload request and per-device upload permission management alongside existing device authorization management.

## Impact

- Affected code: link-share route dispatching and upload endpoints, `FileShareState` runtime authorization state, link-share browser/client upload UI, share screen device lists, desktop tray Easy Share menus, and request notifications.
- APIs: link-share upload-check/upload behavior will include per-device authorization checks; a new request endpoint or equivalent action will be needed for upload permission requests.
- Data: no persistent schema change is required unless existing settings storage needs a default upload authorization preference; runtime per-device upload grants can live with current link-share authorization state.
