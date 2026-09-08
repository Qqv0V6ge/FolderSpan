# Change: Add Easy Share receive toggle and auto-reject unknown devices

## Why
Users need a quick way to block unsolicited share requests from unknown devices while keeping existing trusted devices working.

## What Changes
- Add a "允许其他设备分享给我" toggle in `EasyFileShareSettingsScreen` (default true).
- Persist the new setting and expose it via settings state/utils.
- Update the share request polling endpoint to immediately return REJECTED for unsaved devices when the toggle is disabled.

## Impact
- Affected specs: `device-share-requests`, `manage-easy-file-share-settings`
- Affected code: settings state/utils, `EasyFileShareSettingsScreen`, `ShareRoutes`.
